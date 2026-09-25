// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [gridColumnsFor] 列数下限测试：窄屏必须能下探到单列（旧版下限锁 2 列，
 * 手机会被排成双列、卡片过窄导致标题截断）。
 */
class GridColumnsTest {

    @Test
    fun `窄屏下探到单列而不是被锁成两列`() {
        // 手机竖屏（约 360dp）/ 卡片最小宽 260dp：360/260 = 1 ⇒ 单列
        assertEquals(1, gridColumnsFor(360f, cardMinWidthDp = 260f))
    }

    @Test
    fun `平板宽度给出双列或更多`() {
        assertEquals(2, gridColumnsFor(700f, cardMinWidthDp = 260f))
        assertEquals(3, gridColumnsFor(800f, cardMinWidthDp = 260f))
    }

    @Test
    fun `上限仍为 6 列且宽度未知时保守取单列`() {
        assertEquals(6, gridColumnsFor(4000f, cardMinWidthDp = 168f))
        assertEquals(1, gridColumnsFor(0f, cardMinWidthDp = 260f))
        assertEquals(1, gridColumnsFor(-10f))
    }
}
