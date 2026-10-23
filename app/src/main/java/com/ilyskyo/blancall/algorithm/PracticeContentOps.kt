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

    /** 句级聚焦窗口的半径：目标句前后各再取这么多句（即「这一句 + 附近的句子」） */
    const val FOCUS_SENTENCE_RADIUS = 2

    /**
     * 句级聚焦窗口：句级薄弱榜「点一句去练」与练习记录单条错题「复习」的落点。
     *
     * **为什么不能只按「该句所在段落」聚焦**：段落是按自然段切的（[SectionSplitter]），
     * 一篇文章若没有空行（高考必背源文件就是「每句一行」），整篇只会切出一段 ——
     * 于是「点一句去练」退化成「重做整篇」。这里改为按句取窗口：
     * 目标句前后各 [radius] 句，把原文那一块切成一个合成 Section 交给挖空生成。
     *
     * 切片取 `[startIndex, endIndex)`（见 SentenceSplitter.splitWithPositions）：endIndex 含句末换行，
     * 保证窗口文本重新切句时句界不变（丢掉换行会让以逗号/顿号结尾的句子与下一句粘连成一句）。
     */
    data class FocusWindow(
        /** 合成段落：text 即窗口原文切片，startChar/endChar 为它在全文中的字符区间 */
        val section: SectionSplitter.Section,
        /** 用户点的那个目标句在【全文切句】中的索引（落盘用；窗口被边界裁剪时也能还原） */
        val focusSentence: Int,
        /** 窗口首句在【全文切句】中的索引（错误率表按此平移，见 PracticeViewModel.regenerateCloze） */
        val firstSentenceIndex: Int,
        /** 窗口内句子数 */
        val sentenceCount: Int
    )

    /**
     * 取目标句及其前后各 [radius] 句组成的聚焦窗口。
     *
     * @return null 表示不聚焦：focusSentence < 0、文本为空或索引越界（调用方应回退整篇）
     */
    fun focusWindow(
        fullContent: String,
        focusSentence: Int,
        radius: Int = FOCUS_SENTENCE_RADIUS
    ): FocusWindow? {
        if (fullContent.isBlank() || focusSentence < 0) return null
        val positioned = SentenceSplitter.splitWithPositions(fullContent)
        if (positioned.isEmpty() || focusSentence >= positioned.size) return null
        val from = (focusSentence - radius).coerceAtLeast(0)
        val to = (focusSentence + radius).coerceAtMost(positioned.size - 1)
        val startChar = positioned[from].startIndex
        val endChar = positioned[to].endIndex
        val count = to - from + 1
        val text = fullContent.substring(startChar, endChar)
        return FocusWindow(
            section = SectionSplitter.Section(
                index = 0,
                heading = null,
                text = text,
                contentOnly = text,
                startChar = startChar,
                endChar = endChar,
                sentenceCount = count,
                startSentenceIndex = from
            ),
            firstSentenceIndex = from,
            focusSentence = focusSentence,
            sentenceCount = count
        )
    }

    /**
     * 把「全文句序」键的错误率表平移成「聚焦窗口句序」键（`newKey = key + delta`），
     * 只保留 `0 until limit` 范围内的项（窗口外的句子不参与）。
     *
     * 为什么需要：句级错误率表由 [WeaknessMath.sentenceWeakness] 按**全文切句**索引生成，
     * 而窗口内的句子索引从 0 起。不平移的话「薄弱优先」策略会去打错分句。
     */
    fun shiftSentenceErrorRates(
        rates: Map<Int, Float>,
        delta: Int,
        limit: Int
    ): Map<Int, Float> {
        if (rates.isEmpty()) return emptyMap()
        val out = HashMap<Int, Float>(rates.size)
        for ((k, v) in rates) {
            val shifted = k + delta
            if (shifted in 0 until limit) out[shifted] = v
        }
        return out
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
            else candidates.minByOrNull { abs(it.startIndex - approx) }?.startIndex ?: approx
        }
    }
}
