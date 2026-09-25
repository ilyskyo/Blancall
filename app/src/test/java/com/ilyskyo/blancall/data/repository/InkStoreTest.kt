// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * 错题墨迹存档（[InkStore]）的往返 / 抽稀 / 定点化 / 清理测试。
 *
 * 墨迹是展示性数据：**写出任何问题都不允许影响练习主流程** ——
 * 所以这里的容错用例（坏文件、缺字段、不存在）与功能用例同等重要。
 */
class InkStoreTest {

    private fun newStore(): InkStore =
        InkStore(File(Files.createTempDirectory("blancall-ink").toFile(), "ink"))

    /** 造一条水平笔画：x 从 [x0] 到 [x0]+len，步距 step（px），y 固定。 */
    private fun stroke(x0: Float, len: Float, y: Float, step: Float = 1f, boardW: Int = 1000, boardH: Int = 480): List<Offset> {
        val pts = ArrayList<Offset>()
        var x = x0
        while (x <= x0 + len) {
            pts.add(Offset(x, y))
            x += step
        }
        return pts
    }

    private fun blank(index: Int, strokes: List<List<Offset>>, boardW: Int = 1000, boardH: Int = 480) =
        BlankInk(index, listOf(InkBatch(boardW, boardH, strokes)))

    // ────────────── 往返 ──────────────

    @Test
    fun `保存后可读回且坐标往返误差在 1px 内`() {
        val store = newStore()
        val mainStroke = stroke(100f, 200f, 240f, step = 3f)
        val strokes = listOf(
            mainStroke,
            listOf(Offset(500f, 100f)) // 单点笔
        )
        store.save(recordId = 42, articleId = 7, blanks = listOf(blank(3, strokes)))

        val payload = store.load(42)
        assertNotNull(payload)
        assertEquals(7L, payload!!.articleId)
        assertEquals(1, payload.blanks.size)
        val loaded = payload.blanks[0]
        assertEquals(3, loaded.blankIndex)
        assertEquals(1, loaded.batches.size)
        assertEquals(2, loaded.batches[0].strokes.size)

        // 首末点误差 ≤ 1px（定点化刻度 = 0.1px，抽稀不动首末）
        val first = loaded.batches[0].strokes[0]
        assertTrue("首点误差应 ≤ 1px", abs(first.first().x - 100f) <= 1f && abs(first.first().y - 240f) <= 1f)
        assertTrue("末点误差应 ≤ 1px", abs(first.last().x - mainStroke.last().x) <= 1f)
        // 单点笔保留且仍是单点
        assertEquals(1, loaded.batches[0].strokes[1].size)
        assertTrue(store.has(42))
    }

    @Test
    fun `抽稀后点数明显减少但首末点保留`() {
        // 步距 1px < 阈值 2px ⇒ 中间点大量丢弃；首末必须原样保留
        val dense = stroke(0f, 100f, 50f, step = 1f)
        val thinned = thinPoints(dense)
        assertTrue("应明显变少（实际 ${thinned.size}/${dense.size}）", thinned.size < dense.size / 2 + 2)
        assertEquals(dense.first(), thinned.first())
        assertEquals(dense.last(), thinned.last())
        // 单点 / 双点原样
        assertEquals(1, thinPoints(listOf(Offset(1f, 2f))).size)
        assertEquals(2, thinPoints(listOf(Offset(1f, 2f), Offset(3f, 4f))).size)
    }

    @Test
    fun `定点化边界与还原精度`() {
        assertEquals(0, toFixed(0f, 1000))
        assertEquals(10000, toFixed(1000f, 1000))
        assertEquals(0, toFixed(-5f, 1000))       // 越界夹紧
        assertEquals(10000, toFixed(9999f, 1000)) // 越界夹紧

        val boardW = 1080
        val fx = toFixed(543.21f, boardW)
        val restored = fx / 10000f * boardW
        assertTrue("还原误差应 ≤ 半刻度", abs(restored - 543.21f) <= boardW / 10000f / 2f + 0.01f)
    }

    // ────────────── 容错 ──────────────

    @Test
    fun `不存在或损坏的文件返回 null、不抛异常`() {
        val dir = File(Files.createTempDirectory("blancall-ink-broken").toFile(), "ink")
        val store = InkStore(dir)
        assertNull(store.load(999))
        assertFalse(store.has(999))

        // 写一个坏文件
        dir.mkdirs()
        File(dir, "5.json").writeText("{ broken json")
        assertNull("坏文件应静默返回 null", store.load(5))
        assertTrue("坏文件存在性仍为 true（只做文件探测）", store.has(5))
    }

    @Test
    fun `空内容或全无有效空时不落盘`() {
        val store = newStore()
        store.save(recordId = 1, articleId = 1, blanks = emptyList())
        assertFalse(store.has(1))

        // 空的 batches（无笔画）→ 无有效内容 → 不落盘
        val emptyBatch = BlankInk(0, listOf(InkBatch(1000, 480, emptyList())))
        store.save(recordId = 2, articleId = 1, blanks = listOf(emptyBatch))
        assertFalse(store.has(2))
    }

    // ────────────── 删除与清理 ──────────────

    @Test
    fun `按文章删除只清理该文章的墨迹`() {
        val store = newStore()
        store.save(recordId = 11, articleId = 100, blanks = listOf(blank(0, listOf(stroke(10f, 50f, 100f)))))
        store.save(recordId = 12, articleId = 200, blanks = listOf(blank(0, listOf(stroke(10f, 50f, 100f)))))
        assertTrue(store.has(11))
        assertTrue(store.has(12))

        store.deleteByArticleId(100)
        assertFalse("被删文章的墨迹应清空", store.has(11))
        assertTrue("其它文章的墨迹不受影响", store.has(12))
        assertNotNull(store.load(12))
    }

    @Test
    fun `超过上限时按最旧（recordId 最小）清理`() {
        val store = newStore()
        for (id in 1L..5L) {
            store.save(recordId = id, articleId = id, blanks = listOf(blank(0, listOf(stroke(10f, 50f, 100f)))))
        }
        store.cleanup(maxFiles = 3)
        assertFalse(store.has(1))
        assertFalse(store.has(2))
        assertTrue(store.has(3))
        assertTrue(store.has(4))
        assertTrue(store.has(5))
    }
}
