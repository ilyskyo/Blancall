// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DayBucket] 的口径断言。
 *
 * 这里守的是「一个练习到底算哪一天」——统计页原先同时用滚动毫秒窗口、系统时区日历天、
 * 以及一个从不反推的持久计数器，同一屏上的数字因此互相对不上。
 * 时区固定写成 Asia/Shanghai，不依赖跑测试的机器：换机器就该换出同样的结果。
 */
class DayBucketTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 11)

    /** 北京时间 2026-10-11 01:30（= UTC 2026-10-10 17:30） */
    private val earlyMorningMilli: Long =
        LocalDate.of(2026, 10, 11).atTime(1, 30).atZone(shanghai).toInstant().toEpochMilli()

    @Test
    fun 凌晨的练习按设备时区算进当天而不是前一天() {
        assertEquals(today, DayBucket.localDate(earlyMorningMilli, shanghai))
        // 同一毫秒在 UTC 下仍属于 10-10：证明这条断言量的是时区口径，不是常数巧合
        assertEquals(
            LocalDate.of(2026, 10, 10),
            DayBucket.localDate(earlyMorningMilli, ZoneId.of("UTC"))
        )
    }

    @Test
    fun 最近七天的起点是六个日历天前的零点() {
        val start = DayBucket.startOfLastDays(today, 7, shanghai)
        assertEquals(LocalDate.of(2026, 10, 5), DayBucket.localDate(start, shanghai))
        assertEquals(0, java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(start), shanghai).toLocalTime().nano)
        // 昨天 23:59 的练习必须在窗口内（滚动 7×86400000ms 会把它按小时数挤出去）
        val lastNight = today.minusDays(1).atTime(23, 59).atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(true, lastNight >= start)
    }

    @Test
    fun 连续天数从今天或昨天往回数且断一天即停() {
        val d = { n: Long -> today.minusDays(n) }
        assertEquals(3, DayBucket.consecutiveDays(setOf(d(0), d(1), d(2)), today))
        // 今天还没练：连续段仍然成立，不该显示 0
        assertEquals(3, DayBucket.consecutiveDays(setOf(d(1), d(2), d(3)), today))
        // 昨天断了：今天练过也只算 1
        assertEquals(1, DayBucket.consecutiveDays(setOf(d(0), d(2)), today))
        // 今天与昨天都没练
        assertEquals(0, DayBucket.consecutiveDays(setOf(d(2)), today))
        assertEquals(0, DayBucket.consecutiveDays(emptySet(), today))
    }

    @Test
    fun 历史最长从学过的日子反推与今天无关() {
        val d = { n: Long -> today.minusDays(n) }
        // 中间断两天，最长段是前面那 4 天
        assertEquals(4, DayBucket.longestRun(setOf(d(9), d(8), d(7), d(6), d(4), d(3))))
        // 今天刚断签不影响「历史最长」——徽章看的是这段有没有发生过
        assertEquals(4, DayBucket.longestRun(setOf(d(9), d(8), d(7), d(6), d(0))))
        // 单点、空集
        assertEquals(1, DayBucket.longestRun(setOf(d(3))))
        assertEquals(0, DayBucket.longestRun(emptySet()))
    }
}
