// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 任务流撤销（[FsrsStateStore.snapshotArticles] / [FsrsStateStore.restoreArticles]）的回归测试。
 *
 * 重点不是「撤回去了」，而是三件更容易错的事：
 * 1. 撤销写的是**按下按钮之前**的样子 —— 暂停、练没练过、固定日期三个维度都要还原；
 * 2. 撤销要落盘，否则冷启后任务又跑到明天去，用户会以为撤销没生效；
 * 3. 操作前不存在的记录，撤销后必须回到不存在，而不是留一条脏数据。
 */
class FsrsUndoSnapshotTest {

    private fun newFile(initialJson: String? = null): File {
        val dir = Files.createTempDirectory("blancall-undo-test").toFile()
        val file = File(dir, "fsrs_state.json")
        if (initialJson != null) file.writeText(initialJson)
        return file
    }

    private fun loaded(file: File): FsrsStateStore {
        val store = FsrsStateStore.createForTest(file.absolutePath)
        runBlocking { store.awaitLoaded() }
        return store
    }

    @Test
    fun `移到明天后撤销把 due 还原并落盘`() {
        val file = newFile()
        val store = loaded(file)
        store.save(7L, FsrsEngine.CardState(due = 500L, reviewCount = 1, lastReview = 100L))

        val snapshot = store.snapshotArticles(listOf(7L))
        store.rescheduleArticle(7L, 9_000L, 1_000L)
        assertEquals("前置条件：改期真的写进去了", 9_000L, store.get(7L)?.due)

        assertTrue(store.restoreArticles(snapshot))
        assertEquals("撤销后回到原来的到期时间", 500L, store.get(7L)?.due)

        val reopened = loaded(file)
        assertEquals("撤销必须落盘，冷启后仍是原值", 500L, reopened.get(7L)?.due)
    }

    @Test
    fun `刚暂停的文章撤销后回到未暂停`() {
        val file = newFile()
        val store = loaded(file)
        store.save(7L, FsrsEngine.CardState(due = 5_000L, reviewCount = 1, lastReview = 100L))

        val snapshot = store.snapshotArticles(listOf(7L))
        assertTrue(store.pauseArticle(7L, 1_000L))
        assertTrue("前置条件：暂停生效", store.isArticlePaused(7L))

        assertTrue(store.restoreArticles(snapshot))
        assertFalse("撤销后不该还在暂停表里", store.isArticlePaused(7L))
        assertTrue("暂停表必须整条移除，而不是留一条空记录", store.pausedArticles().isEmpty())
    }

    @Test
    fun `暂停中改期后撤销把固定日期一起还原`() {
        val file = newFile()
        val store = loaded(file)
        store.save(7L, FsrsEngine.CardState(due = 5_000L, reviewCount = 1, lastReview = 100L))
        store.pauseArticle(7L, 1_000L) // 冻结剩余 4000ms

        val snapshot = store.snapshotArticles(listOf(7L))
        store.rescheduleArticle(7L, 50_000L, 1_000L)
        assertEquals("前置条件：固定日期已写入", 50_000L, store.pausedArticles()[7L]?.fixedDue)

        assertTrue(store.restoreArticles(snapshot))
        val pause = store.pausedArticles()[7L]
        assertEquals("固定日期要退回「没设过」", 0L, pause?.fixedDue)
        assertEquals("冻结的剩余期也要还原", 4_000L, pause?.remainingMs)
    }

    @Test
    fun `没练过的计划成员改期后撤销把加入时刻还原`() {
        val file = newFile()
        val store = loaded(file)
        store.enroll(9L, 1_000L)

        val snapshot = store.snapshotArticles(listOf(9L))
        store.rescheduleArticle(9L, 99_000L, 1_000L)
        assertEquals(99_000L, store.enrolledArticles()[9L])

        assertTrue(store.restoreArticles(snapshot))
        assertEquals("未练成员的到期时间就是加入时刻，撤销要退回原值", 1_000L, store.enrolledArticles()[9L])
        assertNull("这类成员没有文章级状态，撤销后也不该凭空生成", store.get(9L))
    }

    @Test
    fun `操作前不存在的记录撤销后回到不存在`() {
        val file = newFile()
        val store = loaded(file)

        val snapshot = store.snapshotArticles(listOf(11L))
        store.save(11L, FsrsEngine.CardState(due = 2_000L, reviewCount = 1, lastReview = 100L))
        assertTrue(store.get(11L) != null)

        assertTrue(store.restoreArticles(snapshot))
        assertNull("快照里是 null ⇒ 撤销要删掉这条，不是留着", store.get(11L))
    }

    @Test
    fun `空快照不写盘`() {
        val file = newFile()
        val store = loaded(file)
        store.save(7L, FsrsEngine.CardState(due = 500L, reviewCount = 1, lastReview = 100L))
        val before = file.readText()

        assertFalse("空快照没有可回滚的东西", store.restoreArticles(emptyList()))
        assertEquals("不该为一次空撤销整本重写文件", before, file.readText())
    }

    @Test
    fun `撤销幂等且同一份快照可连续写回`() {
        val file = newFile()
        val store = loaded(file)
        store.save(7L, FsrsEngine.CardState(due = 500L, reviewCount = 1, lastReview = 100L))

        val snapshot = store.snapshotArticles(listOf(7L))
        store.rescheduleArticle(7L, 9_000L, 1_000L)
        store.rescheduleArticle(7L, 12_000L, 1_000L)

        assertTrue(store.restoreArticles(snapshot))
        assertTrue(store.restoreArticles(snapshot))
        assertEquals("写两遍结果一样", 500L, store.get(7L)?.due)
    }
}
