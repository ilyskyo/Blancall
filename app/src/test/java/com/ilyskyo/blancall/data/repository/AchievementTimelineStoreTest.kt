// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 成就「解锁时刻」表的落盘与播报语义。
 *
 * 守的是两件容易写错的事：
 * ① 第一次建表时不许把用户**早就有**的成就当成刚解锁来播报（否则升级后一进统计页就弹一排假祝贺）；
 * ② 这张表只增不减 —— 派生结果退回未解锁时时间戳要留着，因为「那一刻达成过」是历史事实。
 */
class AchievementTimelineStoreTest {

    private fun freshStore(name: String): AchievementTimelineStore {
        AchievementTimelineStore.resetInstanceForTest()
        val dir = Files.createTempDirectory("blancall-ach-$name").toFile()
        return AchievementTimelineStore.getInstance(dir)
    }

    @Test
    fun `首次建表只记录不播报`() {
        val store = freshStore("first")
        val fresh = store.recordUnlocked(setOf("first_practice", "practice_10"), now = 1000L)
        assertTrue("升级后一进页面就弹「新成就」是假祝贺：$fresh", fresh.isEmpty())
        // 但时间戳该落下，供以后显示「获得于」
        assertEquals(1000L, store.snapshot()["first_practice"])
    }

    @Test
    fun `之后真正新增的那一枚才算新解锁`() {
        val store = freshStore("later")
        store.recordUnlocked(setOf("first_practice"), now = 1000L)
        val fresh = store.recordUnlocked(setOf("first_practice", "streak_7"), now = 2000L)
        assertEquals(setOf("streak_7"), fresh)
        assertEquals(2000L, store.snapshot()["streak_7"])
        // 已记过的不被覆盖成更晚的时间
        assertEquals(1000L, store.snapshot()["first_practice"])
    }

    @Test
    fun `重开进程后时间戳还在`() {
        val dir = Files.createTempDirectory("blancall-ach-reopen").toFile()
        AchievementTimelineStore.resetInstanceForTest()
        AchievementTimelineStore.getInstance(dir).recordUnlocked(setOf("perfect_one"), now = 5000L)
        AchievementTimelineStore.resetInstanceForTest()
        assertEquals(5000L, AchievementTimelineStore.getInstance(dir).snapshot()["perfect_one"])
        assertTrue(File(dir, "achievements.json").exists())
    }

    @Test
    fun `派生退回未解锁时不删历史`() {
        // 删一篇文章会让 practice_10 的派生结果翻回未解锁；这张表不该跟着倒带
        val store = freshStore("keep")
        store.recordUnlocked(setOf("practice_10"), now = 3000L)
        val fresh = store.recordUnlocked(emptySet(), now = 4000L)
        assertTrue(fresh.isEmpty())
        assertEquals(3000L, store.snapshot()["practice_10"])
    }

    @Test
    fun `高版本的表不会被本版本改写`() {
        val dir = Files.createTempDirectory("blancall-ach-newer").toFile()
        File(dir, "achievements.json").writeText("""{"version":99,"unlocked":{"future_badge":1},"futureField":{}}""")
        AchievementTimelineStore.resetInstanceForTest()
        val store = AchievementTimelineStore.getInstance(dir)
        store.recordUnlocked(setOf("practice_10"), now = 9000L)
        val text = File(dir, "achievements.json").readText()
        assertTrue("高版本字段被抹掉了：$text", text.contains("futureField"))
        assertTrue(text.contains("\"version\":99"))
    }
}
