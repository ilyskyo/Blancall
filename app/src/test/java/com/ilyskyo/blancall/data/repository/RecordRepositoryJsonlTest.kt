// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 练习记录仓库的 **JSONL 增量写** 回归测试（格式迁移 / 追加 / 坏行容错 / 索引）。
 *
 * 背景：insert 从「每次全量重写 JSON 数组」升级为「JSONL 追加一行」，
 * 旧文件在首次写入时自动转写。这里把两条格式路径与容错边界全部锁死，
 * 防止将来把「旧数组直接追加」这类会毁文件的改动放进来。
 */
class RecordRepositoryJsonlTest {

    private fun tempPath(name: String = "records.json"): File =
        File(Files.createTempDirectory("blancall-rec-jsonl").toFile(), name)

    private fun record(articleId: Long, ts: Long = System.currentTimeMillis()) = PracticeRecord(
        articleId = articleId,
        mode = "SENTENCE",
        totalBlanks = 3,
        correctCount = 2,
        timestamp = ts,
        mistakes = listOf(MistakeDetail(0, "甲", "乙", "TYPO")),
    )

    /** 读盘断言用：按实际格式解析（兼容旧数组与 JSONL）。 */
    private fun readDiskObjects(path: File): List<JSONObject> {
        val text = path.readText()
        if (text.trimStart().startsWith("[")) {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .toList()
    }

    @Test
    fun `旧版JSON数组文件可加载并在首次插入后转写为JSONL`() {
        val path = tempPath()
        // 预置一份「旧版格式」文件（与升级前的落盘字节结构一致）
        val legacy = JSONArray()
        legacy.put(
            JSONObject().apply {
                put("id", 1)
                put("articleId", 7)
                put("mode", "WORD")
                put("totalBlanks", 2)
                put("correctCount", 1)
                put("timestamp", 1000L)
            }
        )
        path.writeText(legacy.toString())

        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            assertEquals("旧数组应能读入", 1, repo.records.value.size)
            repo.insert(record(articleId = 7))
        }
        val text = path.readText()
        assertFalse("首写后应转写为 JSONL（不再以 [ 开头）", text.trimStart().startsWith("["))
        assertEquals("旧记录 + 新记录 = 两行", 2, readDiskObjects(path).size)

        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertEquals("重新加载两条都在", 2, repo2.records.value.size)
        }
    }

    @Test
    fun `插入走追加且可往返`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 1))
            repo.insert(record(articleId = 2))
            repo.insert(record(articleId = 1))
        }
        assertEquals(3, readDiskObjects(path).size)
        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertEquals(3, repo2.records.value.size)
        }
    }

    @Test
    fun `追加中断留下的残缺行被跳过、其余记录完好`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 5))
        }
        // 模拟「追加途中进程被杀」：文件尾部留下半行
        path.appendText("{\"id\":999")

        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertEquals(1, repo2.records.value.size)
            assertEquals(5L, repo2.records.value.first().articleId)
        }
    }

    @Test
    fun `按文章删除整体重写为JSONL且不复活`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 1))
            repo.insert(record(articleId = 2))
            repo.insert(record(articleId = 1))
            repo.deleteByArticleId(1)
            assertEquals(1, repo.records.value.size)
        }
        assertFalse(path.readText().trimStart().startsWith("["))
        assertEquals(1, readDiskObjects(path).size)
        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertEquals(1, repo2.records.value.size)
            assertEquals(2L, repo2.records.value.first().articleId)
        }
    }

    @Test
    fun `文章索引查询正确且按时间倒序`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 9, ts = 100L))
            repo.insert(record(articleId = 9, ts = 300L))
            repo.insert(record(articleId = 8, ts = 200L))

            val list9 = repo.getByArticleId(9)
            assertEquals(2, list9.size)
            assertTrue("应按时间倒序", list9[0].timestamp > list9[1].timestamp)
            assertEquals(1, repo.getByArticleId(8).size)
            assertEquals(0, repo.getByArticleId(12345).size)
            // 删除后索引同步更新
            repo.deleteByArticleId(9)
            assertEquals(0, repo.getByArticleId(9).size)
            assertEquals(1, repo.getByArticleId(8).size)
        }
    }

    @Test
    fun `主文件全为垃圾行时置位整体重写、绝不追加进垃圾堆`() {
        val path = tempPath()
        path.writeText("garbage line one\nmore garbage\n")
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            assertTrue("全垃圾应空起步", repo.records.value.isEmpty())
            repo.insert(record(articleId = 9))
            assertEquals(1, repo.records.value.size)
        }
        assertFalse("重写后不应是数组格式", path.readText().trimStart().startsWith("["))
        assertEquals("垃圾应被新内容覆盖（仅 1 行有效记录）", 1, readDiskObjects(path).size)
    }
}
