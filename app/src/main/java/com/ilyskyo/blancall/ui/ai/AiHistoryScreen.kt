// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.ai.AiStreamer
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史会话摘要（列表页用） */
data class AiHistorySession(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val messageCount: Int,
    val preview: String,
    val ongoing: Boolean = false
)

/** 历史会话中的单条消息 */
data class AiHistoryMessage(val role: String, val content: String)

/** 训练分析条目（训练分析文件读取结果） */
data class AiAnalysisItem(
    val createdAt: Long,
    val articleId: Long,
    val articleTitle: String,
    val modeLabel: String,
    val weakHints: Int,
    val strongHints: Int,
    val preview: String,
    val analysis: String
)

/** 读取全部历史会话（按时间倒序）；无历史返回空列表 */
fun loadAiHistorySessions(context: Context): List<AiHistorySession> {
    val dir = File(context.filesDir, "ai_history")
    if (!dir.exists()) return emptyList()
    val sessions = dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { file ->
        try {
            val json = JSONObject(file.readText())
            val msgs = json.optJSONArray("messages") ?: JSONArray()
            val lastContent = (0 until msgs.length()).mapNotNull { i ->
                msgs.optJSONObject(i)?.optString("content")
            }.lastOrNull { it.isNotBlank() } ?: ""
            AiHistorySession(
                id = json.optLong("id", 0L),
                title = json.optString("title", "AI 对话"),
                createdAt = json.optLong("createdAt", 0L),
                messageCount = msgs.length(),
                preview = lastContent
            )
        } catch (_: Exception) { null }
    }?.sortedByDescending { it.createdAt } ?: emptyList()
    return sessions
}

/** 删除某个历史会话文件 */
fun deleteAiHistory(context: Context, sessionId: Long) {
    runCatching { File(context.filesDir, "ai_history/$sessionId.json").delete() }
}

/** 读取历史会话的完整数据（文章 ids + 消息），用于「继续对话」恢复上下文；不存在或损坏返回 null */
fun loadAiSessionData(context: Context, sessionId: Long): Pair<List<Long>, List<AiHistoryMessage>>? {
    return try {
        val file = File(context.filesDir, "ai_history/$sessionId.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val ids = json.optJSONArray("articleIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optLong(i, 0L).takeIf { it > 0 } }
        } ?: emptyList()
        val msgs = json.optJSONArray("messages") ?: JSONArray()
        val list = (0 until msgs.length()).mapNotNull { i ->
            val m = msgs.optJSONObject(i) ?: return@mapNotNull null
            val role = m.optString("role", "")
            val content = m.optString("content", "")
            if (content.isBlank()) null else AiHistoryMessage(role, content)
        }
        ids to list
    } catch (_: Exception) {
        null
    }
}

// ── 训练分析 ──

/** 读取全部训练分析（按时间倒序） */
fun loadAiAnalyses(context: Context): List<AiAnalysisItem> {
    val dir = File(context.filesDir, "ai_analysis")
    if (!dir.exists()) return emptyList()
    return dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { file ->
        try {
            val json = JSONObject(file.readText())
            val analysis = json.optString("analysis", "")
            AiAnalysisItem(
                createdAt = json.optLong("createdAt", 0L),
                articleId = json.optLong("articleId", 0L),
                articleTitle = json.optString("articleTitle", "未命名文章"),
                modeLabel = json.optString("modeLabel", ""),
                weakHints = json.optInt("weakHints", 0),
                strongHints = json.optInt("strongHints", 0),
                preview = analysis
                    .replace(Regex("""[#*`>]"""), "")   // 去 Markdown 标题/强调/引用符号
                    .replace(Regex("""\s+"""), " ")     // 压缩空白与换行，预览更紧凑
                    .trim()
                    .take(110),
                analysis = analysis
            )
        } catch (_: Exception) { null }
    }?.sortedByDescending { it.createdAt } ?: emptyList()
}

/** 删除某条训练分析 */
fun deleteAiAnalysis(context: Context, createdAt: Long) {
    runCatching { File(context.filesDir, "ai_analysis/$createdAt.json").delete() }
}

/** 当前"进行中"的 AI 回答会话（由应用级 AiStreamer 提供），合并进历史列表 */
private fun ongoingSessions(): List<AiHistorySession> =
    AiStreamer.activeSessions().mapNotNull { id ->
        val meta = AiStreamer.metaOf(id) ?: return@mapNotNull null
        val st = AiStreamer.stateOf(id)?.value
        AiHistorySession(
            id = id,
            title = meta.title,
            createdAt = meta.sessionId,
            messageCount = 2,
            preview = st?.text ?: "",
            ongoing = true
        )
    }

/**
 * AI 历史页：训练分析 + 对话历史（含进行中会话），支持删除。
 *
 * @param initialSection "analysis" 时只显示训练分析区块（供「查看全部训练分析」入口）；
 *                       默认 "all" 显示训练分析 + 对话历史。
 */
