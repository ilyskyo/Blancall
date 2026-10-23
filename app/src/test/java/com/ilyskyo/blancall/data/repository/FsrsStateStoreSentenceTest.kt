// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [FsrsStateStore] 句子命名空间扩展的回归测试。
 *
 * 注意：getInstance 为进程级单例（只认首个路径），故本类使用「伴生对象 + 独立临时目录」持有实例，
 * 且在取实例前预置"旧格式文件（仅数值键）"以验证存量数据兼容加载。
 *
 * 覆盖：旧格式兼容、句子状态往返、与文章状态同文件共存、allStates 口径不变、
 * removeSentencesForArticle 前缀精确清理、非法键拒写。
 */
class FsrsStateStoreSentenceTest {

    companion object {
        private val dir = Files.createTempDirectory("blancall-fsrs-store-test").toFile()
        private val stateFile = File(dir, "fsrs_state.json")

        private val store: FsrsStateStore

        init {
            // 预置旧格式文件（仅文章数值键）：验证升级后旧数据可完整加载
            stateFile.writeText(
                """{"7":{"difficulty":5.0,"stability":3.0,"due":123456,"lastReview":100000,"reviewCount":2,"lapses":1}}"""
            )
            store = FsrsStateStore.getInstance(stateFile.absolutePath)
        }

        private fun await() = runBlocking { store.awaitLoaded() }
    }

    @Test
    fun `旧格式文件加载文章状态且未存过的句子键为空`() {
        await()
        val article = store.get(7L)
        assertNotNull("旧格式文章状态应完整加载", article)
        assertEquals(2, article!!.reviewCount)
        assertEquals(3.0, article.stability, 0.0)
        assertEquals(1, article.lapses)
        // 未存过的句子键返回 null（句子表按前缀分流，不污染文章表）
        assertNull(store.getSentence(SentenceSelector.sentenceKey(999, "从未保存过的句子内容")))
    }

    @Test
    fun `saveSentence 往返且与文章状态同文件共存`() {
        await()
        val key = SentenceSelector.sentenceKey(55, "落霞与孤鹜齐飞，秋水共长天一色。")
        val state = FsrsEngine.CardState(
            difficulty = 4.2, stability = 2.5, due = 888888L,
            lastReview = 777777L, reviewCount = 3, lapses = 1,
        )
        store.saveSentence(key, state)

        val loaded = store.getSentence(key)
        assertNotNull(loaded)
        assertEquals(4.2, loaded!!.difficulty, 1e-9)
        assertEquals(2.5, loaded.stability, 1e-9)
        assertEquals(888888L, loaded.due)
        assertEquals(777777L, loaded.lastReview)
        assertEquals(3, loaded.reviewCount)
        assertEquals(1, loaded.lapses)

        // 文章级状态不受影响（allStates 口径只含文章键）
        val article = store.get(7L)
        assertNotNull(article)
        assertEquals(2, article!!.reviewCount)
        assertTrue(store.allSentenceStates().containsKey(key))

        // 文件落盘后同时含数值键与句子键（命名空间共存）
        val raw = stateFile.readText()
        assertTrue("文件应含句子键: $key", raw.contains("\"$key\""))
        assertTrue("文件应含文章键", raw.contains("\"7\""))
    }

    @Test
    fun `removeSentencesForArticle 仅清理对应文章前缀`() {
        await()
        val k55a = SentenceSelector.sentenceKey(55, "测试句子内容甲。")
        val k55b = SentenceSelector.sentenceKey(55, "测试句子内容乙。")
        val k66 = SentenceSelector.sentenceKey(66, "测试句子内容丙。")
        store.saveSentence(k55a, FsrsEngine.CardState(reviewCount = 1, due = 1L, lastReview = 1L))
        store.saveSentence(k55b, FsrsEngine.CardState(reviewCount = 1, due = 1L, lastReview = 1L))
        store.saveSentence(k66, FsrsEngine.CardState(reviewCount = 1, due = 1L, lastReview = 1L))

        store.removeSentencesForArticle(55)

        assertNull(store.getSentence(k55a))
        assertNull(store.getSentence(k55b))
        assertNotNull("其它文章的句子状态不应被清理", store.getSentence(k66))
        // 文章级状态与清理无关
        assertNotNull(store.get(7L))
    }

    @Test
    fun `非法句子键拒写`() {
        await()
        store.saveSentence("12", FsrsEngine.CardState(reviewCount = 9))
        store.saveSentence("s:abc:zz", FsrsEngine.CardState(reviewCount = 9))
        store.saveSentence("s:12:", FsrsEngine.CardState(reviewCount = 9))
        assertNull(store.getSentence("12"))
        assertNull(store.getSentence("s:abc:zz"))
        assertNull(store.getSentence("s:12:"))
        assertTrue(store.allSentenceStates().values.none { it.reviewCount == 9 })
    }

