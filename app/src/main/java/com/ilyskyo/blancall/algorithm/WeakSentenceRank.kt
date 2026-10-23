// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord

/**
 * 句级薄弱榜（B3-1）：从「哪篇弱」下沉到「哪句弱」，并把每个条目锚回它所在的段落 ——
 * 用户点一句就能只练那一句及其前后几句（[WeakSentence.sentenceIndex] 沿导航传进练习页，
 * 由 PracticeContentOps.focusWindow 切成聚焦窗口）。
 *
 * ## 数据来源与口径
 * 复刻 [MemoryHeatmap.generate] 的句级结果（收缩后的错误率、样本量、观测错误率），
 * 只做三件事：过滤、排序、锚段。**不重复实现句级统计** —— 热力图与榜必须同源，
 * 否则同一句在两个组件里显示两个错误率，用户只会信任其中更糟的那个。
 *
 * 只收「真的错过（观测错误率 > 0）」且「样本够定档（≥ [WeaknessMath.MIN_SAMPLE] 次作答）」
 * 的句子：一次都没错的句子不该出现在「最常写错」里，样本不足的句子没有资格被判弱。
 *
 * ## 段锚定为什么用字符位置
 * 句子索引存在多种口径（全文切句 / 段内切句）；而「句子在全文中的字符起始位置」是
 * 跨口径稳定的锚点（项目里同一条教训，见 `PracticeRecord.answeredSentenceStarts` 的注释）。
 * 所以这里把句子的 startIndex 落进 [SectionSplitter.Section] 的 `[startChar, endChar)` 区间，
 * 而不是拿「段内第几句」之类的相对序号去对表。锚不到段的句子直接不上榜 ——
 * 进榜却「点不动 / 点去错地方」，比少一行更伤。
 */
internal object WeakSentenceRank {

    /** 榜单条目 */
    data class WeakSentence(
        /** 句子在全文切句中的索引（导航参数 focusSentence 传的就是它） */
        val sentenceIndex: Int,
        /** 句子原文（已 trim，供 UI 单行截断展示） */
        val text: String,
        /** 观测错误率 0..1 —— 展示「错 X%」用这个数，用户练的就是它 */
        val errorRate: Float,
        /** 该句被作答的会话数 */
        val practiceCount: Int,
        /** 该句所在段落的序号（== [SectionSplitter.Section.index]） */
        val sectionIndex: Int
    )

    /** 默认榜长：3 行是「一眼的结论」，再多就开始变成第二个列表页 */
    const val DEFAULT_LIMIT = 3

    /**
     * 排名句级薄弱（自含热力图计算）。
     *
     * @param content 文章全文
     * @param records 该文章的练习记录（顺序无关）
     * @param limit 榜单长度
     */
    fun rank(
        content: String,
        records: List<PracticeRecord>,
        limit: Int = DEFAULT_LIMIT
    ): List<WeakSentence> {
        if (content.isBlank() || records.isEmpty()) return emptyList()
        return rank(MemoryHeatmap.generate(content, records), content, limit)
    }

    /**
     * 复用调用方已算好的热力图数据（统计页里热力图卡与榜的数据源是同一份，不必算两遍）。
     *
     * 传空记录算出的 heat 也不会误出榜：那种情况下每句 `practiceCount=0`、`lowSample=true`，
     * 在过滤条件处就被排除了。
     */
    fun rank(
        heat: MemoryHeatmap.HeatmapData,
        content: String,
        limit: Int = DEFAULT_LIMIT
    ): List<WeakSentence> {
        if (content.isBlank() || heat.sentences.isEmpty()) return emptyList()

        // 锚段要用的两套坐标：全文句子位置（字符）+ 段落字符区间
        val positioned = SentenceSplitter.splitWithPositions(content)
        val sections = SectionSplitter.split(content)

        return heat.sentences
            // 样本门槛按【会话数】判（practiceCount），不复用 SentenceHeat.lowSample ——
            // 后者自 B3-2 起描述的是「留存色档是否成立」，与「错误率数据够不够排榜」是两件事
            .filter { it.practiceCount >= WeaknessMath.MIN_SAMPLE && it.errorRate > 0f }
            .sortedByDescending { it.adjustedRate }
            .mapNotNull { s ->
                val startChar = positioned.getOrNull(s.sentenceIndex)?.startIndex
                    ?: return@mapNotNull null
                val section = sections.firstOrNull { startChar >= it.startChar && startChar < it.endChar }
                    ?: return@mapNotNull null
                WeakSentence(
                    sentenceIndex = s.sentenceIndex,
                    text = s.text.trim(),
                    errorRate = s.errorRate,
                    practiceCount = s.practiceCount,
                    sectionIndex = section.index
                )
            }
            .take(limit)
    }
}
