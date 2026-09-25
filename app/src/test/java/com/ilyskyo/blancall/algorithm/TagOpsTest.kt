// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.model.TagData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TagOps] 纯逻辑测试：名称校验、筛选（并集/未分类）、绑定面板三态与点选、
 * 批量绑定、拖动排序、学习数据按标签聚合。
 */
class TagOpsTest {

    private fun article(id: Long, title: String = "文章$id") =
        Article(id = id, title = title, content = "正文$id")

    private fun tag(id: Long, name: String) = Tag(id = id, name = name, color = 0xEE9DB4)

    private fun record(aid: Long, blanks: Int, correct: Int) =
        PracticeRecord(articleId = aid, mode = "WORD", totalBlanks = blanks, correctCount = correct)

    // ── 名称校验 ──

    @Test
    fun `名称校验：空与超长与重名`() {
        val existing = listOf(tag(1, "诗词"), tag(2, "English"))
        assertEquals(TagOps.NameError.BLANK, TagOps.validateName("   ", existing))
        assertEquals(TagOps.NameError.TOO_LONG, TagOps.validateName("一二三四五六七八九十十一十二十三", existing))
        assertEquals(TagOps.NameError.DUPLICATE, TagOps.validateName("诗词", existing))
        // 忽略大小写 + trim
        assertEquals(TagOps.NameError.DUPLICATE, TagOps.validateName("  english ", existing))
        assertNull(TagOps.validateName(" 山水 ", existing))
        // 重命名排除自身
        assertNull(TagOps.validateName("诗词", existing, excludeId = 1))
        assertEquals(TagOps.NameError.DUPLICATE, TagOps.validateName("English", existing, excludeId = 1))
    }

    @Test
    fun `名称边界：恰好 12 字合法超过被拒`() {
        val twelve = "123456789012" // 12 字符
        assertEquals(12, twelve.length)
        assertNull(TagOps.validateName(twelve, emptyList()))
        assertEquals(TagOps.NameError.TOO_LONG, TagOps.validateName(twelve + "3", emptyList()))
    }

    // ── 筛选 ──

    @Test
    fun `筛选：不选任何条件时原样返回`() {
        val articles = listOf(article(1), article(2))
        val data = TagData(tags = listOf(tag(1, "甲")), links = mapOf(1L to setOf(1L)))
        assertEquals(articles, TagOps.filterArticles(articles, data, emptySet(), includeUntagged = false))
    }

    @Test
    fun `筛选：单标签与多标签并集`() {
        val articles = listOf(article(1), article(2), article(3))
        val data = TagData(
            tags = listOf(tag(1, "甲"), tag(2, "乙")),
            links = mapOf(1L to setOf(1L), 2L to setOf(2L), 3L to setOf(1L, 2L)),
        )
        // 单标签：命中的文章 1 与 3
        assertEquals(listOf(1L, 3L), TagOps.filterArticles(articles, data, setOf(1L), false).map { it.id })
        // 单标签乙：命中的文章 2 与 3
        assertEquals(listOf(2L, 3L), TagOps.filterArticles(articles, data, setOf(2L), false).map { it.id })
        // 多标签并集：命中任一即列出（1、2、3 全中，保持原顺序）
        assertEquals(listOf(1L, 2L, 3L), TagOps.filterArticles(articles, data, setOf(1L, 2L), false).map { it.id })
    }

    @Test
    fun `筛选：仅未分类`() {
        val articles = listOf(article(1), article(2), article(3))
        val data = TagData(tags = listOf(tag(1, "甲")), links = mapOf(1L to setOf(1L)))
        assertEquals(listOf(2L, 3L), TagOps.filterArticles(articles, data, emptySet(), includeUntagged = true).map { it.id })
    }

    @Test
    fun `筛选：标签与未分类并集`() {
        val articles = listOf(article(1), article(2), article(3))
        val data = TagData(tags = listOf(tag(1, "甲")), links = mapOf(1L to setOf(1L)))
        assertEquals(listOf(1L, 2L, 3L), TagOps.filterArticles(articles, data, setOf(1L), includeUntagged = true).map { it.id })
    }

    // ── 绑定面板三态与点选 ──

    @Test
    fun `三态推导：全有全无与混选`() {
        val data = TagData(
            tags = listOf(tag(1, "甲"), tag(2, "乙"), tag(3, "丙")),
            links = mapOf(1L to setOf(1L, 2L), 2L to setOf(1L)),
        )
        val states = TagOps.pickerStates(listOf(1L, 2L), listOf(1L, 2L, 3L), data)
        assertEquals(TagOps.PickerState.ALL, states[1L])
        assertEquals(TagOps.PickerState.MIXED, states[2L])
        assertEquals(TagOps.PickerState.NONE, states[3L])
    }

    @Test
    fun `点选：混选与全无点击为添加`() {
        val added = mutableSetOf<Long>()
        val removed = mutableSetOf<Long>()
        // 混选点一下 → 勾上（添加）
        assertTrue(TagOps.togglePickerTag(2L, TagOps.PickerState.MIXED, added, removed))
        assertTrue(2L in added)
        assertTrue(removed.isEmpty())
        // 再点 → 取消勾选（撤销添加）
        assertTrue(!TagOps.togglePickerTag(2L, TagOps.PickerState.MIXED, added, removed))
        assertTrue(added.isEmpty())
        // 全无点击 → 添加
        assertTrue(TagOps.togglePickerTag(3L, TagOps.PickerState.NONE, added, removed))
        assertTrue(3L in added)
    }

