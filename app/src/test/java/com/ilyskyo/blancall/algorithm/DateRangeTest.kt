// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 学习日历时间维度预设的边界断言。
 *
 * 日期全部由测试构造，不读系统时钟：预设的右端是「今天」，一旦测试跟着系统时钟走，
 * 它在跨午夜的那一分钟里会随机红。
 */
class DateRangeTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 11)

    @Test
    fun `滚动窗口含今天且天数正好`() {
        val r7 = presetRange(RangePreset.LAST_7, today)
        assertEquals(today, r7.end)
        assertEquals(today.minusDays(6), r7.start)
        assertEquals(7, r7.days)
        assertEquals(30, presetRange(RangePreset.LAST_30, today).days)
        assertEquals(90, presetRange(RangePreset.LAST_90, today).days)
        assertEquals(365, presetRange(RangePreset.LAST_365, today).days)
    }

    @Test
    fun `本月从一号到今天`() {
        val r = presetRange(RangePreset.THIS_MONTH, today)
        assertEquals(LocalDate.of(2026, 10, 1), r.start)
        assertEquals(today, r.end)
    }

    @Test
    fun `上月是完整自然月`() {
        val r = presetRange(RangePreset.LAST_MONTH, today)
        assertEquals(LocalDate.of(2026, 9, 1), r.start)
        assertEquals(LocalDate.of(2026, 9, 30), r.end)
    }

    @Test
    fun `月末当天取上月不会溢出成更早的日期`() {
        // 3/31 直接 minusMonths(1) 得到 2/28，起点就不是 1 号了 —— 这里守的是「先落到 1 号再减」
        val r = presetRange(RangePreset.LAST_MONTH, LocalDate.of(2026, 3, 31))
        assertEquals(LocalDate.of(2026, 2, 1), r.start)
        assertEquals(LocalDate.of(2026, 2, 28), r.end)
    }

    @Test
    fun `区间天数含首尾`() {
        assertEquals(1, DateRange(today, today).days)
        assertEquals(2, DateRange(today.minusDays(1), today).days)
    }

    @Test
    fun `of 会把反着的两端交换`() {
        val r = DateRange.of(today, today.minusDays(10))
        assertEquals(today.minusDays(10), r.start)
        assertEquals(today, r.end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `构造反区间直接失败而不是静默画出一张空图`() {
        DateRange(today, today.minusDays(1))
    }

    @Test
    fun `contains 是闭区间`() {
        val r = DateRange(today.minusDays(10), today)
        assertTrue(today in r)
        assertTrue(today.minusDays(10) in r)
        assertTrue(today.minusDays(5) in r)
        assertFalse(today.minusDays(11) in r)
        assertFalse(today.plusDays(1) in r)
    }

    @Test
    fun `每个预设都产出合法闭区间`() {
        for (p in RangePreset.entries) {
            val r = presetRange(p, today)
            assertTrue("$p 起点晚于终点", !r.start.isAfter(r.end))
            assertTrue("$p 天数 ${r.days} 小于 1", r.days >= 1)
        }
    }
}