@Composable
fun AiHistoryScreen(
    navController: NavController,
    onBack: (() -> Unit)? = null,
    initialSection: String = "all"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val analysisOnly = initialSection == "analysis"

    var sessions by remember { mutableStateOf<List<AiHistorySession>>(emptyList()) }
    var analyses by remember { mutableStateOf<List<AiAnalysisItem>>(emptyList()) }
    var showAnalysis by remember { mutableStateOf<AiAnalysisItem?>(null) }
    var pendingDeleteSession by remember { mutableStateOf<AiHistorySession?>(null) }
    var pendingDeleteAnalysis by remember { mutableStateOf<AiAnalysisItem?>(null) }

    // 磁盘会话/分析文件的 mtime 快照：轮询时先比对，无变化则跳过全量读取（降耗）
    var lastSessionMtimes by remember { mutableStateOf<Map<String, Long>?>(null) }
    var lastAnalysisMtimes by remember { mutableStateOf<Map<String, Long>?>(null) }

    /** 读取目录下 *.json 文件的 mtime 快照 */
    fun dirMtimes(dir: File): Map<String, Long> =
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.associate { it.name to it.lastModified() }
            ?: emptyMap()

    suspend fun refresh(force: Boolean = false) {
        val sessionDir = File(context.filesDir, "ai_history")
        val analysisDir = File(context.filesDir, "ai_analysis")
        // mtime 未变化且非强制：跳过磁盘全量读取（进行中会话依赖内存态，始终刷新）
        val sessionMtimes = withContext(Dispatchers.IO) { dirMtimes(sessionDir) }
        val analysisMtimes = withContext(Dispatchers.IO) { dirMtimes(analysisDir) }
        val sessionsChanged = force || lastSessionMtimes != sessionMtimes
        val analysesChanged = force || lastAnalysisMtimes != analysisMtimes
        if (sessionsChanged || analysesChanged) {
            val disk = if (sessionsChanged) withContext(Dispatchers.IO) { loadAiHistorySessions(context) }
            else sessions.filterNot { it.ongoing }
            val al = if (analysesChanged) withContext(Dispatchers.IO) { loadAiAnalyses(context) }
            else analyses
            val ongoing = ongoingSessions()
            sessions = (ongoing + disk).distinctBy { it.id }
            analyses = al
            lastSessionMtimes = sessionMtimes
            lastAnalysisMtimes = analysisMtimes
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(3000) // 周期刷新：捕获进行中会话的增量与完成态
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            // ── 顶部标题栏（返回 + 标题） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { onBack?.invoke() ?: navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    if (analysisOnly) "训练分析" else "AI 历史",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ═══ 训练分析 ═══
                if (analyses.isNotEmpty()) {
                    item(key = "analysisTitle") {
                        SectionTitle("训练分析")
                    }
                    items(analyses, key = { "an_${it.createdAt}" }) { item ->
                        AnalysisCard(
                            item = item,
                            dateFormat = dateFormat,
                            onClick = { showAnalysis = item },
                            onDelete = { pendingDeleteAnalysis = item }
                        )
                    }
                }

                // ═══ 对话历史（analysisOnly 视图下不显示）═══
                if (!analysisOnly) {
                    if (sessions.isEmpty() && analyses.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 96.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("🤖", fontSize = 40.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("暂无记录", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text("开启「保存与 AI 的对话」后，对话会自动保存在这里；训练分析也会保存在这里",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    } else {
                        item(key = "chatTitle") {
                            SectionTitle("对话")
                        }
                        items(sessions, key = { "s_${it.id}" }) { session ->
                            HistorySessionCard(
                                session = session,
                                dateFormat = dateFormat,
                                onClick = { navController.navigate("ai_resume/${session.id}") },
                                onDelete = { pendingDeleteSession = session }
                            )
                        }
                    }
                } else if (analyses.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 96.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📊", fontSize = 40.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无训练分析", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text("完成一次练习并生成分析后，会保存在这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }

    // ── 训练分析详情 ──
    showAnalysis?.let { item ->
        BlancallAlertDialog(
            onDismissRequest = { showAnalysis = null },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            title = { Text("训练分析 · ${item.articleTitle}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${item.modeLabel} · ${dateFormat.format(Date(item.createdAt))}" +
                            if (item.weakHints > 0 || item.strongHints > 0)
                                " · 弱提示 ${item.weakHints} / 强提示 ${item.strongHints}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MarkdownText(item.analysis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAiAnalysis(context, item.createdAt)
                    showAnalysis = null
                    scope.launch { refresh() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showAnalysis = null }) { Text("关闭") }
            }
        )
    }

    // ── 删除会话确认 ──
    pendingDeleteSession?.let { session ->
        BlancallAlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            title = { Text("删除会话") },
            text = { Text("确定删除这条对话记录吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    AiStreamer.remove(session.id)
                    deleteAiHistory(context, session.id)
                    pendingDeleteSession = null
                    scope.launch { refresh() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") }
            }
        )
    }

    // ── 删除分析确认 ──
    pendingDeleteAnalysis?.let { item ->
        BlancallAlertDialog(
            onDismissRequest = { pendingDeleteAnalysis = null },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            title = { Text("删除训练分析") },
            text = { Text("确定删除这条训练分析吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteAiAnalysis(context, item.createdAt)
                    pendingDeleteAnalysis = null
                    scope.launch { refresh() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAnalysis = null }) { Text("取消") }
            }
        )
    }
}

/** 分区小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** 历史会话卡片：标题 + 时间 + 消息数 + 最后消息预览 + 删除 */
@Composable
private fun HistorySessionCard(
    session: AiHistorySession,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dateFormat.format(Date(session.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (session.ongoing) {
                        if (session.preview.isNotBlank()) session.preview + " ▍" else "AI 正在回答…"
                    } else if (session.preview.isNotBlank()) {
                        session.preview
                    } else "（空对话）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Text("🗑", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (session.ongoing) "生成中…" else "${session.messageCount} 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = if (session.ongoing) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 训练分析卡片：文章名 + 时间 + 预览 + 删除 */
@Composable
private fun AnalysisCard(
    item: AiAnalysisItem,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "训练分析 · ${item.articleTitle}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dateFormat.format(Date(item.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.preview.ifBlank { "（空分析）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Text("🗑", fontSize = 14.sp)
                }
            }
        }
    }
}