// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SentenceSplitter 切句质量回归测试 */
class SentenceSplitterTest {

    @Test
    fun `中文句末标点切句`() {
        val text = "床前明月光。疑是地上霜！举头望明月？"
        val sentences = SentenceSplitter.split(text)
        assertEquals(3, sentences.size)
        assertTrue(sentences[0].startsWith("床前明月光"))
        assertTrue(sentences[2].startsWith("举头望明月"))
    }

    @Test
    fun `标题行独立成句`() {
        val text = "阿房宫赋\n六王毕，四海一。蜀山兀，阿房出。"
        val sentences = SentenceSplitter.split(text)
        assertEquals("阿房宫赋", sentences.first().trim())
    }

    @Test
    fun `换行按句切分可开关`() {
        val text = "第一句没有句号\n第二句也没有句号"
        val withNewline = SentenceSplitter.split(text, treatNewlineAsSentence = true)
        assertTrue(withNewline.size >= 2)
        // 关闭换行切句时两行合并为一个（无句末标点）
        val without = SentenceSplitter.split(text, treatNewlineAsSentence = false)
        assertEquals(1, without.size)
    }

    @Test
    fun `英文缩写与小数不误切`() {
        val text = "Prof. Smith paid 3.5 dollars. Then he left."
        val sentences = SentenceSplitter.split(text)
        // "Prof." 与 "3.5" 不应被当成句末
        assertTrue(sentences.any { it.contains("Prof. Smith") || it.contains("Prof") })
        assertTrue(sentences.first().contains("3.5"))
    }

    @Test
    fun `空文本返回空列表且拼接守恒`() {
        assertEquals(0, SentenceSplitter.split("").size)
        val text = "一句。两句。三句。"
        val joined = SentenceSplitter.split(text).joinToString("")
        assertEquals("一句。两句。三句。", joined)
    }

    @Test
    fun `splitWithPositions 与 split 结果一致且位置有效`() {
        val text = "孟德斯鸠说。卢梭也说！"
        val withPos = SentenceSplitter.splitWithPositions(text)
        val plain = SentenceSplitter.split(text)
        assertEquals(plain.size, withPos.size)
        withPos.forEach { sp ->
            assertTrue(sp.startIndex in 0 until text.length)
            assertTrue(sp.endIndex in 1..text.length)
            assertTrue(sp.endIndex > sp.startIndex)
            // 句子文本应与原文对应区间一致（允许 trim 差异）
            assertEquals(sp.text.trim(), text.substring(sp.startIndex, sp.endIndex).trim())
        }
    }
}
