// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用级 AI 流式回答管理器（单例，多会话并发）：
 * - 每个会话（sessionId）拥有独立的流式 Job 与状态，互不取消；
 * - AI 回答在应用作用域运行：对话页退出（ViewModel 销毁）后回答依然继续；
 * - 会话完成（或出错）时自动落盘历史到 ai_history/<sessionId>.json；
 * - 历史页可列出"进行中"会话（[activeSessions]）并持续追踪。
 */
object AiStreamer {

    /** 流式状态：累计文本 + 是否完成 +（成功/失败）消息 */
    data class StreamState(
        val text: String,
        val complete: Boolean,
        val error: String? = null
    )

    /** 一次完整会话的元信息（供完成时持久化） */
    data class SessionMeta(
        val sessionId: Long,
        val articleIds: List<Long>,
        val title: String,
        val userMessage: String,
        /** 发起本次请求时的完整对话历史（含本次 user，不含 system/新回复） */
        val historyMessages: List<AiClient.ChatMessage> = emptyList()
    )

    private class StreamEntry(
        val job: Job,
        val state: MutableStateFlow<StreamState>,
        val meta: SessionMeta,
        @Volatile var completedAt: Long? = null
    )

    private val sessions = ConcurrentHashMap<Long, StreamEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 完成会话的内存保留时长：过期后自动清理，防内存增长 */
    private val COMPLETED_RETENTION_MS = 5 * 60 * 1000L

    /**
     * 启动（或取代）某个会话的一次流式回答。
     * 不同 [sessionId] 的会话互不影响，可同时后台生成。
     * @return 该会话自己的状态流（订阅它获取增量）
     */
    fun start(
        sessionId: Long,
        appContext: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<AiClient.ChatMessage>,
        session: SessionMeta
    ): StateFlow<StreamState> {
        prune()
        // 同一会话重复发起：取消旧回答（覆盖语义）
        startInternal(sessionId, appContext, baseUrl, apiKey, model, messages, session)
        return sessions[sessionId]!!.state.asStateFlow()
    }

    private fun startInternal(
        sessionId: Long,
        appContext: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<AiClient.ChatMessage>,
        session: SessionMeta
    ) {
        cancelInternal(sessionId)
        val state = MutableStateFlow(StreamState("", complete = false))
        val job = scope.launch {
            val sb = StringBuilder()
            try {
                val completed = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                    AiClient.streamChat(baseUrl, apiKey, model, messages)
                        .collect { sb.append(it); state.value = StreamState(sb.toString(), complete = false) }
                    true
                } ?: false
                if (!completed) {
                    state.value = StreamState(sb.toString(), complete = true, error = "回答超时")
                } else {
                    state.value = StreamState(sb.toString(), complete = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = StreamState(sb.toString(), complete = true, error = e.message ?: "请求失败")
            }
            // 记录完成时间，启动延时清理
            sessions[sessionId]?.completedAt = System.currentTimeMillis()
            // 结束：尝试落盘历史（页面可能已销毁，这里不依赖 ViewModel）
            saveSessionQuietly(appContext, session, state.value)
            scope.launch {
                delay(COMPLETED_RETENTION_MS)
                prune()
            }
        }
        sessions[sessionId] = StreamEntry(job, state, session)
    }

    /** 清理超期保留的已完成会话（释放内存）；进行中会话永不清理 */
    private fun prune() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { (id, e) ->
            val done = e.completedAt
            done != null && (now - done) > COMPLETED_RETENTION_MS
        }
    }

    /** 查询某会话的状态流（不存在返回 null） */
    fun stateOf(sessionId: Long): StateFlow<StreamState>? = sessions[sessionId]?.state

    /** 查询某会话的元信息（进行中会话的标题/文章等；不存在返回 null） */
    fun metaOf(sessionId: Long): SessionMeta? = sessions[sessionId]?.meta

    /** 已注册会话的元信息（含进行中与已完成但未清理的），供历史页展示 */
    fun metaOfAll(): List<SessionMeta> = sessions.values.map { it.meta }

    /** 当前仍未完成的会话 id 列表（供历史页展示"进行中"） */
    fun activeSessions(): List<Long> =
        sessions.entries
            .filter { it.value.state.value.complete.not() }
            .map { it.key }
            .sortedDescending()

    /** 取消并移除某会话（删除历史时调用）；已完成的会话直接移除内存态 */
    fun remove(sessionId: Long) {
        cancelInternal(sessionId)
        sessions.remove(sessionId)
    }

    private fun cancelInternal(sessionId: Long) {
        sessions[sessionId]?.job?.cancel()
    }

    private fun saveSessionQuietly(app: Context, meta: SessionMeta, st: StreamState?) {
        if (!AiConfigStoreSavedPrefs.enabled(app)) return
        // 出错时不落盘（与对话页行为一致，避免污染历史）
        val text = st?.takeIf { it.error == null }?.text?.takeIf { it.isNotBlank() } ?: return
        writeSessionFileLock(meta.sessionId) {
            try {
                val dir = File(app.filesDir, "ai_history").apply { mkdirs() }
                // 完整历史 = 请求时的全部 user/assistant（过滤 system）+ 本次 AI 回复
                val msgArr = JSONArray()
                meta.historyMessages.forEach { m ->
                    if (m.role == "user" || m.role == "assistant") {
                        msgArr.put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                }
                msgArr.put(JSONObject().put("role", "assistant").put("content", text))
                val json = JSONObject().apply {
                    put("id", meta.sessionId)
                    put("title", meta.title)
                    put("createdAt", meta.sessionId)
                    put("articleIds", JSONArray().apply { meta.articleIds.forEach { put(it) } })
                    put("messages", msgArr)
                }
                File(dir, "${meta.sessionId}.json").writeText(json.toString())
            } catch (_: Exception) { /* 保存失败不影响回答 */ }
        }
    }

    /**
     * 按 sessionId 加文件写锁：对话页 [AiViewModel] 的 saveSession 与应用级后台保存并发写同一文件时，
     * 通过 synchronized 于路径串，避免整文件覆写撕裂（torn write）。
     */
    fun writeSessionFileLock(sessionId: Long, block: () -> Unit) {
        synchronized("ai_history_$sessionId") {
            block()
        }
    }

    private const val STREAM_TIMEOUT_MS = 120_000L
}

/** 轻量开关读取：避免 AiStreamer 依赖 AppPrefs（ui.theme）造成循环 */
internal object AiConfigStoreSavedPrefs {
    fun enabled(app: Context): Boolean =
        app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("ai_history_enabled", false)
}