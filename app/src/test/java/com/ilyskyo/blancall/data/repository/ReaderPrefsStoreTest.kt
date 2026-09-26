// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ReaderPrefsStore] 的按文章序列化口径测试（含 occlusionCustomConfigId）。
 * 守护「自定义遮挡可生效」的持久化前提：遮挡粒度 / 配置 id / 开关必须随文章存档往返，
 * 且局部更新（如调字号）不得丢失自定义配置指向
 *（真机 bug：基于旧内存快照整体回写，把浮层刚写入的字段覆盖回旧值 → 自定义失效、配置名丢失）。
 */
class ReaderPrefsStoreTest {

    private fun newStore(): ReaderPrefsStore =
        ReaderPrefsStore(File(Files.createTempDirectory("blancall-reader-prefs").toFile(), "reader_prefs.json"))

    private fun prefs(customId: Long, mode: String = "custom") = ReaderPrefs(
        bgMode = 1, fontId = "0", fontWeight = 400, fontPx = 17f, lineHeight = 2f,
        layoutMode = 0, occlusionEnabled = true, occlusionMode = mode,
        occlusionColor = 2, occlusionCustomConfigId = customId
    )

    @Test
    fun `自定义配置 id 与粒度按文章往返持久化`() {
        val store = newStore()
        store.save(7, prefs(customId = 5))
        val loaded = store.get(7)!!
        assertEquals(5L, loaded.occlusionCustomConfigId)
        assertEquals("custom", loaded.occlusionMode)
        assertTrue(loaded.occlusionEnabled)
        assertEquals(2, loaded.occlusionColor)
        // 其它文章不受影响
        assertNull(store.get(8))
    }

    @Test
    fun `局部更新以 store 最新值为基准时不丢自定义配置指向`() {
        val store = newStore()
        store.save(9, prefs(customId = 3))
        // 模拟阅读设置面板只改字号：基准必须取 store 最新值（updateReaderPrefs 现语义）；
        // 若基于旧内存快照整体回写，customId 会被覆盖回旧值（真机 bug）
        store.save(9, store.get(9)!!.copy(fontPx = 21f))
        val loaded = store.get(9)!!
        assertEquals(21f, loaded.fontPx, 0.001f)
        assertEquals(3L, loaded.occlusionCustomConfigId)
        assertEquals("custom", loaded.occlusionMode)
    }

    @Test
    fun `旧文件缺 occlusionCustomConfigId 字段时回落 -1`() {
        val file = File(Files.createTempDirectory("blancall-reader-prefs-legacy").toFile(), "reader_prefs.json")
        file.writeText(
            """{"version":1,"articles":{"11":{"bgMode":0,"fontId":"0","fontWeight":400,""" +
                """"fontPx":17.0,"lineHeight":2.0,"layoutMode":0,"occlusionEnabled":true,""" +
                """"occlusionMode":"custom","occlusionColor":0}}}"""
        )
        val store = ReaderPrefsStore(file)
        assertEquals(-1L, store.get(11)!!.occlusionCustomConfigId)
    }
}
