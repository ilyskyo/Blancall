// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历摆格（纯函数，与绘制分离）。
 *
 * 与 [heatmapGrid] 共用同一个「星期几排第几列」的换算（[rowIndexOf]）—— 两处各写一份的话，
 * 日历热力图与区间选择面板会出现「同一天差一列」的错位，而那种错位在界面上看不出，
 * 只有把两个日期并排放在一起才认得出。
 */

/** 固定 6 行：月历不能因为「这个月正好只占 5 行」而上下跳动，切月时整块会跳一下 */
const val WEEKS_PER_MONTH = 6

/**
 * 某月的 42 格（[WEEKS_PER_MONTH] × 7）。
 *
 * 布局为**行主序**：每 7 个连续元素是一周（行），列 = 星期几（首列 = [firstDayOfWeek]）——
 * 与 `MonthGrid` 的渲染（星期表头 + 逐周成行）一致。早先的注释把这里写成「行 = 星期几」，
 * 与实现相反，照着它写断言就会拿错轴（CalendarMonthTest 踩过）。
 *
 * @param yearMonth 要摆的月份
 * @param firstDayOfWeek 界面语言里一周的第一天，落在**第 0 列**
 * @return 42 个元素；`null` = 补位（属于相邻月份，不该画成可点的一天）。
 *         最大占位 = 6 个前导补位 + 31 天 = 37 ≤ 42，所以 6 行恒够，不需要按需增行。
 */
fun monthCells(yearMonth: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
    val lead = rowIndexOf(yearMonth.atDay(1).dayOfWeek, firstDayOfWeek)
    val length = yearMonth.lengthOfMonth()
    return List(WEEKS_PER_MONTH * 7) { i ->
        val dayOfMonth = i - lead + 1
        if (dayOfMonth in 1..length) yearMonth.atDay(dayOfMonth) else null
    }
}
