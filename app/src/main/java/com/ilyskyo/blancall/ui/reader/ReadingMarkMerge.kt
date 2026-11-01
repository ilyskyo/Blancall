// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import com.ilyskyo.blancall.data.repository.ReadingMark

/** 同段内的划线区间并集：重叠（含首尾相接）合并为一段，hasNote 取并。 */
internal data class MergedRange(val a: Int, val e: Int, val hasNote: Boolean)

/**
 * 把同一段里的划线按区间取并集。
 *
 * 为什么要单独抽出来：盘上可能留着旧版本产生的重叠记录（那时允许叠加）。
 * 渲染若逐条画，交集处颜色会变深；这里统一成互不重叠的段，
 * 由调用方每段只画一次。纯函数、可单测。
 */
internal fun mergedRanges(marks: List<ReadingMark>): List<MergedRange> {
    if (marks.isEmpty()) return emptyList()
    val sorted = marks.sortedWith(compareBy({ it.a }, { it.e }))
    val out = mutableListOf<MergedRange>()
    var a = sorted.first().a
    var e = sorted.first().e
    var noted = sorted.first().hasNote
    for (m in sorted.drop(1)) {
        if (m.a <= e) {
            if (m.e > e) e = m.e
            if (m.hasNote) noted = true
        } else {
            out += MergedRange(a, e, noted)
            a = m.a
            e = m.e
            noted = m.hasNote
        }
    }
    out += MergedRange(a, e, noted)
    return out
}