    // ── 学习任务流：暂停 / 恢复 / 改期（与文章状态同文件同锁）──

    private val day = 24L * 60 * 60 * 1000

    private fun activeState(due: Long, lastReview: Long, reviewCount: Int = 2) =
        FsrsEngine.CardState(
            difficulty = 4.0,
            stability = 3.0,
            due = due,
            lastReview = lastReview,
            reviewCount = reviewCount,
        )

    @Test
    fun `暂停冻结剩余期并落盘恢复后从当下接续`() {
        await()
        val id = 9101L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 2 * day, lastReview = now - day))

        assertTrue(store.pauseArticle(id, now))
        assertTrue(store.isArticlePaused(id))
        assertEquals(2 * day, store.pausedArticles().getValue(id).remainingMs)
        assertTrue("暂停表必须与卡片状态同文件落盘", stateFile.readText().contains("\"paused\""))
        assertTrue(
            "暂停表是版本 2 的新增字段，版本号必须一起抬（旧版读到后才会拒绝写盘）",
            stateFile.readText().contains("\"version\":2")
        )
        // 暂停不改写原 due：恢复时才有「冻结」可谈
        assertEquals(now + 2 * day, store.get(id)!!.due)

        val resumeAt = now + 3 * day
        assertTrue(store.resumeArticle(id, resumeAt))
        assertTrue(!store.isArticlePaused(id))
        assertEquals(resumeAt + 2 * day, store.get(id)!!.due)
    }

    @Test
    fun `暂停中改期写的是恢复后的落点`() {
        await()
        val id = 9102L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 2 * day, lastReview = now - day))
        store.pauseArticle(id, now)

        val target = now + 10 * day
        assertTrue(store.rescheduleArticle(id, target, now + day))
        assertEquals(target, now + day + store.pausedArticles().getValue(id).remainingMs)

        store.resumeArticle(id, now + 2 * day)
        // 恢复时刻 + 冻结剩余期（改期时目标相对「改期那一刻」10 天）
        assertEquals(now + 11 * day, store.get(id)!!.due)
    }

    @Test
    fun `未暂停时改期直接写due`() {
        await()
        val id = 9103L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 2 * day, lastReview = now - day))
        val target = now + 6 * day
        assertTrue(store.rescheduleArticle(id, target, now))
        assertEquals(target, store.get(id)!!.due)
        assertTrue(!store.isArticlePaused(id))
    }

    @Test
    fun `暂停期间手动练习后恢复尊重较晚的推进`() {
        await()
        val id = 9104L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 2 * day, lastReview = now - day))
        store.pauseArticle(id, now)
        // 手动练习：lastReview 晚于暂停时刻，due 也被练习页推进
        store.save(
            id,
            activeState(due = now + 9 * day, lastReview = now + day, reviewCount = 3)
        )
        store.resumeArticle(id, now + 2 * day)
        assertEquals(now + 9 * day, store.get(id)!!.due)
        assertEquals(3, store.get(id)!!.reviewCount)
    }

    @Test
    fun `暂停期间手动练习后改期以新目标恢复`() {
        await()
        val id = 9107L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 2 * day, lastReview = now - day))
        store.pauseArticle(id, now)
        store.save(
            id,
            activeState(due = now + 9 * day, lastReview = now + day, reviewCount = 3)
        )
        val target = now + 20 * day
        assertTrue(store.rescheduleArticle(id, target, now + 2 * day))
        store.resumeArticle(id, now + 3 * day)
        assertEquals(
            "暂停中手动练过后，界面展示的是新 due，恢复落点必须跟它一致",
            target, store.get(id)!!.due
        )
    }

    @Test
    fun `删除文章连带清掉暂停`() {
        await()
        val id = 9105L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + day, lastReview = now - day))
        store.pauseArticle(id, now)
        assertTrue(store.isArticlePaused(id))
        store.remove(id)
        assertNull(store.get(id))
        assertTrue(!store.isArticlePaused(id))
    }

    @Test
    fun `重载后暂停表从磁盘恢复`() {
        await()
        val id = 9106L
        val now = System.currentTimeMillis()
        store.save(id, activeState(due = now + 4 * day, lastReview = now - day))
        store.pauseArticle(id, now)
        runBlocking { store.reload() }
        assertTrue(store.isArticlePaused(id))
        assertEquals(4 * day, store.pausedArticles().getValue(id).remainingMs)
    }
}
