// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 记忆衰减曲线图：基于 FSRS-6 幂律遗忘曲线 R(t,S) = (1 + factor·t/S)^(-w20) 的留存率衰减可视化。
 *
 * 横轴 = 距今天数，纵轴 = 留存率 0..1。
 * - 曲线从"今天"开始绘制（[decayCurve] index 0）
 * - 当前留存率用圆点高亮，并标注百分比
 * - 阈值线（默认 30%）标示"建议复习"水位：曲线跌破该线即应复习
 *
 * @param decayCurve  未来 N 天留存率序列，index 0 = 今天（由 ForgettingPredictor 预计算）
 * @param retentionRate 当前留存率（用于圆点高亮与文字标注）
 * @param threshold   建议复习水位线（0..1），默认 0.3
 * @param daysToShow  横轴显示天数（不超过 decayCurve.size）
 */
@Composable
fun MemoryDecayChart(
    decayCurve: List<Float>,
    retentionRate: Float,
    modifier: Modifier = Modifier,
    threshold: Float = 0.3f,
    daysToShow: Int = 30
) {
    if (decayCurve.isEmpty()) return
    val n = decayCurve.size.coerceAtMost(daysToShow + 1)
    if (n < 2) return

    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    // 曲线绘制动画：从左到右展开
    val animated by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(800, delayMillis = 60),
        label = "decayChartEnter"
    )

    val curveColor = when {
        retentionRate >= 0.6f -> MaterialTheme.colorScheme.primary
        retentionRate >= threshold -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    // 曲线下方渐变填充：顶部主色 25% → 底部透明
    val fillBrush = Brush.verticalGradient(
        0f to curveColor.copy(alpha = 0.25f),
        1f to Color.Transparent
    )
    val thresholdColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor = MaterialTheme.colorScheme.onSurface
    // 阈值线虚线效果（编译一次）
    val thresholdDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

    // Path 对象提到 Composable 层复用，避免动画每帧在 DrawScope 内创建新对象导致 GC 抖动
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    Column(
        modifier = modifier.semantics {
            contentDescription = "记忆衰减曲线，当前留存率 ${(retentionRate * 100).toInt()}%，" +
                "低于 ${(threshold * 100).toInt()}% 建议复习"
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 6.dp.toPx()
                val rightPad = 6.dp.toPx()
                val topPad = 8.dp.toPx()
                val bottomPad = 16.dp.toPx()
                val w = size.width - leftPad - rightPad
                val h = size.height - topPad - bottomPad

                // 阈值线（建议复习水位，虚线）
                val yThreshold = topPad + h * (1f - threshold)
                drawLine(
                    color = thresholdColor,
                    start = Offset(leftPad, yThreshold),
                    end = Offset(leftPad + w, yThreshold),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = thresholdDash
                )

                // 横轴
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad + h),
                    end = Offset(leftPad + w, topPad + h),
                    strokeWidth = 1.dp.toPx()
                )

                // 衰减曲线：复用 Path，rewind 清空而非新建
                val visiblePoints = (n * animated).toInt().coerceAtLeast(2)
                linePath.rewind()
                fillPath.rewind()
                for (i in 0 until visiblePoints) {
                    val x = leftPad + w * i / (n - 1)
                    val y = topPad + h * (1f - decayCurve[i].coerceIn(0f, 1f))
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, topPad + h)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                // 闭合填充区域
                val lastX = leftPad + w * (visiblePoints - 1) / (n - 1)
                fillPath.lineTo(lastX, topPad + h)
                fillPath.close()

                drawPath(fillPath, brush = fillBrush)
                drawPath(
                    linePath,
                    color = curveColor,
                    style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
                )

                // 当前留存率圆点（index 0，即今天）
                val dotX = leftPad
                val dotY = topPad + h * (1f - retentionRate.coerceIn(0f, 1f))
                // 光晕：大半径弱色圆底
                drawCircle(
                    color = curveColor.copy(alpha = 0.25f),
                    radius = 10.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                drawCircle(
                    color = dotColor,
                    radius = 4.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                // 外圈描边增强可见性
                drawCircle(
                    color = curveColor,
                    radius = 6.dp.toPx(),
                    center = Offset(dotX, dotY),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
        // 坐标轴标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("今天", style = MaterialTheme.typography.labelSmall,
                color = labelColor, fontSize = 9.sp)
            Text("复习水位 ${(threshold * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                color = thresholdColor, fontSize = 9.sp)
            Text("${n - 1}天后", style = MaterialTheme.typography.labelSmall,
                color = labelColor, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "当前记忆留存",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontSize = 10.sp
            )
            Text(
                "${(retentionRate * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = curveColor
            )
        }
    }
}
