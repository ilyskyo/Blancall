// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SentenceCardStore] 磁盘往返测试（JVM 版 org.json + 临时目录）。
 * 单例与目录经 [SentenceCardTestDir] 全测试进程共享；各用例自带 date 前缀隔离，互不依赖执行顺序。
 *
 * 覆盖：往返序列化、同日幂等（setTodayIfAbsent）、按文章清理、损坏文件降级（不抛异常、可重抽）。
 */
class SentenceCardStoreTest {

    companion object {
        private val dir = SentenceCardTestDir.dir
        private val store = SentenceCardTestDir.store
    }

    private fun snapshot(
        date: String,
        key: String = "s:12:ab12cd34ef56ab78",
        articleId: Long = 12,
        text: String = "春眠不觉晓，处处闻啼鸟。",
    ) = SentenceCardStore.Snapshot(
        date = date,
        key = key,
        articleId = articleId,
        text = text,
        start = 0,
        end = text.length,
        title = "春晓",
    )

    @Test
    fun `save new then read back round trip`() {
        val s = snapshot("2026-01-01")
        val saved = store.setTodayIfAbsent(s.date, s)
        assertEquals(s, saved)
        assertEquals(s, store.today())
    }

    @Test
    fun `setTodayIfAbsent 同日幂等保留首个快照`() {
        val first = snapshot("2026-02-02", text = "第一个句子内容。")
        val second = snapshot("2026-02-02", key = "s:12:ffffffffffffffff", text = "第二个句子内容。")
        val saved1 = store.setTodayIfAbsent(first.date, first)
        val saved2 = store.setTodayIfAbsent(second.date, second)
        assertEquals(first, saved1)
        assertEquals(first, saved2)
        assertEquals(first, store.today())
    }

    @Test
    fun `clearIfArticle 仅清理归属文章的快照`() {
        val s = snapshot("2026-03-03", articleId = 77)
        store.setTodayIfAbsent(s.date, s)
        store.clearIfArticle(78)
        assertNotNull("非归属文章不应清理", store.today())
        store.clearIfArticle(77)
        assertNull(store.today())
    }

    @Test
    fun `损坏文件读取返回 null 且可重新抽句写盘`() {
        val file = java.io.File(dir, "sentence_card.json")
        file.writeText("{ 这不是合法 JSON")
        assertNull(store.today())
        val s = snapshot("2026-04-04")
        store.setTodayIfAbsent(s.date, s)
        assertEquals(s, store.today())
    }
}
