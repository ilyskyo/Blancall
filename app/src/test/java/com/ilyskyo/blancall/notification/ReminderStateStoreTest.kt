// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒当日去重表的语义。
 *
 * 守的是三件容易写错的事：
 * ① 同一天同一条原因只发一次（否则系统补跑 / 手动触发会「一天三遍」）；
 * ② 跨天自动失效 —— 靠比对 `DayBucket.localDate`，不是靠定时清理；
 * ③ 盘上是更高版本写的文件时拒绝改写（与各 Store 同一份版本守卫）。
 */
class ReminderStateStoreTest {

    /** 某个日历日 00:00 的毫秒（与 DayBucket 同一时区口径）。 */
    private fun epochOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun freshStore(name: String): ReminderStateStore {
        ReminderStateStore.resetInstanceForTest()
        val dir = Files.createTempDirectory("blancall-reminder-$name").toFile()
        return ReminderStateStore.getInstance(dir)
    }

    @Test
    fun `首次没有任何记录时可以发`() {
        val store = freshStore("first")
        assertTrue(store.shouldSend(ReminderReason.NoStudyToday, epochOf(2026, 1, 1)))
    }

    @Test
    fun `标记后当天不再发同一条原因`() {
        val store = freshStore("same-day")
        val now = epochOf(2026, 1, 1) + 20 * 3600_000L
        store.markSent(ReminderReason.NoStudyToday, now)
        assertFalse(store.shouldSend(ReminderReason.NoStudyToday, now))
    }

    @Test
    fun `不同原因互不影响`() {
        val store = freshStore("reasons")
        val now = epochOf(2026, 1, 1)
        store.markSent(ReminderReason.NoStudyToday, now)
        assertTrue(
            "只发过「今日未学习」，不该连累「到期复习」：${store.sentToday(now)}",
            store.shouldSend(ReminderReason.DueReview, now),
        )
    }

    @Test
    fun `跨天自动失效`() {
        val store = freshStore("cross-day")
        val day1 = epochOf(2026, 1, 1) + 20 * 3600_000L
        val day2 = epochOf(2026, 1, 2) + 20 * 3600_000L
        store.markSent(ReminderReason.NoStudyToday, day1)
        assertFalse(store.shouldSend(ReminderReason.NoStudyToday, day1))
        assertTrue("昨天发过不算今天发过", store.shouldSend(ReminderReason.NoStudyToday, day2))
        assertTrue("昨天的记录应在新的一天被清空", store.sentToday(day2).isEmpty())
    }

    @Test
    fun `重开进程后仍然去重`() {
        val dir = Files.createTempDirectory("blancall-reminder-reopen").toFile()
        val now = epochOf(2026, 1, 1) + 8 * 3600_000L
        ReminderStateStore.resetInstanceForTest()
        ReminderStateStore.getInstance(dir).markSent(ReminderReason.DueReview, now)
        ReminderStateStore.resetInstanceForTest()
        val reopened = ReminderStateStore.getInstance(dir)
        assertFalse("落盘没生效，重启后会重复打扰", reopened.shouldSend(ReminderReason.DueReview, now))
        assertTrue(File(dir, "reminder_state.json").exists())
    }

    @Test
    fun `高版本的文件不会被本版本改写`() {
        val dir = Files.createTempDirectory("blancall-reminder-newer").toFile()
        File(dir, "reminder_state.json").writeText(
            """{"version":99,"day":"2026-01-01","sent":["NoStudyToday"],"futureField":{}}"""
        )
        ReminderStateStore.resetInstanceForTest()
        val store = ReminderStateStore.getInstance(dir)
        store.markSent(ReminderReason.DueReview, epochOf(2026, 1, 1))
        val text = File(dir, "reminder_state.json").readText()
        assertTrue("高版本字段被抹掉了：$text", text.contains("futureField"))
        assertTrue(text.contains("\"version\":99"))
    }

    @Test
    fun `未知原因名不会让读取崩溃`() {
        val dir = Files.createTempDirectory("blancall-reminder-unknown").toFile()
        File(dir, "reminder_state.json").writeText(
            """{"version":1,"day":"2026-01-01","sent":["SomethingFromTheFuture"]}"""
        )
        ReminderStateStore.resetInstanceForTest()
        val store = ReminderStateStore.getInstance(dir)
        val now = epochOf(2026, 1, 1)
        assertEquals("未知名字应被忽略而不是抛异常", emptySet<ReminderReason>(), store.sentToday(now))
    }
}
