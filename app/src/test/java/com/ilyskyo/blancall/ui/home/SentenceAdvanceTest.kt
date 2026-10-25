// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻卡推进的落点算术（见 [advanceTargetIndex]）。
 *
 * 目标只由**已提交**的索引决定：飞行期间不提交，抢占时也不提交。
 * 因此这里不再存在「在飞数」——同一时刻只允许一个飞离动画，连滑的第二下会被忽略，
 * 直到当前这张真正落位。这个约定让「手还按着、底层已经换卡」不可能发生。
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
}
