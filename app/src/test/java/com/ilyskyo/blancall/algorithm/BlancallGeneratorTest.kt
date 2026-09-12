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

    // ── 自定义句子挖空（编辑器选点 → 确定性结果）──
    // 回归防护：曾把「句内字符偏移」当作「句子索引」去取句长，
    // 短文章会抛 "Cannot coerce to an empty range" 崩溃，长文章会用别的句子长度裁掉区间。

    /** 8 个句子，句长递增：索引 6 的句子为「庚庚庚庚庚庚庚。」（8 字） */
    private val numberedArticle =
        "甲。乙乙。丙丙丙。丁丁丁丁。戊戊戊戊戊。己己己己己己。庚庚庚庚庚庚庚。辛辛辛辛辛辛辛辛。"

    @Test
    fun `自定义句子挖空_挖空文本等于所选原文区间`() {
        val sentences = SentenceSplitter.split(numberedArticle)
        val expected = sentences[6].substring(2, 5)   // "庚庚庚"

        val result = BlancallGenerator.buildCustomSentenceCloze(
            numberedArticle, mapOf(6 to listOf(2 until 5))
        )

        assertEquals(1, result.blanks.size)
        val blank = result.blanks.first()
        assertEquals("挖空文本必须等于所选原文区间", expected, blank.originalText)
        assertEquals("句子索引应保持为 6", 6, blank.sentenceIndex)
        assertEquals(2, blank.startInSentence)
        assertEquals(5, blank.endInSentence)
    }

    @Test
    fun `自定义句子挖空_短文章句内偏移大于句数不崩溃`() {
        // 该用例修复前必崩：区间起点（5）被当作句子下标去取长度 → 越界得空串 → coerceIn(min>max)
        val short = "短句一。这是一个比较长的句子内容用来做测试。第三个句子也不短呢。"
        val sentences = SentenceSplitter.split(short)

        val result = BlancallGenerator.buildCustomSentenceCloze(short, mapOf(2 to listOf(5 until 8)))

        assertEquals(1, result.blanks.size)
        val blank = result.blanks.first()
        assertEquals("应落在第 3 句", 2, blank.sentenceIndex)
        assertEquals(sentences[2].substring(5, 8), blank.originalText)
    }

    @Test
    fun `自定义句子挖空_多句区间各自独立不错位`() {
        val sentences = SentenceSplitter.split(numberedArticle)
        val result = BlancallGenerator.buildCustomSentenceCloze(
            numberedArticle,
            mapOf(
                1 to listOf(0 until 2),      // 乙乙
                6 to listOf(2 until 5)       // 庚庚庚
            )
        )

        assertEquals(2, result.blanks.size)
        result.blanks.forEach { b ->
            val expected = sentences[b.sentenceIndex]
                .substring(b.startInSentence, b.endInSentence)
            assertEquals("第 ${b.sentenceIndex} 句的挖空文本应与其区间一致", expected, b.originalText)
        }
    }

    @Test
    fun `自定义句子挖空_越界区间裁剪到句长内且不崩溃`() {
        val sentences = SentenceSplitter.split(numberedArticle)
        // 起始偏移远超句长：应被裁到句内最后一个字符，而不是抛异常
        val result = BlancallGenerator.buildCustomSentenceCloze(
            numberedArticle, mapOf(0 to listOf(100 until 200))
        )

        assertEquals(1, result.blanks.size)
        val blank = result.blanks.first()
        assertEquals(0, blank.sentenceIndex)
        assertTrue("区间须落在句长之内", blank.endInSentence <= sentences[0].length)
        assertEquals(
            sentences[0].substring(blank.startInSentence, blank.endInSentence),
            blank.originalText
        )
    }

    @Test
    fun `自定义句子挖空_空内容与空区间安全`() {
        assertEquals(0, BlancallGenerator.buildCustomSentenceCloze("", mapOf(0 to listOf(0 until 3))).blanks.size)
        assertEquals(0, BlancallGenerator.buildCustomSentenceCloze(numberedArticle, emptyMap()).blanks.size)
    }
}
