// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlancallGenerator 生成性质回归测试。
 * 生成含随机性，只测不变量：答案必须来自原文、空数受限、展示文本与挖空一致。
 */
class BlancallGeneratorTest {

    private val article = """
        阿房宫赋

        六王毕，四海一，蜀山兀，阿房出。覆压三百余里，隔离天日。
        二川溶溶，流入宫墙。五步一楼，十步一阁；廊腰缦回，檐牙高啄。
        长桥卧波，未云何龙？复道行空，不霁何虹？
    """.trimIndent()

    @Test
    fun `句子挖空_答案全部来自原文`() {
        val result = BlancallGenerator.generateSentenceCloze(article, count = 3)
        assertTrue("应有挖空", result.blanks.isNotEmpty())
        result.blanks.forEach { b ->
            assertTrue("答案应非空", b.originalText.isNotBlank())
            assertTrue("答案应出现在原文中", article.contains(b.originalText.trim(), ignoreCase = false))
        }
    }

    @Test
    fun `句子挖空_指定空数不超限`() {
        val result = BlancallGenerator.generateSentenceCloze(article, count = 2)
        assertTrue(result.blanks.size <= 2)
    }

    @Test
    fun `句子挖空_空文本安全`() {
        val result = BlancallGenerator.generateSentenceCloze("")
        assertEquals(0, result.blanks.size)
    }

    @Test
    fun `字词挖空_挖空字符来自原文`() {
        val result = BlancallGenerator.generateWordCloze(article, count = 5)
        assertTrue(result.blanks.isNotEmpty())
        result.blanks.forEach { b ->
            assertTrue("挖空字符应非空", b.originalChar.isNotBlank())
            assertTrue("挖空字符应出现在原文中", article.contains(b.originalChar))
            assertTrue("position 应为非负", b.position >= 0)
        }
    }

    @Test
    fun `反向默写_从句来自原文且打乱后集合守恒`() {
        val result = BlancallGenerator.generateDictation(article)
        assertTrue(result.clauses.isNotEmpty())
        // 打乱后的从句集合与原顺序集合守恒（按 originalIndex 对应原文从句）
        assertEquals(result.clauses.size, result.shuffledClauses.size)
        assertEquals(
            result.clauses.map { it.trim() }.sorted(),
            result.shuffledClauses.map { it.originalText.trim() }.sorted()
        )
        // displayOrder 应覆盖 0..n-1
        assertEquals(
            result.clauses.indices.toList().sorted(),
            result.shuffledClauses.map { it.displayOrder }.sorted()
        )
    }

    @Test
    fun `反向默写_空文本安全`() {
        val result = BlancallGenerator.generateDictation("   ")
        assertEquals(0, result.clauses.size)
    }

    @Test
    fun `记忆因子参与生成不破坏性质`() {
        val profile = BlancallGenerator.ErrorProfile(
            sentenceErrorRates = mapOf(0 to 0.8f, 1 to 0.5f),
            memoryFactor = 1.5f
        )
        val result = BlancallGenerator.generateSentenceCloze(
            article, count = 2, errorProfile = profile
        )
        result.blanks.forEach { b ->
            assertTrue(article.contains(b.originalText.trim()))
        }
    }
}
