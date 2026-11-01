// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReadingSelection] 的边界切分测试。
 *
 * 长按选错范围是这类功能最刺眼的 bug：用户只想划一句话，结果带上了上一句的句号，
 * 或者英文单词被切成半个。这里把口径钉死，渲染层只负责把坐标换成偏移。
 */
class ReadingSelectionTest {

    @Test
    fun `中文长按选中整句`() {
        val text = "第一句。第二句。第三句。"
        val r = ReadingSelection.rangeAt(text, 5) // 落在「二」附近
        assertEquals("第二句。", ReadingSelection.textOf(text, r))
    }

    @Test
    fun `落点在句号上归属前一句`() {
        val text = "第一句。第二句。"
        val r = ReadingSelection.rangeAt(text, 3) // 正好在第一句的句号上
        assertEquals("第一句。", ReadingSelection.textOf(text, r))
    }

    @Test
    fun `英文长按选词而不是整句`() {
        val text = "The quick brown fox."
        val r = ReadingSelection.rangeAt(text, 6) // 落在 quick 里
        assertEquals("quick", ReadingSelection.textOf(text, r))
    }

    @Test
    fun `英文句子按句点切 且不把缩写点当句末`() {
        val text = "He is Mr. Smith. Next one."
        // 落在 "Smith" 里属于第二句
        val r = ReadingSelection.sentenceRange(text, 10)
        val picked = ReadingSelection.textOf(text, r)
        assertTrue("应包含 Smith 所在的句子，实际：$picked", picked.contains("Smith"))
        assertTrue("不该跨到下一句，实际：$picked", !picked.contains("Next"))
    }

    @Test
    fun `换行也是句子边界`() {
        val text = "上半句\n下半句"
        val r = ReadingSelection.sentenceRange(text, 5)
        assertEquals("下半句", ReadingSelection.textOf(text, r))
    }

    @Test
    fun `区间规范化会去掉首尾空白`() {
        val text = "  hello  "
        val r = ReadingSelection.normalize(text, 0, text.length)
        assertEquals("hello", ReadingSelection.textOf(text, r))
    }

    @Test
    fun `越界与反向区间被夹回文本内`() {
        val text = "abc"
        // 反向（end < start）与越界都夹到文本范围：不崩、不返回负长度
        val reversed = ReadingSelection.normalize(text, 99, -5)
        assertEquals("abc", ReadingSelection.textOf(text, reversed))
        assertEquals("abc", ReadingSelection.textOf(text, ReadingSelection.normalize(text, 0, 99)))
    }

    @Test
    fun `纯空白不可选`() {
        assertFalse(ReadingSelection.isSelectable("   ", 0 until 3))
        assertFalse(ReadingSelection.isSelectable("", 0 until 0))
        assertTrue(ReadingSelection.isSelectable("字", 0 until 1))
    }

    @Test
    fun `空文本不崩`() {
        assertEquals(0, ReadingSelection.rangeAt("", 5).first)
        assertTrue(ReadingSelection.rangeAt("", 5).isEmpty())
    }

    @Test
    fun `超长无句号段落按逗号切出可读的一段`() {
        // 构造一段 150 字、只有逗号没有句号的文字（PDF 导入常见）
        val head = "甲".repeat(60)
        val tail = "乙".repeat(60)
        val text = "$head，$tail"
        val r = ReadingSelection.sentenceRange(text, 10)
        val picked = ReadingSelection.textOf(text, r)
        assertTrue("应切到逗号处而不是整段：长度 ${picked.length}", picked.length <= 130)
        assertTrue("应当落在前半段", picked.all { it == '甲' || it == '，' })
    }
}
