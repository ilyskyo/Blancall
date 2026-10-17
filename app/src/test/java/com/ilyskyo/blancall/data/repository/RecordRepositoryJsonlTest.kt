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

    @Test
    fun `逐空落点与相似度按原样往返，缺字段的旧行回退哨兵值`() {
        // MistakeDetail 新增的两个字段都是「能省则省」：拿不到就不写，读回来是哨兵 -1。
        // 这条同时守两件事：① 写下去的必须读得回来（否则热力图与错题回顾永远拿不到精确落点）；
        // ② 旧行不许被读成 0 —— 0 是「第 0 句」「相似度 0%」的真实值，占用它当缺省就会伪造数据。
        val path = tempPath()
        val withDetail = PracticeRecord(
            articleId = 3L, mode = "SENTENCE", totalBlanks = 2, correctCount = 1,
            timestamp = 1000L,
            mistakes = listOf(
                MistakeDetail(0, "明月", "名月", "TYPO", sentenceIndex = 2, similarity = 0.8f),
                MistakeDetail(1, "光", "", "MISSING")            // 老形状：两个字段都没有
            )
        )
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(withDetail)
        }

        val stored = readDiskObjects(path).single()
        val mistakes = stored.getJSONArray("mistakes")
        assertTrue("落点写了就该在盘上", mistakes.getJSONObject(0).has("sentenceIndex"))
        assertFalse("缺字段的错处不该被塞一个 0 进盘", mistakes.getJSONObject(1).has("sentenceIndex"))
        assertFalse(mistakes.getJSONObject(1).has("similarity"))

        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            val back = repo2.records.value.single().mistakes
            assertEquals(2, back[0].sentenceIndex)
            assertEquals(0.8f, back[0].similarity, 1e-6f)
            assertEquals(-1, back[1].sentenceIndex)
            assertEquals(-1f, back[1].similarity, 1e-6f)
        }
    }

    @Test
    fun `归档取最旧一批、按 id 裁剪后内存与磁盘一致`() {
        val path = tempPath()
        val t0 = 1_700_000_000_000L
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            val r1 = repo.insert(record(1L, ts = t0))            // 最旧
            val r2 = repo.insert(record(1L, ts = t0 + 1_000))
            val r3 = repo.insert(record(1L, ts = t0 + 2_000))    // 最新

            // 取最旧 2 条（按时间正序）
            val olds = repo.oldestRecords(2)
            assertEquals(listOf(r1.id, r2.id), olds.map { it.id })

            // 归档导出文本可读（JSONL，一行一条）
            val json = repo.archiveJson(olds)
            assertEquals(2, json.trim().lineSequence().count())

            // 裁剪：删掉归档的两条，保留最新
            assertEquals(2, repo.removeByIds(olds.map { it.id }.toSet()))
            assertEquals(listOf(r3.id), repo.records.value.map { it.id })
        }
        // 磁盘重读一致（新实例）
        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertEquals(1, repo2.records.value.size)
        }
    }

    @Test
    fun `归档候选为零或负数时返回空表且不动数据`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            val r = repo.insert(record(1L))
            assertTrue(repo.oldestRecords(0).isEmpty())
            assertTrue(repo.oldestRecords(-3).isEmpty())
            assertEquals(0, repo.removeByIds(emptySet()))
            assertEquals(listOf(r.id), repo.records.value.map { it.id })
        }
    }

    @Test
    fun `reload 重读磁盘替换内存（备份导入场景的单 Store 验证）`() {
        val path = tempPath()
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(1L))   // 内存 1 条（id=1）
            // 模拟「备份导入把磁盘文件整体替换」：外部写入另一条记录（id=99）
            path.writeText("""{"id":99,"articleId":9,"mode":"SENTENCE","totalBlanks":1,"correctCount":1}""" + "\n")
            repo.reload()
            assertEquals(
                "reload 后内存必须等于磁盘（否则旧内存会在下次写盘时覆盖刚恢复的数据）",
                listOf(99L), repo.records.value.map { it.id }
            )
        }
    }
}
