// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历摆格断言：列 = 星期几、首列 = 该语言的一周第一天，与 [heatmapGrid] 同一条对齐规则。
 *
 * 这条规则若两处不一致，日历热力图与区间选择面板会出现「同一天差一列」的错位 ——
 * 界面上看不出来，只有把两个日期并排放在一起才认得出，所以必须由断言钉住。
 *
 * 布局是**行主序**（[monthCells] 返回 42 个连续元素，每 7 个一周、列为星期）——
 * 与 MonthGrid 的渲染方式一致。早期版本把断言写成「行号 = 星期」，拿错了轴：
 * 行是第几周，星期是第几列，两件事。
 */
class CalendarMonthTest {

    @Test
    fun `月历恒为 6 行 42 格`() {
        for (first in DayOfWeek.entries) {
            assertEquals(42, monthCells(YearMonth.of(2026, 10), first).size)
        }
    }

    @Test
    fun `非空格子数正好是这个月的天数`() {
        assertEquals(31, monthCells(YearMonth.of(2026, 10), DayOfWeek.MONDAY).count { it != null })
        assertEquals(28, monthCells(YearMonth.of(2026, 2), DayOfWeek.MONDAY).count { it != null })
        assertEquals(29, monthCells(YearMonth.of(2024, 2), DayOfWeek.MONDAY).count { it != null })
    }

    @Test
    fun `每个日期的列号等于它在那个语言里的星期列`() {
        for (first in DayOfWeek.entries) {
            monthCells(YearMonth.of(2026, 10), first).chunked(7)
                .forEach { week ->
                    week.forEachIndexed { col, date ->
                        if (date != null) {
                            assertEquals(
                                "first=$first $date 的星期是 ${date.dayOfWeek}，列号却排错",
                                rowIndexOf(date.dayOfWeek, first),
                                col
                            )
                        }
                    }
                }
        }
    }

    @Test
    fun `日期按天递增且不跨月`() {
        val dates = monthCells(YearMonth.of(2026, 10), DayOfWeek.SUNDAY).filterNotNull()
        assertEquals(31, dates.size)
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        assertTrue(dates.all { it.year == 2026 && it.monthValue == 10 })
    }

    @Test
    fun `一号落在它自己的星期列`() {
        val first = LocalDate.of(2026, 10, 1)
        for (dow in DayOfWeek.entries) {
            val idx = monthCells(YearMonth.of(2026, 10), dow).indexOf(first)
            assertEquals(rowIndexOf(first.dayOfWeek, dow), idx % 7)
        }
    }
}
