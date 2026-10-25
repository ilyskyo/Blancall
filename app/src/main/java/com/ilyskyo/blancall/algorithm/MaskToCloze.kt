// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 「遮挡读 → 挖空测」的坐标转换：把某一段里**已经遮住**的区间，映射成
 * 全文切句口径下的句子区间（CustomClozeStore.BlankSpec 的 s/a/b）。
 *
 * 为什么不是「把整段都挖掉」：遮挡是按分句/难字生成的，遮住的就是用户刚才
 * 试图回忆的部分；把它原样转成空位，读和测才是同一件事的两半。
 *
 * 边界处理：
 * - 跨越两句的遮挡区间按句子切开（一句一个空），不把跨句区间硬塞进第一句；
 * - 句内区间收紧到句子长度内，越界丢弃；
 * - 归一化（合并/重叠）交给 CustomClozeStore 应用侧的 RangeOps，这里不重复实现。
 */
object MaskToCloze {

    /** 全文切句坐标下的空位：句索引 + 句内半开区间 [a, b)。 */
    data class SentenceRange(val sentenceIndex: Int, val a: Int, val b: Int)

    /**
     * @param paragraphStart 该段在全文中的字符起点
     * @param spans 段内区间（半开 [start, end)，相对段落）
     * @param sentences 全文切句（[SentenceSplitter.splitWithPositions]）
     */
    fun convert(
        paragraphStart: Int,
        spans: List<IntRange>,
        sentences: List<SentenceSplitter.SentenceWithPosition>,
    ): List<SentenceRange> {
        if (spans.isEmpty() || sentences.isEmpty()) return emptyList()
        val out = LinkedHashSet<SentenceRange>()
        for (span in spans) {
            if (span.isEmpty()) continue
            val absStart = paragraphStart + span.first
            val absEnd = paragraphStart + span.last + 1
            for ((idx, sentence) in sentences.withIndex()) {
                val s = maxOf(absStart, sentence.startIndex)
                val e = minOf(absEnd, sentence.endIndex)
                if (s >= e) continue
                val a = s - sentence.startIndex
                val b = e - sentence.startIndex
                if (a in 0..sentence.text.length && b in 0..sentence.text.length && b > a) {
                    out += SentenceRange(idx, a, b)
                }
            }
        }
        return out.toList()
    }
}
