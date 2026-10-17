// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeakSentenceRank] 的口径锁定：过滤（样本 / 真的错过）、排序（收缩错误率）、
 * 段锚定（字符位置落区间）三件事各自有断言，避免「榜单悄悄换了含义」。
 */
class WeakSentenceRankTest {

    // 两段、每段两句；每段 ≥ 30 字符，避免 SectionSplitter 的过短段合并
    private val para1 = "这是第一段的第一句话写在这里用于测试。这是第一段的第二句话同样写在这里。"
    private val para2 = "这是第二段的第一句话写在这里用于测试。这是第二段的第二句话依然写在这里。"
    private val content = "$para1\n\n$para2"

    private fun sentenceStarts(): List<Int> =
        SentenceSplitter.splitWithPositions(content).map { it.startIndex }

    private fun rec(errorSentence: Int, allStarts: List<Int>, mode: String = "SENTENCE") =
        PracticeRecord(
            articleId = 1L,
            mode = mode,
            totalBlanks = allStarts.size,
            correctCount = allStarts.size - 1,
            mistakes = listOf(
                MistakeDetail(
                    blankIndex = errorSentence,
                    correctAnswer = "a",
                    userAnswer = "b",
                    errorType = "TYPO",
                    sentenceIndex = errorSentence
                )
            ),
            answeredSentenceStarts = allStarts
        )

    private fun recClean(allStarts: List<Int>) = PracticeRecord(
        articleId = 1L,
        mode = "SENTENCE",
        totalBlanks = allStarts.size,
        correctCount = allStarts.size,
        mistakes = emptyList(),
        answeredSentenceStarts = allStarts
    )

    @Test
    fun `榜首是错得最多的句子且带正确段锚定`() {
        val starts = sentenceStarts()
        assertEquals("测试前提：全文应为 4 句", 4, starts.size)
        val rows = WeakSentenceRank.rank(
            content,
            listOf(
                rec(2, starts), rec(2, starts), rec(2, starts),   // 句 2 错 3 次（段 1）
                rec(0, starts),                                    // 句 0 错 1 次（段 0）
                recClean(starts),
            )
        )
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals(2, first.sentenceIndex)
        assertEquals("句 2 应锚到第二段（index=1）", 1, first.sectionIndex)
        assertEquals(0.6f, first.errorRate, 0.0001f)   // 观测错误率：3 错 / 5 练
        assertEquals(5, first.practiceCount)           // 5 个会话都练过全部句
    }

    @Test
    fun `观测错误率与收缩排序各司其职`() {
        val starts = sentenceStarts()
        val rows = WeakSentenceRank.rank(
            content,
            listOf(
                rec(2, starts), rec(2, starts), rec(2, starts),
                rec(0, starts), recClean(starts),
            )
        )
        // 句 2：3 错 / 5 练 = 60%；句 0：1 错 / 5 练 = 20%
        assertEquals(0.6f, rows[0].errorRate, 0.0001f)
        assertEquals(0.2f, rows[1].errorRate, 0.0001f)
        assertEquals(0, rows[1].sentenceIndex)
        assertEquals("句 0 应锚到第一段（index=0）", 0, rows[1].sectionIndex)
    }

    @Test
    fun `样本不足的句子不上榜`() {
        val starts = sentenceStarts()
        // 只练了 2 次（< MIN_SAMPLE=3），即使每次都错也不定档
        val rows = WeakSentenceRank.rank(content, listOf(rec(2, starts), rec(2, starts)))
        assertTrue("样本不足不该上榜：$rows", rows.isEmpty())
    }

    @Test
    fun `一次都没错过的句子不上榜`() {
        val starts = sentenceStarts()
        val rows = WeakSentenceRank.rank(content, listOf(recClean(starts), recClean(starts), recClean(starts)))
        assertTrue("全对不该有榜单：$rows", rows.isEmpty())
    }

    @Test
    fun `limit 生效`() {
        val starts = sentenceStarts()
        val rows = WeakSentenceRank.rank(
            content,
            listOf(
                rec(2, starts), rec(2, starts), rec(2, starts),
                rec(0, starts), rec(1, starts), recClean(starts),
            ),
            limit = 1
        )
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].sentenceIndex)
    }

    @Test
    fun `空输入返回空榜`() {
        assertTrue(WeakSentenceRank.rank("", listOf(recClean(emptyList()))).isEmpty())
        assertTrue(WeakSentenceRank.rank("   ", listOf(recClean(emptyList()))).isEmpty())
        assertTrue(WeakSentenceRank.rank(content, emptyList()).isEmpty())
    }

    @Test
    fun `榜单文本为句原文（去首尾空白）`() {
        val starts = sentenceStarts()
        val rows = WeakSentenceRank.rank(
            content,
            listOf(rec(2, starts), rec(2, starts), rec(2, starts))
        )
        assertEquals("这是第二段的第一句话写在这里用于测试。", rows[0].text)
    }
}
