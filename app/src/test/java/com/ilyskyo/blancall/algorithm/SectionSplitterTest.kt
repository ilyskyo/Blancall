// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SectionSplitter 分段与错误率排序回归测试 */
class SectionSplitterTest {

    @Test
    fun `空行分段`() {
        val text = "第一段第一句。第一段第二句。\n\n第二段第一句。\n\n第三段第一句。"
        val sections = SectionSplitter.split(text, minSectionChars = 5)
        assertTrue(sections.size >= 2)
        // 段落覆盖全文：首段起始 0，末段结束 = 文本长度
        assertEquals(0, sections.first().startChar)
        assertEquals(text.length, sections.last().endChar)
    }

    @Test
    fun `段落字符区间拼接守恒`() {
        val text = "甲段内容一二三。\n\n乙段内容四五六。\n\n丙段内容七八九。"
        val sections = SectionSplitter.split(text, minSectionChars = 5)
        val joined = sections.joinToString("") { it.text }
        assertEquals(text.filter { !it.isWhitespace() }, joined.filter { !it.isWhitespace() })
    }

    @Test
    fun `短段落被合并进前段`() {
        val text = "这是一个足够长的段落，包含多个句子用来超过最小长度限制。\n\n短。\n\n这也是一个足够长的段落，包含多个句子用来超过最小长度限制。"
        val sections = SectionSplitter.split(text, minSectionChars = 30)
        // 「短。」不足以独立成段，应被并入相邻段
        assertTrue(sections.none { it.text.trim() == "短。" })
    }

    @Test
    fun `rankByErrorRate 按错误率降序`() {
        val text = "第一段甲。第一段乙。\n\n第二段甲。第二段乙。\n\n第三段甲。第三段乙。"
        val sections = SectionSplitter.split(text, minSectionChars = 5)
        // 手工构造：全文句索引 0/1 属段0，2/3 属段1，4/5 属段2（近似）
        val rates = mapOf(0 to 0.9f, 1 to 0.9f, 2 to 0.1f, 3 to 0.1f, 4 to 0.5f, 5 to 0.5f)
        val ranked = SectionSplitter.rankByErrorRate(sections, rates)
        assertEquals(sections.size, ranked.size)
        assertEquals(ranked.size, ranked.sortedByDescending { it.errorRate }.size)
        assertTrue(ranked.first().errorRate >= ranked.last().errorRate)
    }

    @Test
    fun `无错误率数据时排序稳定且错误率为零`() {
        val text = "甲段内容一二三四五六七八九十。\n\n乙段内容一二三四五六七八九十。"
        val sections = SectionSplitter.split(text, minSectionChars = 5)
        val ranked = SectionSplitter.rankByErrorRate(sections, emptyMap())
        assertTrue(ranked.all { it.errorRate == 0f })
    }
}
