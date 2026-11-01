// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import com.ilyskyo.blancall.ui.theme.ReminderFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪几天提醒、下一次是哪天」的纯函数契约。
 *
 * 这些用例同时保护两件事：Worker 的调度目标与设置页「下次提醒」的显示同源，
 * 以及自定义星期不会被预设频率悄悄覆盖。
 */
class ReminderScheduleTest {

    // 2026-10-26 是周一，用来固定“今天/明天/跨周”的相对位置
    private val mondayNoon = LocalDateTime.of(2026, 10, 26, 12, 0)

    @Test
    fun `还没到今天的提醒时刻时取今天`() {
        val next = ReminderSchedule.nextAt(
            now = mondayNoon,
            hour = 20,
            minute = 0,
            days = ReminderSchedule.ALL_DAYS,
        )
        assertEquals(LocalDateTime.of(2026, 10, 26, 20, 0), next)
    }

    @Test
    fun `今天的时刻已过就跳到下一个提醒日`() {
        val next = ReminderSchedule.nextAt(
            now = LocalDateTime.of(2026, 10, 26, 21, 0),
            hour = 20,
            minute = 0,
            days = ReminderSchedule.THREE_DAYS,
        )
        assertEquals(DayOfWeek.WEDNESDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 10, 28, 20, 0), next)
    }

    @Test
    fun `刚好等于目标时刻也算已过`() {
        // 否则 20:00:00 打开 App 会把下一次算成此刻，立刻补发一条
        val next = ReminderSchedule.nextAt(
            now = LocalDateTime.of(2026, 10, 26, 20, 0),
            hour = 20,
            minute = 0,
            days = ReminderSchedule.ALL_DAYS,
        )
        assertEquals(LocalDateTime.of(2026, 10, 27, 20, 0), next)
    }

    @Test
    fun `自定义星期只命中选中的日子`() {
        val saturdayOnly = ReminderSchedule.nextAt(
            now = mondayNoon,
            hour = 20,
            minute = 0,
            days = setOf(DayOfWeek.SATURDAY),
        )
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 0), saturdayOnly)

        val tuesdayOnly = ReminderSchedule.nextAt(
            now = LocalDateTime.of(2026, 10, 30, 21, 0), // 周五晚
            hour = 20,
            minute = 0,
            days = setOf(DayOfWeek.TUESDAY),
        )
        assertEquals(LocalDateTime.of(2026, 11, 3, 20, 0), tuesdayOnly)
    }

    @Test
    fun `空提醒日按每天兜底`() {
        // 偏好损坏时宁可多提醒一次，也不要永久静默
        val next = ReminderSchedule.nextAt(
            now = LocalDateTime.of(2026, 10, 26, 21, 0),
            hour = 20,
            minute = 0,
            days = emptySet(),
        )
        assertEquals(LocalDateTime.of(2026, 10, 27, 20, 0), next)
        assertTrue(ReminderSchedule.isReminderDay(LocalDate.of(2026, 10, 26), emptySet()))
    }

    @Test
    fun `预设频率映射到固定的提醒日`() {
        assertEquals(ReminderSchedule.ALL_DAYS, ReminderSchedule.daysFor(ReminderFrequency.DAILY))
        assertEquals(ReminderSchedule.WORKDAYS, ReminderSchedule.daysFor(ReminderFrequency.WEEKLY_FIVE))
        assertEquals(ReminderSchedule.THREE_DAYS, ReminderSchedule.daysFor(ReminderFrequency.WEEKLY_THREE))
        // CUSTOM 的真实值在偏好里，这里的返回值只作兜底
        assertEquals(ReminderSchedule.ALL_DAYS, ReminderSchedule.daysFor(ReminderFrequency.CUSTOM))
    }

    @Test
    fun `提醒日编码解析往返`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        assertEquals("MONDAY,WEDNESDAY,FRIDAY", ReminderSchedule.encodeDays(days))
        assertEquals(days, ReminderSchedule.parseDays(ReminderSchedule.encodeDays(days)))
    }

    @Test
    fun `坏掉的提醒日不会拖垮整份配置`() {
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            ReminderSchedule.parseDays("MONDAY,NOT_A_DAY,FRIDAY"),
        )
        assertTrue(ReminderSchedule.parseDays(null).isEmpty())
        assertTrue(ReminderSchedule.parseDays("  ").isEmpty())
    }

    @Test
    fun `指定日期是否提醒`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        assertTrue(ReminderSchedule.isReminderDay(LocalDate.of(2026, 10, 27), days)) // 周二
        assertFalse(ReminderSchedule.isReminderDay(LocalDate.of(2026, 10, 26), days)) // 周一
    }
}
