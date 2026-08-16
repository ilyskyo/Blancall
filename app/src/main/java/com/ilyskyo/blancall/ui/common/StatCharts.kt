// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 环形进度仪表盘：中心显示百分比，环形显示进度。
 * 用于统计页核心正确率的可视化。
 *
 * @param progress 0f..1f
 * @param label    中心副标题（如"总体正确率"）
 */
@Composable
fun GaugeProgress(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val animated by animateFloatAsState(
        targetValue = if (played) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(900, delayMillis = 80),
        label = "gaugeProgress"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // 整个 U 型弧的描边色：让未达到部分也能看清轮廓
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val progressColor = when {
        progress >= 0.8f -> MaterialTheme.colorScheme.primary
        progress >= 0.6f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    // 进度弧渐变（Composable 层创建，Canvas 内仅引用）
    val progressBrush = Brush.sweepGradient(
        colorStops = arrayOf(
            0f to MaterialTheme.colorScheme.primary,
            0.55f to MaterialTheme.colorScheme.tertiary,
            1f to MaterialTheme.colorScheme.primary
        )
    )

    Box(
        modifier = modifier
            .size(120.dp)
            .semantics { contentDescription = "$label ${(progress * 100).toInt()}%" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            // 整个 U 型弧的描边：先画外圈轮廓（含未达到部分）
            val outlineW = 1.5.dp.toPx()
            val innerStroke = stroke - outlineW * 2
            drawArc(
                color = outlineColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 背景轨道：淡色填充（比描边窄一圈，露出描边轮廓）
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = innerStroke, cap = StrokeCap.Round)
            )
            // 进度（渐变弧：主色 → 辅色 → 主色，沿圆周过渡）
            if (animated > 0f) {
                drawArc(
                    brush = progressBrush,
                    startAngle = 135f,
                    sweepAngle = 270f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = innerStroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = progressColor
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 每日练习次数柱状图。
 *
 * @param dailyCounts 最近 N 天的每日练习次数列表（下标 0 = 最早，末位 = 今天）
 */
@Composable
fun DailyTrendChart(
    dailyCounts: List<Int>,
    modifier: Modifier = Modifier
) {
    if (dailyCounts.isEmpty()) return
    val maxCount = (dailyCounts.maxOrNull() ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val todayColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        // 柱状图区域
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            dailyCounts.forEachIndexed { index, count ->
                val fraction = count.toFloat() / maxCount
                val isToday = index == dailyCounts.lastIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(
                            if (isToday) {
                                // 今天柱：辅色渐变 + 高亮描边
                                Brush.verticalGradient(
                                    0f to todayColor,
                                    1f to todayColor.copy(alpha = 0.4f)
                                )
                            } else {
                                Brush.verticalGradient(
                                    0f to barColor,
                                    1f to barColor.copy(alpha = 0.35f)
                                )
                            }
                        )
                        .then(
                            if (isToday) Modifier.border(
                                1.dp, todayColor.copy(alpha = 0.7f),
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            ) else Modifier
                        )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 日期标签（首/中/尾）
        val total = dailyCounts.size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val fmt = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())
            val today = LocalDate.now()
            Text(today.minusDays((total - 1).toLong()).format(fmt),
                style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 9.sp)
            Text("今天", style = MaterialTheme.typography.labelSmall,
                color = if (dailyCounts.lastOrNull()?.let { it > 0 } == true) todayColor else labelColor,
                fontSize = 9.sp)
        }
    }
}

/**
 * GitHub 风格日历热力图：展示最近 [weeks] 周每日学习强度。
 *
 * @param dailyCounts 最近 N 天的每日练习次数，下标 0 = 最早，末位 = 今天
 * @param weeks       展示周数（默认 12 周）
 */
@Composable
fun CalendarHeatmap(
    dailyCounts: List<Int>,
    modifier: Modifier = Modifier,
    weeks: Int = 12
) {
    if (dailyCounts.isEmpty()) return
    val maxCount = (dailyCounts.maxOrNull()?.coerceAtLeast(1)) ?: 1
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    // 色阶：主色 → 辅色渐变 5 级，今天/高活跃单元格用 tertiary 提亮
    val levels = listOf(
        baseColor,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
    )

    fun colorFor(count: Int): Color {
        if (count <= 0) return levels[0]
        val idx = ((count.toFloat() / maxCount) * (levels.size - 1)).toInt()
            .coerceIn(1, levels.lastIndex)
        return levels[idx]
    }

    // 按周分组：末位对齐到今天
    val totalDays = weeks * 7
    val data = dailyCounts.takeLast(totalDays)
    // 对齐到周日开头：补齐到整周
    val padFront = (totalDays - data.size).coerceAtLeast(0)
    val cells = List(padFront) { null } + data
    val cornerRadius = 3.dp
    val spacing = 3.dp

    Column(modifier = modifier) {
        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("少", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Spacer(Modifier.width(3.dp))
            levels.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c)
                )
                Spacer(Modifier.width(2.dp))
            }
            Text("多", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
        Spacer(Modifier.height(6.dp))
        // 网格：单个 Canvas 一次绘制 weeks×7 个单元格，避免 84 个 Composable 节点
        // 宽高比 = weeks:7，保证单元格为正方形
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(weeks.toFloat() / 7f)
        ) {
            val sp = spacing.toPx()
            val cellW = (size.width - sp * (weeks - 1)) / weeks
            val cellH = (size.height - sp * 6) / 7
            val cell = minOf(cellW, cellH)
            val cr = cornerRadius.toPx()
            for (w in 0 until weeks) {
                for (d in 0 until 7) {
                    val idx = w * 7 + d
                    val count = cells.getOrNull(idx)
                    val color = count?.let { colorFor(it) } ?: Color.Transparent
                    val x = w * (cell + sp)
                    val y = d * (cell + sp)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, y),
                        size = Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("近 $weeks 周学习日历 · 共 ${dailyCounts.sum()} 次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    }
}
