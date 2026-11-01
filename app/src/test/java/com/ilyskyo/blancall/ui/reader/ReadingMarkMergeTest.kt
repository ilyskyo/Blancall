// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import com.ilyskyo.blancall.data.repository.ReadingMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergedRanges]：同一段的划线区间并集。
 *
 * 目标语义：划过的句子再多选一个字重划，只延长，不该出现交集更深的一层。
 * 数据层已经不再写重叠记录，但旧盘上可能留着；渲染前必须并成互不重叠的段。
 */
class ReadingMarkMergeTest {

    private fun mark(id: Long, a: Int, e: Int, note: String = "") =
        ReadingMark(id = id, p = 0, a = a, e = e, note = note)

    @Test
    fun `空列表返回空`() {
        assertTrue(mergedRanges(emptyList()).isEmpty())
    }

    @Test
    fun `不相交的区间保持两段并按起点排序`() {
        val out = mergedRanges(listOf(mark(1, 8, 12), mark(2, 0, 4)))
        assertEquals(listOf(0 to 4, 8 to 12), out.map { it.a to it.e })
    }

    @Test
    fun `重叠区间并成一段`() {
        val out = mergedRanges(listOf(mark(1, 0, 5), mark(2, 3, 8)))
        assertEquals(1, out.size)
        assertEquals(0, out[0].a)
        assertEquals(8, out[0].e)
    }

    @Test
    fun `首尾相接也算连续`() {
        val out = mergedRanges(listOf(mark(1, 0, 5), mark(2, 5, 9)))
        assertEquals(1, out.size)
        assertEquals(0, out[0].a)
        assertEquals(9, out[0].e)
    }

    @Test
    fun `被包含的区间不会改变外框`() {
        val out = mergedRanges(listOf(mark(1, 0, 10), mark(2, 3, 5)))
        assertEquals(1, out.size)
        assertEquals(0, out[0].a)
        assertEquals(10, out[0].e)
    }

    @Test
    fun `并集里只要有批注 段就按批注的底色画`() {
        val out = mergedRanges(listOf(mark(1, 0, 5), mark(2, 3, 8, note = "重点")))
        assertEquals(1, out.size)
        assertTrue(out[0].hasNote)
        val plain = mergedRanges(listOf(mark(1, 0, 5), mark(2, 3, 8)))
        assertFalse(plain[0].hasNote)
    }
}
