// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.ui.common.pullReleaseLatched
import com.ilyskyo.blancall.ui.common.pullReleaseReached
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局统计页「下拉进入学习任务流」的阈值语义。
 *
 * 手势闭包（NestedScrollConnection）与这条测试必须说同一件事：
 * 位移**达到**阈值才够「松手即进入」；差一点不算；阈值非正属配置错误，
 * 不允许退化成「一碰就进」。
 *
 * ⚠️ 这条判定只在**抬手那一刻**使用：拖动过程中越过阈值只给一次触感、绝不导航
 * （见 OverviewScreen.releaseFlowPull）。语义放在纯函数里，测试才拦得住回归。
 */
class OverviewFlowPullTest {

    @Test
    fun `未达阈值不进入`() {
        assertFalse(pullReleaseReached(pullPx = 87.9f, thresholdPx = 88f))
    }

    @Test
    fun `刚好达到阈值即满足进入条件`() {
        assertTrue(pullReleaseReached(pullPx = 88f, thresholdPx = 88f))
    }

    @Test
    fun `超过阈值仍满足进入条件`() {
        assertTrue(pullReleaseReached(pullPx = 140f, thresholdPx = 88f))
    }

    @Test
    fun `阈值为零或负数时永不进入`() {
        assertFalse(pullReleaseReached(pullPx = 0f, thresholdPx = 0f))
        assertFalse(pullReleaseReached(pullPx = 120f, thresholdPx = -1f))
    }

    @Test
    fun `满格后小幅回带仍算满格_抬手回带不再随机吞掉进入`() {
        // 回归：「圆圈已画满，松手却差 1–2dp」—— 手指抬起时的轻微回带让瞬时值掉到阈值下。
        val threshold = 88f
        val slack = 12f
        assertTrue(pullReleaseLatched(false, 88f, threshold, slack))
        assertTrue(pullReleaseLatched(true, 84f, threshold, slack))
        assertTrue(pullReleaseLatched(true, 80f, threshold, slack))
        assertTrue(pullReleaseLatched(true, 77f, threshold, slack))
    }

    @Test
    fun `回退超过容差才解除闩锁_保留主动撤回`() {
        val threshold = 88f
        val slack = 12f
        assertFalse(pullReleaseLatched(true, 76f, threshold, slack))
        assertFalse(pullReleaseLatched(true, 0f, threshold, slack))
    }

    @Test
    fun `没满过格时容差不会凭空闩锁`() {
        val threshold = 88f
        val slack = 12f
        assertFalse(pullReleaseLatched(false, 87.9f, threshold, slack))
        assertFalse(pullReleaseLatched(false, 76f, threshold, slack))
        assertTrue(pullReleaseLatched(false, 88f, threshold, slack))
    }

    @Test
    fun `阈值非正时永不闩锁`() {
        assertFalse(pullReleaseLatched(true, 120f, 0f, 12f))
        assertFalse(pullReleaseLatched(true, 120f, -1f, 12f))
    }
}
