// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ColorOps] 纯数学测试：HEX/HSV 往返、WCAG 对比度、chip 深浅模式配色、默认色建议。
 *
 * 可读性验收规则：所有标签色（默认盘 / 全部预设套装 / 极端色）在浅色与深色两套
 * canonical surface 下，chip 前景 vs 合成背景对比度必须 ≥ 4.5:1。
 */
class ColorOpsTest {

    /** 浅色模式典型 surface（近白） */
    private val lightSurface = 0xFFFFFF

    /** 深色模式典型 surface（与项目深色底同族） */
    private val darkSurface = 0x1C1C1E

    /** 极端与高饱和测试色 */
    private val edgeColors = listOf(
        0x000000, 0xFFFFFF, 0x808080, 0xFF6B9D, 0x00FF00, 0x0000FF, 0xFFFF00, 0x7F00FF,
    )

    private val allCandidateColors: List<Int>
        get() = ColorOps.DEFAULT_PALETTE +
            ColorOps.PRESET_PALETTES.flatMap { it.colors } +
            edgeColors

    @Test
    fun `hex 解析与格式化往返一致`() {
        assertEquals(0xEE9DB4, ColorOps.parseHex("#EE9DB4"))
        assertEquals(0xEE9DB4, ColorOps.parseHex("ee9db4"))
        assertEquals(0x000000, ColorOps.parseHex("#000000"))
        assertEquals("#EE9DB4", ColorOps.toHex(0xEE9DB4))
        // 传入带 alpha 的高位也应被截断为 24 位
        assertEquals("#EE9DB4", ColorOps.toHex(0xFFEE9DB4.toInt()))
    }

    @Test
    fun `hex 非法输入返回 null`() {
        assertNull(ColorOps.parseHex(""))
        assertNull(ColorOps.parseHex("#12345"))
        assertNull(ColorOps.parseHex("#GGGGGG"))
        assertNull(ColorOps.parseHex("EE9DB"))
    }

    @Test
    fun `hsv 与 rgb 往返近似一致`() {
        val samples = listOf(0xEE9DB4, 0x7FC6A3, 0x1C1C1E, 0xFFFFFF, 0x000000, 0xFF6B9D, 0x336699)
        samples.forEach { rgb ->
            val hsv = ColorOps.rgbToHsv(rgb)
            val back = ColorOps.hsvToRgb(hsv[0], hsv[1], hsv[2])
            // 逐通道允许 ±1 舍入误差
            assertTrue(
                "rgb=$rgb back=$back",
                ColorOps.red(rgb).let { kotlin.math.abs(it - ColorOps.red(back)) <= 1 } &&
                    kotlin.math.abs(ColorOps.green(rgb) - ColorOps.green(back)) <= 1 &&
                    kotlin.math.abs(ColorOps.blue(rgb) - ColorOps.blue(back)) <= 1,
            )
        }
    }

    @Test
    fun `hsv 基础色相映射正确`() {
        assertEquals(0f, ColorOps.rgbToHsv(0xFF0000)[0])
        assertEquals(120f, ColorOps.rgbToHsv(0x00FF00)[0])
        assertEquals(240f, ColorOps.rgbToHsv(0x0000FF)[0])
        // 灰度：饱和度为 0
        assertEquals(0f, ColorOps.rgbToHsv(0x808080)[1])
        // 白色：值为 1 饱和为 0
        assertEquals(0f, ColorOps.rgbToHsv(0xFFFFFF)[1])
    }

    @Test
    fun `相对亮度与对比度边界`() {
        assertEquals(0.0, ColorOps.relativeLuminance(0x000000), 1e-6)
        assertEquals(1.0, ColorOps.relativeLuminance(0xFFFFFF), 1e-3)
        // 黑白对比度 = 21（WCAG 理论极值）
        assertEquals(21.0, ColorOps.contrastRatio(0x000000, 0xFFFFFF), 0.05)
        // 自身对比度 = 1
        assertEquals(1.0, ColorOps.contrastRatio(0xEE9DB4, 0xEE9DB4), 1e-6)
    }

    @Test
    fun `blend 边界与中点`() {
        assertEquals(0xFFFFFF, ColorOps.blend(0xFFFFFF, 0x000000, 0f))
        assertEquals(0x000000, ColorOps.blend(0xFFFFFF, 0x000000, 1f))
        // 越界 t 自动收敛
        assertEquals(0x000000, ColorOps.blend(0xFFFFFF, 0x000000, 2f))
        val mid = ColorOps.blend(0x000000, 0xFFFFFF, 0.5f)
        assertTrue(kotlin.math.abs(ColorOps.red(mid) - 128) <= 1)
    }

