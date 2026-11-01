// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观快照的落盘契约（JVM，org.json 走 testImplementation）。
 *
 * 这些字段现在会进「一键备份」。序列化错一个键名，表现是「恢复后外观回默认」——
 * 不崩、不报错，只是用户调了半天的东西没了。
 */
class AppearanceStoreTest {

    private fun sample() = AppearanceSnapshot(
        themeMode = ThemeMode.DARK,
        accentIndex = 3,
        customArgb = 0xFF1E88E5.toInt(),
        useCustomAccent = true,
        beigeBackground = true,
        glassTint = false,
        uiFont = AppearanceSnapshot.FONT_XWWK,
        uiFontScale = 1.15f,
        materialYou = true,
    )

    @Test
    fun 编码解码往返一致() {
        val s = sample()
        val back = AppearanceStore.decode(AppearanceStore.encode(s))
        assertEquals("往返后快照必须完全相等（少一个键就等于丢一项设置）", s, back)
    }

    @Test
    fun 缺字段时用默认值而不是崩() {
        val back = AppearanceStore.decode("""{"version":1}""")
        assertTrue("空对象也必须解出一份默认快照", back != null)
        assertEquals(ThemeMode.SYSTEM, back!!.themeMode)
        assertEquals(0, back.accentIndex)
        assertEquals(AppearanceSnapshot.FONT_SERIF, back.uiFont)
        assertEquals(1f, back.uiFontScale, 0.001f)
        assertTrue("玻璃染色默认开（关掉是用户的显式选择）", back.glassTint)
    }

    @Test
    fun 损坏的文件解不出来而不是抛异常() {
        assertEquals(null, AppearanceStore.decode("not json"))
        assertEquals(null, AppearanceStore.decode(""))
    }

    @Test
    fun 非法字体名回落到系统衬线() {
        val back = AppearanceStore.decode("""{"uiFont":"comic-sans","uiFontScale":1.0}""")
        assertEquals(AppearanceSnapshot.FONT_SERIF, back!!.uiFont)
    }

    @Test
    fun 字号缩放被夹在合法区间() {
        val tiny = AppearanceStore.decode("""{"uiFontScale":0.1}""")!!
        val huge = AppearanceStore.decode("""{"uiFontScale":9.9}""")!!
        assertEquals(AppearanceStore.MIN_SCALE, tiny.uiFontScale, 0.001f)
        assertEquals(AppearanceStore.MAX_SCALE, huge.uiFontScale, 0.001f)
    }

    @Test
    fun 自定义色优先于预设下标() {
        val custom = sample().accentPreset()
        val preset = sample().copy(useCustomAccent = false).accentPreset()
        assertTrue("启用自定义色后不该再是预设色", custom.primaryLight != preset.primaryLight)
        assertEquals(0xFF1E88E5.toInt(), custom.primaryLight.toArgb())
    }

    @Test
    fun 关闭自定义色后回到预设() {
        val s = AppearanceSnapshot(accentIndex = 2, customArgb = 0xFF00FF00.toInt(), useCustomAccent = false)
        assertEquals(AccentPresets[2], s.accentPreset())
    }

    @Test
    fun 下标越界回落到第一套() {
        val s = AppearanceSnapshot(accentIndex = 99)
        assertEquals(AccentPresets[0], s.accentPreset())
        assertFalse("越界不应该被当成自定义色", s.useCustomAccent)
    }
}
