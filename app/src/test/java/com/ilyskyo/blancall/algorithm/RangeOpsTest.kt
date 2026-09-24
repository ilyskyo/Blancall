// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test

/** RangeOps.mergeRanges 行为锁定：相邻/重叠/包含/乱序/空输入 */
class RangeOpsTest {

    // ── normalizeClampedRanges：自定义挖空保存/应用的统一口径（越界裁剪 + 合并） ──

    @Test
    fun `normalize start at or beyond length clamps to last char`() {
        // a >= len：旧实现会 coerce 出空区间崩溃，现裁到最后一个字符
        assertEquals(listOf(4..4), RangeOps.normalizeClampedRanges(5, listOf(9..12)))
    }

    @Test
    fun `normalize end beyond length clamps to length`() {
        assertEquals(listOf(2..4), RangeOps.normalizeClampedRanges(5, listOf(2..99)))
    }

    @Test
    fun `normalize negative start clamps to zero`() {
        assertEquals(listOf(0..2), RangeOps.normalizeClampedRanges(5, listOf(-3..2)))
    }

    @Test
    fun `normalize zero length sentence returns empty`() {
        assertEquals(emptyList<IntRange>(), RangeOps.normalizeClampedRanges(0, listOf(0..2)))
    }

    @Test
    fun `normalize merges adjacent ranges after clamp`() {
        assertEquals(listOf(0..4), RangeOps.normalizeClampedRanges(5, listOf(0..1, 2..9)))
    }

    @Test
    fun `normalize empty input returns empty`() {
        assertEquals(emptyList<IntRange>(), RangeOps.normalizeClampedRanges(5, emptyList()))
    }

    // ── mergeRanges ──

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList<IntRange>(), RangeOps.mergeRanges(emptyList()))
    }

    @Test
    fun `single range unchanged`() {
        assertEquals(listOf(2..7), RangeOps.mergeRanges(listOf(2..7)))
    }

    @Test
    fun `adjacent ranges merge`() {
        assertEquals(listOf(0..9), RangeOps.mergeRanges(listOf(0..4, 5..9)))
    }

    @Test
    fun `overlapping ranges merge`() {
        assertEquals(listOf(0..9), RangeOps.mergeRanges(listOf(0..5, 3..9)))
    }

    @Test
    fun `contained range is absorbed`() {
        assertEquals(listOf(0..9), RangeOps.mergeRanges(listOf(0..9, 3..4)))
    }

    @Test
    fun `disjoint ranges stay separate`() {
        assertEquals(listOf(0..2, 5..7), RangeOps.mergeRanges(listOf(0..2, 5..7)))
    }

    @Test
    fun `unsorted input is sorted and merged`() {
        assertEquals(listOf(0..9, 20..22), RangeOps.mergeRanges(listOf(20..22, 5..9, 0..4)))
    }

    @Test
    fun `gap of one breaks merge`() {
        // [0..4] 与 [6..9] 之间隔 5，不相邻不合并
        assertEquals(listOf(0..4, 6..9), RangeOps.mergeRanges(listOf(0..4, 6..9)))
    }
}
