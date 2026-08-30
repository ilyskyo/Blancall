// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.data.ai.AiClient
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.ai.AiStreamer
import com.ilyskyo.blancall.data.ai.SearchClient
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.ai.loadAiSessionData
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 对话页 ViewModel：
 * - 加载选中文章，构造 system 上下文提示词
 * - 管理对话消息列表（含流式增量）
 * - 调用 AiClient 流式接口，逐段更新 UI
 */
class AiViewModel(application: Application) : AndroidViewModel(application) {

    /** 展示用消息（user / assistant / error） */
    data class UiMessage(
        val id: Long,
        val role: String,          // "user" / "assistant" / "error"
        val content: String,
        val isStreaming: Boolean = false
    )

    private val idCounter = AtomicLong(0)

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // 发送过程状态提示（如"🔍 正在联网搜索…"），null 表示无提示
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 订阅 AiStreamer 增量的协程（页面退出自动取消，流本身在应用级不中断） */
    private var streamerJob: Job? = null

    /** 流代次：每次发送新消息 +1，旧订阅者据此忽略新流的状态（防止覆盖上一轮回复） */
    private var streamGeneration = 0L

    private val _title = MutableStateFlow("AI 助手")
    val title: StateFlow<String> = _title.asStateFlow()

    // 发送给 API 的完整历史（首条为 system）
    private val history = mutableListOf<AiClient.ChatMessage>()

    // 当前会话持久化信息（开启「保存与 AI 的对话」时使用）
    private var sessionId: Long = 0L
    private var sessionArticleIds: List<Long> = emptyList()

    /** 最近一次失败的 user 文本（供错误气泡"重发"）；成功或新输入时清空 */
    private var lastFailedUserText: String? = null

    companion object {
        /** 搜索上下文注入前缀（N3 据此去重，只保留最新一条） */
        private const val SEARCH_CONTEXT_PREFIX = "以下是针对用户当前问题的最新网络搜索"

        /** system 提示截断上限（字符） */
        private const val SYSTEM_PROMPT_CHAR_LIMIT = 8000

        /** 发送给 API 的历史总字符预算（含 system） */
        private const val REQUEST_HISTORY_CHAR_BUDGET = 14000
    }

    /**
     * 上下文裁剪：发送给 API 的消息按"最近优先"保留到预算内，最早的轮次成对丢弃（user/assistant 对称），
     * 避免多轮对话（尤其联网搜索）后 token 超出上下文窗口导致 400 / 费用暴涨。
     * 不影响落盘的完整镜像 [history]。
     */
    private fun trimHistoryForRequest(full: List<AiClient.ChatMessage>): List<AiClient.ChatMessage> {
        if (full.size <= 1) return full

        // 首条（system）始终保留，超长截断
        val system = full.first()
        val systemContent = system.content.take(SYSTEM_PROMPT_CHAR_LIMIT)
        var budget = REQUEST_HISTORY_CHAR_BUDGET - systemContent.length

        // 其余消息从最近一条倒序收集，预算不足即停（丢弃最早的整轮）
        val tail = full.drop(1)
        val kept = mutableListOf<AiClient.ChatMessage>()
        for (i in tail.indices.reversed()) {
            val msg = tail[i]
            if (budget - msg.content.length < 0) break
            kept.add(msg)
            budget -= msg.content.length
        }
        // 还原正序
        kept.reverse()
        // 对称保证：最旧保留的消息应以 user 开头（丢弃孤立的 assistant 开头轮次）
        while (kept.isNotEmpty() && kept.first().role != "user") {
            kept.removeAt(0)
        }

        return buildList {
            add(AiClient.ChatMessage(system.role, systemContent))
            addAll(kept)
        }
    }

