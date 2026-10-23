// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * 「学习任务流」的纯调度推导：暂停冻结、恢复落点、按日分桶与 7 日预测。
 *
 * 暂停不是把 due 改到很远的地方 —— 那样恢复时原始间隔已经丢了。这里冻结的是
 * **暂停那一刻离到期还有多久**（[Pause.remainingMs]），恢复时从当下重新接上这段剩余期；
 * 暂停期间用户若手动练过一次，状态已由练习页正常推进，恢复就尊重那次推进（取较晚者）。
 *
 * 全部函数无 Android 依赖、可注入时钟与时区，JVM 单测直接覆盖。
 */
object StudyFlow {

    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** 任务在时间轴上的位置；顺序即界面分组顺序 */
    enum class Phase { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER, PAUSED }

    /** 暂停快照：暂停时刻 + 当时剩余的到期时长（毫秒，>=0） */
    data class Pause(val pausedAt: Long, val remainingMs: Long)

    /** 暂停时冻结的剩余期：已逾期则冻结为 0（恢复即到期） */
    fun remainingAtPause(due: Long, now: Long): Long = (due - now).coerceAtLeast(0L)

    fun pauseOf(state: FsrsEngine.CardState, now: Long): Pause =
        Pause(pausedAt = now, remainingMs = remainingAtPause(state.due, now))

    /**
     * 暂停中的任务「预计在哪天到期」：
     * - 暂停后没有手动练习：恢复时刻 + 冻结剩余期，随当前时间滑动；
     * - 暂停后手动练过：那次练习已经写了新的 due，展示与恢复都以它为准（已过期则回到现在）。
     */
    fun projectedDue(
        state: FsrsEngine.CardState,
        pause: Pause?,
        now: Long,
    ): Long {
        if (pause == null) return state.due
        if (state.lastReview > pause.pausedAt) return max(state.due, now)
        return now + pause.remainingMs
    }

    /** 恢复后的 due；语义与 [projectedDue] 相同，单独命名让调用点读得懂意图 */
    fun resumedDue(state: FsrsEngine.CardState, pause: Pause, now: Long): Long =
        projectedDue(state, pause, now)

    /** 按本地日历日分桶；暂停优先于任何日期结论 */
    fun phase(
        due: Long,
        paused: Boolean,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Phase {
        if (paused) return Phase.PAUSED
        val today = localDate(now, zone)
        val startToday = startOfDay(today, zone)
        val startTomorrow = startOfDay(today.plusDays(1), zone)
        val startDayAfter = startOfDay(today.plusDays(2), zone)
        val startWeek = startOfDay(today.plusDays(7), zone)
        return when {
            due < startToday -> Phase.OVERDUE
            due < startTomorrow -> Phase.TODAY
            due < startDayAfter -> Phase.TOMORROW
            due < startWeek -> Phase.THIS_WEEK
            else -> Phase.LATER
        }
    }

    /** 任务落在未来 [days] 天里的第几格（0 = 今天，负/越界返回 null；已逾期不计入预测） */
    fun forecastIndex(
        due: Long,
        now: Long,
        days: Int = 7,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        val today = localDate(now, zone)
        val dueDate = localDate(due, zone)
        val index = ChronoUnit.DAYS.between(today, dueDate).toInt()
        return if (index in 0 until days) index else null
    }

    /** 未来 [days] 天每天到期的任务数（index 0 = 今天；已逾期不计） */
    fun forecast(
        dueTimes: List<Long>,
        now: Long,
        days: Int = 7,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Int> {
        val out = MutableList(days) { 0 }
        dueTimes.forEach { due ->
            forecastIndex(due, now, days, zone)?.let { out[it]++ }
        }
        return out
    }

    /** 把某个时间戳挪到本地日的 00:00；今天及以前的选择会回落到 [now]（立即到期） */
    fun dueAtDayStart(
        epochDaySource: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val day = localDate(epochDaySource, zone)
        return max(startOfDay(day, zone), now)
    }

    fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
