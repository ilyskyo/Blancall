// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 字符区间 [IntRange] 的通用合并工具（自定义挖空编辑器等使用）。
 * 合并规则：按起点排序后，相邻（r.first <= last.last + 1）或重叠的区间并入前块。
 */
object RangeOps {

    /** 合并相邻/重叠区间，返回按起点升序的不相交区间列表 */
    fun mergeRanges(ranges: List<IntRange>): List<IntRange> =
        ranges.sortedBy { it.first }
            .fold(mutableListOf<IntRange>()) { acc, r ->
                val last = acc.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    acc[acc.size - 1] = last.first..maxOf(last.last, r.last)
                } else acc.add(r)
                acc
            }

    /**
     * 把「句内字符区间」规范化到给定句长（自定义挖空保存/应用的统一口径）。
     *
     * - 越界裁剪：a 取 `coerceIn(0, len-1)`、b 取 `coerceIn(a+1, len)`；
     * - 非法区间（b<=a）丢弃；
     * - 相邻/重叠合并（与 [mergeRanges] 同规则）；
     * - `sentenceLength <= 0`（句子不存在/空白）返回空列表。
     *
     * 语义与 [com.ilyskyo.blancall.algorithm.BlancallGenerator.buildCustomSentenceCloze] 的
     * 历史修复一致：旧实现 a 可被 coerce 到 len，随后 `coerceIn(len+1, len)` 会抛
     * "Cannot coerce value to an empty range" 崩溃（文章内容变短后应用旧配置即触发）。
     * 保存（编辑器 buildConfig）与应用（applyCustomPractice）必须共用本函数，避免再次漂移。
     */
    fun normalizeClampedRanges(sentenceLength: Int, ranges: List<IntRange>): List<IntRange> {
        if (sentenceLength <= 0 || ranges.isEmpty()) return emptyList()
        return ranges.mapNotNull { r ->
            val a = r.first.coerceIn(0, sentenceLength - 1)
            val b = (r.last + 1).coerceIn(a + 1, sentenceLength)
            if (b > a) a until b else null
        }.sortedBy { it.first }
            .fold(mutableListOf<IntRange>()) { acc, r ->
                val last = acc.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    acc[acc.size - 1] = last.first..maxOf(last.last, r.last)
                } else acc.add(r)
                acc
            }
    }
}
