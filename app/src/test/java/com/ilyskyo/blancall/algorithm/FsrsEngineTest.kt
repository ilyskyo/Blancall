// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FsrsEngine 回归测试（now 可注入保证确定性）。
 * 测试不变量而非精确数值：FSRS-6 内部含随机扰动因子，精确值会随实现抖动。
 */
class FsrsEngineTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `新卡首次评级产生正稳定性与合法难度`() {
        for (rating in listOf(
            FsrsEngine.Rating.AGAIN, FsrsEngine.Rating.HARD,
            FsrsEngine.Rating.GOOD, FsrsEngine.Rating.EASY
        )) {
            val s = FsrsEngine.review(FsrsEngine.CardState(), rating, now)
            assertTrue("stability 应为正: $rating", s.stability > 0.0)
            assertTrue("difficulty 应在 1-10: $rating", s.difficulty in 1.0..10.0)
            assertEquals("reviewCount=1: $rating", 1, s.reviewCount)
            assertEquals("lastReview=now: $rating", now, s.lastReview)
            assertTrue("due > lastReview: $rating", s.due > now)
        }
    }

    @Test
    fun `AGAIN 计入 lapses 且下次复习时间最近`() {
        val again = FsrsEngine.review(FsrsEngine.CardState(), FsrsEngine.Rating.AGAIN, now)
        val good = FsrsEngine.review(FsrsEngine.CardState(), FsrsEngine.Rating.GOOD, now)
        assertEquals(1, again.lapses)
        assertEquals(0, good.lapses)
        assertTrue("AGAIN 的间隔应短于 GOOD", again.due - now <= good.due - now)
    }

    @Test
    fun `连续 GOOD 稳定性单调增长`() {
        var state = FsrsEngine.CardState()
        var t = now
        var prev = 0.0
        repeat(5) {
            state = FsrsEngine.review(state, FsrsEngine.Rating.GOOD, t)
            assertTrue("稳定性应单调增长: ${state.stability} vs $prev", state.stability > prev)
            prev = state.stability
            t += 1 * day
        }
    }

    @Test
    fun `retentionRate 随时间衰减且在 0-1 之间`() {
        var state = FsrsEngine.CardState()
        state = FsrsEngine.review(state, FsrsEngine.Rating.GOOD, now)
        val r1 = FsrsEngine.retentionRate(state, now + day)
        val r30 = FsrsEngine.retentionRate(state, now + 30 * day)
        assertTrue("留存率应在 0-1: $r1", r1 in 0.0..1.0)
        assertTrue("时间越远留存越低", r30 < r1)
        // 刚复习完留存率应接近满
        val r0 = FsrsEngine.retentionRate(state, now)
        assertTrue(r0 > 0.85)
    }

    @Test
    fun `gradeFromSimilarity 满分轻松零分忘记`() {
        assertEquals(FsrsEngine.Rating.EASY, FsrsEngine.gradeFromSimilarity(1.0f))
        assertEquals(FsrsEngine.Rating.AGAIN, FsrsEngine.gradeFromSimilarity(0.0f))
    }

    @Test
    fun `retentionForTemplate 三档目标留存率合法`() {
        for (id in listOf("sprint", "standard", "deep")) {
            val r = FsrsEngine.retentionForTemplate(id)
            assertTrue("目标留存率应在 0.5-1: $id=$r", r in 0.5..1.0)
        }
    }
}
