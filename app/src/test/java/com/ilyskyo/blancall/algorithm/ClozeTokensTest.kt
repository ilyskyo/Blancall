// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ClozeTokens 行为锁定：四级粒度切分、标点归并、英文整体、区间对齐校验 */
class ClozeTokensTest {

    @Test
    fun `levelName mapping`() {
        assertEquals("复句", ClozeTokens.levelName(0))
        assertEquals("分句", ClozeTokens.levelName(1))
        assertEquals("字词", ClozeTokens.levelName(2))
        assertEquals("单字", ClozeTokens.levelName(3))
    }

    @Test
    fun `level0 is whole sentence`() {
        val t = ClozeTokens.tokensFor("你好世界。", 0)
        assertEquals(1, t.size)
        assertEquals("你好世界。", t[0].text)
        assertEquals(0, t[0].range.first)
        assertEquals(4, t[0].range.last)
    }

    @Test
    fun `level1 splits by punctuation and attaches punct to previous token`() {
        val t = ClozeTokens.tokensFor("你好，世界。再见！", 1)
        assertEquals(listOf("你好，", "世界。", "再见！"), t.map { it.text })
        // 区间连续覆盖全句
        assertEquals(0, t.first().range.first)
        assertEquals(8, t.last().range.last)
    }

    @Test
    fun `level1 single clause falls back to whole sentence`() {
        val t = ClozeTokens.tokensFor("你好世界", 1)
        assertEquals(1, t.size)
        assertEquals("你好世界", t[0].text)
    }

    @Test
    fun `level2 chunks chinese into pairs`() {
        val t = ClozeTokens.tokensFor("你好世界呀", 2)
        assertEquals(listOf("你好", "世界", "呀"), t.map { it.text })
    }

    @Test
    fun `level3 splits chinese into chars`() {
        val t = ClozeTokens.tokensFor("你好世界", 3)
        assertEquals(listOf("你", "好", "世", "界"), t.map { it.text })
    }

    @Test
    fun `english word stays whole at char and word levels`() {
        listOf(2, 3).forEach { level ->
            val t = ClozeTokens.tokensFor("hi你好", level)
            assertTrue(t.first().text == "hi")
            assertEquals(0, t.first().range.first)
            assertEquals(1, t.first().range.last)
        }
    }

    @Test
    fun `level1 without punctuation keeps whole sentence`() {
        val t = ClozeTokens.tokensFor("hi你好", 1)
        assertEquals(1, t.size)
        assertEquals("hi你好", t[0].text)
    }

    @Test
    fun `punctuation merges into previous token at char level`() {
        val t = ClozeTokens.tokensFor("你好，世界", 3)
        assertEquals(listOf("你", "好，", "世", "界"), t.map { it.text })
    }

    @Test
    fun `tokens tile the sentence without gaps`() {
        val s = "化学平衡常数K，只与温度有关 (temperature)！"
        listOf(1, 2, 3).forEach { level ->
            var cursor = 0
            ClozeTokens.tokensFor(s, level).forEach { t ->
                assertEquals(cursor, t.range.first)
                cursor = t.range.last + 1
            }
            assertEquals(s.length, cursor)
        }
    }

    @Test
    fun `rangesAligned accepts whole-token unions`() {
        val s = "你好世界"
        // 单字级：连续 3 个字
        assertTrue(ClozeTokens.rangesAligned(s, 3, listOf(0..2)))
        // 字词级：两个整块
        assertTrue(ClozeTokens.rangesAligned(s, 2, listOf(0..1, 2..3)))
    }

    @Test
    fun `rangesAligned rejects mid-token cuts`() {
        val s = "你好世界"
        // 字词级下切在块中间（单字）
        assertFalse(ClozeTokens.rangesAligned(s, 2, listOf(0..0)))
        // 越界区间
        assertFalse(ClozeTokens.rangesAligned(s, 3, listOf(0..9)))
    }

    @Test
    fun `restoreLevel refines until aligned`() {
        val s = "你好世界呀"
        // 字词级下 0..2 切在「世界」块中间 → 细化到单字级
        assertEquals(3, ClozeTokens.restoreLevel(s, 2, listOf(0..2)))
        // 本来就对齐则维持原粒度
        assertEquals(2, ClozeTokens.restoreLevel(s, 2, listOf(0..1)))
        // 空区间集：直接维持原粒度
        assertEquals(1, ClozeTokens.restoreLevel(s, 1, emptyList()))
    }
}
