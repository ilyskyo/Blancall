// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import com.ilyskyo.blancall.ui.theme.ReminderFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 「哪几天提醒、下一次是哪天」的唯一口径。
 *
 * 为什么把提醒日从频率枚举里拆出来：旧实现只有三档写死的星期，用户既不能选周二/周四，
 * 界面上也看不出「每周 3 天」到底是哪三天。现在枚举只负责挑选预设，
 * 真正参与调度的是 [com.ilyskyo.blancall.ui.theme.ReminderPrefs.reminderDays] 这份集合。
 *
 * 本对象是纯函数集合：Worker 的调度与设置页的「下次提醒」显示共用同一份计算，
 * 避免出现「界面说周三、实际周五才响」这种两套规则。
 */
object ReminderSchedule {

    val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    val WORKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    val THREE_DAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY,
    )

    /**
     * 预设频率对应的提醒日。
     *
     * [ReminderFrequency.CUSTOM] 的真实值存在偏好里，这里返回 [ALL_DAYS] 只作兜底：
     * 调用方在 CUSTOM 时不该拿这个返回值覆盖用户的选择。
     */
    fun daysFor(frequency: ReminderFrequency): Set<DayOfWeek> = when (frequency) {
        ReminderFrequency.DAILY -> ALL_DAYS
        ReminderFrequency.WEEKLY_FIVE -> WORKDAYS
        ReminderFrequency.WEEKLY_THREE -> THREE_DAYS
        ReminderFrequency.CUSTOM -> ALL_DAYS
    }

    /**
     * 下一次提醒的本地时间。
     *
     * - 传入的时钟由调用方给：Worker 与测试都不依赖系统时钟。
     * - 今天的目标时刻**刚好等于** now 时算明天，避免「设定 20:00、20:00:00 打开 App」
     *   把下一次算成此刻又立刻补发。
     * - [days] 为空视为每天：偏好损坏时宁可多提醒一次，也不要永久静默。
     */
    fun nextAt(
        now: LocalDateTime,
        hour: Int,
        minute: Int,
        days: Set<DayOfWeek>,
    ): LocalDateTime {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val effectiveDays = days.ifEmpty { ALL_DAYS }

        var candidate = now.toLocalDate().atTime(h, m)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)

        // 最多遍历 7 天：effectiveDays 非空，所以一定能在 7 天内命中
        var guard = 0
        while (candidate.dayOfWeek !in effectiveDays && guard < 7) {
            candidate = candidate.plusDays(1)
            guard++
        }
        return candidate
    }

    /** 指定日期是否该提醒；空集合按每天处理（与 [nextAt] 同一兜底）。 */
    fun isReminderDay(date: LocalDate, days: Set<DayOfWeek>): Boolean =
        date.dayOfWeek in days.ifEmpty { ALL_DAYS }

    /** 落盘格式：`MONDAY,WEDNESDAY,FRIDAY`。按数值排序，便于人工排查与稳定 diff。 */
    fun encodeDays(days: Set<DayOfWeek>): String =
        days.sortedBy { it.value }.joinToString(",") { it.name }

    /** 解析落盘格式；未知项忽略，坏数据不会让整个提醒失效。 */
    fun parseDays(raw: String?): Set<DayOfWeek> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .mapNotNull { token ->
                runCatching { DayOfWeek.valueOf(token.trim().uppercase(Locale.ROOT)) }.getOrNull()
            }
            .toSet()
    }

    /** 星期名跟着界面语言走；用 SHORT 而不是 FULL，七个 chip 才排得下。 */
    fun shortDayName(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.SHORT, locale)

    /** 预设的「周一 · 周三 · 周五」说明，固定从周一开始。 */
    fun formatDays(days: Set<DayOfWeek>, locale: Locale): String =
        days.sortedBy { it.value }.joinToString(" · ") { shortDayName(it, locale) }
}
