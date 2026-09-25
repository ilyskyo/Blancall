// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InkWidthSim] 纯逻辑测试：触屏仿造压感（速度 → 宽度因子）的收敛方向、
 * 平滑与边界防护、收笔收细（taperEnd）。
 *
 * 约定：density = 3（常见 xhdpi+ 手机），速度 = 位移(px)/密度/时间(ms)，单位 dp/ms。
 */
class InkWidthSimTest {

    private val density = 3f

    /** 持续推进同一速度的采样，返回最终因子。 */
    private fun feed(
        sim: InkWidthSim,
        dxPx: Float,
        dtMs: Long,
        times: Int = 40,
        density: Float = 3f
    ): Float {
        var f = 0f
        repeat(times) { f = sim.onSample(dxPx, 0f, dtMs, density) }
        return f
    }

    @Test
    fun `慢速轨迹收敛到粗档`() {
        // 1px / 40ms / density3 ≈ 0.008 dp/ms ＜ slow(0.10) ⇒ 目标为最粗档
        val f = feed(InkWidthSim(), dxPx = 1f, dtMs = 40L)
        assertTrue("慢写应收敛到粗档，实际 $f", f > 0.85f)
    }

    @Test
    fun `快速轨迹收敛到细档`() {
        // 24px / 8ms / density3 = 1.0 dp/ms ≥ fast(0.80) ⇒ 目标为最细档
        val f = feed(InkWidthSim(), dxPx = 24f, dtMs = 8L)
        assertTrue("快写应收敛到细档，实际 $f", f < 0.15f)
    }

    @Test
    fun `零位移与时间倒挂保持上一因子`() {
        val sim = InkWidthSim()
        val initial = sim.onSample(0f, 0f, 0L, density)
        assertEquals(InkWidthSim.INITIAL, initial, 1e-6f)

        // 先快写一段（变小），再给零位移/负时差样本：因子必须原样保持
        val afterFast = feed(sim, dxPx = 24f, dtMs = 8L, times = 10)
        val hold1 = sim.onSample(0f, 0f, 8L, density)
        val hold2 = sim.onSample(5f, 0f, 0L, density)
        val hold3 = sim.onSample(0.01f, 0f, 8L, density) // 位移 < 0.05px 视为没有动
        assertEquals(afterFast, hold1, 1e-6f)
        assertEquals(afterFast, hold2, 1e-6f)
        assertEquals(afterFast, hold3, 1e-6f)
    }

    @Test
    fun `因子始终限定在 0 到 1`() {
        // 极慢（0.1px/100ms ≈ 0.0003 dp/ms）与极快（200px/1ms ≈ 66 dp/ms），都必须被限制在 0..1
        val slow = feed(InkWidthSim(), dxPx = 0.1f, dtMs = 100L, times = 60)
        val fast = feed(InkWidthSim(), dxPx = 200f, dtMs = 1L, times = 60)
        assertTrue(slow in 0f..1f)
        assertTrue(fast in 0f..1f)
        assertEquals(1f, slow, 1e-3f)
        assertEquals(0f, fast, 1e-3f)
    }

    @Test
    fun `速度按密度归一：同一物理尺寸的位移时间比在不同密度下同档`() {
        // 3dp 位移 / 30ms：density3 ⇒ 9px、density1 ⇒ 3px —— 归一后同为 0.1 dp/ms（慢档边界）
        val f3 = feed(InkWidthSim(), dxPx = 9f, dtMs = 30L, density = 3f)
        val f1 = feed(InkWidthSim(), dxPx = 3f, dtMs = 30L, density = 1f)
        assertEquals("同一 dp 口径速度应得到同一档位", f3, f1, 0.02f)
    }

    @Test
    fun `reset 恢复到初始因子`() {
        val sim = InkWidthSim()
        feed(sim, dxPx = 24f, dtMs = 8L, times = 20)
        sim.reset()
        // reset 后首个「无速度可比」样本应回到初始值
        assertEquals(InkWidthSim.INITIAL, sim.onSample(1f, 0f, 0L, density), 1e-6f)
    }

    @Test
    fun `收笔收细：末段递减到 floor其余保持不变`() {
        val factors = MutableList(8) { 1f }
        InkWidthSim.taperEnd(factors, count = 4, floor = 0.5f)
        // 前 4 个不动
        for (i in 0 until 4) assertEquals(1f, factors[i], 1e-6f)
        // 末 4 个线性压向 floor：0.875 / 0.75 / 0.625 / 0.5
        assertEquals(0.875f, factors[4], 1e-4f)
        assertEquals(0.75f, factors[5], 1e-4f)
        assertEquals(0.625f, factors[6], 1e-4f)
        assertEquals(0.5f, factors[7], 1e-4f)
    }

    @Test
    fun `收笔收细：样本不足时不动`() {
        val one = MutableList(1) { 0.8f }
        InkWidthSim.taperEnd(one)
        assertEquals(0.8f, one[0], 1e-6f)

        val two = MutableList(2) { 0.8f }
        InkWidthSim.taperEnd(two, count = 0)
        assertEquals(0.8f, two[0], 1e-6f)
        assertEquals(0.8f, two[1], 1e-6f)
    }
}
