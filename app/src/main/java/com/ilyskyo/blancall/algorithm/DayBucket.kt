// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「天」的唯一口径。
 *
 * 之前统计页里同时存在三套互不相容的定义：
 * ① 趋势用滚动窗口 `System.currentTimeMillis() - 7*86400000`（跨不过时区与夏令时，
 *    夜里 23:00 练的和 01:00 练的会被算进不同的一侧）；
 * ② 日历热力图与今日次数用系统时区的日历天；
 * ③ 连续打卡只读一个持久化计数器，从不从记录反推，所以计数器一旦与记录不一致就一直错下去。
 * 同一个页面里三种「天」并排显示，用户看到的就是数字互相对不上。
 *
 * 全部收敛到这里：一律按**设备时区的日历天**分桶。
 */
object DayBucket {

    /** epochMilli → 设备时区的日历天 */
    fun localDate(epochMilli: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMilli).atZone(zone).toLocalDate()

    /**
     * 「最近 [days] 个日历天」的起点毫秒（含今天，即 [days] 减 1 天前的 00:00）。
     * 用它替代滚动 `days*86400000ms`，趋势的分界才会和热力图、今日次数落在同一条线上。
     */
    fun startOfLastDays(
        today: LocalDate,
        days: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long =
        today.minusDays((days - 1).coerceAtLeast(0).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * 从「学过的日子」集合反推连续天数：今天学过就从今天往回数，
     * 今天还没学则从昨天往回数（昨天学了就叫「连续 N 天，今天还没破」）。
     * 空集合返回 0。
     */
    fun consecutiveDays(studiedDays: Set<LocalDate>, today: LocalDate): Int {
        if (studiedDays.isEmpty()) return 0
        var cursor = if (today in studiedDays) today else today.minusDays(1)
        if (cursor !in studiedDays) return 0
        var count = 0
        while (cursor in studiedDays) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /**
     * 从「学过的日子」集合反推【历史最长】连续天数（无序集合也可，内部先排序）。
     *
     * 为什么不用 SharedPreferences 那个计数器：它只由「提交成功」自增、从不从记录反推，
     * 清过数据、导过备份、换过机之后会停在某个无据可查的数字上。而成就徽章是按它发的奖 ——
     * 同一个屏幕上半数数字来自记录、徽章却来自另一套真相，就是上面那条「三套天」的老病。
     */
    fun longestRun(studiedDays: Set<LocalDate>): Int {
        if (studiedDays.isEmpty()) return 0
        val sorted = studiedDays.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}
