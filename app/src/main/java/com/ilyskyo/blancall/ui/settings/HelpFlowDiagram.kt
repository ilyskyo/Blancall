// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R

/**
 * 「帮助与说明」顶部的全流程图。
 *
 * 为什么要画：旧版帮助是八段折叠文字，用户得逐段展开才知道「今日计划 / 任务流 / 练习 /
 * 数据页」怎么串起来。图放在最上、文字留在下面，先给骨架再给细节。
 *
 * 画法约定：所有线条、箭头、序号底都是 Compose 自绘（Canvas / 形状），不用 emoji、
 * 不用现成排版符号；颜色全取主题色，明暗两套自动跟随。
 */
@Composable
internal fun HelpFlowOverviewCard() {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.help_flow_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.help_flow_intro),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            FlowStep(1, stringResource(R.string.help_flow_1_t), stringResource(R.string.help_flow_1_d), accent)
            FlowConnector(accent)
            FlowStep(2, stringResource(R.string.help_flow_2_t), stringResource(R.string.help_flow_2_d), accent)
            FlowConnector(accent)
            FlowStep(3, stringResource(R.string.help_flow_3_t), stringResource(R.string.help_flow_3_d), accent)
            FlowConnector(accent)
            FlowStep(4, stringResource(R.string.help_flow_4_t), stringResource(R.string.help_flow_4_d), accent)
            FlowConnector(accent)
            FlowStep(5, stringResource(R.string.help_flow_5_t), stringResource(R.string.help_flow_5_d), accent)

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 闭环提示：一段回转箭头，把「练完还会回来」这件事画出来
                Canvas(Modifier.size(width = 30.dp, height = 26.dp)) {
                    val stroke = 1.6.dp.toPx()
                    val right = size.width - 4.dp.toPx()
                    val left = 8.dp.toPx()
                    val top = 4.dp.toPx()
                    val bottom = size.height - 2.dp.toPx()
                    drawLine(accent.copy(alpha = 0.55f), Offset(right, bottom), Offset(right, (top + bottom) / 2), stroke, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.55f), Offset(right, (top + bottom) / 2), Offset(left, (top + bottom) / 2), stroke, StrokeCap.Round)
                    drawLine(accent.copy(alpha = 0.55f), Offset(left, (top + bottom) / 2), Offset(left, top), stroke, StrokeCap.Round)
                    // 箭头：两条斜线，指向「回到上面」
                    drawLine(accent, Offset(left, top), Offset(left - 3.5.dp.toPx(), top + 4.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(accent, Offset(left, top), Offset(left + 3.5.dp.toPx(), top + 4.dp.toPx()), stroke, StrokeCap.Round)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.help_flow_loop),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FlowStep(index: Int, title: String, desc: String, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 步骤之间的竖线 + 箭头（序号圆 26dp，线对齐圆心）。 */
@Composable
private fun FlowConnector(accent: Color) {
    Canvas(
        Modifier
            .padding(start = 12.dp)
            .size(width = 2.dp, height = 16.dp)
    ) {
        drawLine(
            accent.copy(alpha = 0.45f),
            Offset(size.width / 2, 0f),
            Offset(size.width / 2, size.height),
            strokeWidth = size.width,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 共通数据：同一个值可能有好几个入口，改哪一处都是改同一份。
 *
 * 用户反馈「设置里也有每日学习时长、数据页也有今日目标，到底以哪个为准」——
 * 这一块就是回答「同一份数据、多个入口」，不再让用户以为存在两套目标。
 */
@Composable
internal fun HelpSharedDataCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.help_shared_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.help_shared_intro),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SharedRow(stringResource(R.string.help_shared_1_t), stringResource(R.string.help_shared_1_d))
            SharedRow(stringResource(R.string.help_shared_2_t), stringResource(R.string.help_shared_2_d))
            SharedRow(stringResource(R.string.help_shared_3_t), stringResource(R.string.help_shared_3_d))
            SharedRow(stringResource(R.string.help_shared_4_t), stringResource(R.string.help_shared_4_d))
        }
    }
}

@Composable
private fun SharedRow(name: String, desc: String) {
    val dotColor = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.padding(top = 6.dp).size(5.dp)) {
            drawCircle(dotColor, radius = size.minDimension / 2)
        }
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
