// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.repository.MaskConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MaskSpanOps 行为锁定：toggle 取消/替换/新增语义、merge 同色合并规则 */
class MaskSpanOpsTest {

    private fun m(p: Int, a: Int, e: Int, c: Int = 0) = MaskConfigStore.MaskSpan(p, a, e, c)

    // ── toggle ──

    @Test
    fun `toggle adds new block when nothing hit`() {
        val out = MaskSpanOps.toggle(emptyList(), 0, 2, 6, 3)
        assertEquals(listOf(m(0, 2, 6, 3)), out)
    }

    @Test
    fun `toggle cancels when hit fully covers tap range`() {
        val out = MaskSpanOps.toggle(listOf(m(0, 0, 5, 1)), 0, 0, 5, 1)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `toggle cancels when tap range inside existing block`() {
        // 点到已有块内部的更小单元（如字块里点字）→ 取消整块
        val out = MaskSpanOps.toggle(listOf(m(0, 0, 5, 1)), 0, 2, 3, 1)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `toggle replaces partial overlap`() {
        // 已有 [0,5)，新点 [3,8)：不构成完整覆盖 → 替换为 [3,8)
        val out = MaskSpanOps.toggle(listOf(m(0, 0, 5, 1)), 0, 3, 8, 2)
        assertEquals(listOf(m(0, 3, 8, 2)), out)
    }

    @Test
    fun `toggle ignores other paragraphs`() {
        val out = MaskSpanOps.toggle(listOf(m(1, 0, 5, 0)), 0, 0, 5, 2)
        assertEquals(listOf(m(1, 0, 5, 0), m(0, 0, 5, 2)), out)
    }

    @Test
    fun `toggle with empty range adds nothing`() {
        val out = MaskSpanOps.toggle(emptyList(), 0, 3, 3, 1)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `toggle removes only hit blocks on replace`() {
        val base = listOf(m(0, 0, 2, 0), m(0, 4, 6, 0))
        // 新点 [4,9) 与 [4,6) 相交但不完整覆盖（4<=4 && 6>=9 假）→ 移除命中块再加新块
        val out = MaskSpanOps.toggle(base, 0, 4, 9, 1)
        assertEquals(listOf(m(0, 0, 2, 0), m(0, 4, 9, 1)), out)
    }

    // ── merge ──

    @Test
    fun `merge joins same-color adjacent blocks`() {
        val out = MaskSpanOps.merge(listOf(m(0, 0, 5, 0), m(0, 5, 9, 0)))
        assertEquals(listOf(m(0, 0, 9, 0)), out)
    }

    @Test
    fun `merge joins same-color overlapping blocks`() {
        val out = MaskSpanOps.merge(listOf(m(0, 0, 6, 0), m(0, 3, 9, 0)))
        assertEquals(listOf(m(0, 0, 9, 0)), out)
    }

    @Test
    fun `merge keeps different colors separate`() {
        val out = MaskSpanOps.merge(listOf(m(0, 0, 5, 0), m(0, 5, 9, 1)))
        assertEquals(listOf(m(0, 0, 5, 0), m(0, 5, 9, 1)), out)
    }

    @Test
    fun `merge never joins across paragraphs`() {
        val out = MaskSpanOps.merge(listOf(m(0, 0, 5, 0), m(1, 0, 5, 0)))
        assertEquals(listOf(m(0, 0, 5, 0), m(1, 0, 5, 0)), out)
    }

    @Test
    fun `merge sorts by paragraph then start`() {
        // 同段同色但不相邻（有缺口）不合并
        val out = MaskSpanOps.merge(listOf(m(1, 2, 4, 0), m(0, 6, 8, 0), m(0, 0, 3, 0)))
        assertEquals(listOf(m(0, 0, 3, 0), m(0, 6, 8, 0), m(1, 2, 4, 0)), out)
    }

    @Test
    fun `merge handles empty input`() {
        assertTrue(MaskSpanOps.merge(emptyList()).isEmpty())
    }
}
