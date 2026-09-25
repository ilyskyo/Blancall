// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.data.database.ArticleStorage
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 数据存亡矩阵：并发写入不丢 + 损坏恢复（[RecordRepository] / [ArticleStorage]）。
 *
 * 隔离说明：两个类都是**构造器实例**（非单例），每个用例使用独立临时目录，互不干扰。
 *
 * 覆盖：
 * - 多线程并发 insert 后「内存条数 == 磁盘条数 == id 去重数」；
 * - 主文件损坏 → 从 `.bak` 回读（恢复到的正是上一版内容）；
 * - 主文件与备份全损坏 → 安全空起步且可继续写入（不崩溃）。
 */
class RecordRepositorySurvivalTest {

    private fun record(articleId: Long) = PracticeRecord(
        articleId = articleId,
        mode = "WORD",
        totalBlanks = 1,
        correctCount = 1,
    )

    @Test
    fun `并发 insert 不丢记录且磁盘与内存一致`() {
        val path = File(Files.createTempDirectory("blancall-records-concurrency").toFile(), "records.json")
        val repo = RecordRepository(path.absolutePath)
        val threads = 8
        val perThread = 25
        runBlocking {
            repo.awaitLoaded()
            (0 until threads).map { t ->
                async(Dispatchers.Default) {
                    repeat(perThread) { i ->
                        repo.insert(record(articleId = t * 1000L + i))
                    }
                }
            }.awaitAll()
        }
        val expected = threads * perThread
        assertEquals("内存条数", expected, repo.records.value.size)
        val arr = JSONArray(path.readText())
        assertEquals("磁盘条数", expected, arr.length())
        val diskIds = (0 until arr.length()).map { arr.getJSONObject(it).getLong("id") }.toSet()
        assertEquals("磁盘 id 不应重复", expected, diskIds.size)
    }

    @Test
    fun `主文件损坏时从备份恢复记录`() {
        val dir = Files.createTempDirectory("blancall-records-bak").toFile()
        val path = File(dir, "records.json")
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 1)) // v1（首写，无备份）
            repo.insert(record(articleId = 2)) // v2；v1 轮换入 .bak
        }
        path.writeText("{ 不是合法 JSON")
        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            // 回读到的是上一版（只含 articleId=1）
            assertEquals(setOf(1L), repo2.records.value.map { it.articleId }.toSet())
        }
    }

    @Test
    fun `主文件与备份全损坏时安全空起步且可继续写入`() {
        val dir = Files.createTempDirectory("blancall-records-broken").toFile()
        val path = File(dir, "records.json")
        runBlocking {
            val repo = RecordRepository(path.absolutePath)
            repo.awaitLoaded()
            repo.insert(record(articleId = 1))
            repo.insert(record(articleId = 2)) // 生成 .bak
        }
        path.writeText("{ broken")
        File(dir, "records.json.bak").writeText("{{ also broken")
        runBlocking {
            val repo2 = RecordRepository(path.absolutePath)
            repo2.awaitLoaded()
            assertTrue("全损坏应空起步", repo2.records.value.isEmpty())
            repo2.insert(record(articleId = 9))
            assertEquals(1, repo2.records.value.size)
            assertEquals("损坏文件应被新内容覆盖", 1, JSONArray(path.readText()).length())
        }
    }
}

class ArticleStorageSurvivalTest {

    private fun article(title: String) = Article(title = title, content = "正文内容 $title")

    @Test
    fun `并发 insert 不丢文章且磁盘与内存一致`() {
        val path = File(Files.createTempDirectory("blancall-articles-concurrency").toFile(), "articles.json")
        val storage = ArticleStorage(path.absolutePath)
        val threads = 8
        val perThread = 25
        runBlocking {
            storage.awaitLoaded()
            (0 until threads).map { t ->
                async(Dispatchers.Default) {
                    repeat(perThread) { i ->
                        storage.insert(article("文章-$t-$i"))
                    }
                }
            }.awaitAll()
        }
        val expected = threads * perThread
        assertEquals("内存条数", expected, runBlocking { storage.articles.first() }.size)
        val arr = JSONArray(path.readText())
        assertEquals("磁盘条数", expected, arr.length())
        val diskIds = (0 until arr.length()).map { arr.getJSONObject(it).getLong("id") }.toSet()
        assertEquals("磁盘 id 不应重复", expected, diskIds.size)
    }

