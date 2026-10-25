// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点框的来源判定。
 *
 * 不能用「最近有没有 PressInteraction」推断：触摸后节点常常仍持有焦点，
 * 而一次滚动或点击留下的 Press 会让该实例此后永远不再画框。
 * Android 的 `View.isInTouchMode` 是平台自带的判据。
 */
class PressFeedbackTest {

    @Test
    fun `触摸焦点不画框`() {
        assertFalse(shouldShowFocusRing(focused = true, inTouchMode = true))
    }

    @Test
    fun `键盘焦点必须画框`() {
        assertTrue(shouldShowFocusRing(focused = true, inTouchMode = false))
    }

    @Test
    fun `失焦永远不画框`() {
        assertFalse(shouldShowFocusRing(focused = false, inTouchMode = false))
        assertFalse(shouldShowFocusRing(focused = false, inTouchMode = true))
    }
}
