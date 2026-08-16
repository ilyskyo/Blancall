// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.data.ai.AiClient
import com.ilyskyo.blancall.data.ai.SearchClient
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.ai.loadAiSessionData
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
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

    private val _title = MutableStateFlow("AI 助手")
    val title: StateFlow<String> = _title.asStateFlow()

    // 发送给 API 的完整历史（首条为 system）
    private val history = mutableListOf<AiClient.ChatMessage>()

    // 当前会话持久化信息（开启「保存与 AI 的对话」时使用）
    private var sessionId: Long = 0L
    private var sessionArticleIds: List<Long> = emptyList()

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
            // 新会话标识（保存历史用）
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
        }
    }

    /**
     * 发送消息并流式接收回复。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isSending.value) return

        val userMsg = UiMessage(idCounter.incrementAndGet(), "user", trimmed)
        val pendingId = idCounter.incrementAndGet()
        history.add(AiClient.ChatMessage("user", trimmed))
        _messages.value = _messages.value + userMsg + UiMessage(pendingId, "assistant", "", isStreaming = true)
        _isSending.value = true
        // 用户消息已入列：持久化一次
        saveSession()

        viewModelScope.launch {
            val sb = StringBuilder()
            try {
                // Keystore 解密放 IO 线程，避免主线程阻塞（部分设备硬件安全模块较慢）
                val apiKey = withContext(Dispatchers.IO) { AppPrefs.aiApiKey }
                if (apiKey.isBlank()) {
                    throw AiClient.AiException("尚未配置 API Key，请到「设置 → 启用 AI 功能」中填写")
                }
                val baseUrl = AppPrefs.aiBaseUrl
                if (baseUrl.isBlank()) {
                    throw AiClient.AiException("尚未配置 API 地址，请到「设置 → 启用 AI 功能」中填写")
                }
                val model = AppPrefs.aiModel
                if (model.isBlank()) {
                    throw AiClient.AiException("尚未配置模型名，请到「设置 → 启用 AI 功能」中填写")
                }

                // ── 联网搜索核验（开启且配置了搜索 Key 时）──
                var searchFailed: String? = null
                if (AppPrefs.aiSearchEnabled) {
                    val searchKey = withContext(Dispatchers.IO) { AppPrefs.aiSearchApiKey }
                    if (searchKey.isBlank()) {
                        _statusText.value = "⚠️ 未配置搜索 Key，已跳过联网搜索"
                    } else {
                        _statusText.value = "🔍 正在联网搜索…"
                        try {
                            val results = withContext(Dispatchers.IO) {
                                SearchClient.search(searchKey, trimmed)
                            }
                            if (results.isNotEmpty()) {
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

                AiClient.streamChat(baseUrl, apiKey, model, history.toList()).collect { delta ->
                    sb.append(delta)
                    updateMessage(pendingId) { it.copy(content = sb.toString(), isStreaming = true) }
                }
                history.add(AiClient.ChatMessage("assistant", sb.toString()))
                updateMessage(pendingId) { it.copy(isStreaming = false) }
                // AI 回复完成：持久化最终内容
                saveSession()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消（如退出页面）必须向上传播，不能当作业务错误处理
                throw e
            } catch (e: AiClient.AiException) {
                // 移除已加入历史但未获回复的用户消息，避免重发时重复
                if (history.isNotEmpty() && history.last().role == "user") {
                    history.removeAt(history.lastIndex)
                }
                replaceMessage(pendingId, UiMessage(pendingId, "error", e.message ?: "请求失败"))
                saveSession()
            } catch (e: Exception) {
                if (history.isNotEmpty() && history.last().role == "user") {
                    history.removeAt(history.lastIndex)
                }
                replaceMessage(pendingId, UiMessage(pendingId, "error", "请求失败：${e.message}"))
                saveSession()
            } finally {
                _isSending.value = false
                _statusText.value = null
            }
        }
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
        val sb = StringBuilder("以下是针对用户当前问题的最新网络搜索结果（请优先参考核验，并标注来源）：\n")
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
        val msgs = _messages.value.filter { it.content.isNotBlank() }
        if (msgs.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
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
