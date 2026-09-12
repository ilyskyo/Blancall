// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 两个配置 Store 的磁盘往返测试（JVM 版 org.json + 临时目录）。
 * 单例按「每测试类一个独立临时目录」持有；类内各用例用不同 articleId 隔离。
 * 覆盖：往返序列化、A1 回归（覆盖保存不重置 createdAt）、B6/C10 字段、旧格式向后兼容。
 */
class MaskConfigStoreTest {

    companion object {
        // 单例按「每测试类一个独立临时目录」持有（companion 保证全类共享同一实例与文件）
        private val dir = Files.createTempDirectory("blancall-mask-store-test").toFile()
        private val store = MaskConfigStore.getInstance(dir)
    }

    private fun cfg(id: Long, name: String, createdAt: Long, spans: List<MaskConfigStore.MaskSpan>, hash: String? = null) =
        MaskConfigStore.MaskConfig(id, name, createdAt, spans, hash)

    @Test
    fun `save new then read back round trip`() {
        val id = store.saveConfig(
            101,
            cfg(0, "测试A", 1000, listOf(MaskConfigStore.MaskSpan(0, 0, 5, 2), MaskConfigStore.MaskSpan(1, 3, 9, 4)), "abc123")
        )
        assertTrue(id > 0)
        val loaded = store.getConfigs(101).single()
        assertEquals("测试A", loaded.name)
        assertEquals(1000, loaded.createdAt)
        assertEquals("abc123", loaded.contentHash)
        assertEquals(2, loaded.spans.size)
        assertEquals(MaskConfigStore.MaskSpan(0, 0, 5, 2), loaded.spans[0])
        assertEquals(MaskConfigStore.MaskSpan(1, 3, 9, 4), loaded.spans[1])
    }

    @Test
    fun `update with createdAt zero preserves original createdAt (A1 regression)`() {
        val id = store.saveConfig(102, cfg(0, "原名", 55555, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0))))
        // 编辑保存：上层未带 createdAt（0）→ 必须保留磁盘原值，不得跳到列表末尾
        store.saveConfig(102, cfg(id, "新名", 0, listOf(MaskConfigStore.MaskSpan(0, 0, 2, 1))))
        val loaded = store.getConfigs(102).single()
        assertEquals(55555, loaded.createdAt)
        assertEquals("新名", loaded.name)
    }

    @Test
    fun `delete removes config and clears selected when in use`() {
        val id = store.saveConfig(103, cfg(0, "待删", 1, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0))))
        store.setSelected(103, id)
        assertEquals(id, store.getSelected(103))
        store.deleteConfig(103, id)
        assertTrue(store.getConfigs(103).isEmpty())
        assertEquals(-1L, store.getSelected(103))
    }

    @Test
    fun `selected defaults to -1`() {
        assertEquals(-1L, store.getSelected(999))
    }

    @Test
    fun `old json without contentHash reads as null`() {
        val id = store.saveConfig(
            104,
            cfg(0, "旧版", 42, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0)))
        )
        // 模拟旧格式：直接改写文件去掉 contentHash 字段
        val file = java.io.File(dir, "mask_config.json")
        val raw = file.readText().replace("\"contentHash\"", "\"__removed__\"")
        file.writeText(raw)
        val loaded = store.getConfigs(104).single { it.id == id }
        assertNull(loaded.contentHash)
    }
}

class CustomClozeStoreTest {

    companion object {
        private val dir = Files.createTempDirectory("blancall-cloze-store-test").toFile()
        private val store = CustomClozeStore.getInstance(dir)
    }

    private fun cfg(
        id: Long,
        name: String,
        createdAt: Long,
        blanks: List<CustomClozeStore.BlankSpec>,
        mode: String = "WORD",
        levels: List<Int> = emptyList(),
        hash: String? = null
    ) = CustomClozeStore.CustomConfig(id, name, createdAt, blanks, mode, levels, hash)

    @Test
    fun `save new then read back round trip`() {
        val id = store.saveConfig(
            201,
            cfg(0, "字词练习", 2000, listOf(CustomClozeStore.BlankSpec(0, 0, 2), CustomClozeStore.BlankSpec(1, 3, 5)), "WORD", listOf(0, 3), "deadbeef")
        )
        assertTrue(id > 0)
        val loaded = store.getConfigs(201).single()
        assertEquals("字词练习", loaded.name)
        assertEquals(2000, loaded.createdAt)
        assertEquals("WORD", loaded.mode)
        assertEquals(listOf(0, 3), loaded.levels)
        assertEquals("deadbeef", loaded.contentHash)
        assertEquals(2, loaded.blanks.size)
        assertEquals(CustomClozeStore.BlankSpec(0, 0, 2), loaded.blanks[0])
    }

    @Test
    fun `sentence mode config with empty levels round trips`() {
        val id = store.saveConfig(202, cfg(0, "句子", 3000, listOf(CustomClozeStore.BlankSpec(0, 0, 4)), "SENTENCE"))
        val loaded = store.getConfigs(202).single { it.id == id }
        assertEquals("SENTENCE", loaded.mode)
        assertTrue(loaded.levels.isEmpty())
    }

    @Test
    fun `update with createdAt zero preserves original createdAt (A1 regression)`() {
        val id = store.saveConfig(203, cfg(0, "旧名", 777, listOf(CustomClozeStore.BlankSpec(0, 0, 1))))
        store.saveConfig(203, cfg(id, "新名", 0, listOf(CustomClozeStore.BlankSpec(0, 0, 2))))
        val loaded = store.getConfigs(203).single()
        assertEquals(777, loaded.createdAt)
        assertEquals("新名", loaded.name)
    }

    @Test
    fun `delete removes config`() {
        val id = store.saveConfig(204, cfg(0, "待删", 1, listOf(CustomClozeStore.BlankSpec(2, 0, 1))))
        store.deleteConfig(204, id)
        assertTrue(store.getConfigs(204).none { it.id == id })
    }

    @Test
    fun `legacy json without levels and contentHash stays readable`() {
        val file = java.io.File(dir, "custom_cloze.json")
        file.writeText(
            """{"version":1,"articles":{"43":[{"id":7,"name":"旧配置","createdAt":100,
                "mode":"WORD","blanks":[{"s":0,"a":0,"b":2}]}]}}""".replace("\n", "")
        )
        val loaded = store.getConfigs(43).single()
        assertEquals("旧配置", loaded.name)
        assertEquals("WORD", loaded.mode)
        assertTrue(loaded.levels.isEmpty())
        assertNull(loaded.contentHash)
        assertEquals(listOf(CustomClozeStore.BlankSpec(0, 0, 2)), loaded.blanks)
    }
}
