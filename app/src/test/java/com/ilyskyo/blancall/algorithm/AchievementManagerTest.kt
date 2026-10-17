// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeMode
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成就判据回归：徽章必须能回答「记住了什么」，而不只是「练了多少」。
 *
 * 三条容易写歪的地方：
 * ① 「全才」的门槛跟着 PracticeMode 全集走，写死 3 会在加第 4 种模式时提前且静默地发奖；
 * ② 记忆向两枚要求「平均留存率达标 **且** 已练句数够多」，否则只练过两句的号也能白拿；
 * ③ avgRetention 为 null（一句都没练过）不许被当成 0% 或直接解锁。
 */
class AchievementManagerTest {

    private val day = 24L * 60 * 60 * 1000

    private fun record(mode: String = "SENTENCE", correct: Int = 5, blanks: Int = 5, durationMin: Long = 1) =
        PracticeRecord(
            articleId = 1L, mode = mode, totalBlanks = blanks, correctCount = correct,
            timestamp = System.currentTimeMillis(), duration = durationMin * 60_000L
        )

    private fun unlockedIds(records: List<PracticeRecord>, memory: AchievementManager.MemoryStats, longest: Int = 0) =
        AchievementManager.evaluate(records, longest, memory).filter { it.unlocked }.map { it.id }.toSet()

    @Test
    fun `徽章只给判据不给文案`() {
        val all = AchievementManager.evaluate(listOf(record()), 0, AchievementManager.MemoryStats())
        // id 唯一（渲染层按 id 取资源，撞号就是两枚徽章共用一条文案）
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val first = all.first { it.id == "first_practice" }
        assertEquals(1, first.current)
        assertEquals(1, first.target)
        assertEquals(1f, first.progress, 0f)
        assertTrue(first.unlocked)
    }

    @Test
    fun `全才跟着模式枚举全集而不是写死的数字`() {
        val modes = PracticeMode.entries.map { it.name }
        // 少练一种就绝不能解锁
        val partial = unlockedIds(modes.dropLast(1).map { record(mode = it) }, AchievementManager.MemoryStats())
        assertFalse("缺一种模式也算全才：$partial", partial.contains("all_modes"))
        val full = unlockedIds(modes.map { record(mode = it) }, AchievementManager.MemoryStats())
        assertTrue("三种模式都练过却不算全才：$full", full.contains("all_modes"))
    }

    @Test
    fun `留存率达标但没有练够句数不算掌握`() {
        val notEnough = AchievementManager.MemoryStats(learnedSentences = 5, avgRetention = 0.98f, dueSentences = 0)
        val ids = unlockedIds(listOf(record()), notEnough)
        assertFalse("只练过 5 句就给「留存九成」：$ids", ids.contains("memory_90"))

        val enough = AchievementManager.MemoryStats(learnedSentences = 25, avgRetention = 0.92f, dueSentences = 3)
        val ids2 = unlockedIds(listOf(record()), enough)
        assertTrue("留存 92% 且练过 25 句却不算：$ids2", ids2.contains("memory_90"))
        assertFalse("92% 不该满足 95% 档：$ids2", ids2.contains("memory_95"))
    }

    @Test
    fun `一句都没练过时留存率为空而不是零`() {
        // avgRetention=null 是「无从判断」，与「留存 0%」是两回事：
        // 混成一谈会让新用户的统计页显示 0% 留存，或让记忆向徽章被空数据解锁
        assertNull(AchievementManager.MemoryStats().avgRetention)
        val ids = unlockedIds(listOf(record()), AchievementManager.MemoryStats())
        assertTrue("空记忆数据解锁了记忆向徽章：$ids",
            ids.intersect(setOf("memory_90", "memory_95", "due_cleared")).isEmpty())
    }

    @Test
    fun `到期清零要求有存量且无到期`() {
        val cleared = AchievementManager.MemoryStats(learnedSentences = 30, avgRetention = 0.7f, dueSentences = 0)
        assertTrue(unlockedIds(listOf(record()), cleared).contains("due_cleared"))
        val stillDue = AchievementManager.MemoryStats(learnedSentences = 30, avgRetention = 0.7f, dueSentences = 2)
        assertFalse(unlockedIds(listOf(record()), stillDue).contains("due_cleared"))
    }

    @Test
    fun `族与单位都齐备以便渲染层取文案`() {
        val all = AchievementManager.evaluate(listOf(record()), 0, AchievementManager.MemoryStats())
        // 阶梯顺序：族内按定义顺序给（渲染层按族分组，不再按解锁态打散）
        val practice = all.filter { it.family == AchievementManager.Family.PRACTICE }.map { it.target }
        assertEquals(listOf(1, 10, 100), practice)
        val streak = all.filter { it.family == AchievementManager.Family.STREAK }.map { it.unit }.distinct()
        assertEquals(listOf(AchievementManager.Unit.DAY), streak)
        assertNotNull(all.first { it.id == "focus_60" }.unit)
    }

    @Test
    fun `断签不影响历史最长那三枚`() {
        // 判据只看 longestStreak（由 DayBucket.longestRun 从记录反推）：
        // 今天没练不该让已经拿到的连击徽章消失
        val earned = unlockedIds(listOf(record()), AchievementManager.MemoryStats(), longest = 8)
        assertTrue(earned.contains("streak_7"))
        assertFalse(earned.contains("streak_30"))
    }
}
