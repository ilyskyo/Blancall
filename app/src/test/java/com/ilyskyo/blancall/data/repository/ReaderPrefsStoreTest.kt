// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 本类的写路径是**整表回写**，所以「把损坏当空表」的代价比别的 Store 更大：
     * 损坏后第一次改阅读设置，就会把其余所有文章的存档全部抹掉。
     */
    @Test
    fun `主文件损坏且无备份时拒绝写盘（不得以空表抹掉其余文章的存档）`() {
        val dir = Files.createTempDirectory("blancall-reader-prefs-corrupt").toFile()
        val file = File(dir, "reader_prefs.json")
        val seeded = ReaderPrefsStore(file)
        seeded.save(21, prefs(customId = 5))
        seeded.save(22, prefs(customId = 6))
        val healthy = file.readText()
        File(dir, "reader_prefs.json.bak").delete()
        file.writeText("{ 损坏的阅读存档")

        // 本类有内存镜像，所以「损坏」要模拟成发生在两次进程之间：重开一个实例才会真去读盘
        val store = ReaderPrefsStore(file)
        // 读：损坏 → 现场另存 .corrupt-* → 无备份则各文章回落全局基线（null），不崩溃
        assertNull(store.get(21))
        val corrupt = dir.listFiles { f -> f.name.startsWith("reader_prefs.json.corrupt-") }
        assertTrue("损坏现场应被另存保留", corrupt != null && corrupt.isNotEmpty())

        // 写：粘性 loadFailed 必须拦截 —— 旧实现完全没有这个标记，此处的 save 会以空表整写覆盖
        store.save(23, prefs(customId = 7))
        assertFalse("损坏且无备份时必须拒绝写盘", file.exists())

        // 收尾：还原健康内容（成功解析会清除粘性标记），并验证既有存档仍在、此后还能正常保存
        file.writeText(healthy)
        val reopened = ReaderPrefsStore(file)
        assertEquals(6L, reopened.get(22)!!.occlusionCustomConfigId)
        reopened.save(23, prefs(customId = 7))
        assertEquals(7L, reopened.get(23)!!.occlusionCustomConfigId)
    }

    /** 删除文章的级联：只清自己那一条，其余文章的存档必须留着 */
    @Test
    fun `removeArticle 只清该文章且其余存档保留`() {
        val file = File(Files.createTempDirectory("blancall-reader-prefs-remove").toFile(), "reader_prefs.json")
        val store = ReaderPrefsStore(file)
        store.save(31, prefs(customId = 1))
        store.save(32, prefs(customId = 2))
        store.removeArticle(31)
        assertNull(store.get(31))
        assertEquals(2L, store.get(32)!!.occlusionCustomConfigId)
        // 真的落盘了（重开进程后读得到），而不是只改了内存表
        assertFalse(file.readText().contains("\"31\""))
        // 删没有存档的文章不该触发整表重写
        val before = file.readText()
        store.removeArticle(999)
        assertEquals(before, file.readText())
    }

    /**
     * 旧实现完全没有同步：`save()` 往共享 map 写入后紧接着遍历同一个 map 做序列化，
     * 另一线程插进来遍历时抛 ConcurrentModificationException，又被空 catch 吞掉 ——
     * 那次阅读设置静默不落盘。加锁后并发写必须一条不丢。
     */
    @Test
    fun `并发保存一条不丢`() {
        val store = newStore()
        val threads = 6
        val perThread = 15
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                for (i in 0 until perThread) store.save(t * 1000L + i, prefs(customId = t * 100L + i))
                done.countDown()
            }.also { it.start() }
        }
        start.countDown()
        assertTrue("并发保存在 60s 内未收敛", done.await(60, java.util.concurrent.TimeUnit.SECONDS))
        workers.forEach { it.join() }
        for (t in 0 until threads) for (i in 0 until perThread) {
            assertEquals(
                "并发写丢了文章 ${t * 1000L + i} 的阅读存档",
                t * 100L + i,
                store.get(t * 1000L + i)?.occlusionCustomConfigId
            )
        }
    }
}
