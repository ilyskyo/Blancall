// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.database.ArticleStorage
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 备份导入后的重载契约：
 * - 主文件缺失 = 导出/导入语义下的「这份数据不存在」，必须清空内存，且**不能**回读导入前的 `.bak`；
 * - reload 与 insert 共用写锁，交错执行后内存与磁盘必须同构（不能出现「内存有、盘上没有」）。
 */
class StorageReloadConsistencyTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory("blancall-$prefix").toFile()

    @Test
    fun `ArticleStorage reload 缺主文件时清空内存且不复活旧 bak`() = runBlocking {
        val dir = tempDir("article-reload")
        val file = File(dir, "articles.json")
        file.writeText("""[{"id":7,"title":"旧文","content":"旧正文"}]""")
        val storage = ArticleStorage(file.absolutePath)
        storage.awaitLoaded()
        assertNotNull(storage.getById(7L))

        // 导入前的旧备份 + 导入后备份里没有这篇文章（主文件不存在）
        File(dir, "articles.json.bak").writeText("""[{"id":99,"title":"更旧","content":"bak 正文"}]""")
        file.delete()
        storage.reload()

        assertNull("主文件缺失时必须清空，不能把导入前的旧文章留下", storage.getById(7L))
        assertNull("更不能从 .bak 复活旧文章", storage.getById(99L))
        val newId = storage.insert(Article(title = "新文", content = "新正文"))
        assertEquals("清空后 id 必须从 1 重新开始", 1L, newId)
    }

    @Test
    fun `RecordRepository reload 缺主文件时清空内存且不复活旧 bak`() = runBlocking {
        val dir = tempDir("record-reload")
        val file = File(dir, "records.json")
        file.writeText(
            """{"id":7,"articleId":1,"mode":"SENTENCE","totalBlanks":1,"correctCount":1,"timestamp":1}""" + "\n"
        )
        val repo = RecordRepository(file.absolutePath)
        repo.awaitLoaded()
        assertEquals(1, repo.records.value.size)

        File(dir, "records.json.bak").writeText(
            """{"id":99,"articleId":1,"mode":"SENTENCE","totalBlanks":1,"correctCount":1,"timestamp":1}""" + "\n"
        )
        file.delete()
        repo.reload()

        assertTrue("主文件缺失时必须清空记录", repo.records.value.isEmpty())
        val inserted = repo.insert(
            PracticeRecord(articleId = 2L, mode = "SENTENCE", totalBlanks = 1, correctCount = 1)
        )
        assertEquals("清空后 id 必须从 1 重新开始", 1L, inserted.id)
    }

    @Test
    fun `ArticleStorage 并发 insert 与 reload 后内存与磁盘一致`() = runBlocking {
        val dir = tempDir("article-concurrent")
        val file = File(dir, "articles.json")
        file.writeText("[]")
        val storage = ArticleStorage(file.absolutePath)
        storage.awaitLoaded()

        kotlinx.coroutines.coroutineScope {
            repeat(40) { i ->
                launch(Dispatchers.Default) {
                    storage.insert(Article(title = "t$i", content = "c$i"))
                }
                launch(Dispatchers.Default) { storage.reload() }
            }
        }

        val memoryIds = storage.articles.first().map { it.id }.toSet()
        val diskIds = JSONArray(file.readText()).let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optLong("id", 0L) }
                .filter { it > 0L }
                .toSet()
        }
        assertEquals("reload 与 insert 交错后内存必须与磁盘同构", diskIds, memoryIds)
        assertEquals(memoryIds.size, memoryIds.distinct().size)
    }

    @Test
    fun `RecordRepository 并发 insert 与 reload 后内存与磁盘一致`() = runBlocking {
        val dir = tempDir("record-concurrent")
        val file = File(dir, "records.json")
        file.writeText("")
        val repo = RecordRepository(file.absolutePath)
        repo.awaitLoaded()

        kotlinx.coroutines.coroutineScope {
            repeat(40) { i ->
                launch(Dispatchers.Default) {
                    repo.insert(
                        PracticeRecord(articleId = (i % 3).toLong() + 1, mode = "SENTENCE", totalBlanks = 1, correctCount = 1)
                    )
                }
                launch(Dispatchers.Default) { repo.reload() }
            }
        }

        val memoryIds = repo.records.value.map { it.id }.toSet()
        val diskIds = file.readLines()
            .mapNotNull { runCatching { org.json.JSONObject(it.trim()).optLong("id", 0L) }.getOrNull() }
            .filter { it > 0L }
            .toSet()
        assertEquals("reload 与 insert 交错后内存必须与磁盘同构", diskIds, memoryIds)
    }

    @Test
    fun `Fsrs saveSentences 批量落盘只 bump 一次 revision 且全部可读回`() = runBlocking {
        val dir = tempDir("fsrs-batch")
        val file = File(dir, "fsrs_state.json")
        val store = FsrsStateStore.createForTest(file.absolutePath)
        store.awaitLoaded()

        val updates = (1..5).associate { i ->
            val key = SentenceSelector.sentenceKey(12L, "第 $i 句")
            key to FsrsEngine.CardState(
                difficulty = 5.0, stability = 3.0, due = 1_000L + i,
                lastReview = 900L, reviewCount = 1, lapses = 0
            )
        }
        val before = store.revision.value
        store.saveSentences(updates)

        assertEquals("批量保存只能通知一次 revision", before + 1, store.revision.value)
        updates.forEach { (key, state) ->
            val loaded = store.getSentence(key)
            assertNotNull("批量保存后 $key 应可读回", loaded)
            assertEquals(state.due, loaded!!.due)
        }
        val disk = file.readText()
        updates.keys.forEach { assertTrue("盘上必须包含 $it", disk.contains(it)) }
    }

    @Test
    fun `Fsrs reload 缺主文件时清空且不复活旧 bak`() = runBlocking {
        val dir = tempDir("fsrs-reload")
        val file = File(dir, "fsrs_state.json")
        val store = FsrsStateStore.createForTest(file.absolutePath)
        store.awaitLoaded()
        store.save(7L, FsrsEngine.CardState(difficulty = 5.0, stability = 3.0, due = 1L, reviewCount = 1))
        assertNotNull(store.get(7L))

        // 持久化会轮换出 .bak；删除主文件模拟「备份里没有 fsrs_state」
        file.delete()
        store.reload()

        assertNull("导入语义下主文件缺失必须清空", store.get(7L))
        assertTrue(store.allStates().isEmpty())
        assertTrue(store.allSentenceStates().isEmpty())
    }
}
