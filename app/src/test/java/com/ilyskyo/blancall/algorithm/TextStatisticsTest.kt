// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test

class TextStatisticsTest {

    @Test
    fun `中文按汉字计数且不把标点算进字数`() {
        val s = TextStatistics.analyze("春眠不觉晓，处处闻啼鸟。\n\n夜来风雨声，花落知多少。")
        assertEquals(TextStatistics.CountUnit.HAN, s.unit)
        assertEquals(20, s.primaryCount)
        assertEquals(20, s.han)
        assertEquals(4, s.punctuation)
        assertEquals(0, s.spaces)
        assertEquals(2, s.paragraphs)
        assertEquals(26, s.totalChars)
        assertEquals(24, s.charsWithSpaces)
        assertEquals(24, s.charsNoSpaces)
        assertEquals(0, s.letters)
    }

    @Test
    fun `英文按单词计数且词内撇号不拆词`() {
        val s = TextStatistics.analyze("Hello, world! It's 2026.")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(4, s.primaryCount)
        assertEquals(4, s.words)
        assertEquals(13, s.letters)
        assertEquals(4, s.digits)
        assertEquals(4, s.punctuation)
        assertEquals(3, s.spaces)
        assertEquals(24, s.totalChars)
        assertEquals(21, s.charsNoSpaces)
        assertEquals(1, s.paragraphs)
    }

    @Test
    fun `日文按汉字加假名计数`() {
        val s = TextStatistics.analyze("こんにちは、世界。")
        assertEquals(TextStatistics.CountUnit.KANA, s.unit)
        assertEquals(7, s.primaryCount)
        assertEquals(5, s.kana)
        assertEquals(2, s.han)
        assertEquals(2, s.punctuation)
        assertEquals(9, s.totalChars)
    }

    @Test
    fun `韩文按空格分词计数`() {
        val s = TextStatistics.analyze("안녕하세요 세계")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(2, s.primaryCount)
        assertEquals(7, s.hangul)
        assertEquals(1, s.spaces)
        assertEquals(2, s.words)
    }

    @Test
    fun `俄文按空格分词计数`() {
        val s = TextStatistics.analyze("Привет, мир!")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(2, s.primaryCount)
        assertEquals(9, s.cyrillicLetters)
        assertEquals(2, s.punctuation)
        assertEquals(1, s.spaces)
    }

    @Test
    fun `中英混排不会被英文单词带偏`() {
        val s = TextStatistics.analyze("学习 Kotlin 很有趣。")
        assertEquals(TextStatistics.CountUnit.HAN, s.unit)
        assertEquals(5, s.primaryCount)
        assertEquals(5, s.han)
        assertEquals(6, s.latinLetters)
        assertEquals(1, s.nonCjkWords)
        assertEquals(3, s.words)
        assertEquals(2, s.spaces)
    }

    @Test
    fun `只有中文里夹带英文单词时也不算成英文文章`() {
        val s = TextStatistics.analyze("我在读 The Great Gatsby 这本书。")
        assertEquals(TextStatistics.CountUnit.HAN, s.unit)
        assertEquals(6, s.primaryCount)
        assertEquals(3, s.nonCjkWords)
    }

    @Test
    fun `英文文章里夹中文仍按英文计`() {
        val s = TextStatistics.analyze("The word 道 means way.")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(5, s.primaryCount)
        assertEquals(1, s.han)
    }

    @Test
    fun `其他有空格的语言按词计`() {
        val s = TextStatistics.analyze("Καλημέρα κόσμε")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(2, s.primaryCount)
        assertEquals(2, s.words)
        assertEquals(13, s.otherLetters)
    }

    @Test
    fun `emoji 算符号不算标点`() {
        val s = TextStatistics.analyze("Hi 😀")
        assertEquals(TextStatistics.CountUnit.WORD, s.unit)
        assertEquals(1, s.primaryCount)
        assertEquals(2, s.letters)
        assertEquals(0, s.punctuation)
        assertEquals(1, s.symbols)
        assertEquals(4, s.totalChars)
    }

    @Test
    fun `连续非空行只算一段`() {
        val s = TextStatistics.analyze("第一段\n第一段续行\n\n第二段")
        assertEquals(2, s.paragraphs)
        assertEquals(11, s.han)
        assertEquals(14, s.totalChars)
        assertEquals(11, s.charsWithSpaces)
    }

    @Test
    fun `空文本不会除零或乱判语言`() {
        val s = TextStatistics.analyze("")
        assertEquals(TextStatistics.CountUnit.CHARACTER, s.unit)
        assertEquals(0, s.primaryCount)
        assertEquals(0, s.paragraphs)
        assertEquals(0, s.words)
    }
}