    /**
     * 初始化上下文：加载文章并构造 system 提示词（幂等，重复进入时重建）。
     */
    fun initContext(articleIds: List<Long>) {
        viewModelScope.launch {
            val repo = ArticleRepository.getInstance(
                getApplication<Application>().filesDir.resolve("articles.json").absolutePath
            )
            val recordRepo = RecordRepository.getInstance(
                getApplication<Application>().filesDir.resolve("records.json").absolutePath
            )
            // 同时加载文章与练习记录（学习数据用于 AI 针对性辅导）
            val (articles, records) = withContext(Dispatchers.IO) {
                val arts = articleIds.mapNotNull { id -> runCatching { repo.getArticleById(id) }.getOrNull() }
                val recs = articleIds.flatMap { id ->
                    runCatching { recordRepo.getByArticleId(id) }.getOrNull() ?: emptyList()
                }
                arts to recs
            }
            if (articles.isEmpty()) return@launch

            _title.value = if (articles.size > 1) {
                "跨文 AI · ${articles.size} 篇"
            } else {
                articles.first().title
            }
            history.clear()
            val systemPrompt = withContext(Dispatchers.Default) { buildSystemPrompt(articles, records) }
            history.add(AiClient.ChatMessage("system", systemPrompt))

            // 每次进入都是全新会话（进行中的回答会后台完成并存档，从历史页查看）
            sessionId = System.currentTimeMillis()
            sessionArticleIds = articleIds
            _messages.value = listOf(
                UiMessage(
                    id = idCounter.incrementAndGet(),
                    role = "assistant",
                    content = if (articles.size > 1) {
                        "你好，我是你的 AI 学习助手 👋\n已载入 ${articles.size} 篇文章，你可以问我任何关于内容的问题，比如解释某句话、出练习题、总结要点等。"
                    } else {
                        "你好，我是你的 AI 学习助手 👋\n已载入《${articles.first().title}》，你可以问我任何关于内容的问题，比如解释某句话、出练习题、总结要点等。"
                    }
                )
            )
        }
    }

    /**
     * 从历史会话恢复（继续对话）：
     * 读取保存的文章 ids 与消息，重建 system 上下文与 API 历史；
     * 会话 id 沿用原值，后续对话继续写入同一历史文件。
     */
    fun resumeSession(sessionId: Long) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (savedIds, savedMessages) = withContext(Dispatchers.IO) {
                loadAiSessionData(app, sessionId)
            } ?: run {
                _messages.value = listOf(UiMessage(idCounter.incrementAndGet(), "error", "历史会话不存在或已损坏"))
                return@launch
            }

            val repo = ArticleRepository.getInstance(app.filesDir.resolve("articles.json").absolutePath)
            val recordRepo = RecordRepository.getInstance(app.filesDir.resolve("records.json").absolutePath)
            val (articles, records) = withContext(Dispatchers.IO) {
                val arts = savedIds.mapNotNull { id -> runCatching { repo.getArticleById(id) }.getOrNull() }
                val recs = savedIds.flatMap { id ->
                    runCatching { recordRepo.getByArticleId(id) }.getOrNull() ?: emptyList()
                }
                arts to recs
            }
            if (articles.isEmpty()) {
                _messages.value = listOf(UiMessage(idCounter.incrementAndGet(), "error", "历史会话对应的文章已不存在"))
                return@launch
            }

            this@AiViewModel.sessionId = sessionId
            sessionArticleIds = savedIds
            _title.value = if (articles.size > 1) {
                "跨文 AI · ${articles.size} 篇"
            } else {
                articles.first().title
            }
            history.clear()
            val systemPrompt = withContext(Dispatchers.Default) { buildSystemPrompt(articles, records) }
            history.add(AiClient.ChatMessage("system", systemPrompt))
            // API 历史只含 user/assistant；error 消息仅展示不发送
            savedMessages.forEach { m ->
                if (m.role == "user" || m.role == "assistant") {
                    history.add(AiClient.ChatMessage(m.role, m.content))
                }
            }
            _messages.value = savedMessages.map { UiMessage(idCounter.incrementAndGet(), it.role, it.content) }

