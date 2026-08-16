// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.viewmodel.AiViewModel

/**
 * AI 学习助手对话页：
 * - 顶部：文章标题（多篇显示跨文）+ 系统返回手势退出
 * - 中部：对话气泡列表（用户右 / AI 左，流式实时增长，错误红色提示）
 * - 底部：输入框 + 发送按钮
 */
@Composable
fun AiScreen(navController: NavController, articleIds: List<Long> = emptyList(), resumeSessionId: Long? = null) {
    val vm: AiViewModel = viewModel()
    val messages by vm.messages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val statusText by vm.statusText.collectAsState()
    val title by vm.title.collectAsState()
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 复制整段对话（含角色前缀，方便粘贴分享）
    val copyAll = {
        val text = messages.joinToString("\n") { m ->
            when (m.role) {
                "user" -> "我：${m.content}"
                "error" -> "[错误] ${m.content}"
                else -> "Blancall：${m.content}"
            }
        }
        if (text.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("AI 对话", text))
            Toast.makeText(context, "对话已复制", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(articleIds, resumeSessionId) {
        if (resumeSessionId != null) {
            // 从历史会话恢复（继续对话）
            vm.resumeSession(resumeSessionId)
        } else {
            vm.initContext(articleIds)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            // ── 顶部标题栏（返回 + 标题 + 复制） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 复制对话（整段带角色前缀）
                TextButton(
                    onClick = copyAll,
                    enabled = messages.isNotEmpty()
                ) {
                    Text("复制对话", style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 对话消息列表 ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    AiBubble(
                        role = msg.role,
                        content = msg.content,
                        isStreaming = msg.isStreaming,
                        onLongClick = {
                            // 长按复制单条消息
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("AI 消息", msg.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // ── 底部输入区 ──
            // 发送过程状态提示（搜索中 / AI 回答中 / 搜索失败）
            statusText?.let { st ->
                Text(
                    st,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 2000) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入问题…") },
                    enabled = !isSending,
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                        if (input.isNotBlank() && !isSending) {
                            vm.send(input)
                            input = ""
                        }
                    })
                )
                Spacer(Modifier.width(8.dp))
                // 发送按钮（发送中转圈）
                FilledIconButton(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !isSending,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        AppIcon(
                        kind = AppIconKind.ArrowForward,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    }
                }
            }
        }
    }
}

