// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [HistoryGrouping] 的行为锁定：分组边界、预览截断、跨月组头、展开全量。
 */
class HistoryGroupingTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 16)

    private fun ms(daysAgo: Long): Long =
        today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun rec(daysAgo: Long): PracticeRecord = PracticeRecord(
        id = nextId++,
        articleId = 1L,
        mode = "SENTENCE",
        totalBlanks = 10,
        correctCount = 8,
        timestamp = ms(daysAgo)
    )

    @Test
    fun `按月分组：同月记录共享一个组头`() {
        val records = listOf(rec(0), rec(1), rec(2))   // 全部 10 月
        val rows = HistoryGrouping.build(records, expanded = true, zoneId = zone)
        assertEquals(4, rows.size)
        assertTrue(rows[0] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows.drop(1).all { it is HistoryGrouping.Row.RecordRow })
    }

    @Test
    fun `跨月时在分界处插组头`() {
        // 10-16 / 10-10（10 月） + 09-20 / 09-15（9 月），保持倒序
        val records = listOf(rec(0), rec(6), rec(26), rec(31))
        val rows = HistoryGrouping.build(records, expanded = true, zoneId = zone)
        assertEquals(6, rows.size)
        assertTrue(rows[0] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows[1] is HistoryGrouping.Row.RecordRow)
        assertTrue(rows[2] is HistoryGrouping.Row.RecordRow)
        assertTrue("第 3 行应是 9 月的组头：${rows[3]}", rows[3] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows[4] is HistoryGrouping.Row.RecordRow)
        assertTrue(rows[5] is HistoryGrouping.Row.RecordRow)
    }

    @Test
    fun `未展开时只铺最近 N 条，截断点之前的跨月组头照常出现`() {
        // 10-16 / 10-10（10 月） + 09-20 / 09-15（9 月），保持倒序
        val records = listOf(rec(0), rec(6), rec(26), rec(31))
        val rows = HistoryGrouping.build(records, expanded = false, previewCount = 3, zoneId = zone)
        assertEquals(5, rows.size)   // H(10月) R R | H(9月) R —— 第 3 条落在 9 月
        assertTrue(rows[0] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows[1] is HistoryGrouping.Row.RecordRow)
        assertTrue(rows[2] is HistoryGrouping.Row.RecordRow)
        assertTrue("第 4 行应是 9 月组头：${rows[3]}", rows[3] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows[4] is HistoryGrouping.Row.RecordRow)

        // 预览更短时，截断在组头产生之前发生：只有首月组头
        val short = HistoryGrouping.build(records, expanded = false, previewCount = 2, zoneId = zone)
        assertEquals(3, short.size)
        assertTrue(short[0] is HistoryGrouping.Row.MonthHeader)
        assertTrue(short[1] is HistoryGrouping.Row.RecordRow)
        assertTrue(short[2] is HistoryGrouping.Row.RecordRow)
    }

    @Test
    fun `展开后给出全部记录`() {
        val records = listOf(rec(0), rec(6), rec(26), rec(31))
        val rows = HistoryGrouping.build(records, expanded = true, zoneId = zone)
        assertEquals(4, rows.count { it is HistoryGrouping.Row.RecordRow })
    }

    @Test
    fun `previewCount 为 1 的边界`() {
        val records = listOf(rec(0), rec(1))
        val rows = HistoryGrouping.build(records, expanded = false, previewCount = 1, zoneId = zone)
        assertEquals(2, rows.size)   // 组头 + 1 条
        assertTrue(rows[0] is HistoryGrouping.Row.MonthHeader)
        assertTrue(rows[1] is HistoryGrouping.Row.RecordRow)
    }

    @Test
    fun `空列表与非法 previewCount`() {
        assertTrue(HistoryGrouping.build(emptyList(), expanded = true, zoneId = zone).isEmpty())
        assertTrue(
            HistoryGrouping.build(listOf(rec(0)), expanded = false, previewCount = 0, zoneId = zone).isEmpty()
        )
    }
}
