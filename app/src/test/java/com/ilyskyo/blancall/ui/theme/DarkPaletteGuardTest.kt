// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 深色模式「少发光」守卫（JVM）。
 *
 * 判据来自用户 2026-10-07 的设计要求：**深色模式下不许有不透明的浅色控件底** ——
 * 人开深色是为了在保住内容可读的前提下尽量少发光，所以要看的是**发光面积**，
 * 不是对比度。一块白底在深色界面对比度很好，但它就是屏幕上最亮的那块，
 * 恰恰违背开深色的目的（返回键那次就是这么改的：白盘 → 半透明中性底 + 白箭头）。
 *
 * 这条守卫拦的是「以后有人给深色调色板塞进一个亮容器色」——那种改动编译照过、
 * 单测照绿，只有眼睛看得出来，而它每次都会悄悄扩大发光面积。
 *
 * 注意 `InverseSurface*` 不在检查之列：M3 里 inverse 就是「深色主题下的浅色面」
 * （snackbar 那种反色浮层），把它当违规会天天误报。
 */
class DarkPaletteGuardTest {

    @Test
    fun 深色容器必须是暗的且前景必须是亮的() {
        val darkContainers = listOf(
            "BackgroundDark" to BackgroundDark,
            "SurfaceDark" to SurfaceDark,
            "SurfaceVariantDark" to SurfaceVariantDark,
            "SurfaceContainerHighDark" to SurfaceContainerHighDark,
            "OutlineVariantDark" to OutlineVariantDark,
        )
        val darkForegrounds = listOf(
            "OnBackgroundDark" to OnBackgroundDark,
            "OnSurfaceDark" to OnSurfaceDark,
            "OnSurfaceVariantDark" to OnSurfaceVariantDark,
        )
        // 断条数而不是「非空」：有人把常量从名单里摘掉，守卫就会静默失去覆盖面
        assertTrue("深色容器名单应 ≥5 条，实为 ${darkContainers.size}（有人把常量摘出名单了？）",
            darkContainers.size >= 5)
        assertTrue("深色前景名单应 ≥3 条，实为 ${darkForegrounds.size}",
            darkForegrounds.size >= 3)

        val tooBright = darkContainers.filter { (_, c) -> luminance(c) > DARK_MAX }
            .map { (n, c) -> "$n=${c.toHex()} 亮度=${"%.3f".format(luminance(c))}" }
        assertTrue(
            "深色容器太亮，会扩大发光面积（上限 $DARK_MAX）：\n" + tooBright.joinToString("\n"),
            tooBright.isEmpty()
        )

        val tooDim = darkForegrounds.filter { (_, c) -> luminance(c) < LIGHT_MIN }
            .map { (n, c) -> "$n=${c.toHex()} 亮度=${"%.3f".format(luminance(c))}" }
        assertTrue(
            "深色模式前景过暗会丢内容可读性（下限 $LIGHT_MIN）：\n" + tooDim.joinToString("\n"),
            tooDim.isEmpty()
        )
    }

    @Test
    fun 深色启动屏底色必须是暗的() {
        val night = File("src/main/res/values-night/colors.xml")
        assertTrue("找不到 ${night.path} —— 测试工作目录变了，这条守卫已失效", night.isFile)
        val day = File("src/main/res/values/colors.xml").readText()
        val nightText = night.readText()

        val entries = Regex("""<color name="([^"]+)">([^<]+)""")
            .findAll(nightText).associate { it.groupValues[1] to it.groupValues[2].trim() }
        assertTrue("night 资源里一条颜色都没有，解析没生效", entries.isNotEmpty())

        val offenders = entries.filter { (name, ref) ->
            val argb = resolveArgb(ref, day) ?: return@filter false   // 引用型且解不出：另说
            luminance(Color(argb)) > DARK_MAX
        }.keys
        assertTrue(
            "values-night 里这些颜色是亮色，深色启动/界面会闪白：$offenders",
            offenders.isEmpty()
        )
    }

    /** `@color/x` 追一层引用；十六进制直接解。解不出返回 null（不猜）。 */
    private fun resolveArgb(ref: String, dayText: String): Long? {
        if (!ref.startsWith("@color/")) {
            val hex = ref.removePrefix("#")
            val full = if (hex.length == 6) "FF$hex" else hex
            return full.toLongOrNull(16)
        }
        val target = ref.removePrefix("@color/")
        val m = Regex("""<color name="$target">#?([0-9a-fA-F]{6,8})""").find(dayText)
            ?: return null
        val hex = m.groupValues[1]
        return (if (hex.length == 6) "FF$hex" else hex).toLongOrNull(16)
    }

    /** WCAG 相对亮度（sRGB 线性化后 Rec.709 加权），0=全黑 1=全白。 */
    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    // 不用 Color.argb：1.10 上它是 ULong，喂给 %X 会抛 IllegalFormatConversionException
    private fun Color.toHex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255 + 0.5f).toInt(), (green * 255 + 0.5f).toInt(), (blue * 255 + 0.5f).toInt())

    private companion object {
        /** 深色容器的亮度上限：0.25 已相当宽松（现值最高 SurfaceContainerHighDark≈0.05）。 */
        const val DARK_MAX = 0.25
        const val LIGHT_MIN = 0.60
    }
}
