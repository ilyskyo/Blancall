// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 统计数字条目（公共组件）。
 *
 * 上方为加粗数值，下方为说明文字；用于 OverviewScreen 与 StatisticsScreen 的统计卡片。
 *
 * @param label    说明文字（为空时仅显示数值）
 * @param value    数值文本
 * @param modifier 外部约束
 * @param fontSize 可选数值字号；不传时使用 titleLarge（18sp），传值可缩小以适配紧凑布局
 */
@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit? = null,
    accentColor: Color? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val baseStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        val valueStyle = if (fontSize != null) baseStyle.copy(fontSize = fontSize) else baseStyle
        // 数值用主题色强调，与整体视觉一致；可传 accentColor 覆盖（如错误率用 error 色）
        Text(
            value,
            style = valueStyle,
            color = accentColor ?: MaterialTheme.colorScheme.primary
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
