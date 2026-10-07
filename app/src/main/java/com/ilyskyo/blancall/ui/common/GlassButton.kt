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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 磨砂玻璃按钮（无渐变版）：半透明底 + 细描边 + 大圆角。
 * 已移除顶部高光 verticalGradient 装饰。
 *
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
    val isDark = isBlancallDark()
    val bgColor = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    }
    val shape = RoundedCornerShape(14.dp)
    // 按压反馈走统一档（缩放＋亮度＋焦点/悬停），涟漪退出这里。
    // 改造前这个组件是**显式** `indication = ripple()` —— 全 app 唯一主动要涟漪的地方，
    // 而它是一块 14dp 圆角的玻璃面，水波从文字底下铺开的观感和「玻璃被按下去」是两种语言。
    val src = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .pressFeedback(src, PressTier.Container, enabled = enabled)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        // 内容（文字/图案）在按钮内水平垂直居中
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}