    @Test
    fun `主文件损坏时从备份恢复文章`() {
        val dir = Files.createTempDirectory("blancall-articles-bak").toFile()
        val path = File(dir, "articles.json")
        runBlocking {
            val storage = ArticleStorage(path.absolutePath)
            storage.awaitLoaded()
            storage.insert(article("第一版"))
            storage.insert(article("第二版")) // 第一版轮换入 .bak
        }
        path.writeText("{ 不是合法 JSON")
        runBlocking {
            val storage2 = ArticleStorage(path.absolutePath)
            storage2.awaitLoaded()
            assertEquals(listOf("第一版"), storage2.articles.first().map { it.title })
        }
    }

    @Test
    fun `主文件与备份全损坏时安全空起步且可继续写入`() {
        val dir = Files.createTempDirectory("blancall-articles-broken").toFile()
        val path = File(dir, "articles.json")
        runBlocking {
            val storage = ArticleStorage(path.absolutePath)
            storage.awaitLoaded()
            storage.insert(article("甲"))
            storage.insert(article("乙")) // 生成 .bak
        }
        path.writeText("{ broken")
        File(dir, "articles.json.bak").writeText("{{ also broken")
        runBlocking {
            val storage2 = ArticleStorage(path.absolutePath)
            storage2.awaitLoaded()
            assertTrue("全损坏应空起步", storage2.articles.first().isEmpty())
            storage2.insert(article("丙"))
            assertEquals(1, storage2.articles.first().size)
            assertEquals("损坏文件应被新内容覆盖", 1, JSONArray(path.readText()).length())
        }
    }
}

/**
 * 首页布局存储的损坏恢复（[HomeLayoutStore] 为进程级单例：仅本类使用，不会与其它测试类抢目录）。
 */
class HomeLayoutStoreSurvivalTest {

    companion object {
        private val dir = Files.createTempDirectory("blancall-home-layout-survival").toFile()
        private val store = HomeLayoutStore.getInstance(dir)
    }

    private val cardA = HomeLayoutStore.Card(id = "due", type = HomeLayoutStore.CardType.DUE)
    private val cardB = HomeLayoutStore.Card(id = "add", type = HomeLayoutStore.CardType.ADD_ARTICLE)

    @Test
    fun `主文件损坏时回读备份并保留损坏现场`() {
        store.saveCards(listOf(cardA))            // v1（首写）
        store.saveCards(listOf(cardA, cardB))     // v2；v1 轮换入 .bak
        val file = File(dir, "home_layout.json")
        val healthy = file.readText()
        file.writeText("{ 损坏的布局")

        // 读：主损坏 → 现场另存 .corrupt-* → 回读 .bak（只有 cardA）
        assertEquals(listOf("due"), store.getCards().map { it.id })
        val corrupt = dir.listFiles { f -> f.name.startsWith("home_layout.json.corrupt-") }
        assertTrue("损坏现场应被另存保留", corrupt != null && corrupt.isNotEmpty())

        // 收尾：写回健康内容，避免影响其它用例
        file.writeText(healthy)
    }

    @Test
    fun `主文件损坏且无备份时回退默认布局且可重新保存`() {
        val file = File(dir, "home_layout.json")
        val bak = File(dir, "home_layout.json.bak")
        file.writeText("{ 损坏")
        bak.delete()

        // 无备份 → 空库 → getCards 回退默认布局（不崩溃、不为空）
        val recovered = store.getCards()
        assertTrue("应回退默认布局而非空列表", recovered.isNotEmpty())

        // 且可重新保存（覆盖损坏文件）
        store.saveCards(listOf(cardA))
        assertEquals(listOf("due"), store.getCards().map { it.id })
    }
}
