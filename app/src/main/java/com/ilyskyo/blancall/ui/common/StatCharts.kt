// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.DateRange
import com.ilyskyo.blancall.algorithm.dayOfWeekAtRow
import com.ilyskyo.blancall.algorithm.heatmapGridForRange
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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
    // rememberSaveable 而不是 remember：这张表住在统计页 LazyColumn 的 item 里，滚出视口后子树会被
    // 回收 —— 用 remember 的话「已播」标记跟着丢，滚回来时又从 0 长出来，用户读成「数据刷新了」。
    var played by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val animated by animateFloatAsState(
        targetValue = if (played) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = MotionFade.number(MotionFade.chartReveal, 80),
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
            // 进度（已移除 sweepGradient，纯色绘制）
            if (animated > 0f) {
                drawArc(
                    color = progressColor,
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
    // 柱色：同一蓝紫色相，按数值深浅渐变（今天最深，其余按占比），与学习日历图例同色系连续
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 读屏拿得到的替代文本：这张图是纯 Box/Canvas，没有它，屏幕阅读器在这里是一片空白。
    // 数量句一律走 plurals（英文 "1 day"/"2 days"、俄语 one/few/many 由系统选档）
    val trendDescription = pluralStringResource(
        com.ilyskyo.blancall.R.plurals.a11y_daily_trend,
        dailyCounts.size, dailyCounts.size, dailyCounts.sum(), maxCount
    )

    Column(modifier = modifier.semantics { contentDescription = trendDescription }) {
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
                            barColor.copy(alpha = if (isToday) 0.85f else (0.30f + fraction * 0.5f))
                        )
                        .then(
                            if (isToday) Modifier.border(
                                1.dp, barColor.copy(alpha = 0.85f),
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
                color = if (dailyCounts.lastOrNull()?.let { it > 0 } == true) barColor else labelColor,
                fontSize = 9.sp)
        }
    }
}

/**
 * GitHub 风格日历热力图：展示 [range] 区间内每日学习强度。
 *
 * 区间由学习日历的「时间维度」选择器给出（预设或自定义），列数不再固定 12 ——
 * 近 7 天一两列、近一年 53 列，全部由 [heatmapGridForRange] 按星期对齐铺出来。
 *
 * @param range   要展示的闭区间
 * @param countOn 某天的练习次数；区间内没练过的日子由它返回 0
 */
@Composable
fun CalendarHeatmap(
    range: DateRange,
    countOn: (LocalDate) -> Int,
    modifier: Modifier = Modifier
) {
    // 一周从哪天开始由**界面语言**决定（中/日/韩/俄从周一、英文从周日）。
    // 必须读 LocalConfiguration 而不是 Locale.getDefault()：低版本的 app 语言是按
    // createConfigurationContext 注入的，默认 Locale 根本不会跟着变。
    val locale = LocalConfiguration.current.locales.get(0)
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    // 摆格逻辑在 algorithm/HeatmapGrid.kt（纯函数，配 JVM 断言）：行 = 星期几、列 = 一周，
    // 列数由区间长度决定。旧实现按 idx = 列*7 + 行 铺满且右端钉在今天，整张图每天错开一行。
    val grid = heatmapGridForRange(range, firstDay, countOn)
    val weeks = grid.size
    val total = grid.sumOf { column -> column.sumOf { it?.count ?: 0 } }
    val maxCount = (grid.maxOfOrNull { column -> column.maxOfOrNull { it?.count ?: 0 } ?: 0 } ?: 0)
        .coerceAtLeast(1)
    // 色阶：同一主色相的连续渐变（极浅→深），保证「少→多」颜色平滑连续、无跳变
    val primary = MaterialTheme.colorScheme.primary
    val levels = listOf(
        primary.copy(alpha = 0.10f),
        primary.copy(alpha = 0.25f),
        primary.copy(alpha = 0.45f),
        primary.copy(alpha = 0.65f),
        primary.copy(alpha = 0.85f)
    )

    fun colorFor(count: Int): Color {
        if (count <= 0) return levels[0]
        val idx = ((count.toFloat() / maxCount) * (levels.size - 1)).toInt()
            .coerceIn(1, levels.lastIndex)
        return levels[idx]
    }
    val rowLabels = remember(locale, firstDay) {
        (0 until 7).map { dayOfWeekAtRow(it, firstDay).getDisplayName(TextStyle.NARROW, locale) }
    }
    val rangeFmt = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
    val cornerRadius = 3.dp
    // 格子间距随列数收窄：近一年 53 列时固定 3dp 会把格子挤到 2dp（糊成一片），
    // 收窄间距换取可辨认的格宽，总宽仍落在容器里。
    val spacing = when {
        weeks > 30 -> 1.dp
        weeks > 16 -> 2.dp
        else -> 3.dp
    }

    // 同上：Canvas 里的格子对读屏完全不可见，结构（行=星期几、列=周）也要说清楚，
    // 否则用户只听到一句「少 多」
    val calendarDescription = pluralStringResource(
        com.ilyskyo.blancall.R.plurals.a11y_calendar_heatmap,
        range.days, range.days, total
    )

    Column(modifier = modifier.semantics { contentDescription = calendarDescription }) {
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
        // 网格：单个 Canvas 一次绘制 weeks×7 个单元格，避免 84 个 Composable 节点。
        // 左边一列星期标签是这张图唯一的「哪一行是周几」锚点 —— 原先什么都没有，
        // 于是错位一周这件事在界面上完全看不出来。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val labelW = 14.dp
            val cell = ((maxWidth - labelW - spacing * (weeks - 1)) / weeks).coerceAtLeast(2.dp)
            val gridW = cell * weeks + spacing * (weeks - 1)
            val gridH = cell * 7 + spacing * 6
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.width(labelW).height(gridH)) {
                    rowLabels.forEachIndexed { row, label ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = (cell + spacing) * row)
                                .height(cell),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.width(2.dp))
                Canvas(
                    modifier = Modifier
                        .width(gridW)
                        .height(gridH)
                ) {
                    val sp = spacing.toPx()
                    val side = cell.toPx()
                    val cr = cornerRadius.toPx()
                    for (w in 0 until weeks) {
                        for (d in 0 until 7) {
                            val count = grid[w][d]?.count
                            val color = count?.let { colorFor(it) } ?: Color.Transparent
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(w * (side + sp), d * (side + sp)),
                                size = Size(side, side),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // 日期区间是第二层锚点：只写「近 N 周」的话，用户无从把某一格对到具体某一天
        Text(
            "${range.start.format(rangeFmt)} – ${range.end.format(rangeFmt)} · " +
                pluralStringResource(
                    com.ilyskyo.blancall.R.plurals.calendar_range_total, total, total
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    }
}
