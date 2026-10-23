// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.time.LocalDate

/**
 * 日历热力图的摆格逻辑（纯函数，与绘制分离）。
 *
 * 为什么要抽出来：原来的 [com.ilyskyo.blancall.ui.common.CalendarHeatmap] 直接把
 * 「最近 84 天」按 `idx = 列*7 + 行` 铺满，而它自己那句「对齐到周日开头」的注释从来没生效
 * —— `padFront` 恒为 0（数据长度本来就等于 weeks*7），于是行号等于「距今天数 mod 7」，
 * **这张图每天整体下移一格**，同一行的今天和上周并不是同一天，图上又没有任何星期或日期锚点，
 * 用户完全无从发现。这是已发布的正确性缺陷，不是观感问题。
 *
 * 抽成纯函数还有一层用意：Canvas 里画的像素 JVM 测不到，而「哪天落在哪一列哪一行」
 * 是可以用断言钉住的逻辑 —— 对齐公式一旦再被改回去，测试会当场红。
 */

/** 一格：日期 + 当天练习次数。未来日子不入格（由 [heatmapGrid] 返回 null 占位）。 */
data class HeatmapCell(val date: LocalDate, val count: Int)

/**
 * 把「最近 N 天的每日次数」摆成**按星期对齐**的网格。
 *
 * @param dailyCounts 下标 0 = 最早，**末位 = today**（与 CalendarHeatmap 既有约定一致）
 * @param today 今天。由调用方传入而不是在函数里读时钟：测试要能喂固定日期，
 *              且页面开着跨过午夜时也要能跟着换（见 OverviewScreen 的分钟级 ticker）
 * @param firstDayOfWeek 界面语言里一周的第一天，它落在**第 0 行**
 * @param weeks 列数（每列一周）
 * @return `grid[列][行]`；末列里晚于今天的格子为 null（本周还没到的日子不该画成「没练」）
 */
fun heatmapGrid(
    dailyCounts: List<Int>,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    weeks: Int
): List<List<HeatmapCell?>> {
    val totalDays = weeks * 7
    // 窗口 = 覆盖今天的这 weeks 周，末行是一周的最后一天（今天是周三且一周从周一开始时，末行是周日）
    val windowEnd = today.plusDays((6 - rowIndexOf(today.dayOfWeek, firstDayOfWeek)).toLong())
    val windowStart = windowEnd.minusDays((totalDays - 1).toLong())
    return List(weeks) { col ->
        List(7) { row ->
            val date = windowStart.plusDays((col * 7 + row).toLong())
            val fromToday = daysBetween(date, today)     // 正数 = 过去，负数 = 本周还没到的日子
            if (fromToday < 0) {
                null
            } else {
                // counts 的末位就是今天 ⇒ 倒数第 (fromToday+1) 个；负下标 = 比历史起点更早，按零计
                val index = dailyCounts.size - 1 - fromToday
                HeatmapCell(date, if (index >= 0) dailyCounts[index] else 0)
            }
        }
    }
}

/**
 * 把 [range] 铺成按星期对齐的网格 —— [heatmapGrid] 的「任意区间」版。
 *
 * 与 [heatmapGrid] 的唯一差别是**右端不再钉在今天**：它先补到区间起点所在周的周首、
 * 再补到终点所在周的周末，于是列数由区间长度决定（近 7 天 1~2 列、近一年 53 列）。
 * 区间之外的格子返回 `null`（不画），而不是 0 —— 0 会被读成「那天练了 0 次」，
 * 而「不在所选区间里」和「练了 0 次」是两件事。
 *
 * @param countOn 某天的练习次数；查不到就返回 0，由调用方决定缺省
 */
