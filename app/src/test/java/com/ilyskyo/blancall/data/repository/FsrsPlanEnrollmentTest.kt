// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 计划成员表（`fsrs_state.json` v3）的回归测试。
 *
 * 重点不是「enroll 能写进去」，而是三件更容易出事的事：
 * 1. 读旧文件不丢数据，且既有成员的判定口径不变（升级后计划一条不能少）；
 * 2. 加入/移除幂等，重复点按不会把文件写坏；
 * 3. 删文章时成员记录连带走，否则任务流会留一张永远点不开的卡。
 */
class FsrsPlanEnrollmentTest {

    private fun newFile(initialJson: String? = null): File {
        val dir = Files.createTempDirectory("blancall-plan-test").toFile()
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
    fun `v2 文件没有成员表时口径退回 reviewCount`() {
        val file = newFile(
            """{"version":2,"7":{"difficulty":5.0,"stability":3.0,"due":123,"lastReview":100,"reviewCount":2,"lapses":1}}"""
        )
        val store = loaded(file)
        assertTrue("练过的文章仍在计划里", store.isInPlan(7L))
        assertFalse("没写过的文章不在计划里", store.isInPlan(8L))
        assertTrue("旧文件没有成员表，读出来必须是空表", store.enrolledArticles().isEmpty())
    }

    @Test
    fun `enroll 幂等且冷启后仍在计划里`() {
        val file = newFile()
        val store = loaded(file)
        assertTrue("首次加入应返回 true", store.enroll(5L, 1_000L))
        assertFalse("重复加入不再写盘", store.enroll(5L, 2_000L))
        assertTrue(store.isInPlan(5L))
        assertEquals("保留首次加入时刻", 1_000L, store.enrolledArticles()[5L])

        // 模拟冷启：新实例重读同一份文件
        val reopened = loaded(file)
        assertTrue("加入状态必须落盘", reopened.isInPlan(5L))
        assertEquals(1_000L, reopened.enrolledArticles()[5L])
        assertTrue("落盘版本号应升到 3", file.readText().contains("\"version\":3"))
    }

    @Test
    fun `练过的文章无需再 enroll`() {
        val file = newFile()
        val store = loaded(file)
        store.save(9L, FsrsEngine.CardState(due = 500L, reviewCount = 1, lastReview = 100L))
        assertFalse("已在计划里就不该再写成员表", store.enroll(9L, 1_000L))
        assertTrue(store.enrolledArticles().isEmpty())
        assertTrue(store.isInPlan(9L))
    }

    @Test
    fun `unenroll 只删成员表 不影响练过的文章`() {
        val file = newFile()
        val store = loaded(file)
        store.enroll(3L, 1_000L)
        assertTrue(store.unenroll(3L))
        assertFalse("移除后不在计划里", store.isInPlan(3L))
        assertFalse("重复移除返回 false", store.unenroll(3L))

        store.save(4L, FsrsEngine.CardState(due = 500L, reviewCount = 2, lastReview = 100L))
        assertFalse("练过的文章没有成员记录可删", store.unenroll(4L))
        assertTrue("但它仍靠 reviewCount 留在计划里", store.isInPlan(4L))
    }

    @Test
    fun `删除文章连带清掉成员记录`() {
        val file = newFile()
        val store = loaded(file)
        store.enroll(11L, 1_000L)
        store.remove(11L)
        assertFalse(store.isInPlan(11L))
        assertTrue("成员表不能留孤儿", store.enrolledArticles().isEmpty())
        assertTrue("落盘也必须是空的", !loaded(file).isInPlan(11L))
    }

    @Test
    fun `加入但没练过的文章可以暂停与改期`() {
        val now = 10_000L
        val file = newFile()
        val store = loaded(file)
        store.enroll(21L, now)

        assertTrue("未练成员也应能暂停", store.pauseArticle(21L, now))
        assertTrue(store.isArticlePaused(21L))
        assertTrue("暂停不改变成员资格", store.isInPlan(21L))

        // 暂停中改期：写绝对落点，恢复后落到那一天而不是「加入时刻」
        val target = now + 3 * 24 * 60 * 60 * 1000L
        assertTrue(store.rescheduleArticle(21L, target, now))
        assertTrue("恢复应成功", store.resumeArticle(21L, now))
        assertFalse(store.isArticlePaused(21L))
        assertEquals("改期结果要落到成员表的时刻上", target, store.enrolledArticles()[21L])
    }

    @Test
    fun `不在计划的文章暂停与改期都被拒绝`() {
        val file = newFile()
        val store = loaded(file)
        assertFalse(store.pauseArticle(31L, 1_000L))
        assertFalse(store.rescheduleArticle(31L, 5_000L, 1_000L))
    }

    @Test
    fun `成员表与文章状态同文件共存`() {
        val file = newFile()
        val store = loaded(file)
        store.save(41L, FsrsEngine.CardState(due = 500L, reviewCount = 3, lastReview = 100L))
        store.enroll(42L, 1_000L)

        val reopened = loaded(file)
        assertNotNull("文章状态不能被成员表挤掉", reopened.get(41L))
        assertEquals(3, reopened.get(41L)!!.reviewCount)
        assertTrue("成员记录同样要读回来", reopened.isInPlan(42L))
    }
}
