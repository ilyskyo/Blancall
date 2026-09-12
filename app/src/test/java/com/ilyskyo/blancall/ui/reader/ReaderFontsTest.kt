// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderFonts] 字重能力判定的回归测试（纯逻辑，JVM 可测）。
 *
 * 这些判定直接决定阅读设置里「字重」一栏显示哪些档位，写错会导致
 * 「切了字体却没有对应字重可选」或「选了却落不到真实字体文件」这类静默问题。
 */
class ReaderFontsTest {

    @Test
    fun `内置霞鹜文楷为多字重并提供三个真实档位`() {
        assertEquals(WeightMode.MULTI, ReaderFonts.weightMode(ReaderFonts.XWWK_ID))
        assertEquals(listOf(300, 400, 500), ReaderFonts.weightOptions(WeightMode.MULTI))
        // 三档必须与三份字体文件一一对应（Light / Regular / Medium）
        assertEquals(3, ReaderFonts.XWWK_WEIGHTS.size)
    }

    @Test
    fun `预设列表包含霞鹜文楷`() {
        val xwwk = ReaderFonts.presets.firstOrNull { it.id == ReaderFonts.XWWK_ID }
        assertTrue("预设里应含 id=4 的霞鹜文楷", xwwk != null)
        assertEquals("霞鹜文楷", xwwk!!.name)
        assertEquals(FontSource.PRESET, xwwk.source)
    }

    @Test
    fun `系统字体族预设与文件字体都是单字重两档`() {
        for (id in listOf("0", "1", "2", "3")) {
            assertEquals("预设 $id 应为单字重", WeightMode.SINGLE, ReaderFonts.weightMode(id))
        }
        assertEquals(WeightMode.SINGLE, ReaderFonts.weightMode("imp:abc.ttf"))
        assertEquals(WeightMode.SINGLE, ReaderFonts.weightMode("/system/fonts/NotoSansCJK.ttc"))
        assertEquals(listOf(400, 700), ReaderFonts.weightOptions(WeightMode.SINGLE))
    }

    @Test
    fun `系统预设的字体族名映射`() {
        assertEquals("sans-serif", ReaderFonts.presetFamilyName("0"))
        assertEquals("serif", ReaderFonts.presetFamilyName("1"))
        assertEquals("sans-serif", ReaderFonts.presetFamilyName("2"))
        assertEquals("monospace", ReaderFonts.presetFamilyName("3"))
        // 内置资源字体与文件字体没有系统族名
        assertEquals(null, ReaderFonts.presetFamilyName(ReaderFonts.XWWK_ID))
        assertEquals(null, ReaderFonts.presetFamilyName("imp:a.ttf"))
        assertTrue(ReaderFonts.isSystemFamilyPreset("0"))
        assertTrue(!ReaderFonts.isSystemFamilyPreset(ReaderFonts.XWWK_ID))
    }

    @Test
    fun `字重收敛到最近档位`() {
        // 多字重（300/400/500）
        assertEquals(300, ReaderFonts.snapWeight(300, WeightMode.MULTI))
        assertEquals(400, ReaderFonts.snapWeight(400, WeightMode.MULTI))
        assertEquals(500, ReaderFonts.snapWeight(500, WeightMode.MULTI))
        assertEquals(300, ReaderFonts.snapWeight(340, WeightMode.MULTI))   // 距 300 更近（40 vs 60）
        assertEquals(400, ReaderFonts.snapWeight(360, WeightMode.MULTI))   // 距 400 更近（40 vs 60）
        assertEquals(500, ReaderFonts.snapWeight(480, WeightMode.MULTI))
        // 单字重（400/700）：从霞鹜文楷的 500 切到单字重字体应落到最近的 400
        assertEquals(400, ReaderFonts.snapWeight(500, WeightMode.SINGLE))
        assertEquals(700, ReaderFonts.snapWeight(600, WeightMode.SINGLE))
        assertEquals(700, ReaderFonts.snapWeight(900, WeightMode.SINGLE))
    }

    @Test
    fun `字重收敛到可选区间`() {
        assertEquals(300, ReaderFonts.clampWeight(100))
        assertEquals(300, ReaderFonts.clampWeight(300))
        assertEquals(900, ReaderFonts.clampWeight(1000))
        assertEquals(400, ReaderFonts.clampWeight(400))
    }
}
