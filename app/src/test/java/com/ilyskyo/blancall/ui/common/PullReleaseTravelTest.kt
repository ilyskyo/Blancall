// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「拉出 / 拉回」手势的位移推进。
 *
 * 这里锁的是三条手感承诺（真机 bug 的回归锚点）：
 * 1. **阈值内 1:1 跟手** —— 拉多少转多少，不提前加速；
 * 2. **满格后继续拉几乎不动** —— 阻尼 + 硬顶，传递「到界了」；
 * 3. **满格后往回退立刻掉** —— 哪怕已经转满，一退就倒转，撤回永远有反馈。
 *
 * 只靠阈值判定（[pullReleaseReached]）拦不住「转满就跳页」这类回归，
 * 因为那是**什么时候判定**的问题，答案写在这套位移推进里。
 */
class PullReleaseTravelTest {

    private val threshold = 88f
    private val overshoot = 8f
    private val ratio = 0.08f

    @Test
    fun `阈值内下拉一比一跟手`() {
        assertEquals(50f, pullReleaseTravel(0f, 50f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `阈值内回退一比一跟手`() {
        assertEquals(40f, pullReleaseTravel(60f, -20f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `满格后继续拉只吃阻尼且封顶`() {
        // 88 + 100*0.08 = 96，正好撞上硬顶 88+8
        assertEquals(96f, pullReleaseTravel(88f, 100f, threshold, overshoot, ratio), 0.001f)
        // 已经封顶后再怎么拉都不动
        assertEquals(96f, pullReleaseTravel(96f, 200f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `满格后往回退立刻掉进度`() {
        // 转满（96）往回退 6px ⇒ 立刻掉到 90，圈环倒转可见
        assertEquals(90f, pullReleaseTravel(96f, -6f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `满格后一路回退可退到阈值以下`() {
        assertEquals(76f, pullReleaseTravel(96f, -20f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `回退到零就停住不会变负`() {
        assertEquals(0f, pullReleaseTravel(10f, -50f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `一路下拉最多到硬顶`() {
        assertEquals(96f, pullReleaseTravel(0f, 500f, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `阈值非法时位移归零`() {
        assertEquals(0f, pullReleaseTravel(50f, 10f, 0f, overshoot, ratio), 0.001f)
        assertEquals(0f, pullReleaseTravel(50f, 10f, -1f, overshoot, ratio), 0.001f)
    }

    @Test
    fun `非法输入不产生 NaN 位移`() {
        assertEquals(0f, pullReleaseTravel(Float.NaN, 10f, threshold, overshoot, ratio), 0.001f)
        assertEquals(0f, pullReleaseTravel(0f, Float.NaN, threshold, overshoot, ratio), 0.001f)
    }

    @Test
    fun `不设阻尼余量时满格即硬止`() {
        assertEquals(88f, pullReleaseTravel(88f, 12f, threshold, 0f, 0f), 0.001f)
    }
}
