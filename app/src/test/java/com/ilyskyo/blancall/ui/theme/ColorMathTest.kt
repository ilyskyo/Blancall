// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 主题色数学守卫（JVM，不碰 Android）。
 *
 * 这一层是「换主题色」能不能落地的地方：ColorScheme、马卡龙卡片、玻璃染色、
 * 小组件、启动屏全都从这几个函数取色。任何一处算错，表现都不是崩溃，
 * 而是「某个界面颜色不对」—— 只有眼睛能发现，所以必须钉在这里。
 */
class ColorMathTest {

    @Test
    fun 色相旋转零度必须原样返回() {
        val samples = listOf(0xFF3B5DE7, 0xFF006C4C, 0xFFB31654, 0xFFFBEFF3, 0xFF595959)
        for (s in samples) {
            val argb = s.toInt()
            assertEquals(
                "delta=0 时必须短路返回原值（否则每次重组都在做 HSL 往返，还会漂 ±1）",
                argb, ColorMath.rotateHue(argb, 0f)
            )
        }
    }

    @Test
    fun HSL往返误差不超过一档() {
        val samples = listOf(0xFF3B5DE7, 0xFF006C4C, 0xFFB31654, 0xFFEFB86E, 0xFF2A2A2C)
        for (s in samples) {
            val argb = s.toInt()
            val hsl = ColorMath.rgbToHsl(argb)
            val back = ColorMath.hslToArgb(hsl[0], hsl[1], hsl[2])
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((back shr shift) and 0xFF) - ((argb shr shift) and 0xFF))
                assertTrue("HSL 往返偏移过大：${Integer.toHexString(argb)} → ${Integer.toHexString(back)}", d <= 1)
            }
        }
    }

    @Test
    fun 转一圈色相回到原色() {
        val argb = 0xFF3B5DE7.toInt()
        val round = ColorMath.rotateHue(argb, 360f)
        for (shift in intArrayOf(16, 8, 0)) {
            val d = abs(((round shr shift) and 0xFF) - ((argb shr shift) and 0xFF))
            assertTrue("转 360° 必须回到原色，实际偏了 $d", d <= 2)
        }
    }

    @Test
    fun 黑白对比度是满值21() {
        val r = ColorMath.contrast(ColorMath.WHITE, ColorMath.BLACK)
        assertEquals(21.0, r, 0.05)
    }

    @Test
    fun 自定义主题色的按钮文字必须可读() {
        // 覆盖 24 个色相 × 3 档明度：任何一格上 onPrimary 都得够 4.5:1，
        // 否则「选了个浅黄主题」就会出现白字压白底。
        for (h in 0 until 360 step 15) {
            for (l in listOf(0.25f, 0.5f, 0.72f)) {
                val argb = ColorMath.hslToArgb(h.toFloat(), 0.8f, l)
                val preset = accentPresetFromArgb(argb)
                val ratio = ColorMath.contrast(
                    preset.primaryLight.toArgb(),
                    preset.onPrimaryLight.toArgb(),
                )
                assertTrue(
                    "h=$h l=$l 上按钮文字对比度只有 ${"%.2f".format(ratio)}（要求 4.5）",
                    ratio >= 4.5
                )
            }
        }
    }

    @Test
    fun 自定义主题色在深色模式下要够亮() {
        for (h in 0 until 360 step 15) {
            val argb = ColorMath.hslToArgb(h.toFloat(), 0.85f, 0.22f) // 很深的品牌色
            val preset = accentPresetFromArgb(argb)
            val ratio = ColorMath.contrast(preset.primaryDark.toArgb(), ColorMath.BLACK)
            assertTrue(
                "h=$h 的深色 primary 在黑底上只有 ${"%.2f".format(ratio)}（要求 4.5），文字会糊",
                ratio >= 4.5
            )
        }
    }

    @Test
    fun 灰色主题不许带动马卡龙色板() {
        // 石墨预设与用户自定义的灰：色相无意义，偏移必须是 0，否则粉色卡片会变随机彩虹。
        assertEquals(0f, hueShiftFor(0xFF595959.toInt()), 0.001f)
        assertEquals(0f, hueShiftFor(0xFF8E8E93.toInt()), 0.001f)
    }

    @Test
    fun 彩色主题必须带动马卡龙色板转同样角度() {
        val green = 0xFF006C4C.toInt()
        val shift = hueShiftFor(green)
        assertTrue("翠绿的偏移应该很明显，实际 $shift", abs(shift) > 30f)

        val baseHue = ColorMath.rgbToHsl(0xFF8FB2E6.toInt())[0] // 天空蓝 accent
        val movedHue = ColorMath.rgbToHsl(Macaron.hueAt(2, shift, false).accent.toArgb())[0]
        val expected = (baseHue + shift + 360f) % 360f
        var diff = abs(movedHue - expected)
        if (diff > 180f) diff = 360f - diff
        assertTrue("马卡龙应整体旋转 $shift°，实际色相 $movedHue，期望 $expected", diff < 6f)
    }

    @Test
    fun 零偏移时马卡龙与旧版逐位一致() {
        // 靛蓝主题下（偏移≈0）必须和改造前完全一样 —— 老用户不该因为这次重构看到配色变化。
        val expectedLightFill = listOf(
            0xFFFBEFF3, 0xFFEAF5EF, 0xFFEAF1FB, 0xFFFDF2E0, 0xFFF1ECFB, 0xFFF4F0EB,
        )
        val expectedDarkAccent = listOf(
            0xFFE9A9B8, 0xFFA9DCC0, 0xFFA9C4EC, 0xFFE8C892, 0xFFC9B0E8, 0xFF9A958C,
        )
        for (i in 0..5) {
            assertEquals(
                "第 $i 组浅色 fill 变了",
                expectedLightFill[i].toInt(), Macaron.hueAt(i, 0f, false).fill.toArgb()
            )
            assertEquals(
                "第 $i 组深色 accent 变了",
                expectedDarkAccent[i].toInt(), Macaron.hueAt(i, 0f, true).accent.toArgb()
            )
        }
    }

    @Test
    fun 五组彩色马卡龙色相互不相同() {
        // 第 6 组 neutral（暖白）按设计就是「近灰的暖色」，色相天然贴着 warn（蜜桃）——
        // 它不该参与色相间隔判定，否则守着守着会把有意的设计判成 bug。
        val hues = (0..4).map { ColorMath.rgbToHsl(Macaron.hueAt(it, 0f, false).accent.toArgb())[0] }
        for (i in hues.indices) {
            for (j in i + 1 until hues.size) {
                var d = abs(hues[i] - hues[j])
                if (d > 180f) d = 360f - d
                assertTrue("第 $i 与第 $j 组色相太近（$d°），卡片会分不出类别", d >= 20f)
            }
        }
    }

    @Test
    fun 第六组是低饱和暖白而不是第六个彩() {
        val neutral = Macaron.hueAt(5, 0f, false).accent
        val hsl = ColorMath.rgbToHsl(neutral.toArgb())
        assertTrue("neutral 的饱和度 ${"%.2f".format(hsl[1])} 太高，会跟彩色组抢眼", hsl[1] < 0.20f)
    }

    @Test
    fun 三级色必须跟着主题色走() {
        val (light, dark) = tertiaryFromAccent(0xFF3B5DE7.toInt(), 0xFF8DA9FF.toInt())
        // 旧版 tertiary 写死棕色 #8B4A2E；现在必须是从 accent 转出来的色，
        // 至少不能再等于那个棕色（否则「翠绿主题 + 棕色重点」会同时出现在屏幕上）。
        assertTrue("三级色仍然是旧的棕色，等于没改", light.toArgb() != 0xFF8B4A2E.toInt())
        assertTrue("三级色与主色太接近，层级读不出来", ColorMath.contrast(light.toArgb(), 0xFF3B5DE7.toInt()) > 1.2)
        assertTrue("深色三级色不该是纯黑或纯白", dark != Color.Black && dark != Color.White)
    }
}
