// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.algorithm.FsrsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 评级按钮状态回归测试：
 * - [resolveDisplayedRating] 回显语义：会话内最新选择优先 → 持久化上次评级 → 无高亮；
 * - 「今日已评 = 只读」已移除：回看已评卡可改判（防只读逻辑被加回的注释锚点，
 *   rate() 内部仅保留 animating 防连点 guard）。
 */
class RateButtonStateTest {

    @Test
    fun `回显：会话内最新选择优先于持久化评级`() {
        assertEquals(
            FsrsEngine.Rating.GOOD,
            resolveDisplayedRating(session = FsrsEngine.Rating.GOOD, persisted = "HARD"),
        )
    }

    @Test
    fun `回显：无会话记录时解析持久化的上次评级`() {
        assertEquals(
            FsrsEngine.Rating.HARD,
            resolveDisplayedRating(session = null, persisted = "HARD"),
        )
        assertEquals(
            FsrsEngine.Rating.AGAIN,
            resolveDisplayedRating(session = null, persisted = "AGAIN"),
        )
    }

    @Test
    fun `回显：空与未知评级均不显示高亮`() {
        assertNull(resolveDisplayedRating(session = null, persisted = ""))
        assertNull(resolveDisplayedRating(session = null, persisted = null))
        assertNull(resolveDisplayedRating(session = null, persisted = "UNKNOWN"))
    }
}