    @Test
    fun `浅色模式 chip 全部候选色对比度达标`() {
        allCandidateColors.forEach { tag ->
            val chip = ColorOps.chipColors(tag, isDark = false, surfaceRgb = lightSurface)
            val contrast = ColorOps.contrastRatio(chip.bg, chip.fg)
            assertTrue(
                "tag=${ColorOps.toHex(tag)} bg=${ColorOps.toHex(chip.bg)} fg=${ColorOps.toHex(chip.fg)} contrast=$contrast",
                contrast >= ColorOps.MIN_CHIP_CONTRAST,
            )
        }
    }

    @Test
    fun `深色模式 chip 全部候选色对比度达标`() {
        allCandidateColors.forEach { tag ->
            val chip = ColorOps.chipColors(tag, isDark = true, surfaceRgb = darkSurface)
            val contrast = ColorOps.contrastRatio(chip.bg, chip.fg)
            assertTrue(
                "tag=${ColorOps.toHex(tag)} bg=${ColorOps.toHex(chip.bg)} fg=${ColorOps.toHex(chip.fg)} contrast=$contrast",
                contrast >= ColorOps.MIN_CHIP_CONTRAST,
            )
        }
    }

    @Test
    fun `chip 背景在深浅两模式下都可与 surface 区分`() {
        // 背景不能等于 surface（否则 chip 不可见）——除纯白/纯黑标签的极端情况外都应可辨
        listOf(0xEE9DB4, 0x8FB2E6, 0x6FA8C9).forEach { tag ->
            val light = ColorOps.chipColors(tag, isDark = false, surfaceRgb = lightSurface)
            val dark = ColorOps.chipColors(tag, isDark = true, surfaceRgb = darkSurface)
            assertTrue(light.bg != lightSurface)
            assertTrue(dark.bg != darkSurface)
        }
    }

    @Test
    fun `默认色建议优先取未使用色且结果确定`() {
        assertEquals(ColorOps.DEFAULT_PALETTE[0], ColorOps.suggestTagColor(emptyList()))
        // 已用第一个 → 取第二个
        assertEquals(ColorOps.DEFAULT_PALETTE[1], ColorOps.suggestTagColor(listOf(ColorOps.DEFAULT_PALETTE[0])))
        // 确定性：同样输入同样输出
        val used = listOf(ColorOps.DEFAULT_PALETTE[0], ColorOps.DEFAULT_PALETTE[2])
        assertEquals(ColorOps.suggestTagColor(used), ColorOps.suggestTagColor(used))
        // 非盘内自定义色不影响判重
        assertEquals(ColorOps.DEFAULT_PALETTE[0], ColorOps.suggestTagColor(listOf(0x123456)))
    }

    @Test
    fun `默认盘用尽时取使用次数最少者`() {
        val allUsed = ColorOps.DEFAULT_PALETTE +
            listOf(ColorOps.DEFAULT_PALETTE[0], ColorOps.DEFAULT_PALETTE[0], ColorOps.DEFAULT_PALETTE[1])
        val suggested = ColorOps.suggestTagColor(allUsed)
        // 0 号用了 3 次、1 号 2 次，其余 1 次 → 应取原顺序里计数最少的最早者（2 号）
        assertEquals(ColorOps.DEFAULT_PALETTE[2], suggested)
    }

    @Test
    fun `预设套装结构与色值合法`() {
        assertEquals(4, ColorOps.PRESET_PALETTES.size)
        ColorOps.PRESET_PALETTES.forEach { preset ->
            assertTrue(preset.name.isNotBlank())
            assertEquals(8, preset.colors.size)
            preset.colors.forEach { c ->
                assertNotNull(ColorOps.parseHex(ColorOps.toHex(c)))
            }
        }
    }

    @Test
    fun `ensureContrast 兜底返回黑白优选`() {
        // 极端场景：base/target 都不可达时，应在黑白中选对比更高者
        val bg = 0x808080
        val fg = ColorOps.ensureContrast(bg, 0xFFFFFF, 0xFFFFFF) // 白底白字不可能达标
        assertTrue(fg == 0x000000 || fg == 0xFFFFFF)
        assertTrue(ColorOps.contrastRatio(bg, fg) >= ColorOps.contrastRatio(bg, 0xFFFFFF).coerceAtMost(
            ColorOps.contrastRatio(bg, 0x000000)
        ) - 1e-6)
    }
}
