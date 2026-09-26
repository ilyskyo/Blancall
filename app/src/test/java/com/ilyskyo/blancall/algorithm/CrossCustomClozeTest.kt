// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.repository.CustomClozeStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨文复习「自定义挖空」的按文章映射测试（[CrossTextReview.mapCustomClozeRanges]）：
 * 每篇文章各自选定的配置只作用于该文章自己的句子（经 sources 映射到混合句序），
 * 未选择 / 无配置的文章不参与挖空，越界区间按既有口径裁剪、不跨句错配。
 */
class CrossCustomClozeTest {

    private fun cfg(id: Long, blanks: List<Pair<Int, IntRange>>) = CustomClozeStore.CustomConfig(
        id = id,
        name = "配置$id",
        createdAt = id,
        blanks = blanks.map { (s, r) -> CustomClozeStore.BlankSpec(s, r.first, r.last + 1) },
        mode = "WORD",
    )

    @Test
    fun `A 用配置1、B 用配置8：各自只作用于原文对应句子`() {
        // 混合句序：A句0 / B句0 / A句1 / B句1（模拟打乱后的 sources）
        val sentences = listOf("A一。", "B一。", "A二。", "B二。")
        val sources = listOf(
            CrossTextReview.SourceInfo(1L, "A文", 0),
            CrossTextReview.SourceInfo(2L, "B文", 0),
            CrossTextReview.SourceInfo(1L, "A文", 1),
            CrossTextReview.SourceInfo(2L, "B文", 1),
        )
        val configs = mapOf(
            1L to cfg(1, listOf(0 to (0 until 1))),   // A：原句 0 的 [0,1)
            2L to cfg(8, listOf(1 to (1 until 2))),   // B：原句 1 的 [1,2)
        )
        val out = CrossTextReview.mapCustomClozeRanges(sentences, sources, configs)
        assertEquals(
            mapOf(0 to listOf(0 until 1), 3 to listOf(1 until 2)),
            out,
        )
    }

    @Test
    fun `未选择配置的文章不参与挖空`() {
        val sentences = listOf("A一。", "B一。")
        val sources = listOf(
            CrossTextReview.SourceInfo(1L, "A文", 0),
            CrossTextReview.SourceInfo(2L, "B文", 0),
        )
        val out = CrossTextReview.mapCustomClozeRanges(
            sentences, sources,
            mapOf(1L to cfg(1, listOf(0 to (0 until 1)))),
        )
        assertEquals(mapOf(0 to listOf(0 until 1)), out)
    }

    @Test
    fun `句索引错位的标注不应用到其它句子`() {
        val sentences = listOf("A一。", "A二。")
        val sources = listOf(
            CrossTextReview.SourceInfo(1L, "A文", 0),
            CrossTextReview.SourceInfo(1L, "A文", 1),
        )
        // 配置只标了原句 1（映射到混合句 1），混合句 0 不应有任何空
        val out = CrossTextReview.mapCustomClozeRanges(
            sentences, sources,
            mapOf(1L to cfg(1, listOf(1 to (0 until 1)))),
        )
        assertEquals(mapOf(1 to listOf(0 until 1)), out)
    }

    @Test
    fun `越界区间按既有口径贴边裁剪，不越界错配`() {
        // 文章被改短：原区间 [5,9) 对 2 字句 → 归一化裁剪为 [1,2)（与单篇自定义练习同一口径）
        val sentences = listOf("短。")
        val sources = listOf(CrossTextReview.SourceInfo(1L, "A文", 0))
        val out = CrossTextReview.mapCustomClozeRanges(
            sentences, sources,
            mapOf(1L to cfg(1, listOf(0 to (5 until 9)))),
        )
        assertEquals(mapOf(0 to listOf(1 until 2)), out)
    }

    @Test
    fun `配置为空或无选中文章时返回空表（调用方回退提示）`() {
        val sentences = listOf("A一。")
        val sources = listOf(CrossTextReview.SourceInfo(1L, "A文", 0))
        assertEquals(
            emptyMap<Int, List<IntRange>>(),
            CrossTextReview.mapCustomClozeRanges(sentences, sources, emptyMap()),
        )
        // 文章无空位标注（blanks 为空）
        assertEquals(
            emptyMap<Int, List<IntRange>>(),
            CrossTextReview.mapCustomClozeRanges(sentences, sources, mapOf(1L to cfg(1, emptyList()))),
        )
    }
}