            // ── 进行中会话实时续订：若 AiStreamer 里该会话仍在生成，订阅增量并打标 ──
            val streamFlow = AiStreamer.stateOf(this@AiViewModel.sessionId)
            if (streamFlow != null && !streamFlow.value.complete) {
                val pendingId = idCounter.incrementAndGet()
                _messages.value = _messages.value + UiMessage(pendingId, "assistant", streamFlow.value.text, isStreaming = true)
                _title.value = "${_title.value}（生成中…）"
                _isSending.value = true
                val gen = ++streamGeneration
                streamerJob = viewModelScope.launch {
                    streamFlow.collect { st ->
                        if (gen != streamGeneration) return@collect
                        updateMessage(pendingId) { it.copy(content = st.text, isStreaming = !st.complete) }
                        if (st.complete) {
                            finishStream(pendingId, st)
                        }
                    }
                }
            }
        }
    }

    /**
     * 发送消息并流式接收回复。
     * 流式收集运行在应用级 [AiStreamer] 中：退出对话页后回答仍继续，
     * 重新进入时会恢复展示未完成的回答。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isSending.value) return

        // 每次新发送：清除上次失败记录（重发即新发送）
        lastFailedUserText = null

        val userMsg = UiMessage(idCounter.incrementAndGet(), "user", trimmed)
        val pendingId = idCounter.incrementAndGet()
        history.add(AiClient.ChatMessage("user", trimmed))
        _messages.value = _messages.value + userMsg + UiMessage(pendingId, "assistant", "", isStreaming = true)
        _isSending.value = true
        // 用户消息已入列：持久化一次
        saveSession()

        viewModelScope.launch {
            try {
                // 统一走 AiConfigStore 的当前生效对话配置（与练习 AI 挖空同一套配置）
                val profile = AiConfigStore.activeChatProfile
                // Keystore 解密放 IO 线程，避免主线程阻塞（部分设备硬件安全模块较慢）
                val apiKey = profile?.let { withContext(Dispatchers.IO) { it.decryptApiKey() } }.orEmpty()
                if (apiKey.isBlank()) {
                    throw AiClient.AiException("尚未配置有效的 API Key，请到「设置 → AI 配置」中创建并启用对话配置")
                }
                val baseUrl = profile?.baseUrl.orEmpty()
                if (baseUrl.isBlank()) {
                    throw AiClient.AiException("尚未配置 API 地址，请到「设置 → AI 配置」中创建并启用对话配置")
                }
                val model = profile?.model.orEmpty()
                if (model.isBlank()) {
                    throw AiClient.AiException("尚未配置模型名，请到「设置 → AI 配置」中创建并启用对话配置")
                }

                // ── 联网搜索核验（开启且配置了搜索 Key 时）──
                var searchFailed: String? = null
                if (AppPrefs.aiSearchEnabled) {
                    val searchKey = AiConfigStore.activeSearchProfile?.let {
                        withContext(Dispatchers.IO) { it.decryptApiKey() }
                    }.orEmpty()
                    if (searchKey.isBlank()) {
                        _statusText.value = "⚠️ 未配置搜索 Key，已跳过联网搜索"
                    } else {
                        _statusText.value = "🔍 正在联网搜索…"
                        try {
                            val results = withContext(Dispatchers.IO) {
                                SearchClient.search(searchKey, trimmed)
                            }
                            if (results.isNotEmpty()) {
                                // 只保留最新一条搜索上下文，避免历史里累积过期搜索结果
                                history.removeAll { it.role == "system" && it.content.startsWith(SEARCH_CONTEXT_PREFIX) }
                                history.add(AiClient.ChatMessage("system", buildSearchContext(results)))
                            }
                        } catch (e: SearchClient.SearchException) {
                            searchFailed = e.message
                        }
                    }
                }

                _statusText.value = if (searchFailed != null) {
                    "⚠️ 搜索失败（$searchFailed），已基于现有知识回答"
                } else {
                    "💬 AI 正在回答…"
                }

                // 启动本会话的应用级流式回答（页面销毁后依然继续，结束自动落盘历史）
                val sessionIdNow = sessionId
                val gen = ++streamGeneration
                val historySnapshot = history.toList()
                // 发送给 API 的历史做上下文裁剪（多轮后 token 不超限）；落盘仍用完整镜像
                val requestMessages = trimHistoryForRequest(historySnapshot)
                val streamFlow = AiStreamer.start(
                    sessionIdNow,
                    getApplication<Application>(),
                    baseUrl,
                    apiKey,
                    model,
                    requestMessages,
                    AiStreamer.SessionMeta(
                        sessionIdNow,
                        sessionArticleIds,
                        _title.value,
                        trimmed,
                        historySnapshot
                    )
                )
                observeAiStream(pendingId, gen, streamFlow)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消（如退出页面）必须向上传播，不能当作业务错误处理
                throw e
            } catch (e: Exception) {
                // 移除已加入历史但未获回复的用户消息，避免重发时重复；记录文本供"重发"
                if (history.isNotEmpty() && history.last().role == "user") {
                    lastFailedUserText = history.last().content
                    history.removeAt(history.lastIndex)
                }
                replaceMessage(pendingId, UiMessage(pendingId, "error", e.message ?: "请求失败"))
                _isSending.value = false
                _statusText.value = null
                saveSession()
            }
        }
    }

    /** 订阅本会话的流式回答：把增量推到界面；完成/出错后收尾并停止订阅。 */
    private fun observeAiStream(
        pendingId: Long,
        gen: Long,
        flow: kotlinx.coroutines.flow.StateFlow<AiStreamer.StreamState>
    ) {
        streamerJob?.cancel()
        streamerJob = viewModelScope.launch {
            flow.collect { st ->
                // 新消息已发出（代次变化）：本订阅属于旧轮，忽略其状态，绝不覆盖上轮回复
                if (gen != streamGeneration) return@collect
                updateMessage(pendingId) { it.copy(content = st.text, isStreaming = !st.complete) }
                if (st.complete) {
                    finishStream(pendingId, st)
                }
            }
        }
    }

    /** 流结束统一收尾：去标题"（生成中…）"后缀、落盘、结束发送态并停止订阅。 */
    private fun finishStream(pendingId: Long, st: AiStreamer.StreamState) {
        _title.value = _title.value.removeSuffix("（生成中…）")
        if (st.error != null) {
            // 移除未获回复的用户消息并记录其文本，供错误气泡"重发"
            if (history.isNotEmpty() && history.last().role == "user") {
                lastFailedUserText = history.last().content
                history.removeAt(history.lastIndex)
            }
            replaceMessage(pendingId, UiMessage(pendingId, "error", st.error))
            saveSession()
        } else {
            history.add(AiClient.ChatMessage("assistant", st.text))
            updateMessage(pendingId) { it.copy(content = st.text, isStreaming = false) }
            saveSession()
        }
        _isSending.value = false
        _statusText.value = null
        // 本轮完成：停止订阅（防止误覆盖已完成回复）
        streamerJob?.cancel()
    }

    /** 重发最近一次失败的 user 消息（错误气泡"重发"按钮调用）。 */
    fun retryLast() {
        val text = lastFailedUserText ?: return
        if (_isSending.value) return
        // 直接复用 send：内部会清空 lastFailedUserText 并重新入列
        send(text)
    }

    /** 更新指定 id 的消息（流式增量）：定位下标后就地替换，避免每次全量重建 */
    private fun updateMessage(id: Long, transform: (UiMessage) -> UiMessage) {
        val list = _messages.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        _messages.value = list.toMutableList().also { it[idx] = transform(it[idx]) }
    }

    /** 替换指定 id 的消息（错误态）：定位下标后就地替换，避免每次全量重建 */
    private fun replaceMessage(id: Long, newMsg: UiMessage) {
        val list = _messages.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        _messages.value = list.toMutableList().also { it[idx] = newMsg }
    }

    /**
     * 构建搜索结果注入文本：供 AI 优先参考核验（标注来源）。
     */
    private fun buildSearchContext(results: List<SearchClient.SearchResult>): String {
        val sb = StringBuilder("$SEARCH_CONTEXT_PREFIX 结果（请优先参考核验，并标注来源）：\n")
        results.take(5).forEachIndexed { i, r ->
            sb.append("${i + 1}. ").append(r.title).append("\n")
            sb.append(r.content.take(500)).append("\n")
            if (r.url.isNotBlank()) sb.append("来源：").append(r.url).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 持久化当前会话到 filesDir/ai_history/<sessionId>.json。
     * 仅在开启「保存与 AI 的对话」时生效；流式过程中不调用（避免频繁 IO），
     * 只在用户消息入列 / 回复完成 / 出错三个节点保存。
     */
    private fun saveSession() {
        if (!AppPrefs.aiHistoryEnabled) return
        if (sessionId <= 0L) return
        val title = _title.value
        // 与 AiStreamer 落盘一致：只持久化 user/assistant（error 仅展示不落盘）
        val msgs = _messages.value.filter { it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
        if (msgs.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AiStreamer.writeSessionFileLock(sessionId) {
                    try {
                        val dir = File(getApplication<Application>().filesDir, "ai_history").apply { mkdirs() }
                        val json = JSONObject().apply {
                            put("id", sessionId)
                            put("title", title)
                            put("createdAt", sessionId)
                            put("articleIds", JSONArray().apply { sessionArticleIds.forEach { put(it) } })
                            put("messages", JSONArray().apply {
                                msgs.forEach { m ->
                                    put(JSONObject().put("role", m.role).put("content", m.content))
                                }
                            })
                        }
                        File(dir, "$sessionId.json").writeText(json.toString())
                    } catch (_: Exception) { /* 保存失败不影响对话 */ }
                }
            }
        }
    }

    /**
     * 构造 system 提示词：注入文章全文作为上下文（多篇时标注来源，超长截断防 token 超限），
     * 并附上用户学习数据（练习次数 / 正确率 / 错误分布 / 高频易错内容），供 AI 针对性辅导。
     */
    private fun buildSystemPrompt(articles: List<Article>, records: List<PracticeRecord>): String {
        val maxCharsPerArticle = 12000 / articles.size.coerceAtLeast(1)
        val sb = StringBuilder(
            "你是 Blancall 学习助手（直接自称\"Blancall 学习助手\"即可，不要自称或解释为\"完形填空\"）。" +
                "用户正在学习以下文章内容，请基于这些内容回答问题（可以解释难点、出练习题、总结要点等）：\n"
        )
        articles.forEach { a ->
            sb.append("【").append(a.title).append("】\n")
            sb.append(a.content.take(maxCharsPerArticle))
            sb.append("\n\n")
        }
        // 学习数据接入（无记录时跳过）
        buildStudyDataSummary(records)?.let { sb.append(it) }
        sb.append("请用简洁清晰的中文回答。")
        return sb.toString()
    }

    /**
     * 构建学习数据摘要（无练习记录时返回 null）：
     * 练习次数 / 模式分布 / 总体正确率 / 错误类型分布 / 高频易错内容。
     */
    private fun buildStudyDataSummary(records: List<PracticeRecord>): String? {
        if (records.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("\n【用户学习数据】\n")
        sb.append("练习次数：").append(records.size).append(" 次\n")

        val modes = records.groupBy { it.mode }
        if (modes.isNotEmpty()) {
            sb.append("练习模式：")
            sb.append(modes.entries.joinToString("、") { (m, list) ->
                when (m) {
                    "SENTENCE" -> "句子挖空 ${list.size}次"
                    "WORD" -> "字词挖空 ${list.size}次"
                    "REVERSE" -> "反向默写 ${list.size}次"
                    else -> "其他 ${list.size}次"
                }
            })
            sb.append("\n")
        }

        val totalBlanks = records.sumOf { it.totalBlanks }
        val totalCorrect = records.sumOf { it.correctCount }
        if (totalBlanks > 0) {
            sb.append("总体正确率：").append(totalCorrect * 100 / totalBlanks).append("%\n")
        }

        val mistakes = records.flatMap { it.mistakes }
        if (mistakes.isNotEmpty()) {
            val typo = mistakes.count { it.errorType == "TYPO" }
            val missing = mistakes.count { it.errorType == "MISSING" }
            val extra = mistakes.count { it.errorType == "EXTRA" }
            val order = mistakes.count { it.errorType == "WRONG_ORDER" }
            val incorrect = mistakes.count { it.errorType == "INCORRECT" }
            sb.append("错误类型分布：")
            val parts = mutableListOf<String>()
            if (typo > 0) parts.add("错别字 $typo")
            if (missing > 0) parts.add("漏字 $missing")
            if (extra > 0) parts.add("多字 $extra")
            if (order > 0) parts.add("顺序错 $order")
            if (incorrect > 0) parts.add("不正确 $incorrect")
            sb.append(parts.joinToString("、")).append("\n")

            // 高频易错内容（取前 8 个，长文本截断防 token 浪费）
            val topErrors = mistakes.map { it.correctAnswer }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
            if (topErrors.isNotEmpty()) {
                sb.append("高频易错内容：")
                sb.append(topErrors.joinToString("、") { "${it.key.take(20)}（错${it.value}次）" })
                sb.append("\n")
            }
        }
        sb.append("请结合以上学习数据针对性辅导：如针对易错内容出练习题、分析错误原因、给出复习建议。")
        return sb.toString()
    }
}
