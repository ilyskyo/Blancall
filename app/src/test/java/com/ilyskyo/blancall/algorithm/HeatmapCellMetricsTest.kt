// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格子尺寸规范的回归锁定。
 *
 * 被钉住的是用户报的那条 bug：**同一张热力图，时间维度一换，格子大小就整体变形**
 * （本月被撑成大方块、一年缩成米粒）。尺寸是这张图的刻度，它只能是"分档常量 + 必要时收缩"，
 * 绝不能是"可用宽度 / 列数"的连续函数 —— 后者正是旧实现。
 */
class HeatmapCellMetricsTest {

    @Test
    fun `短区间封顶在 16dp，不随容器撑大`() {
        val m = heatmapCellMetrics(weeks = 3, availableWidth = 300f)
        assertEquals(16f, m.cell, 0.001f)
        // 网格明显窄于容器 —— 余量交给居中去处理，而不是拉大格子
        assertTrue(m.gridWidth(3) < 300f)
    }

    @Test
    fun `近 30 天与本月格子同尺寸（跨范围不跳变）`() {
        val month = heatmapCellMetrics(weeks = 4, availableWidth = 310f)
        val thirtyDays = heatmapCellMetrics(weeks = 5, availableWidth = 310f)
        assertEquals(month.cell, thirtyDays.cell, 0.001f)
        assertEquals(month.gap, thirtyDays.gap, 0.001f)
    }

    @Test
    fun `近一年按容器收缩且不溢出`() {
        val m = heatmapCellMetrics(weeks = 53, availableWidth = 310f)
        assertEquals(1f, m.gap, 0.001f)
        assertTrue(m.cell < 16f)
        assertTrue(m.gridWidth(53) <= 310f + 0.01f)
    }

    @Test
    fun `间距按列数分三档，档内恒定`() {
        assertEquals(4f, heatmapCellMetrics(14, 310f).gap, 0.001f)
        assertEquals(4f, heatmapCellMetrics(2, 310f).gap, 0.001f)
        assertEquals(2f, heatmapCellMetrics(15, 310f).gap, 0.001f)
        assertEquals(2f, heatmapCellMetrics(32, 310f).gap, 0.001f)
        assertEquals(1f, heatmapCellMetrics(33, 310f).gap, 0.001f)
    }

    @Test
    fun `任何区间下格子都不超过上限`() {
        // 3 列（约本月）到 53 列（一年）连续扫一遍：上限恒为 16dp
        for (weeks in 1..53) {
            val m = heatmapCellMetrics(weeks, 310f)
            assertTrue("weeks=$weeks cell=${m.cell}", m.cell <= 16f + 0.001f)
            assertTrue("weeks=$weeks cell=${m.cell}", m.cell >= 1.5f - 0.001f)
        }
    }

    @Test
    fun `网格高度按 7 行计算`() {
        val m = heatmapCellMetrics(weeks = 5, availableWidth = 310f)
        assertEquals(m.cell * 7 + m.gap * 6, m.gridHeight(), 0.001f)
    }
}
