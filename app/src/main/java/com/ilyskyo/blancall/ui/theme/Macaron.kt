// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * ## 为什么要跟着主题色转
 * 这套色板最初是照「靛蓝」调的：6 组色相写死在代码里，与用户选的强调色完全无关 ——
 * 首页、统计页、阅读页近 60 处卡片底色因此永远是粉/绿/蓝/蜜桃/紫，
 * 换主题色只换了按钮和标题小字，用户读作「换了但没完全换」。
 * 现在只做一件事：**整族按 [LocalAccentHueShift] 绕色相旋转**，
 * 明度/饱和度保持原设计值 —— 靛蓝（偏移≈0）时与旧版逐位相同，
 * 换绿/橙/玫红时整族一起偏过去，卡片才真的属于当前主题。
 *
 * 灰色主题（石墨、或用户自定义的灰）色相无意义，[hueShiftFor] 直接给 0，
 * 于是退回原本的暖色族，不会被甩成一套随机彩。
 */
data class MacaronHue(val fill: Color, val accent: Color)

object Macaron {

    // ── 浅色：近白淡彩（高明度、低饱和）──
    private val lightFill = intArrayOf(
        0xFFFBEFF3.toInt(), // review 樱花粉
        0xFFEAF5EF.toInt(), // continue 薄荷绿
        0xFFEAF1FB.toInt(), // info 天空蓝
        0xFFFDF2E0.toInt(), // warn 蜜桃
        0xFFF1ECFB.toInt(), // lavender 薰衣草
        0xFFF4F0EB.toInt(), // neutral 暖白
    )
    private val lightAccent = intArrayOf(
        0xFFEE9DB4.toInt(),
        0xFF7FC6A3.toInt(),
        0xFF8FB2E6.toInt(),
        0xFFEFB86E.toInt(),
        0xFFB79BE0.toInt(),
        0xFFB6AFA4.toInt(),
    )

    // ── 深色：同色相雾感（降低明度，保证浅色文字可读）──
    private val darkFill = intArrayOf(
        0xFF3A2A30.toInt(),
        0xFF233029.toInt(),
        0xFF252C38.toInt(),
        0xFF3A3228.toInt(),
        0xFF2E2738.toInt(),
        0xFF2A2A2C.toInt(),
    )
    private val darkAccent = intArrayOf(
        0xFFE9A9B8.toInt(),
        0xFFA9DCC0.toInt(),
        0xFFA9C4EC.toInt(),
        0xFFE8C892.toInt(),
        0xFFC9B0E8.toInt(),
        0xFF9A958C.toInt(),
    )

    /**
     * 取第 [index] 组色（纯函数，单测直接跑）。
     * [shift] = 0 时与旧版硬编码值完全一致。
     */
    internal fun hueAt(index: Int, shift: Float, dark: Boolean): MacaronHue {
        val i = index.coerceIn(0, lightFill.lastIndex)
        val fill = if (dark) darkFill[i] else lightFill[i]
        val accent = if (dark) darkAccent[i] else lightAccent[i]
        return MacaronHue(
            fill = Color(ColorMath.rotateHue(fill, shift)),
            accent = Color(ColorMath.rotateHue(accent, shift)),
        )
    }

    @Composable
    private fun pick(index: Int): MacaronHue {
        val shift = LocalAccentHueShift.current
        val dark = isBlancallDark()
        // remember 已经按 (index, shift, dark) 记忆住结果，不再自建全局缓存
        // （全局 HashMap 会变成跨组合的共享可变状态，且随自定义色无限增长）
        return remember(index, shift, dark) { hueAt(index, shift, dark) }
    }

    @Composable fun review(): MacaronHue = pick(0)
    @Composable fun continueP(): MacaronHue = pick(1)
    @Composable fun info(): MacaronHue = pick(2)
    @Composable fun warn(): MacaronHue = pick(3)
    @Composable fun lavender(): MacaronHue = pick(4)
    @Composable fun neutral(): MacaronHue = pick(5)
}
