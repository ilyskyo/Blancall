// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻卡推进的落点算术（见 [advanceTargetIndex]）。
 *
 * 只测「该落到第几张」，不测动画：竞态的全部后果都体现在这个数上，
 * 而它发生在协程与动画之间 —— 少翻一张卡在屏幕上只是「划得挺顺」，
 * 但 FSRS 就少了一次评分、进度条也跳了一格。所以这条必须被测住。
 */
class SentenceAdvanceTest {

    @Test
    fun `连续两次前进不吞掉中间那张`() {
        // 第一次飞行还没提交：current=0 而 inFlight=1，目标必须是 2 而不是 1
        assertEquals(1, advanceTargetIndex(current = 0, inFlight = 0, forward = true, size = 5))
        assertEquals(2, advanceTargetIndex(current = 0, inFlight = 1, forward = true, size = 5))
        assertEquals(3, advanceTargetIndex(current = 0, inFlight = 2, forward = true, size = 5))
    }

    @Test
    fun `回看同样带上在飞数并夹在 0`() {
        assertEquals(4, advanceTargetIndex(current = 5, inFlight = 0, forward = false, size = 5))
        assertEquals(3, advanceTargetIndex(current = 4, inFlight = 0, forward = false, size = 5))
        assertEquals(0, advanceTargetIndex(current = 1, inFlight = 0, forward = false, size = 5))
        // 第一张再往下划也不能变负
        assertEquals(0, advanceTargetIndex(current = 0, inFlight = 0, forward = false, size = 5))
    }

    @Test
    fun `末张之后停在完成态而不是越界`() {
        // size 这一档是「完成」页：可以到达、不能越过
        assertEquals(5, advanceTargetIndex(current = 4, inFlight = 0, forward = true, size = 5))
        assertEquals(5, advanceTargetIndex(current = 4, inFlight = 3, forward = true, size = 5))
        assertEquals(5, advanceTargetIndex(current = 5, inFlight = 0, forward = true, size = 5))
    }
}
