// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 颜色数学（**不依赖 Android / Compose**，可在 JVM 单测里直接跑）。
 *
 * 为什么单独一层：主题色要同时驱动 ColorScheme、马卡龙卡片、玻璃染色、
 * 小部件与启动屏，这些地方各自只会拿到一个 ARGB。所有推导（色相旋转、
 * 容器色混合、可读前景色、对比度）必须只有一份实现，否则同一套主题会在
 * 「按钮」和「卡片」上算出两个色。
 */
internal object ColorMath {

    /** 拆成 0..255 三通道。 */
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun compose(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        ((a and 0xFF) shl 24) or
            ((r.coerceIn(0, 255) and 0xFF) shl 16) or
            ((g.coerceIn(0, 255) and 0xFF) shl 8) or
            (b.coerceIn(0, 255) and 0xFF)

    /** RGB → HSL：[h] 0..360，[s] 0..1，[l] 0..1。 */
    fun rgbToHsl(argb: Int): FloatArray {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val d = maxC - minC
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h % 360f, s, l)
    }

    /** HSL → ARGB（[h] 任意角度，内部归一化）。 */
    fun hslToArgb(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val lig = l.coerceIn(0f, 1f)
        if (sat < 1e-6f) {
            val v = (lig * 255f).roundToInt()
            return compose(v, v, v)
        }
        val q = if (lig < 0.5f) lig * (1f + sat) else lig + sat - lig * sat
        val p = 2f * lig - q
        fun channel(t0: Float): Int {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            val v = when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
            return (v * 255f).roundToInt().coerceIn(0, 255)
        }
        return compose(
            channel(hh / 360f + 1f / 3f),
            channel(hh / 360f),
            channel(hh / 360f - 1f / 3f),
        )
    }

    /**
     * 只转色相、保留原明度与饱和度。
     * [deltaDegrees] 为 0 时**原样返回**（不做往返换算，避免 1/255 级漂移）。
     */
    fun rotateHue(argb: Int, deltaDegrees: Float): Int {
        if (abs(deltaDegrees) < 0.01f) return argb
        val hsl = rgbToHsl(argb)
        if (hsl[1] < 1e-6f) return argb
        return hslToArgb(hsl[0] + deltaDegrees, hsl[1], hsl[2]) or (argb and 0xFF000000.toInt())
    }

    /** 按 alpha 把 [fg] 叠到 [bg] 上（等价于 Compose 的 compositeOver）。 */
    fun blend(fg: Int, bg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        return compose(
            (red(fg) * a + red(bg) * (1f - a)).roundToInt(),
            (green(fg) * a + green(bg) * (1f - a)).roundToInt(),
            (blue(fg) * a + blue(bg) * (1f - a)).roundToInt(),
        )
    }

    /** WCAG 相对亮度。 */
    fun luminance(argb: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(red(argb)) + 0.7152 * lin(green(argb)) + 0.0722 * lin(blue(argb))
    }

    /** WCAG 对比度（1..21）。 */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 该底色上最可读的前景色（白 / 近黑二选一）。 */
    fun readableOn(argb: Int): Int =
        if (contrast(argb, WHITE) >= contrast(argb, INK)) WHITE else INK

    /**
     * 把颜色往白里提，直到与 [against] 的对比度达到 [minRatio]（最多提 [limit] 比例）。
     * 用于深色模式：品牌色常常太深，直接当文字色会糊在黑底上。
     */
    fun lightenUntilContrast(argb: Int, against: Int, minRatio: Double, limit: Float = 0.85f): Int {
        if (contrast(argb, against) >= minRatio) return argb
        var t = 0.1f
        while (t <= limit) {
            val candidate = blend(WHITE, argb, t)
            if (contrast(candidate, against) >= minRatio) return candidate
            t += 0.05f
        }
        return blend(WHITE, argb, limit)
    }

    /** 往黑里压，[t]=0 保留原色，=1 全黑。 */
    fun darken(argb: Int, t: Float): Int = blend(BLACK, argb, t.coerceIn(0f, 1f))

    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val BLACK: Int = 0xFF000000.toInt()

    /**
     * 「深色前景」候选。
     *
     * 必须是纯黑而不是 #1B1B1B：自定义主题色里有一整段中明度色（亮度 ≈0.2），
     * 白字只有 4.2:1、近黑字也只有 4.2:1 —— 两头都不达标。换成纯黑后
     * 最差的一格仍有 4.58:1，任何色相都保得住 4.5。
     */
    const val INK: Int = 0xFF000000.toInt()
}