    @Test
    fun `点选：全有取消记入 removed 再点恢复`() {
        val added = mutableSetOf<Long>()
        val removed = mutableSetOf<Long>()
        // 全有点一下 → 取消（移除）
        assertTrue(!TagOps.togglePickerTag(1L, TagOps.PickerState.ALL, added, removed))
        assertTrue(1L in removed)
        assertTrue(added.isEmpty())
        // 再点 → 恢复勾选（removed 清除）
        assertTrue(TagOps.togglePickerTag(1L, TagOps.PickerState.ALL, added, removed))
        assertTrue(removed.isEmpty())
        assertTrue(added.isEmpty())
    }

    @Test
    fun `批量绑定结果：并集减去移除`() {
        assertEquals(setOf(1L, 3L), TagOps.batchApply(setOf(1L, 2L), setOf(3L), setOf(2L)))
        assertEquals(setOf(1L), TagOps.batchApply(setOf(1L), emptySet(), emptySet()))
        assertEquals(emptySet<Long>(), TagOps.batchApply(setOf(1L), emptySet(), setOf(1L)))
    }

    // ── 排序 ──

    @Test
    fun `拖动排序：向前向后与边界`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), TagOps.reorderByMove(list, 0, 1))
        assertEquals(listOf("a", "c", "d", "b"), TagOps.reorderByMove(list, 1, 3))
        assertEquals(listOf("d", "a", "b", "c"), TagOps.reorderByMove(list, 3, 0))
        // 原地不动
        assertEquals(list, TagOps.reorderByMove(list, 1, 1))
        // 越界收敛
        assertEquals(listOf("d", "a", "b", "c"), TagOps.reorderByMove(list, 9, -3))
        // 原列表不被修改
        TagOps.reorderByMove(list, 0, 3)
        assertEquals(listOf("a", "b", "c", "d"), list)
    }

    // ── 有序标签 ──

    @Test
    fun `文章标签按标签库顺序返回`() {
        val data = TagData(
            tags = listOf(tag(2, "乙"), tag(1, "甲"), tag(3, "丙")),
            links = mapOf(9L to setOf(3L, 2L)),
        )
        assertEquals(listOf("乙", "丙"), TagOps.orderedTagsOf(data, 9L).map { it.name })
        assertEquals(listOf("乙", "丙"), TagOps.tagsByArticle(data)[9L]?.map { it.name })
    }

    // ── 学习数据聚合 ──

    @Test
    fun `按标签聚合：计数与正确率与未分类`() {
        val articles = listOf(article(1), article(2), article(3))
        val data = TagData(
            tags = listOf(tag(1, "甲"), tag(2, "乙")),
            links = mapOf(1L to setOf(1L, 2L), 2L to setOf(1L)),
        )
        val records = listOf(
            record(1, 4, 3), record(1, 2, 2), // 文章1：2 次
            record(2, 5, 5),                  // 文章2：1 次
            record(3, 1, 0),                  // 文章3（未分类）：1 次
            record(-1, 10, 10),               // 跨文练习：无单篇归属，应被排除
        )
        val stats = TagOps.aggregateTagStats(articles, records, data)

        // 甲：文章1+2 → 3 次练习、blanks 11、correct 10；乙：文章1 → 2 次、blanks 6、correct 5
        val jia = stats.first { it.tag?.id == 1L }
        assertEquals(2, jia.articleCount)
        assertEquals(3, jia.practices)
        assertEquals(11, jia.blanks)
        assertEquals(10, jia.correct)
        assertEquals(10f / 11f, jia.rate, 1e-4f)

        val yi = stats.first { it.tag?.id == 2L }
        assertEquals(1, yi.articleCount)
        assertEquals(2, yi.practices)
        assertEquals(6, yi.blanks)
        assertEquals(5, yi.correct)

        // 未分类在末尾
        val untagged = stats.last()
        assertNull(untagged.tag)
        assertEquals(1, untagged.articleCount)
        assertEquals(1, untagged.practices)
        assertEquals(0f, untagged.rate, 1e-4f)

        // 排序：篇数降序（甲 2 篇在前）
        assertEquals(1L, stats.first().tag?.id)
    }

    @Test
    fun `按标签聚合：全部有标签时无未分类行`() {
        val articles = listOf(article(1))
        val data = TagData(tags = listOf(tag(1, "甲")), links = mapOf(1L to setOf(1L)))
        val stats = TagOps.aggregateTagStats(articles, emptyList(), data)
        assertEquals(1, stats.size)
        assertTrue(stats.all { it.tag != null })
        assertEquals(0, stats.first().practices)
        assertEquals(0f, stats.first().rate, 1e-4f)
    }

    @Test
    fun `按标签聚合：空文章列表返回空`() {
        assertTrue(TagOps.aggregateTagStats(emptyList(), emptyList(), TagData()).isEmpty())
    }
}
