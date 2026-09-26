// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `主文件损坏时回读备份并保留损坏现场`() {
        val id = store.saveConfig(105, cfg(0, "备份源", 10, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0))))
        // 再存一次：上一版（"备份源"）轮换入 .bak
        store.saveConfig(105, cfg(id, "备份源v2", 0, listOf(MaskConfigStore.MaskSpan(0, 0, 2, 0))))
        val file = java.io.File(dir, "mask_config.json")
        val healthy = file.readText()
        file.writeText("{ 损坏的配置库")

        // 读：主损坏 → 现场另存 .corrupt-* → 回读 .bak（上一版）
        val loaded = store.getConfigs(105).single { it.id == id }
        assertEquals("备份源", loaded.name)
        val corrupt = dir.listFiles { f -> f.name.startsWith("mask_config.json.corrupt-") }
        assertTrue("损坏现场应被另存保留", corrupt != null && corrupt.isNotEmpty())

        // 收尾：写回健康内容，避免影响其它用例
        file.writeText(healthy)
    }

    @Test
    fun `保存后 getConfig 精确回填且按文章隔离（编辑器重进回填的数据源）`() {
        val id = store.saveConfig(107, cfg(0, "回填源", 88, listOf(MaskConfigStore.MaskSpan(2, 4, 9, 3))))
        val one = store.getConfig(107, id)!!
        assertEquals("回填源", one.name)
        assertEquals(listOf(MaskConfigStore.MaskSpan(2, 4, 9, 3)), one.spans)
        // 列表页与编辑器回填读同一套数据
        assertEquals(id, store.getConfigs(107).single().id)
        // 按文章隔离：同一 configId 在其它文章下必须查不到（防串篇）
        assertNull(store.getConfig(108, id))
    }

    @Test
    fun `主文件损坏且无备份：保存被拦截，不得以空库覆盖（防抹库回归）`() {
        val keepId = store.saveConfig(106, cfg(0, "保命配置", 7, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0))))
        val file = java.io.File(dir, "mask_config.json")
        val healthy = file.readText()
        val bak = java.io.File(dir, "mask_config.json.bak")
        if (bak.exists()) bak.delete()
        file.writeText("{ 再损坏一次")

        // 读：损坏且无备份 → 空库（现场另存 .corrupt-* 供人工恢复）
        assertTrue(store.getConfigs(106).isEmpty())
        // 写：粘性 loadFailed 必须拦截 —— 旧实现尾部用 file.exists() 判定（损坏文件
        // 已被改名搬走→恒为 false）会误放行，以空 root 写盘抹掉全部文章的配置
        store.saveConfig(106, cfg(0, "不该写进去", 8, listOf(MaskConfigStore.MaskSpan(0, 0, 1, 0))))
        assertFalse("损坏且无备份时必须拒绝写盘", file.exists())

        // 收尾：恢复健康数据（成功解析会清除粘性标记），不影响其它用例
        file.writeText(healthy)
        assertEquals("保命配置", store.getConfigs(106).single { it.id == keepId }.name)
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

    @Test
    fun `主文件损坏时回读备份并保留损坏现场`() {
        val id = store.saveConfig(205, cfg(0, "备份源", 10, listOf(CustomClozeStore.BlankSpec(0, 0, 2))))
        // 再存一次：上一版（"备份源"）轮换入 .bak
        store.saveConfig(205, cfg(id, "备份源v2", 0, listOf(CustomClozeStore.BlankSpec(0, 0, 3))))
        val file = java.io.File(dir, "custom_cloze.json")
        val healthy = file.readText()
        file.writeText("{ 损坏的配置库")

        // 读：主损坏 → 现场另存 .corrupt-* → 回读 .bak（上一版）
        val loaded = store.getConfigs(205).single { it.id == id }
        assertEquals("备份源", loaded.name)
        val corrupt = dir.listFiles { f -> f.name.startsWith("custom_cloze.json.corrupt-") }
        assertTrue("损坏现场应被另存保留", corrupt != null && corrupt.isNotEmpty())

        // 收尾：写回健康内容，避免影响其它用例
        file.writeText(healthy)
    }
}
