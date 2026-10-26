// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻卡推进的落点算术（见 [advanceTargetIndex] 与 [acceptsTapOn]）。
 *
 * 目标只由**已提交**的索引决定。手势飞离期间不提交、抢占时也不提交，
 * 所以「手还按着、底层已经换卡」不可能发生。
 *
 * 点按评级走的是另一条：索引在点击这一帧就提交，飞走的是旧卡副本 ——
 * 见下面的连点用例，这是「点三次只翻两次」那个 bug 的回归线。
 */
class SentenceAdvanceTest {

    @Test
    fun `前进与回看都只基于当前已提交索引`() {
        assertEquals(1, advanceTargetIndex(current = 0, forward = true, size = 5))
        assertEquals(2, advanceTargetIndex(current = 1, forward = true, size = 5))
        assertEquals(0, advanceTargetIndex(current = 1, forward = false, size = 5))
        assertEquals(3, advanceTargetIndex(current = 4, forward = false, size = 5))
    }

    @Test
    fun `两端都夹住不越界`() {
        // 第一张再往下划不能变负
        assertEquals(0, advanceTargetIndex(current = 0, forward = false, size = 5))
        // size 这一档是「完成」页：可以到达、不能越过
        assertEquals(5, advanceTargetIndex(current = 4, forward = true, size = 5))
        assertEquals(5, advanceTargetIndex(current = 5, forward = true, size = 5))
    }

    @Test
    fun `连点三次就翻三张_每次落在不同的卡上`() {
        // 模拟连续点评级键：每次点击都结算「此刻」那张，然后索引当场前进
        val size = 10
        var index = 0
        val tapped = mutableListOf<Int>()
        repeat(3) {
            tapped += index
            index = advanceTargetIndex(index, forward = true, size)
        }
        // 点的不是同一张（旧 bug：三次全记在第一张上）
        assertEquals(listOf(0, 1, 2), tapped)
        // 也不是跳着走的（旧 bug：第二下被吞，落到 2 只用了两次推进）
        assertEquals(3, index)
    }

    @Test
    fun `连点停在最后一张只到完成页不越界`() {
        var index = 0
        repeat(5) { index = advanceTargetIndex(index, forward = true, size = 3) }
        assertEquals(3, index)
    }

    @Test
    fun `点的是此刻那张才采纳`() {
        // 正常连点：索引已前进，按钮已绑到新卡
        assertTrue(acceptsTapOn(currentIndex = 1, size = 5, tappedKey = "a1:1", currentKey = "a1:1"))
        // 同一帧内的第二下：lambda 还握着上一张 —— 丢弃，否则一张卡连跳两格
        assertFalse(acceptsTapOn(currentIndex = 1, size = 5, tappedKey = "a1:0", currentKey = "a1:1"))
        // 已到完成页（没有卡可评）
        assertFalse(acceptsTapOn(currentIndex = 5, size = 5, tappedKey = "a1:4", currentKey = null))
        // 队列还是空的时候
        assertFalse(acceptsTapOn(currentIndex = 0, size = 0, tappedKey = "a1:0", currentKey = null))
    }
}
