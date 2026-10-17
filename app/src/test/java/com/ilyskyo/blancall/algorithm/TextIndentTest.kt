// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首行缩进补全与「已自带缩进则不再补」回归测试 */
class TextIndentTest {

    private val indent = "\u3000\u3000"

    @Test
    fun `未缩进的纯文本每段补两格`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        val out = applyFirstLineIndent(text)
        assertEquals("${indent}第一段。\n\n${indent}第二段。\n\n${indent}第三段。", out)
    }

    @Test
    fun `已自带缩进的纯文本整篇不再补`() {
        // 网络下载的中文小说典型形态：每段开头已缩进两字
        val text = "${indent}第一段。\n\n${indent}第二段。\n\n${indent}第三段。"
        assertEquals(text, applyFirstLineIndent(text))
        assertTrue(isDocumentIndented(text))
    }

    @Test
    fun `多数已缩进时整篇视为已缩进`() {
        // 首行是标题（无缩进），其余段落均已缩进 —— 不应因个别行把整篇判成未缩进
        val text = "标题\n\n${indent}第一段。\n\n${indent}第二段。"
        assertTrue(isDocumentIndented(text))
        assertEquals(text, applyFirstLineIndent(text))
    }

    @Test
    fun `多数未缩进时仍逐段补齐`() {
        val text = "第一段。\n\n第二段。\n\n${indent}第三段。"
        assertFalse(isDocumentIndented(text))
        assertEquals("${indent}第一段。\n\n${indent}第二段。\n\n${indent}第三段。", applyFirstLineIndent(text))
    }

    @Test
    fun `已缩进段落原样保留且结果幂等`() {
        val text = "${indent}第一段。\n\n第二段。"
        val once = applyFirstLineIndent(text)
        assertEquals("${indent}第一段。\n\n${indent}第二段。", once)
        assertEquals(once, applyFirstLineIndent(once))
    }

    @Test
    fun `CRLF 换行先归一为 LF 再分段`() {
        val text = "第一段。\r\n\r\n第二段。"
        val out = applyFirstLineIndent(text)
        assertEquals("${indent}第一段。\n\n${indent}第二段。", out)
    }

    @Test
    fun `CRLF 已缩进文本归一后不再补`() {
        val text = "${indent}第一段。\r\n\r\n${indent}第二段。"
        assertEquals("${indent}第一段。\n\n${indent}第二段。", applyFirstLineIndent(text))
    }

    @Test
    fun `单换行逐句成行且已缩进时不补`() {
        // 无空行、每行即一段的诗词/笔记：按行统计
        val text = "${indent}床前明月光\n${indent}疑是地上霜"
        assertTrue(isDocumentIndented(text))
        assertEquals(text, applyFirstLineIndent(text))
    }

    @Test
    fun `单换行未缩进时只补首行`() {
        // 无空行视为一段：仅段首补缩进，段内折行保持原样
        val text = "第一行\n第二行"
        assertEquals("${indent}第一行\n第二行", applyFirstLineIndent(text))
    }

    @Test
    fun `空文本不抛异常`() {
        assertEquals("", applyFirstLineIndent(""))
        assertFalse(isDocumentIndented(""))
    }
}
