// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [heatmapGrid] 的行对齐断言。
 *
 * 这里守的是「同一行的日子必须真的相差整周」——原实现每天整体下移一格，
 * 而它自己注释声称已对齐周日开头，肉眼与既有测试都发现不了。
 * 日期全部由测试构造，不读系统时钟（页面跨过午夜时也要能换 today 重算）。
 */
class HeatmapGridTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 11)

    /** counts 末位 = today：第 i 格填 i（于是每个格子的 count 就是它相对今天的偏移凭证） */
    private fun counts(days: Int = 84): List<Int> = (0 until days).map { it }

    @Test
    fun 每格的行号恒等于它自己在那个语言里的星期行() {
        for (first in DayOfWeek.entries) {
            val grid = heatmapGrid(counts(), today, first, weeks = 12)
            assertEquals(12, grid.size)
            grid.forEachIndexed { col, column ->
                assertEquals(7, column.size)
                column.forEachIndexed { row, cell ->
                    val date = cell?.date ?: return@forEachIndexed
                    assertEquals(
                        "first=$first 列 $col 行 $row：${date} 的星期是 ${date.dayOfWeek}，行号却排错",
                        rowIndexOf(date.dayOfWeek, first),
                        row
                    )
                }
            }
        }
    }

    @Test
    fun 同一行相邻两列相差整周() {
        for (first in DayOfWeek.entries) {
            val grid = heatmapGrid(counts(), today, first, weeks = 12)
            for (row in 0 until 7) {
                for (col in 1 until 12) {
                    val a = grid[col - 1][row]?.date ?: continue
                    val b = grid[col][row]?.date ?: continue
                    assertEquals("first=$first 行 $row 列 $col", 7L, ChronoUnit.DAYS.between(a, b))
                }
            }
        }
    }

    @Test
    fun 今天落在自己那行的末列且本周未到的日子是空格() {
        for (first in DayOfWeek.entries) {
            val grid = heatmapGrid(counts(), today, first, weeks = 12)
            val rowOfToday = rowIndexOf(today.dayOfWeek, first)
            val last = grid.last()
            assertEquals(
                "today 必须在最后一列、它自己那一行",
                today,
                last[rowOfToday]?.date
            )
            assertEquals(counts().last(), last[rowOfToday]!!.count)
            // 今天之后的同列格子（本周还没到的日子）必须是 null，而不是画成「那天没练」
            for (row in (rowOfToday + 1) until 7) {
                assertNull("first=$first 行 $row 应是未来空格", last[row])
            }
        }
    }

    @Test
    fun `次数按距今天数逐格对应不多不少`() {
        val grid = heatmapGrid(counts(), today, DayOfWeek.MONDAY, weeks = 12)
        val seen = grid.flatten().filterNotNull()
        assertEquals(84, seen.size)
        seen.forEach { cell ->
            val daysAgo = ChronoUnit.DAYS.between(cell.date, today).toInt()
            assertEquals("格子 ${cell.date} 差 $daysAgo 天", 83 - daysAgo, cell.count)
        }
    }

    @Test
    fun 历史不足窗口时早于起点的格子记为零而不是越界() {
        val grid = heatmapGrid(counts(30), today, DayOfWeek.MONDAY, weeks = 12)
        val dated = grid.flatten().filterNotNull()
        assertTrue(dated.isNotEmpty())
        // counts 末位 = 今天 ⇒ 只有最近 30 天有数据，其中 count 恰为 0 的是最早那一天
        assertEquals(30, dated.count { ChronoUnit.DAYS.between(it.date, today).toInt() in 0..29 })
        assertEquals(29, dated.count { it.count > 0 })
        assertEquals(today.minusDays(28), dated.first { it.count > 0 }.date)
    }

    /** 行标签与摆格必须用同一个函数：两者互为逆运算，否则标签会和格子说假话 */
    @Test
    fun 行标签与格子行号互为逆运算() {
        for (first in DayOfWeek.entries) {
            for (row in 0 until 7) {
                assertEquals(row, rowIndexOf(dayOfWeekAtRow(row, first), first))
            }
        }
    }

    // ── 任意区间版（学习日历的时间维度）──

    @Test
    fun `任意区间里每一格都在区间内且行号正确`() {
        val range = DateRange(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 10, 11))
        for (first in DayOfWeek.entries) {
            val grid = heatmapGridForRange(range, first) { it.dayOfMonth }
            grid.forEach { column ->
                assertEquals(7, column.size)
                column.forEachIndexed { row, cell ->
                    val date = cell?.date ?: return@forEachIndexed
                    assertTrue("$date 落在区间外", date in range)
                    assertEquals(rowIndexOf(date.dayOfWeek, first), row)
                }
            }
            // 区间内的每一天恰好出现一次：多了是重叠，少了是漏格
            val seen = grid.flatten().filterNotNull().map { it.date }
            assertEquals(range.days, seen.size)
            assertEquals(seen.size, seen.distinct().size)
        }
    }

    @Test
    fun `区间右端不再钉在今天`() {
        val range = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        val dates = heatmapGridForRange(range, DayOfWeek.MONDAY) { 0 }
            .flatten().filterNotNull().map { it.date }
        assertEquals(LocalDate.of(2026, 9, 1), dates.first())
        assertEquals(LocalDate.of(2026, 9, 30), dates.last())
    }

    @Test
    fun `countOn 对区间内每一天恰好取一次值`() {
        val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 11))
        val calls = mutableMapOf<LocalDate, Int>()
        val grid = heatmapGridForRange(range, DayOfWeek.MONDAY) { d ->
            calls[d] = (calls[d] ?: 0) + 1
            d.dayOfMonth
        }
        assertEquals(range.days, calls.size)
        assertTrue(calls.values.all { it == 1 })
        grid.flatten().filterNotNull().forEach { assertEquals(it.date.dayOfMonth, it.count) }
    }

    @Test
    fun `单日区间也能铺出一格而不是空网格`() {
        val day = LocalDate.of(2026, 10, 11)
        val grid = heatmapGridForRange(DateRange(day, day), DayOfWeek.MONDAY) { 5 }
        val cells = grid.flatten().filterNotNull()
        assertEquals(1, cells.size)
        assertEquals(day, cells.first().date)
        assertEquals(5, cells.first().count)
    }
}
