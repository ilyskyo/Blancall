// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 磨砂玻璃按钮：半透明底 + 顶部高光 + 细描边 + 大圆角，与 GlassCard 风格一致。
 * 用于页面右上角「添加 / 设置 / 导入 / 导出 / 筛选」等次要操作入口。
 *
 * @param enabled 为 false 时按钮不可点击且整体降低透明度（同 M3 按钮的禁用语义）
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val highlightColor = if (isDark) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    val bgColor = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA_DARK)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA_LIGHT)
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = enabled,
                onClick = onClick
            ),
        // 内容（文字/图案）在按钮内水平垂直居中
        contentAlignment = Alignment.Center
    ) {
        // 顶部高光：matchParentSize 不参与测量（不会撑大按钮宽度），渐变仅覆盖上半部
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to highlightColor,
                            0.5f to Color.Transparent
                        )
                    )
                )
        )
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
