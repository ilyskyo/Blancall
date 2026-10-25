// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 玻璃 View 的明暗染色。
 *
 * [LiquidGlassView] 是 AndroidView，工厂只跑一次；明暗模式在页面存活时切换时，
 * 只有 `update` 会再跑。把染色收在这里，工厂与 update 用同一份参数，避免夜间模式
 * 切换后玻璃仍保留浅色底、在深色页上变成一块发光的白斑。
 *
 * @param darkTintIsBlack true 时深色用黑染（页面底玻璃）；false 时深色也用白染（滑块/胶囊）
 */
internal fun LiquidGlassView.applyGlassThemeTint(
    isDark: Boolean,
    darkTintIsBlack: Boolean,
    darkTintAlpha: Float,
    lightTintAlpha: Float,
) {
    if (isDark && darkTintIsBlack) {
        setTintColorRed(0f)
        setTintColorGreen(0f)
        setTintColorBlue(0f)
    } else {
        setTintColorRed(1f)
        setTintColorGreen(1f)
        setTintColorBlue(1f)
    }
    setTintAlpha(if (isDark) darkTintAlpha else lightTintAlpha)
}

/** 玻璃未绑定采样源前的兜底底色；与 [applyGlassThemeTint] 同时更新才能覆盖日夜切换。 */
internal fun LiquidGlassView.applyGlassThemeBackground(isDark: Boolean, alpha: Int) {
    setBackgroundColor(
        if (isDark) android.graphics.Color.argb(alpha, 26, 26, 26)
        else android.graphics.Color.argb(alpha, 255, 255, 255)
    )
}
