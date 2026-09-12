// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test

/** RangeOps.mergeRanges 行为锁定：相邻/重叠/包含/乱序/空输入 */
class RangeOpsTest {

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
