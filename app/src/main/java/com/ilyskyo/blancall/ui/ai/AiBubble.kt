// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import com.ilyskyo.blancall.ui.common.MarkdownText
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * AI 对话气泡（公共组件）：用户右（主色）/ AI 左（浅色）/ 错误（红色）。
 * 用于 AI 对话页与历史对话查看页；onLongClick 非空时支持长按复制。
 */
@Composable
fun AiBubble(
    role: String,
    content: String,
    isStreaming: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val isUser = role == "user"
    val isError = role == "error"
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        isUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 气泡圆角：同侧小角（聊天气泡语言）
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = containerColor,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .then(
                    if (onLongClick != null) Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongClick
                    ) else Modifier
                )
        ) {
            // AI 内容支持 Markdown 渲染；流式中用纯文本（避免每 token 整体 re-parse 卡顿），完成后只解析一次
            if (!isUser && !isError && content.isNotBlank()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isStreaming) {
                        Text(
                            text = content + " ▍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        MarkdownText(
                            markdown = content,
                            contentColor = contentColor
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        // 流式生成中：尾部光标提示
                        text = content + if (isStreaming && content.isNotEmpty()) " ▍" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip
                    )
                    // 错误气泡：一键重发（复用最近一次失败的 user 文本）
                    if (isError && onRetry != null) {
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                        ) {
                            Text("重发", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
