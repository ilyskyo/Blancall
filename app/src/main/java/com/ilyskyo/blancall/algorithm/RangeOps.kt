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
}
