// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字号滑杆「无极拖动 + 5% 刻度吸附」的取值规则。
 *
 * 为什么要有这几条：吸附是手感，不是像素 —— 只靠真机看，很难区分
 * 「吸住了」和「碰巧落在整数」。把规则钉成纯函数后，靠近、离开、越界三种情形都能复算。
 */
class FontScaleSnapTest {

    @Test
    fun 刻度值原样返回() {
        var v = AppearanceStore.MIN_SCALE
        while (v <= AppearanceStore.MAX_SCALE + 1e-4f) {
            assertEquals(v, AppearanceStore.snapUiFontScale(v), 1e-4f)
            v += AppearanceStore.FONT_SCALE_STEP
        }
    }

    @Test
    fun 刻度附近吸附() {
        assertEquals(1.15f, AppearanceStore.snapUiFontScale(1.1534f), 1e-4f)
        assertEquals(1.15f, AppearanceStore.snapUiFontScale(1.1472f), 1e-4f)
        assertEquals(1.00f, AppearanceStore.snapUiFontScale(0.9968f), 1e-4f)
        assertEquals(1.25f, AppearanceStore.snapUiFontScale(1.2535f), 1e-4f)
    }

    @Test
    fun 吸附区外按百分之一取整() {
        // 与最近的 1.20 相差 0.0166 > 吸附半径 0.006
        assertEquals(1.18f, AppearanceStore.snapUiFontScale(1.1834f), 1e-4f)
        assertEquals(1.02f, AppearanceStore.snapUiFontScale(1.0210f), 1e-4f)
        // 与最近的 1.10 相差 0.0099 > 0.006，按 1% 取整仍是 1.11
        assertEquals(1.11f, AppearanceStore.snapUiFontScale(1.1099f), 1e-4f)
    }

    @Test
    fun 越界先钳制再吸附() {
        assertEquals(AppearanceStore.MIN_SCALE, AppearanceStore.snapUiFontScale(0.50f), 1e-4f)
        assertEquals(AppearanceStore.MAX_SCALE, AppearanceStore.snapUiFontScale(2.00f), 1e-4f)
        assertEquals(AppearanceStore.MIN_SCALE, AppearanceStore.snapUiFontScale(-1f), 1e-4f)
    }

    @Test
    fun 刻度判定用于吸附反馈() {
        assertTrue(AppearanceStore.isFontScaleStop(0.85f))
        assertTrue(AppearanceStore.isFontScaleStop(1.20f))
        assertFalse(AppearanceStore.isFontScaleStop(1.18f))
        assertFalse(AppearanceStore.isFontScaleStop(1.02f))
    }
    @Test
    fun 拖动值连续且只在刻度附近吸附() {
        // 不在吸附半径内：原样保留连续值，不能按 1% 取整（否则滑杆一跳十几像素）
        assertEquals(1.1732f, AppearanceStore.dragUiFontScale(1.1732f), 1e-4f)
        assertEquals(1.07f, AppearanceStore.dragUiFontScale(1.07f), 1e-4f)
        // 刻度附近仍然吸附
        assertEquals(1.15f, AppearanceStore.dragUiFontScale(1.1534f), 1e-4f)
        // 越界钳制
        assertEquals(AppearanceStore.MIN_SCALE, AppearanceStore.dragUiFontScale(0.5f), 1e-4f)
        assertEquals(AppearanceStore.MAX_SCALE, AppearanceStore.dragUiFontScale(2f), 1e-4f)
    }
}
