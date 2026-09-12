// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import kotlin.math.abs

/**
 * 练习内容相关的纯逻辑（从 PracticeViewModel 抽出，便于 JVM 单测锁定行为）。
 *
 * 为什么单独抽出来：句子索引在本项目有**三套互不相等的口径**
 * （① 全文切句 ② 分段切句累加 ③ 生成器内的切句），跨口径传递位置只能靠
 * 「全文字符起始位置」来锚定。这段换算历史上出过错（错标到文章开头），
 * 放在 ViewModel 里既无法单测、又容易被后续改动悄悄破坏，故抽为纯函数并配套测试。
 */
object PracticeContentOps {

    /**
     * 段落选择模式下的「有效正文」：
     * - 整篇模式、未选择、或选中全部段落 → 返回原文（保证与全文切句口径一致）
     * - 否则按段落起始位置排序后用 "\n\n" 拼接选中段落
     */
    fun effectiveContent(
        fullContent: String,
        sections: List<SectionSplitter.Section>,
        isFullMode: Boolean,
        selected: Set<Int>
    ): String {
        if (isFullMode) return fullContent
        if (selected.isEmpty() || selected.size == sections.size) return fullContent
        return sections
            .filter { it.index in selected }
            .sortedBy { it.startChar }
            .joinToString("\n\n") { it.text }
    }

    /**
     * 构建「effectiveContent 句子索引 → 该句在全文中的字符起始位置」锚点表。
     *
     * 两步定位：
     * 1. 按选中段落的字符区间，把子集内偏移换算为全文**近似**位置；
     * 2. 用句子文本在全文切句结果中**精确**匹配；句子重复出现时取位置最近者。
     *
     * 近似位置只用于重复句消歧，因此段落行首尾 trim 造成的几字符误差不会影响最终归属。
     *
     * @return 长度与 effectiveContent 的句子数一致的位置列表；空输入返回空列表
     */
    fun buildSentenceAnchors(
        fullContent: String,
        effectiveContent: String,
        sections: List<SectionSplitter.Section>,
        selected: Set<Int>
    ): List<Int> {
        if (fullContent.isEmpty()) return emptyList()
        // 整篇（或选中全部段落）：子集即全文，直接用全文切句位置
        if (effectiveContent == fullContent) {
            return SentenceSplitter.splitWithPositions(fullContent).map { it.startIndex }
        }
        // 段落模式：建立 effectiveContent 偏移区间 → 全文起始偏移 的换算表
        val ordered = sections.filter { it.index in selected }.sortedBy { it.startChar }
        if (ordered.isEmpty()) return emptyList()
        // 三元组：子集内起始偏移、子集内结束偏移（exclusive）、该段在全文的起始偏移
        val spans = ArrayList<Triple<Int, Int, Int>>()
        var cursor = 0
        for (s in ordered) {
            val len = s.text.length
            spans.add(Triple(cursor, cursor + len, s.startChar))
            cursor += len + 2   // joinToString("\n\n") 的分隔符占 2 字符
        }
        val fullSents = SentenceSplitter.splitWithPositions(fullContent)
        val byText = fullSents.groupBy { it.text }
        return SentenceSplitter.splitWithPositions(effectiveContent).map { (text, start, _) ->
            val approx = spans.firstOrNull { start >= it.first && start < it.second }
                ?.let { (it.third + (start - it.first)).coerceIn(0, fullContent.length) }
                ?: start.coerceIn(0, fullContent.length)
            val candidates = byText[text]
            if (candidates.isNullOrEmpty()) approx
            else candidates.minByOrNull { abs(it.startIndex - approx) }!!.startIndex
        }
    }
}
