// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import com.ilyskyo.blancall.algorithm.CrossTextReview
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** 句级错误画像：聚合/归一化与跨文映射行为锁定（P1-7 数据链路） */
class SentenceErrorProfileTest {

    private fun record(articleId: Long, mistakeIndices: List<Int>) = PracticeRecord(
        articleId = articleId,
        mode = "SENTENCE",
        totalBlanks = 3,
        correctCount = 1,
        mistakeSentenceIndices = mistakeIndices
    )

    @Test
    fun `single article counts normalize to 0 to 1`() {
        val rates = sentenceErrorRatesFromRecords(listOf(record(1L, listOf(0, 2)), record(1L, listOf(2))))
        // 句 2 错 2 次（max=2 → 1.0），句 0 错 1 次（0.5）
        assertEquals(1.0f, rates[2]!!, 1e-6f)
        assertEquals(0.5f, rates[0]!!, 1e-6f)
        assertEquals(2, rates.size)
    }

    @Test
    fun `empty or no-data input returns empty map`() {
        assertEquals(emptyMap<Int, Float>(), sentenceErrorRatesFromRecords(emptyList()))
        assertEquals(emptyMap<Int, Float>(), sentenceErrorRatesFromRecords(listOf(record(1L, emptyList()))))
    }

    @Test
    fun `cross mapping translates article coords to mixed order`() {
        // 混合句序：[A#1, B#0, A#0] → A#0 在混合索引 2，B#0 在混合索引 1
        val sources = listOf(
            CrossTextReview.SourceInfo(articleId = 10L, articleTitle = "A", sentenceIndex = 1),
            CrossTextReview.SourceInfo(articleId = 20L, articleTitle = "B", sentenceIndex = 0),
            CrossTextReview.SourceInfo(articleId = 10L, articleTitle = "A", sentenceIndex = 0)
        )
        val rates = crossSentenceErrorRates(
            listOf(record(10L, listOf(0, 0)), record(20L, listOf(0))),
            sources
        )
        assertEquals(2, rates.size)
        assertEquals(1.0f, rates[2]!!, 1e-6f)   // A#0 错 2 次 → 归一化 1.0
        assertEquals(0.5f, rates[1]!!, 1e-6f)   // B#0 错 1 次 → 0.5
        assertEquals(null, rates[0])            // A#1 无错 → 不在表中
    }

    @Test
    fun `cross mapping skips cross session records and unknown coords`() {
        val sources = listOf(CrossTextReview.SourceInfo(10L, "A", 0))
        val rates = crossSentenceErrorRates(
            listOf(
                record(-1L, listOf(0)),   // 跨文会话记录：跨 mix 坐标不可复用 → 跳过
                record(99L, listOf(0)),   // 文章不在本次 sources 中 → 跳过
                record(10L, listOf(5))    // 原文句索引 5 未进入混合 → 跳过
            ),
            sources
        )
        assertEquals(emptyMap<Int, Float>(), rates)
    }

    @Test
    fun `cross mapping without sources returns empty`() {
        assertEquals(emptyMap<Int, Float>(), crossSentenceErrorRates(listOf(record(10L, listOf(0))), emptyList()))
    }

    @Test
    fun `buildErrorProfile aggregates sentence errors`() {
        val profile = buildErrorProfile(listOf(record(1L, listOf(1, 1, 1))))
        assertEquals(1.0f, profile.sentenceErrorRates[1]!!, 1e-6f)
    }
}
