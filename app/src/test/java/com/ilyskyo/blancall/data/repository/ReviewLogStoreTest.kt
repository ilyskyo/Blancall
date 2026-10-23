// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ReviewLogStore] 的格式与守卫回归：
 * 追加可读回、高版本行被拒绝、损坏行不致命、软上限后停止追加。
 */
class ReviewLogStoreTest {

    private fun tempFile(name: String = "fsrs_reviews.jsonl"): File =
        File(Files.createTempDirectory("blancall-review-log").toFile(), name)

    private fun state() = FsrsEngine.CardState(
        difficulty = 5.5, stability = 12.25, due = 1_800_000_000_000L,
        lastReview = 1_700_000_000_000L, reviewCount = 7, lapses = 2
    )

    @Test
    fun `追加一行可原样读回`() {
        val f = tempFile()
        assertTrue(
            ReviewLogStore.append(
                f, key = "s:12:ab12cd34ef56ab78", ts = 1_750_000_000_000L,
                rating = 3, elapsedMs = 5_000L, state = state()
            )
        )
        val events = ReviewLogStore.read(f)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("s:12:ab12cd34ef56ab78", e.key)
        assertEquals(1_750_000_000_000L, e.ts)
        assertEquals(3, e.rating)
        assertEquals(5_000L, e.elapsedMs)
        assertEquals(5.5, e.state.difficulty, 1e-9)
        assertEquals(12.25, e.state.stability, 1e-9)
        assertEquals(7, e.state.reviewCount)
        assertEquals(2, e.state.lapses)
    }

    @Test
    fun `批量追加一次写入且顺序可读回`() {
        val f = tempFile()
        val events = (1..3).map { i ->
            ReviewLogStore.ReviewEvent(
                key = "s:1:k$i", ts = 100L + i, rating = 3,
                elapsedMs = i.toLong(), state = state()
            )
        }
        assertTrue(ReviewLogStore.appendAll(f, events))
        assertEquals(
            listOf("s:1:k1", "s:1:k2", "s:1:k3"),
            ReviewLogStore.read(f).map { it.key }
        )
    }

    @Test
    fun `多条按文件顺序读出`() {
        val f = tempFile()
        ReviewLogStore.append(f, "a:1", 1L, 2, 0L, state())
        ReviewLogStore.append(f, "s:1:xx", 2L, 3, 100L, state())
        val events = ReviewLogStore.read(f)
        assertEquals(listOf("a:1", "s:1:xx"), events.map { it.key })
    }

    @Test
    fun `高版本行被拒绝并回调，不污染其余事件`() {
        val f = tempFile()
        ReviewLogStore.append(f, "a:1", 1L, 3, 0L, state())
        // 手工追加一个未来版本的行（模拟新版写下的数据）
        f.appendText("""{"v":99,"key":"a:2","ts":2}""" + "\n")
        val skipped = mutableListOf<Pair<String, ReviewLogStore.SkipReason>>()
        val events = ReviewLogStore.read(f) { line, reason -> skipped += line to reason }
        assertEquals(1, events.size)
        assertEquals("a:1", events[0].key)
        assertEquals(1, skipped.size)
        assertEquals(ReviewLogStore.SkipReason.HIGH_VERSION, skipped[0].second)
    }

    @Test
    fun `损坏行与空行不致命`() {
        val f = tempFile()
        ReviewLogStore.append(f, "a:1", 1L, 3, 0L, state())
        f.appendText("not a json\n")
        f.appendText("\n")
        f.appendText("""{"v":1}""" + "\n")   // 缺 key → malformed
        ReviewLogStore.append(f, "a:2", 2L, 3, 0L, state())
        val skipped = mutableListOf<ReviewLogStore.SkipReason>()
        val events = ReviewLogStore.read(f) { _, r -> skipped += r }
        assertEquals(listOf("a:1", "a:2"), events.map { it.key })
        assertEquals(2, skipped.count { it == ReviewLogStore.SkipReason.MALFORMED })
    }

    @Test
    fun `软上限后停止追加且不清旧行`() {
        val f = tempFile()
        // 用小上限把文件撑到边界
        ReviewLogStore.append(f, "a:1", 1L, 3, 0L, state(), maxBytes = 1L)
        // 已经有一行（非空文件）→ 下一次起不再追加
        val ok = ReviewLogStore.append(f, "a:2", 2L, 3, 0L, state(), maxBytes = 1L)
        assertFalse("超过软上限后必须停止追加", ok)
        assertEquals(1, ReviewLogStore.read(f).size)
    }

    @Test
    fun `文件不存在读出空表`() {
        assertTrue(ReviewLogStore.read(tempFile("missing.jsonl")).isEmpty())
    }

    private fun File.appendText(text: String) {
        parentFile?.mkdirs()
        java.io.FileOutputStream(this, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