fun heatmapGridForRange(
    range: DateRange,
    firstDayOfWeek: DayOfWeek,
    countOn: (LocalDate) -> Int
): List<List<HeatmapCell?>> {
    val windowStart = range.start.minusDays(rowIndexOf(range.start.dayOfWeek, firstDayOfWeek).toLong())
    val windowEnd = range.end.plusDays((6 - rowIndexOf(range.end.dayOfWeek, firstDayOfWeek)).toLong())
    val weeks = (ChronoUnit.DAYS.between(windowStart, windowEnd).toInt() + 1) / 7
    return List(weeks) { col ->
        List(7) { row ->
            val date = windowStart.plusDays((col * 7 + row).toLong())
            if (date in range) HeatmapCell(date, countOn(date)) else null
        }
    }
}

/** 该星期落在第几行（[firstDayOfWeek] 自己就是第 0 行） */
fun rowIndexOf(day: DayOfWeek, firstDayOfWeek: DayOfWeek): Int =
    (day.value - firstDayOfWeek.value + 7) % 7

/** 第 [row] 行是哪个星期（画行标签用，与 [rowIndexOf] 必须互为逆运算） */
fun dayOfWeekAtRow(row: Int, firstDayOfWeek: DayOfWeek): DayOfWeek =
    DayOfWeek.of(((firstDayOfWeek.value - 1 + row) % 7) + 1)

/** date1 → date2 的天数（date2 在后者时为正） */
fun daysBetween(date1: LocalDate, date2: LocalDate): Int =
    ChronoUnit.DAYS.between(date1, date2).toInt()

/** 热力图的格子尺寸（单位 dp，纯数值 —— 不引入 Compose 类型，JVM 可测） */
data class HeatmapMetrics(
    /** 单格边长（正方形） */
    val cell: Float,
    /** 格间距 */
    val gap: Float,
) {
    /** 整张网格的宽度（列 × 格 + 列间隙） */
    fun gridWidth(weeks: Int): Float = cell * weeks + gap * (weeks - 1)

    /** 网格高度（7 行） */
    fun gridHeight(): Float = cell * 7 + gap * 6
}

/** 格子边长上限（dp）：短区间不再被「撑满容器」拉成大方块 */
private const val HEATMAP_MAX_CELL_DP = 16f

/** 格子边长下限（dp）：一年档的物理底线，再小就只剩一片模糊 */
private const val HEATMAP_MIN_CELL_DP = 1.5f

/**
 * 格子尺寸规范 v1。
 *
 * 修复的问题：旧实现 `cell = 可用宽度 / 列数`，于是同一张卡在「本月」（3~5 列）里
 * 被撑成 50dp 以上的巨型方块，切到「近一年」（53 列）又缩到 4dp 以下 —— **同一张图，
 * 选完范围像换了一个控件**。格子尺寸是这张图的"单位刻度"，它一变，用户就没法跨范围比较了。
 *
 * 规范：
 * 1. **上限封顶 16dp**：短区间的格子与一年档之外的所有档位同尺寸，绝不撑满；
 * 2. 间距按列数分三档（4 / 2 / 1dp），只在跨档时变化，不做连续插值；
 * 3. 宽度放不下时才让格子收缩（一年档 53 列在所难免），且下限 1.5dp；
 * 4. 网格宽度可小于容器 —— 由绘制方居中，而不是把格子拉大来填满。
 *
 * @param weeks 列数（每列一周），必须 > 0
 * @param availableWidth 可用于网格的宽度（dp，已扣掉左侧星期标签列）
 */
fun heatmapCellMetrics(weeks: Int, availableWidth: Float): HeatmapMetrics {
    require(weeks > 0) { "weeks must be > 0" }
    val gap = when {
        weeks > 32 -> 1f
        weeks > 14 -> 2f
        else -> 4f
    }
    val fit = (availableWidth - gap * (weeks - 1)).coerceAtLeast(0f) / weeks
    val cell = if (fit >= HEATMAP_MAX_CELL_DP) HEATMAP_MAX_CELL_DP
    else fit.coerceAtLeast(HEATMAP_MIN_CELL_DP)
    return HeatmapMetrics(cell = cell, gap = gap)
}
