// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StylusPresence] 判定依据测试：`sources` 的 STYLUS 类别位识别。
 *
 * 纯位运算逻辑（[InputDevice.SOURCE_STYLUS] 为编译期常量、JVM 直接内联），
 * 不触碰 InputManager 等运行时 API。
 */
class StylusPresenceTest {

    @Test
    fun `STYLUS 类别设备判定为有笔`() {
        assertTrue(StylusPresence.hasStylusSource(InputDevice.SOURCE_STYLUS))
    }

    @Test
    fun `触屏键盘鼠标不会误判为有笔`() {
        assertFalse(StylusPresence.hasStylusSource(InputDevice.SOURCE_TOUCHSCREEN))
        assertFalse(StylusPresence.hasStylusSource(InputDevice.SOURCE_KEYBOARD))
        assertFalse(StylusPresence.hasStylusSource(InputDevice.SOURCE_MOUSE))
        assertFalse(StylusPresence.hasStylusSource(InputDevice.SOURCE_JOYSTICK))
    }

    @Test
    fun `触屏与笔的组合来源仍判定为有笔`() {
        // 数字化仪常见上报：SOURCE_TOUCHSCREEN | SOURCE_STYLUS
        assertTrue(
            StylusPresence.hasStylusSource(
                InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
            )
        )
    }
}
