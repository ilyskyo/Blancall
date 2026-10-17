// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面滑动的**覆盖几何**守卫：转场的任何一刻，两页的并集必须盖满容器。
 *
 * 为什么值得单独立一条：`slideIntoContainer` / `slideOutOfContainer` 的 `initialOffset` /
 * `targetOffset` 符号写反，编译过、运行不报错，只是转场中段屏幕上出现一条
 * **两页都没盖住**的竖带，露出窗口底色 —— 用户在真机录像里抓到它（2026-10-09）。
 * 这类东西没法用截图守卫抓（转瞬即逝），但几何关系是纯算术，在 JVM 里就能判死。
 *
 * 模型：容器宽 1.0，每页宽 1.0，位置记左边缘。
 * 「跟随方向的那一页」走满幅（默认端点），「让位那一页」只走 [Motion.parallaxOffset] 的量 ——
 * 所以符号一旦反过来，让位页就朝反方向退，两侧同时留空。
 */
class PageTransitionCoverageTest {

    private val width = 1.0
    private val parallax get() = Motion.parallaxOffset(1000) / 1000.0

    @Test
    fun 视差量必须朝让位方向为负() {
        assertTrue("parallaxOffset 必须是负数（让位页要往被压住的一侧退），实为 $parallax", parallax < 0.0)
        assertTrue("视差量幅度应在 0~0.5 之间，实为 $parallax", parallax > -0.5)
    }

    @Test
    fun 前进转场全程盖满容器() {
        // 新页从右边缘滑入：x = (1-t)*W → 0；旧页向左让位：x = 0 → parallax
        gaps("前进", samples) { t ->
            listOf(span((1.0 - t) * width), span(parallax * t))
        }
    }

    @Test
    fun 返回转场全程盖满容器() {
        // 旧页向右推出：x = 0 → W；上一页从左偏位置回位：x = parallax → 0
        gaps("返回", samples) { t ->
            listOf(span(width * t), span(parallax * (1.0 - t)))
        }
    }

    private val samples = (0..20).map { it / 20.0 }

    private fun span(left: Double) = left to left + width

    private fun gaps(name: String, ts: List<Double>, pages: (Double) -> List<Pair<Double, Double>>) {
        val bad = mutableListOf<String>()
        for (t in ts) {
            val ps = pages(t).sortedBy { it.first }
            var cursor = 0.0
            for ((l, r) in ps) {
                if (l > cursor + 1e-9) {
                    bad += "t=%.2f 处 %.3f..%.3f 无人覆盖".format(t, cursor, l)
                    break
                }
                cursor = maxOf(cursor, r)
                if (cursor >= width - 1e-9) break
            }
            if (cursor < width - 1e-9) bad += "t=%.2f 处右缘 %.3f..%.3f 无人覆盖".format(t, cursor, width)
        }
        assertTrue(
            "$name 转场有露底的一刻（两页并集没盖满容器）：\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
    }
}
