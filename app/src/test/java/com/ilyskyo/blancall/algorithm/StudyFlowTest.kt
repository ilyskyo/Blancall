// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StudyFlowTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = StudyFlow.MILLIS_PER_DAY
    private val now = 1_760_000_000_000L // 2025-10-09 02:13:20 UTC, 本地 10:13

    private fun state(
        due: Long,
        lastReview: Long = now - day,
        reviewCount: Int = 3,
    ) = FsrsEngine.CardState(
        difficulty = 4.0,
        stability = 3.0,
        due = due,
        lastReview = lastReview,
        reviewCount = reviewCount,
    )

    @Test
    fun `暂停冻结剩余期而不是把due推到远处`() {
        val s = state(due = now + 2 * day)
        val pause = StudyFlow.pauseOf(s, now)
        assertEquals(2 * day, pause.remainingMs)
        // 断言原始状态没有被改写：冻结只发生在快照里
        assertEquals(now + 2 * day, s.due)
    }

    @Test
    fun `暂停中预计到期随当下滑动且恢复从当下接续`() {
        val s = state(due = now + 2 * day)
        val pause = StudyFlow.pauseOf(s, now)
        val later = now + 5 * day
        assertEquals(later + 2 * day, StudyFlow.projectedDue(s, pause, later))
        assertEquals(later + 2 * day, StudyFlow.resumedDue(s, pause, later))
    }

    @Test
    fun `已逾期再暂停冻结为零恢复即到期`() {
        val s = state(due = now - 3 * day)
        val pause = StudyFlow.pauseOf(s, now)
        assertEquals(0L, pause.remainingMs)
        assertEquals(now + 4 * day, StudyFlow.resumedDue(s, pause, now + 4 * day))
    }

    @Test
    fun `暂停期间手动练过则尊重那次推进`() {
        val s = state(due = now + 2 * day)
        val pause = StudyFlow.pauseOf(s, now)
        // 暂停后手动练习：练习页把 lastReview 与 due 一起推进
        val practiced = s.copy(lastReview = now + day, due = now + 9 * day)
        val resumeAt = now + 3 * day
        assertEquals(now + 9 * day, StudyFlow.resumedDue(practiced, pause, resumeAt))
        // 手动练习后 due 已经过期：恢复时回到当下，而不是回到过去
        val stale = s.copy(lastReview = now + day, due = now + 2 * day)
        assertEquals(resumeAt, StudyFlow.resumedDue(stale, pause, resumeAt))
    }

    @Test
    fun `按本地日历日分桶且暂停优先`() {
        val startToday = Studies.startOfToday(now, zone)
        assertEquals(StudyFlow.Phase.OVERDUE, StudyFlow.phase(startToday - 1, false, now, zone))
        assertEquals(StudyFlow.Phase.TODAY, StudyFlow.phase(startToday, false, now, zone))
        assertEquals(StudyFlow.Phase.TODAY, StudyFlow.phase(startToday + 23 * 3_600_000L, false, now, zone))
        assertEquals(StudyFlow.Phase.TOMORROW, StudyFlow.phase(startToday + day, false, now, zone))
        assertEquals(StudyFlow.Phase.THIS_WEEK, StudyFlow.phase(startToday + 3 * day, false, now, zone))
        assertEquals(StudyFlow.Phase.LATER, StudyFlow.phase(startToday + 8 * day, false, now, zone))
        // 本该「已逾期」的时间，只要暂停就归到 PAUSED
        assertEquals(StudyFlow.Phase.PAUSED, StudyFlow.phase(startToday - 11 * day, true, now, zone))
    }

    @Test
    fun `预测只统计未来七天且已逾期不入格`() {
        val startToday = Studies.startOfToday(now, zone)
        val dueTimes = listOf(
            startToday - day,
            startToday,
            startToday + day,
            startToday + 6 * day,
            startToday + 7 * day,
        )
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 1), StudyFlow.forecast(dueTimes, now, 7, zone))
        assertNull(StudyFlow.forecastIndex(startToday - 1, now, 7, zone))
        assertNull(StudyFlow.forecastIndex(startToday + 7 * day, now, 7, zone))
        assertEquals(0, StudyFlow.forecastIndex(startToday, now, 7, zone))
    }

    @Test
    fun `选择今天不会把到期时间放回今天零点`() {
        val startToday = Studies.startOfToday(now, zone)
        assertEquals(now, StudyFlow.dueAtDayStart(startToday, now, zone))
        assertEquals(startToday + day, StudyFlow.dueAtDayStart(startToday + day, now, zone))
    }

    @Test
    fun `暂停中改期用绝对日期_时间前进也不漂移`() {
        val s = state(due = now + 2 * day)
        val target = now + 10 * day
        val pause = StudyFlow.pauseOf(s, now).copy(fixedDue = target)

        // 模拟时间前进 3 天：冻结剩余期会滑到 now+12d，绝对日期必须仍是 D
        assertEquals(target, StudyFlow.projectedDue(s, pause, now + 3 * day))
        assertEquals(target, StudyFlow.resumedDue(s, pause, now + 3 * day))
    }

    @Test
    fun `恢复时绝对日期已过则立即到期`() {
        val s = state(due = now + 2 * day)
        val target = now + 10 * day
        val pause = StudyFlow.pauseOf(s, now).copy(fixedDue = target)

        assertEquals(now + 12 * day, StudyFlow.resumedDue(s, pause, now + 12 * day))
    }

    @Test
    fun `暂停期间手动练习后绝对日期不覆盖新的due`() {
        val s = state(due = now + 2 * day)
        val pause = StudyFlow.pauseOf(s, now).copy(fixedDue = now + 10 * day)
        val practiced = s.copy(lastReview = now + day, due = now + 9 * day)

        assertEquals(now + 9 * day, StudyFlow.projectedDue(practiced, pause, now + 3 * day))
        assertEquals(now + 9 * day, StudyFlow.resumedDue(practiced, pause, now + 3 * day))
    }

    /** 测试侧复用的日界算法，避免把被测实现当成期望值 */
    private object Studies {
        fun startOfToday(now: Long, zone: ZoneId): Long =
            LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
                .atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
