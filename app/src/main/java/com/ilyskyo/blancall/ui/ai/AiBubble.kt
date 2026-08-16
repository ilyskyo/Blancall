// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    onLongClick: (() -> Unit)? = null
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
            Text(
                // 流式生成中：尾部光标提示
                text = content + if (isStreaming && content.isNotEmpty()) " ▍" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
