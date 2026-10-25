// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import android.content.Context
import android.util.Log
import com.ilyskyo.blancall.R
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

    /**
     * 完成会话的内存保留时长：过期后自动清理，防内存增长。
     * 从 5 分钟放宽到 30 分钟：关闭「保存与 AI 的对话」的用户点到完成通知时，
     * 只能靠这份内存态恢复内容，5 分钟对「后台放一会儿再回来看」太短。
     */
    private val COMPLETED_RETENTION_MS = 30 * 60 * 1000L

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
        return startInternal(sessionId, appContext, baseUrl, apiKey, model, messages, session)
    }

    private fun startInternal(
        sessionId: Long,
        appContext: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<AiClient.ChatMessage>,
        session: SessionMeta
    ): StateFlow<StreamState> {
        cancelInternal(sessionId)
        // 请求由界面发起，此时应用一定在前台；先登记前后台跟踪，避免完成时把前台误判成后台
        AiReplyNotifier.ensureTracking(appContext, assumeForeground = true)
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
                    state.value = StreamState(
                        sb.toString(), complete = true,
                        error = appContext.getString(R.string.ai_reply_timeout)
                    )
                } else {
                    state.value = StreamState(sb.toString(), complete = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? AiClient.AiException)?.messageFor(appContext)
                    ?: e.message
                    ?: appContext.getString(R.string.ai_error_request_failed)
                state.value = StreamState(sb.toString(), complete = true, error = message)
            }
            // 记录完成时间，启动延时清理
            sessions[sessionId]?.completedAt = System.currentTimeMillis()
            // 先落盘历史：通知只是锦上添花，任何通知侧异常都不能连累用户丢失这一轮回复
            saveSessionQuietly(appContext, session, state.value)
            // 用户已把应用推到后台：用系统通知告诉他「回复好了」。
            // 前台完成不弹通知 —— 用户正看着流式输出，再弹是重复打扰。
            val finished = state.value
            if (finished.error == null && finished.text.isNotBlank() &&
                AiReplyNotifier.isAppInBackground()
            ) {
                runCatching { AiReplyNotifier.notifyCompleted(appContext, sessionId, session.title) }
                    .onFailure { Log.w(TAG, "AI 完成通知发送失败（回复已正常落盘）", it) }
            }
            scope.launch {
                delay(COMPLETED_RETENTION_MS)
                prune()
            }
        }
        val entry = StreamEntry(job, state, session)
        sessions[sessionId] = entry
        // 极快的回答可能在 entry 入表之前就结束了，那样协程里的 completedAt 写不到任何对象，
        // 这个 entry 就永远不会被 prune 掉。入表后补一次判定。
        if (state.value.complete) entry.completedAt = System.currentTimeMillis()
        return entry.state.asStateFlow()
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

    /**
     * 已成功完成、但可能还没落盘（历史开关关闭）的会话快照。
     * 供 [com.ilyskyo.blancall.ui.viewmodel.AiViewModel.resumeSession] 从通知返回时兜底；
     * 只认「完成且无错误且有正文」的状态，进行中/失败都不算。
     */
    fun completedSnapshot(sessionId: Long): Pair<SessionMeta, StreamState>? {
        val entry = sessions[sessionId] ?: return null
        val stateValue = entry.state.value
        if (!stateValue.complete || stateValue.error != null || stateValue.text.isBlank()) return null
        return entry.meta to stateValue
    }

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
    private const val TAG = "AiStreamer"
}

/** 轻量开关读取：避免 AiStreamer 依赖 AppPrefs（ui.theme）造成循环 */
internal object AiConfigStoreSavedPrefs {
    fun enabled(app: Context): Boolean =
        app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("ai_history_enabled", false)
}