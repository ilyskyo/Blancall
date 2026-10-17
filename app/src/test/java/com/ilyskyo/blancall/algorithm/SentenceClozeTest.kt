// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SentenceCloze] 回归测试：挖空提示的语义（分句首字保留、标点原样、其余变占位）。
 *
 * 这些断言直接对应界面观感 —— 挖错一个字符，用户看到的就是一句结构不对的题面，
 * 而它不会崩、不会报错，只是"题面读起来怪怪的"，最难被复现发现。
 */
class SentenceClozeTest {

    @Test
    fun `分句首字保留其余变占位标点原样`() {
        assertEquals(
            "独□□□，湘□□□，橘□□□。",
            SentenceCloze.cue("独立寒秋，湘江北去，橘子洲头。"),
        )
    }

    @Test
    fun `无标点整句只留首字`() {
        assertEquals("天□□□□", SentenceCloze.cue("天行健君子"))
    }

    @Test
    fun `短分句只留一字不产生空块`() {
        // 两字分句：留首字、藏一字，标点仍在
        assertEquals("学□，", SentenceCloze.cue("学习，"))
    }

    @Test
    fun `空串与纯标点原样返回`() {
        assertEquals("", SentenceCloze.cue(""))
        assertEquals("，。", SentenceCloze.cue("，。"))
    }

    @Test
    fun `hidesAnything 只在真的藏了字时为真`() {
        assertTrue(SentenceCloze.hidesAnything("独立寒秋，湘江北去，橘子洲头。"))
        // 单字句藏无可藏：界面据此跳过「显示答案」这道门，避免"点了没反应"
        assertFalse(SentenceCloze.hidesAnything("好"))
        assertFalse(SentenceCloze.hidesAnything("，。"))
        assertFalse(SentenceCloze.hidesAnything(""))
    }

    @Test
    fun `挖空不改变标点序列`() {
        val text = "先天下之忧而忧，后天下之乐而乐。"
        // 占位符本身不是字母，得排除掉，否则比的是"标点 + 一堆方框"
        val marks = { s: String -> s.filter { !it.isLetter() && it != SentenceCloze.PLACEHOLDER } }
        assertEquals(marks(text), marks(SentenceCloze.cue(text)))
    }
}
