// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.model.TagData

/**
 * 标签业务纯逻辑（无 Store / Android 依赖 → JVM 单测覆盖）：
 * 名称校验、筛选、绑定面板三态、批量绑定、拖动排序、学习数据聚合。
 */
object TagOps {

    /** 标签名最大长度（chip 展示上限，超出即拦） */
    const val MAX_NAME_LENGTH = 12

    /** 名称校验错误 */
    enum class NameError { BLANK, TOO_LONG, DUPLICATE }

    /**
     * 校验标签名：trim 后非空、≤ [MAX_NAME_LENGTH] 字、忽略大小写唯一。
     * [excludeId] 用于重命名时排除自身。
     */
    fun validateName(name: String, existing: List<Tag>, excludeId: Long = -1L): NameError? {
        val t = name.trim()
        if (t.isEmpty()) return NameError.BLANK
        if (t.length > MAX_NAME_LENGTH) return NameError.TOO_LONG
        if (existing.any { it.id != excludeId && it.name.equals(t, ignoreCase = true) }) {
            return NameError.DUPLICATE
        }
        return null
    }

    /** 某篇文章的已绑定标签，按标签库顺序（tags 数组顺序）返回 */
    fun orderedTagsOf(data: TagData, articleId: Long): List<Tag> {
        val ids = data.links[articleId] ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        return data.tags.filter { it.id in ids }
    }

    /** 全量「文章ID → 有序标签列表」（首页/列表一次算好，避免逐行重复 join） */
    fun tagsByArticle(data: TagData): Map<Long, List<Tag>> {
        if (data.links.isEmpty() || data.tags.isEmpty()) return emptyMap()
        return data.links.keys
            .associateWith { orderedTagsOf(data, it) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * 文章筛选（列表页标签筛选器）：
     * - [selected] 为空且 [includeUntagged] = false → 不筛选，原样返回；
     * - 否则「并集」语义：命中任一选中标签的文章 +（[includeUntagged] 时）无标签文章。
     */
    fun filterArticles(
        articles: List<Article>,
        data: TagData,
        selected: Set<Long>,
        includeUntagged: Boolean,
    ): List<Article> {
        if (selected.isEmpty() && !includeUntagged) return articles
        return articles.filter { a ->
            val ids = data.links[a.id]
            val hit = ids != null && ids.any { it in selected }
            hit || (includeUntagged && ids.isNullOrEmpty())
        }
    }

    // ── 绑定面板（批量语义）──

    /** 单个标签在目标文章集上的勾选三态 */
    enum class PickerState {
        /** 目标全部已绑定 */
        ALL,

        /** 目标全部未绑定 */
        NONE,

        /** 部分绑定（混选） */
        MIXED,
    }

    /** 推导每个标签在目标文章集上的三态（[targetIds] 为空时全部按 NONE 处理） */
    fun pickerStates(
        targetIds: Collection<Long>,
        tagIds: List<Long>,
        data: TagData,
    ): Map<Long, PickerState> = tagIds.associateWith { tagId ->
        val count = targetIds.count { aid -> data.links[aid]?.contains(tagId) == true }
        when {
            count == 0 -> PickerState.NONE
            count == targetIds.size -> PickerState.ALL
            else -> PickerState.MIXED
        }
    }

    /** 当前勾选态 = 增量优先（added 含 → 勾；removed 含 → 未勾；否则全有即勾） */
    fun pickerChecked(
        tagId: Long,
        state: PickerState,
        added: Set<Long>,
        removed: Set<Long>,
    ): Boolean = when {
        tagId in added -> true
        tagId in removed -> false
        state == PickerState.ALL -> true
        else -> false
    }

    /**
     * 面板点选一个标签：更新 added/removed 增量集，返回点击后的勾选态。
     *
     * 语义（防误删）：未点击过的标签绝不改动——
     * - 从「全有」取消 → 记入 removed；重新勾上 → removed 移除；
     * - 从「全无/混选」勾上（或撤销勾上）→ added 增/减。
     */
    fun togglePickerTag(
        tagId: Long,
        state: PickerState,
        added: MutableSet<Long>,
        removed: MutableSet<Long>,
    ): Boolean {
        val checked = pickerChecked(tagId, state, added, removed)
        if (checked) {
            added.remove(tagId)
            if (state == PickerState.ALL) removed.add(tagId)
        } else {
            removed.remove(tagId)
            if (state != PickerState.ALL) added.add(tagId)
        }
        return !checked
    }

    /** 批量绑定结果：current ∪ add − remove */
    fun batchApply(current: Set<Long>, add: Set<Long>, remove: Set<Long>): Set<Long> =
        (current + add) - remove

    // ── 排序 ──

    /** 拖动排序：from → to（越界自动收敛；不改动入参；f == t 原样返回） */
    fun <T> reorderByMove(list: List<T>, from: Int, to: Int): List<T> {
        if (list.size < 2) return list
        val f = from.coerceIn(0, list.size - 1)
        val t = to.coerceIn(0, list.size - 1)
        if (f == t) return list
        val out = list.toMutableList()
        out.add(t, out.removeAt(f))
        return out
    }

    // ── 学习数据按标签聚合 ──

    /** 聚合行；[tag] = null 表示「未分类」（无标签文章） */
    data class TagStat(
        val tag: Tag?,
        val articleCount: Int,
        val practices: Int,
        val blanks: Int,
        val correct: Int,
    ) {
        val rate: Float get() = if (blanks > 0) correct.toFloat() / blanks else 0f
    }

    /**
     * 按标签聚合学习数据：每标签「文章数 / 练习次数 / 累计填空 / 正确数」。
     *
     * - 多标签文章计入各自标签（各标签之和会大于总量，UI 已注明）；
     * - 跨文练习记录（articleId = -1）无单篇归属，自然排除；
     * - 「未分类」= 无任何标签的文章，只要有就附在末尾。
     */
    fun aggregateTagStats(
        articles: List<Article>,
        records: List<PracticeRecord>,
        data: TagData,
    ): List<TagStat> {
        if (articles.isEmpty()) return emptyList()
        val recordsByArticle = records.groupBy { it.articleId }

        fun statOf(tag: Tag?, articleIds: List<Long>): TagStat {
            var practices = 0
            var blanks = 0
            var correct = 0
            articleIds.forEach { aid ->
                recordsByArticle[aid]?.forEach { r ->
                    practices++
                    blanks += r.totalBlanks
                    correct += r.correctCount
                }
            }
            return TagStat(tag, articleIds.size, practices, blanks, correct)
        }

        val byTag = data.tags
            .map { tag ->
                statOf(tag, articles.filter { data.links[it.id]?.contains(tag.id) == true }.map { it.id })
            }
            .sortedByDescending { it.articleCount } // 稳定排序：同篇数保持标签库顺序

        val untagged = articles.filter { data.links[it.id].isNullOrEmpty() }
        return if (untagged.isEmpty()) byTag else byTag + statOf(null, untagged.map { it.id })
    }
}
