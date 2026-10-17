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

    @Test
    fun `间隔预览可复现且随评级单调不减`() {
        val fresh = FsrsEngine.CardState()
        // 跳过 fuzz 才敢当按钮文案：同一输入必须每次给出同一个数，否则重组一次就跳一个值
        assertEquals(
            FsrsEngine.previewIntervalDays(fresh, FsrsEngine.Rating.GOOD, now),
            FsrsEngine.previewIntervalDays(fresh, FsrsEngine.Rating.GOOD, now),
        )
        val again = FsrsEngine.previewIntervalDays(fresh, FsrsEngine.Rating.AGAIN, now)
        val hard = FsrsEngine.previewIntervalDays(fresh, FsrsEngine.Rating.HARD, now)
        val good = FsrsEngine.previewIntervalDays(fresh, FsrsEngine.Rating.GOOD, now)
        assertTrue("间隔下限为 1 天", again >= 1)
        assertTrue("新卡 AGAIN 不应长于 HARD: $again > $hard", again <= hard)
        assertTrue("新卡 HARD 不应长于 GOOD: $hard > $good", hard <= good)
    }

    @Test
    fun `间隔预览落在真实调度的扰动区间内`() {
        // 成熟卡（连续三次 GOOD 后）：间隔已过 fuzz 生效阈值（>=2.5 天）
        var mature = FsrsEngine.CardState()
        mature = FsrsEngine.review(mature, FsrsEngine.Rating.GOOD, now)
        mature = FsrsEngine.review(mature, FsrsEngine.Rating.GOOD, now + 5 * day)
        mature = FsrsEngine.review(mature, FsrsEngine.Rating.GOOD, now + 20 * day)
        val t = now + 60 * day

        val preview = FsrsEngine.previewIntervalDays(mature, FsrsEngine.Rating.GOOD, t)
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        repeat(200) {
            val d = ((FsrsEngine.review(mature, FsrsEngine.Rating.GOOD, t).due - t) / day).toInt()
            lo = minOf(lo, d)
            hi = maxOf(hi, d)
        }
        // fuzz 只做 ±5% 抖动、不改量级：预览必须落在真实间隔的取值区间里
        assertTrue("预览 $preview 应落在真实间隔 [$lo, $hi] 内", preview in lo..hi)
    }
}
