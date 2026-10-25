// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局统计页「下拉进入学习任务流」的阈值语义。
 *
 * 手势闭包（NestedScrollConnection）与这条测试必须说同一件事：
 * 位移**达到**阈值即触发（震动 + 导航），差一点不触发；阈值非正属配置错误，
 * 不允许退化成「一碰就进」。导航只由 `flowPullArmed` 控制一次，见 OverviewScreen。
 */
class OverviewFlowPullTest {

    @Test
    fun `未达阈值不触发`() {
        assertFalse(flowPullReached(pullPx = 87.9f, thresholdPx = 88f))
    }

    @Test
    fun `刚好达到阈值即触发`() {
        assertTrue(flowPullReached(pullPx = 88f, thresholdPx = 88f))
    }

    @Test
    fun `超过阈值仍触发`() {
        assertTrue(flowPullReached(pullPx = 140f, thresholdPx = 88f))
    }

    @Test
    fun `阈值为零或负数时永不触发`() {
        assertFalse(flowPullReached(pullPx = 0f, thresholdPx = 0f))
        assertFalse(flowPullReached(pullPx = 120f, thresholdPx = -1f))
    }
}
