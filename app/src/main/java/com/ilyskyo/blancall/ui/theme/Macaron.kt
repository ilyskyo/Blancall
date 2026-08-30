// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 清新马卡龙淡色板（Macaron palette）。
 *
 * 设计要点：
 * - 浅色模式 fill 取「近白淡彩」（极低饱和、极高明度），经 GlassCard 的 stain 层
 *   （alpha≈0.8）后呈现为极淡的清新水洗色，而非原 error/primary 那种饱和色块。
 * - 深色模式 fill 取「同色相降明度」的雾感色，保证浅色文字可读，同时保留马卡龙色相。
 * - accent 为对应色相的柔和点缀色，用于圆点 / 标签，与 fill 同族形成清新协调感。
 *
 * 用法：在 Composable 中调用 [Macaron.review]() 等获取当前主题下的 [MacaronHue]，
 * 取 .fill 作为 GlassCard 的 containerColor、.accent 作为圆点 / 标签色。
 */
data class MacaronHue(val fill: Color, val accent: Color)

object Macaron {
    // ── 浅色：近白淡彩（高明度、低饱和）──
    private val light = object {
        val review = MacaronHue(Color(0xFFFBEFF3), Color(0xFFEE9DB4))    // 樱花粉
        val continueP = MacaronHue(Color(0xFFEAF5EF), Color(0xFF7FC6A3)) // 薄荷绿
        val info = MacaronHue(Color(0xFFEAF1FB), Color(0xFF8FB2E6))      // 天空蓝
        val warn = MacaronHue(Color(0xFFFDF2E0), Color(0xFFEFB86E))      // 蜜桃
        val lavender = MacaronHue(Color(0xFFF1ECFB), Color(0xFFB79BE0))  // 薰衣草
        val neutral = MacaronHue(Color(0xFFF4F0EB), Color(0xFFB6AFA4))   // 暖白（替代 surfaceVariant 灰）
    }

    // ── 深色：同色相雾感（降低明度，保证浅色文字可读）──
    private val dark = object {
        val review = MacaronHue(Color(0xFF3A2A30), Color(0xFFE9A9B8))
        val continueP = MacaronHue(Color(0xFF233029), Color(0xFFA9DCC0))
        val info = MacaronHue(Color(0xFF252C38), Color(0xFFA9C4EC))
        val warn = MacaronHue(Color(0xFF3A3228), Color(0xFFE8C892))
        val lavender = MacaronHue(Color(0xFF2E2738), Color(0xFFC9B0E8))
        val neutral = MacaronHue(Color(0xFF2A2A2C), Color(0xFF9A958C))
    }

    @Composable
    private fun pick(lightH: MacaronHue, darkH: MacaronHue): MacaronHue =
        if (isBlancallDark()) darkH else lightH

    @Composable fun review(): MacaronHue = pick(light.review, dark.review)
    @Composable fun continueP(): MacaronHue = pick(light.continueP, dark.continueP)
    @Composable fun info(): MacaronHue = pick(light.info, dark.info)
    @Composable fun warn(): MacaronHue = pick(light.warn, dark.warn)
    @Composable fun lavender(): MacaronHue = pick(light.lavender, dark.lavender)
    @Composable fun neutral(): MacaronHue = pick(light.neutral, dark.neutral)
}
