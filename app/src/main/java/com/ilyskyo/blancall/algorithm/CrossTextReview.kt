// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 跨文本联动复习（F7）
 *
 * 将多篇关联文章合并为混合练习内容，支持：
 * - 按句子粒度打乱顺序，防止机械记忆段落顺序
 * - 保留原文标识，展示时标注每句来源
 * - 两种混合策略：全文混合 / 按段交替
 */
object CrossTextReview {

    enum class MixStrategy {
        /** 全文混合：所有句子完全打乱（默认） */
        SHUFFLE_ALL,
        /** 按段交替：保持各文章内部顺序，交替排列 */
        INTERLEAVE
    }

    data class SourceInfo(
        val articleId: Long,
        val articleTitle: String,
        val sentenceIndex: Int       // 该句在原文中的索引
    )

    data class MixedContent(
        /** 合并后的完整文本（用于挖空生成） */
        val content: String,
        /** 每句的来源信息，与 SentenceSplitter 拆分结果一一对应 */
        val sources: List<SourceInfo>,
        /** 参与混合的文章标题列表 */
        val articleTitles: List<String>
    )

    /** 内部带标签的句子 */
    private data class TaggedSentence(
        val text: String,
        val articleId: Long,
        val articleTitle: String,
        val sentenceIndex: Int
    )

    /**
     * 混合多篇文章内容
     * @param articles 文章列表 (id, title, content)
     * @param strategy 混合策略
     */
    fun mix(
        articles: List<Triple<Long, String, String>>,
        strategy: MixStrategy = MixStrategy.SHUFFLE_ALL
    ): MixedContent {
        if (articles.isEmpty()) {
            return MixedContent("", emptyList(), emptyList())
        }
        if (articles.size == 1) {
            val (id, title, content) = articles.first()
            val sentences = SentenceSplitter.split(content)
            // 纯空白文本：split 返回空，显式返回空 content，避免 content 非空但 sources 为空的不一致
            if (sentences.isEmpty()) {
                return MixedContent("", emptyList(), listOf(title))
            }
            val sources = sentences.indices.map { SourceInfo(id, title, it) }
            return MixedContent(content, sources, listOf(title))
        }

        // 拆分每篇文章为句子
        val allTagged = mutableListOf<TaggedSentence>()
        for ((id, title, content) in articles) {
            val sentences = SentenceSplitter.split(content)
            sentences.forEachIndexed { idx, s ->
                allTagged.add(TaggedSentence(s, id, title, idx))
            }
        }

        val ordered = when (strategy) {
            MixStrategy.SHUFFLE_ALL -> {
                // 随机打乱，但避免同一文章的句子扎堆
                shuffledFair(allTagged)
            }
            MixStrategy.INTERLEAVE -> {
                // 交替排列：取每篇文章的下一句轮流放置
                interleave(allTagged, articles.size)
            }
        }

        val content = ordered.joinToString("\n") { it.text }
        val sources = ordered.map { SourceInfo(it.articleId, it.articleTitle, it.sentenceIndex) }

        return MixedContent(content, sources, articles.map { it.second })
    }

    /**
     * 公平打乱：将同一文章的句子尽量分散，同时引入随机性。
     *
     * 原实现为确定性轮流取句（无随机性），此处：
     * 1. 对每组内部句子顺序随机打乱（避免机械记忆原文顺序）；
     * 2. 每轮取句时再打乱组顺序（避免固定 A-B-C 节奏）。
     * 从而让 SHUFFLE_ALL 真正随机，同时仍保持跨文章分散。
     */
    private fun shuffledFair(tagged: List<TaggedSentence>): List<TaggedSentence> {
        if (tagged.size <= 1) return tagged.toList()

        // 按文章分组，并随机打乱组内顺序
        val groups = tagged.groupBy { it.articleId }
            .values
            .map { it.shuffled().toMutableList() }

        val result = mutableListOf<TaggedSentence>()
        // 轮流从各组取一句；每轮打乱组顺序，避免固定节奏
        while (groups.any { it.isNotEmpty() }) {
            for (gIdx in groups.indices.shuffled()) {
                val group = groups[gIdx]
                if (group.isNotEmpty()) {
                    // 从末尾取句，removeAt 末尾为 O(1)
                    result.add(group.removeAt(group.size - 1))
                }
            }
        }

        return result
    }

    /**
     * 交替排列：轮流从各文章取一句，保持原文内部顺序
     */
    private fun interleave(tagged: List<TaggedSentence>, articleCount: Int): List<TaggedSentence> {
        val groups = tagged.groupBy { it.articleId }.values.toList()
        val maxLen = groups.maxOfOrNull { it.size } ?: 0
        val result = mutableListOf<TaggedSentence>()

        for (i in 0 until maxLen) {
            for (group in groups) {
                if (i < group.size) {
                    result.add(group[i])
                }
            }
        }

        return result
    }
}
