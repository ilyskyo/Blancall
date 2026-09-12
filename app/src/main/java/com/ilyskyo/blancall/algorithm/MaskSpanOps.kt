// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.repository.MaskConfigStore

/**
 * 遮挡块（MaskConfigStore.MaskSpan）的纯逻辑操作（原遮挡编辑器私有实现上移，行为不变）。
 * 全部为无副作用纯函数，便于单元测试锁定语义。
 */
object MaskSpanOps {

    /**
     * 点选切换遮挡语义：
     * - 命中已遮块且完整覆盖本次区间 → 仅取消（移除命中块）
     * - 命中已遮块但未完整覆盖 → 移除命中块后按新区间整块遮上（替换，如点词盖过字块）
     * - 未命中且区间有效（e > a）→ 新增整块
     */
    fun toggle(spans: List<MaskConfigStore.MaskSpan>, p: Int, a: Int, e: Int, color: Int): List<MaskConfigStore.MaskSpan> {
        val hits = spans.filter { it.p == p && it.a < e && a < it.e }
        var result = spans
        if (hits.isNotEmpty()) {
            result = result - hits.toSet()
            if (hits.any { it.a <= a && it.e >= e }) return result
        }
        if (e > a) result = result + MaskConfigStore.MaskSpan(p, a, e, color)
        return result
    }

    /** 合并同段内同色相邻/重叠块，返回按段序、起点升序的规范 spans（保存用） */
    fun merge(spans: List<MaskConfigStore.MaskSpan>): List<MaskConfigStore.MaskSpan> =
        spans.groupBy { it.p }.toSortedMap().flatMap { (p, list) ->
            list.sortedBy { it.a }.fold(mutableListOf<MaskConfigStore.MaskSpan>()) { acc, s ->
                val last = acc.lastOrNull()
                if (last != null && last.c == s.c && s.a <= last.e) {
                    acc[acc.size - 1] = last.copy(e = maxOf(last.e, s.e))
                } else acc.add(s)
                acc
            }
        }
}
