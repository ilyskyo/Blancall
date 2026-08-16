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
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassCard
import kotlinx.coroutines.Dispatchers
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
    val preview: String
)

/** 历史会话中的单条消息 */
data class AiHistoryMessage(val role: String, val content: String)

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

/**
 * AI 历史对话列表页：展示全部保存的会话，
 * 点击进入可继续对话的详情页（ai_resume/{sessionId}）。
 */
@Composable
fun AiHistoryScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 会话列表（IO 读取）
    var sessions by remember { mutableStateOf<List<AiHistorySession>>(emptyList()) }
    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            loadAiHistorySessions(context)
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
                // 触点展开方式进入时由 onBack 收起（popBackStack 在栈底无效）
                BackButton(onClick = { onBack?.invoke() ?: navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "AI 历史对话",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🤖", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("暂无历史对话", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("开启「保存与 AI 的对话」后，对话会自动保存在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        HistorySessionCard(
                            session = session,
                            dateFormat = dateFormat,
                            onClick = { navController.navigate("ai_resume/${session.id}") }
                        )
                    }
                }
            }
        }
    }
}

/** 历史会话卡片：标题 + 时间 + 消息数 + 最后消息预览 */
@Composable
private fun HistorySessionCard(
    session: AiHistorySession,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(Modifier.padding(14.dp)) {
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
            Text(
                if (session.preview.isNotBlank()) session.preview else "（空对话）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${session.messageCount} 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
