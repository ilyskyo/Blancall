// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskToClozeTest {

    private val content = "山不在高，有仙则名。水不在深，有龙则灵。\n\n斯是陋室，惟吾德馨。"
    private val sentences = SentenceSplitter.splitWithPositions(content)

    @Test
    fun `段内遮挡映射到所属句子`() {
        // 第一段从 0 开始，「山不在高」= [0,4)
        val ranges = MaskToCloze.convert(0, listOf(0 until 4), sentences)
        assertEquals(listOf(MaskToCloze.SentenceRange(0, 0, 4)), ranges)
    }

    @Test
    fun `跨句遮挡按句拆开`() {
        val firstEnd = sentences[0].endIndex
        val ranges = MaskToCloze.convert(0, listOf((firstEnd - 2) until (firstEnd + 2)), sentences)
        assertEquals(2, ranges.size)
        assertEquals(0, ranges[0].sentenceIndex)
        assertEquals(1, ranges[1].sentenceIndex)
        assertEquals(0, ranges[1].a)
        assertTrue(ranges[1].b > 0)
    }

    @Test
    fun `第二段起点偏移被正确加上`() {
        val paraStart = content.indexOf("斯是陋室")
        val ranges = MaskToCloze.convert(paraStart, listOf(0 until 4), sentences)
        assertEquals(2, ranges.single().sentenceIndex)
        assertEquals(0, ranges.single().a)
        assertEquals(4, ranges.single().b)
        assertEquals("斯是陋室", sentences[2].text.substring(0, 4))
    }

    @Test
    fun `空区间与越界不产生空位`() {
        assertTrue(MaskToCloze.convert(0, listOf(3 until 3), sentences).isEmpty())
        assertTrue(MaskToCloze.convert(0, listOf(IntRange(999, 1000)), sentences).isEmpty())
    }
}
