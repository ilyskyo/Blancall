// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 错误类型分布条形图（公共组件）。
 *
 * 将五类错误（错别字 / 漏填 / 多填 / 顺序错误 / 完全错）的计数合并展示为横向比例条，
 * 总数内部自动汇总，仅渲染计数大于 0 的项。OverviewScreen 与 StatisticsScreen 共用。
 *
 * @param typo       错别字次数
 * @param missing    漏填次数
 * @param extra      多填次数
 * @param order      顺序错误次数
 * @param incorrect  完全答错次数（编辑距离过大，非前四类）
 * @param modifier   外部约束
 */
@Composable
fun MistakeBar(
    typo: Int,
    missing: Int,
    extra: Int,
    order: Int,
    incorrect: Int = 0,
    modifier: Modifier = Modifier
) {
    val total = typo + missing + extra + order + incorrect
    if (total <= 0) return

    Column(modifier = modifier) {
        if (typo > 0) {
            MistakeRow("错别字", typo, total, MaterialTheme.colorScheme.error)
        }
        if (missing > 0) {
            MistakeRow("漏填", missing, total, MaterialTheme.colorScheme.tertiary)
        }
        if (extra > 0) {
            MistakeRow("多填", extra, total, MaterialTheme.colorScheme.secondary)
        }
        if (order > 0) {
            MistakeRow("顺序错误", order, total, MaterialTheme.colorScheme.primary)
        }
        if (incorrect > 0) {
            MistakeRow("完全错", incorrect, total, MaterialTheme.colorScheme.errorContainer)
        }
    }
}

/**
 * 单条错误比例条。
 */
@Composable
private fun MistakeRow(label: String, count: Int, total: Int, color: Color) {
    val fraction = count.toFloat() / total
    // 入场动画：进度条从 0 生长到目标比例
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val animatedFraction by animateFloatAsState(
        targetValue = if (played) fraction else 0f,
        animationSpec = tween(600),
        label = "mistakeBar"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.75f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${count}次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
    }
}
