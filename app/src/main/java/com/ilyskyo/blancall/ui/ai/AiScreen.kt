// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.CoachMark
import com.ilyskyo.blancall.ui.common.CoachShape
import com.ilyskyo.blancall.ui.common.coachAnchor
import com.ilyskyo.blancall.ui.common.rememberCoachAnchor
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
    val listAnchor = rememberCoachAnchor()
    val vm: AiViewModel = viewModel()
    val messages by vm.messages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val statusText by vm.statusText.collectAsState()
    val title by vm.title.collectAsState()
    var input by remember { mutableStateOf("") }
    val context = LocalContext.current
    // 文案一律经 LocalResources 读取：LocalContext 取资源会在配置切换时读到旧值（lint 也会拦）
    val resources = LocalResources.current

    // 复制整段对话（含角色前缀，方便粘贴分享）
    val copyAll = {
        val text = messages.joinToString("\n") { m ->
            when (m.role) {
                "user" -> resources.getString(com.ilyskyo.blancall.R.string.ai_you_prefix) + m.content
                "error" -> resources.getString(com.ilyskyo.blancall.R.string.ai_error_prefix) + " " + m.content
                else -> "Blancall：${m.content}"
            }
        }
        if (text.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(resources.getString(com.ilyskyo.blancall.R.string.ai_chat_title), text))
            Toast.makeText(context, resources.getString(com.ilyskyo.blancall.R.string.ai_chat_copied), Toast.LENGTH_SHORT).show()
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
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 氛围光斑背景：让气泡/输入框透出层次（与首页/统计页统一玻璃语言）
        AmbientBackground()
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
                // 复制对话（整段带角色前缀）与历史记录入口
                TextButton(
                    onClick = { navController.navigate("ai_history") }
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_history_title), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = copyAll,
                    enabled = messages.isNotEmpty()
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_copy_chat), style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── 对话消息列表 ──
            val listState = rememberLazyListState()
            // 新消息/流式回答增长时自动滚动到底（用户手动上翻时不抢滚）
            LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
                if (messages.isEmpty()) return@LaunchedEffect
                val last = messages.lastIndex
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= last - 1) {
                    listState.animateScrollToItem(last)
                }
            }
            // 「长按消息复制」：AI 气泡是长按交互，但气泡本身没有任何提示
            CoachMark(
                coachKey = "ai_bubble_long_press",
                anchor = listAnchor,
                title = stringResource(com.ilyskyo.blancall.R.string.coach_ai_bubble_title),
                body = stringResource(com.ilyskyo.blancall.R.string.coach_ai_bubble_body),
                shape = CoachShape.Card,
                enabled = messages.isNotEmpty(),
            )
            LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    // 首条气泡锚点：长按复制的指引圈只圈第一条（圈整块列表等于没圈）
                    Box(if (msg.id == messages.firstOrNull()?.id) Modifier.coachAnchor(listAnchor) else Modifier) {
                    AiBubble(
                        role = msg.role,
                        content = msg.content,
                        isStreaming = msg.isStreaming,
                        onLongClick = {
                            // 长按复制单条消息
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText(resources.getString(com.ilyskyo.blancall.R.string.ai_message), msg.content))
                            Toast.makeText(context, resources.getString(com.ilyskyo.blancall.R.string.ai_copied), Toast.LENGTH_SHORT).show()
                        },
                        onRetry = if (msg.role == "error" && !isSending) ({ vm.retryLast() }) else null
                    )
                    }
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
                    placeholder = {
                        Text(if (isSending) stringResource(com.ilyskyo.blancall.R.string.ai_answering_can_type) else stringResource(com.ilyskyo.blancall.R.string.ai_input_hint))
                    },
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3,
                    // 透明容器：玻璃输入框（氛围底透出）
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    ),
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
                        tint = MaterialTheme.colorScheme.onPrimary,
                        contentDescription = stringResource(com.ilyskyo.blancall.R.string.ai_send)
                    )
                    }
                }
            }
        }
    }
}

