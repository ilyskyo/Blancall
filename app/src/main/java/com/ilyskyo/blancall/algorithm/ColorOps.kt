// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 标签颜色纯数学工具（无 Compose / Android 依赖 → JVM 单测可直接覆盖）。
 *
 * 颜色一律使用 **0xRRGGBB**（24 位、无 alpha），与 tags.json 的 "#RRGGBB" 互转。
 * 提供服务：
 * - HEX ⇄ RGB、RGB ⇄ HSV（色环取色用）；
 * - WCAG 2.1 相对亮度 / 对比度（可读性校验规则 ≥ [MIN_CHIP_CONTRAST]）；
 * - chip 前景/背景配色（深浅模式自动取深/取浅 + 对比度兜底）；
 * - 新建标签默认色建议盘 + 4 套预设色系套装。
 */
object ColorOps {

    /** chip 前景/背景对比度下限（WCAG AA 正文级） */
    const val MIN_CHIP_CONTRAST = 4.5

    // ── 基础通道操作 ──

    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF
    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF
    fun blue(rgb: Int): Int = rgb and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** "#RRGGBB" / "RRGGBB" → 0xRRGGBB；非法（长度不对 / 非十六进制）返回 null */
    fun parseHex(hex: String): Int? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6) return null
        return s.toIntOrNull(16)?.and(0xFFFFFF)
    }

    /** 0xRRGGBB → "#RRGGBB"（大写 6 位） */
    fun toHex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    /** 线性混色：a·(1−t) + b·t（t 收敛 0..1，逐通道四舍五入） */
    fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return rgb(
            lerp(red(a), red(b), f),
            lerp(green(a), green(b), f),
            lerp(blue(a), blue(b), f),
        )
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).roundToInt()

    // ── HSV（色环取色）──

    /** RGB → [H(0..360), S(0..1), V(0..1)] */
    fun rgbToHsv(rgb: Int): FloatArray {
        val r = red(rgb) / 255f
        val g = green(rgb) / 255f
        val b = blue(rgb) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val d = maxC - minC
        val h = when {
            d == 0f -> 0f
            maxC == r -> (60f * (((g - b) / d) % 6f) + 360f) % 360f
            maxC == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        val s = if (maxC == 0f) 0f else d / maxC
        return floatArrayOf(h, s, maxC)
    }

    /** HSV → 0xRRGGBB（h 自动归一化到 [0,360)，s/v 收敛 0..1） */
    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = vv - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(
            ((r1 + m) * 255f).roundToInt(),
            ((g1 + m) * 255f).roundToInt(),
            ((b1 + m) * 255f).roundToInt(),
        )
    }

    // ── 亮度 / 对比度（WCAG 2.1）──

    private fun channelLuminance(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** 相对亮度（0..1） */
    fun relativeLuminance(rgb: Int): Double =
        0.2126 * channelLuminance(red(rgb)) +
            0.7152 * channelLuminance(green(rgb)) +
            0.0722 * channelLuminance(blue(rgb))

    /** 对比度 (max(L1,L2)+0.05) / (min+0.05)，范围 1..21 */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    // ── chip 配色 ──

    /** chip 最终配色（均已是不透明 0xRRGGBB，直接可用） */
    data class ChipColors(val bg: Int, val fg: Int)

    /**
     * 计算标签 chip 的前景/背景色：
     * - 浅色模式：背景 = surface 上叠 14% 标签色（近白淡彩，与状态 chip 同语言），
     *   文字从标签色向黑迭代压暗直到对比度 ≥ [MIN_CHIP_CONTRAST]；
     * - 深色模式：背景 = surface 上叠 30% 标签色，文字向白迭代提亮；
     * - 迭代始终不达标 → 取黑/白中对比更高者（兜底，保证可读性）。
     */
    fun chipColors(tagRgb: Int, isDark: Boolean, surfaceRgb: Int): ChipColors {
        val bg = if (isDark) blend(surfaceRgb, tagRgb, 0.30f) else blend(surfaceRgb, tagRgb, 0.14f)
        val target = if (isDark) 0xFFFFFF else 0x000000
        return ChipColors(bg = bg, fg = ensureContrast(bg, tagRgb, target))
    }

    /** 从 [base] 向 [target] 以 0.02 步长混合，取首个对 [bg] 对比度 ≥ 下限的颜色；全不达标取黑白优选 */
    fun ensureContrast(bg: Int, base: Int, target: Int): Int {
        var t = 0f
        while (t <= 1.0001f) {
            val candidate = blend(base, target, t)
            if (contrastRatio(candidate, bg) >= MIN_CHIP_CONTRAST) return candidate
            t += 0.02f
        }
        return if (contrastRatio(0x000000, bg) >= contrastRatio(0xFFFFFF, bg)) 0x000000 else 0xFFFFFF
    }

    // ── 默认取色盘与预设套装 ──

    /**
     * 新建标签默认色建议盘（12 色）：马卡龙 8 色 + 扩展 4 色。
     * 刻意不含 App 主题主色（靛蓝系），避免新标签默认色与主题强调色混淆。
     */
    val DEFAULT_PALETTE: List<Int> = listOf(
        0xEE9DB4, 0xEFB86E, 0x9DC97F, 0x7FC6A3, 0x7FC6CF, 0x8FB2E6, 0xB79BE0, 0xD48FC6,
        0xE89B8F, 0x9C8FE0, 0xC7B45C, 0x6FA8C9,
    )

    /**
     * 新建标签默认色：取默认盘中第一个「现有标签均未使用」的颜色；
     * 全部用尽时取使用次数最少者（按盘顺序稳定，结果确定可测）。
     */
    fun suggestTagColor(existing: List<Int>): Int {
        val used = existing.toSet()
        DEFAULT_PALETTE.firstOrNull { it !in used }?.let { return it }
        val counts = DEFAULT_PALETTE.associateWith { c -> existing.count { it == c } }
        val minCount = counts.values.min()
        return DEFAULT_PALETTE.first { counts[it] == minCount }
    }

    /** 预设色系套装 */
    data class PresetPalette(val name: String, val colors: List<Int>)

    /** 4 套 × 8 色成品配色，直接点选赋色 */
    val PRESET_PALETTES: List<PresetPalette> = listOf(
        PresetPalette(
            "马卡龙",
            listOf(0xEE9DB4, 0xEFB86E, 0x9DC97F, 0x7FC6A3, 0x7FC6CF, 0x8FB2E6, 0xB79BE0, 0xD48FC6),
        ),
        PresetPalette(
            "莫兰迪",
            listOf(0xC08C8C, 0xC7A98B, 0xC4BE93, 0xA3B39A, 0x93A8B0, 0x9C94B8, 0xB593A8, 0xA8A8A8),
        ),
        PresetPalette(
            "国风",
            listOf(0xA8463C, 0xC77A4B, 0xB99A4A, 0x6F9271, 0x4A7C88, 0x5B6BA8, 0x8A6B9E, 0x7A6652),
        ),
        PresetPalette(
            "霓虹糖",
            listOf(0xFF6B9D, 0xFF8A5C, 0xFFC94A, 0x7BE495, 0x4FD1C5, 0x5AA9FF, 0x9B7BFF, 0xE36BFF),
        ),
    )
}
