// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.model.TagData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveSentencePool] 抽句范围组合测试：标签 ∩ 文章两个维度的组合与回退语义，
 * 重点守护「选了文章却抽不出句子」的空池回归（防「每日一句」凭空消失）。
 *
 * 数据：
 * - 文章 1 带标签 甲；文章 2 带标签 乙；文章 3 带标签 甲+乙
 */
class SentenceCardPoolTest {

    private fun article(id: Long) = Article(id = id, title = "文章$id", content = "正文$id")
    private fun tag(id: Long, name: String) = Tag(id = id, name = name, color = 0xEE9DB4)

    private val articles = listOf(article(1), article(2), article(3))
    private val tagData = TagData(
        tags = listOf(tag(1, "甲"), tag(2, "乙")),
        links = mapOf(1L to setOf(1L), 2L to setOf(2L), 3L to setOf(1L, 2L)),
    )

    @Test
    fun `两个维度都不选：全部文章`() {
        val pool = resolveSentencePool(articles, tagData, emptySet(), emptySet())
        assertEquals(listOf(1L, 2L, 3L), pool.map { it.id })
    }

    @Test
    fun `仅选标签：保持原标签筛选语义`() {
        // 标签甲命中 1、3
        val pool = resolveSentencePool(articles, tagData, setOf(1L), emptySet())
        assertEquals(listOf(1L, 3L), pool.map { it.id })
    }

    @Test
    fun `仅选文章：池 = 所选文章（选了文章必有句子可抽）`() {
        val pool = resolveSentencePool(articles, tagData, emptySet(), setOf(2L, 3L))
        assertEquals(listOf(2L, 3L), pool.map { it.id })
    }

    @Test
    fun `标签与文章都选：取交集`() {
        // 标签甲命中 {1,3}，文章选 {1,3} → 交集 {1,3}
        assertEquals(
            listOf(1L, 3L),
            resolveSentencePool(articles, tagData, setOf(1L), setOf(1L, 3L)).map { it.id },
        )
        // 标签乙命中 {2,3}，文章选 {1,2} → 交集 {2}
        assertEquals(
            listOf(2L),
            resolveSentencePool(articles, tagData, setOf(2L), setOf(1L, 2L)).map { it.id },
        )
    }

    @Test
    fun `交集为空：回退文章维度而非空池`() {
        // 标签甲只挂在 1、3；文章仅选 2（只有标签乙）→ 交集空 → 回退文章选择结果 {2}
        val pool = resolveSentencePool(articles, tagData, setOf(1L), setOf(2L))
        assertEquals(listOf(2L), pool.map { it.id })
    }

    @Test
    fun `文章筛选引用已删文章：视为不限并回退全部`() {
        // 运行时 Screen 侧已对账过滤；函数自身也做兜底：全部无效 → 回退全部文章
        val pool = resolveSentencePool(articles, tagData, emptySet(), setOf(99L))
        assertEquals(listOf(1L, 2L, 3L), pool.map { it.id })
    }
}
