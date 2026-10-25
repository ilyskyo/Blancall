// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.contentpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentPackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ContentPackStore = ContentPackStore(tmp.newFolder())

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `带清单导入后可列出与逐篇读取`() {
        val bytes = zip(
            mapOf(
                "manifest.json" to """{"format":"blancall-pack","version":1,"name":"必修三","entries":[{"file":"a.txt","title":"岳阳楼记","author":"范仲淹"},{"file":"md/b.md"}]}""",
                "a.txt" to "庆历四年春，滕子京谪守巴陵郡。",
                "md/b.md" to "# 劝学\n\n学不可以已。",
                "cover.png" to "ignored",
            )
        )
        val store = store()
        val result = store.import(ByteArrayInputStream(bytes), "fallback.zip")
        assertTrue(result is ContentPackStore.ImportResult.Success)
        val pack = (result as ContentPackStore.ImportResult.Success).pack
        assertEquals("必修三", pack.name)
        assertEquals(2, pack.entries.size)
        assertEquals("岳阳楼记", pack.entries[0].title)
        assertEquals("范仲淹", pack.entries[0].author)
        assertEquals("b", pack.entries[1].title)
        assertEquals("# 劝学\n\n学不可以已。", store.readEntry(pack.id, 1))
    }

    @Test
    fun `没有清单时按文件名回退且包名取 zip 名`() {
        val bytes = zip(mapOf("a.txt" to "一", "b.md" to "二"))
        val result = store().import(ByteArrayInputStream(bytes), "我的合集.zip")
        val pack = (result as ContentPackStore.ImportResult.Success).pack
        assertEquals("我的合集", pack.name)
        assertEquals(listOf("a", "b"), pack.entries.map { it.title })
    }

    @Test
    fun `路径穿越条目整包拒绝`() {
        val bytes = zip(
            mapOf(
                "manifest.json" to """{"format":"blancall-pack","version":1,"name":"x","entries":[{"file":"../../evil.txt"}]}""",
                "../../evil.txt" to "bad",
            )
        )
        val result = store().import(ByteArrayInputStream(bytes), "x.zip")
        assertEquals(ContentPackStore.Reason.BAD_PATH, (result as ContentPackStore.ImportResult.Failure).reason)
    }

    @Test
    fun `清单引用不存在的文件拒绝`() {
        val bytes = zip(
            mapOf(
                "manifest.json" to """{"format":"blancall-pack","version":1,"name":"x","entries":[{"file":"missing.txt"}]}""",
                "a.txt" to "a",
            )
        )
        val result = store().import(ByteArrayInputStream(bytes), "x.zip")
        assertEquals(ContentPackStore.Reason.BAD_MANIFEST, (result as ContentPackStore.ImportResult.Failure).reason)
    }

    @Test
    fun `超长条目拒绝而不是截断`() {
        val big = "字".repeat(2048)
        val bytes = zip(mapOf("a.txt" to big))
        val result = store().import(
            ByteArrayInputStream(bytes), "x.zip", maxEntryBytes = 1024
        )
        assertEquals(ContentPackStore.Reason.TOO_LARGE, (result as ContentPackStore.ImportResult.Failure).reason)
    }

    @Test
    fun `条目数上限拒绝`() {
        val bytes = zip((1..5).associate { "a$it.txt" to "x" })
        val result = store().import(
            ByteArrayInputStream(bytes), "x.zip", maxEntries = 3
        )
        assertEquals(
            ContentPackStore.Reason.TOO_MANY_ENTRIES,
            (result as ContentPackStore.ImportResult.Failure).reason
        )
    }

    @Test
    fun `删除包后列表与正文都消失`() {
        val bytes = zip(mapOf("a.txt" to "一"))
        val store = store()
        val pack = (store.import(ByteArrayInputStream(bytes), "x.zip") as ContentPackStore.ImportResult.Success).pack
        store.deletePack(pack.id)
        assertTrue(store.listPacks().isEmpty())
        assertEquals(null, store.readEntry(pack.id, 0))
    }

    @Test
    fun `空包拒绝`() {
        val result = store().import(ByteArrayInputStream(zip(emptyMap())), "x.zip")
        assertEquals(ContentPackStore.Reason.EMPTY, (result as ContentPackStore.ImportResult.Failure).reason)
    }
}
