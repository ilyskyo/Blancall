// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 底栏物理层的验收测试（对应任务书第 8 节）：
 * 拖动一格准确落格、超调 ≤8%、整栏位移 ≤4dp、速度拉伸有界并衰减、按压先放大后收回、
 * 减少动态效果时直接到位。
 *
 * 纯 JVM 测试：物理层不依赖 Android View / 绘制，只按 8ms 帧步进。
 */
class NavLiquidPhysicsTest {

    @Test
    fun `拖动一个 tab 后准确落到下一格并收敛`() {
        val physics = physics(initialTab = 0)
        var now = 0L
        physics.press()

        // 10 帧、每帧 25px：一共 250px = 1 个 tab
        repeat(10) {
            now += FRAME_MS
            physics.drag(25f, now)
            physics.step(FRAME_SECONDS, now)
        }

        val target = physics.release()
        assertEquals("松手应落在第 1 格", 1, target)

        var maxPosition = physics.position
        var frames = 0
        while (physics.isActive && frames < MAX_FRAMES) {
            now += FRAME_MS
            physics.step(FRAME_SECONDS, now)
            maxPosition = maxOf(maxPosition, physics.position)
            frames++
        }

        assertEquals("位置应收敛到 1.0", 1f, physics.position, 0.01f)
        assertTrue("超调应 ≤ 8%：实际 $maxPosition", maxPosition <= 1.08f)
        assertTrue("速度应衰减到 0：实际 ${physics.velocity}", abs(physics.velocity) < 0.01f)
        assertEquals("按压缩放应回位", 1f, physics.visualScaleX, 0.01f)
        assertEquals("按压缩放应回位", 1f, physics.visualScaleY, 0.01f)
        assertFalse("落格后不应还处于拖动态", physics.isDraggingNow)
    }

    @Test
    fun `整栏位移不超过 4dp 且松手归零`() {
        val physics = physics(initialTab = 0)
        physics.press()
        physics.drag(500f, FRAME_MS)

        // 500 / 1000 = 0.5；easeOutCubic(0.5) = 0.875；4 * 0.875 = 3.5
        assertEquals("半栏拖动应产生约 3.5px 位移", 3.5f, physics.panelOffsetPx, 0.05f)

        physics.drag(2000f, FRAME_MS * 2)
        assertTrue(
            "整栏位移必须被夹在 ±4dp：实际 ${physics.panelOffsetPx}",
            abs(physics.panelOffsetPx) <= 4f + 0.01f,
        )

        physics.release()
        settle(physics, FRAME_MS * 2)
        assertEquals("松手后整栏应回正", 0f, physics.panelOffsetPx, 0.01f)
    }

    @Test
    fun `拖动超出范围时目标与落格都被夹住`() {
        val physics = physics(initialTab = 0)
        physics.press()
        var now = 0L
        repeat(20) {
            now += FRAME_MS
            physics.drag(250f, now)
        }
        assertEquals("目标不得超过最后一格", 3f, physics.targetPosition, 0.001f)
        assertEquals("松手应落在最后一格", 3, physics.release())
    }

    @Test
    fun `点击切 tab 会先按下再缩回`() {
        val physics = physics(initialTab = 1)
        physics.animateToTab(3)
        var now = 0L
        var maxPress = 0f
        var frames = 0
        while (physics.isActive && frames < MAX_FRAMES) {
            now += FRAME_MS
            physics.step(FRAME_SECONDS, now)
            maxPress = maxOf(maxPress, physics.pressProgress)
            frames++
        }

        assertTrue("点击切页应出现按压放大：实际 $maxPress", maxPress > 0.9f)
        assertEquals("位置应滑到第 3 格", 3f, physics.position, 0.01f)
        assertEquals("松手后按压应归零", 0f, physics.pressProgress, 0.01f)
        assertEquals("缩放应回位", 1f, physics.visualScaleX, 0.01f)
    }

    @Test
    fun `速度拉伸有界并在松手后衰减`() {
        val physics = physics(initialTab = 0)
        physics.press()
        var now = 0L
        var maxStretch = 0f

        // 快速甩动：每帧 40px，速度远大于拉伸上限
        repeat(8) {
            now += FRAME_MS
            physics.drag(40f, now)
            physics.step(FRAME_SECONDS, now)
            maxStretch = maxOf(maxStretch, abs(physics.stretch))
        }
        assertTrue("速度拉伸必须夹在 ±0.2：实际 $maxStretch", maxStretch <= 0.2f + 1e-4f)

        physics.release()
        settle(physics, now)
        assertTrue("松手后速度应衰减到 0", abs(physics.velocity) < 0.01f)
    }

    @Test
    fun `减少动态效果时直接到位且不拉伸`() {
        val physics = physics(initialTab = 0, reducedMotion = true)
        physics.press()
        physics.drag(250f, FRAME_MS)

        val target = physics.release()
        assertEquals("仍应准确落格", 1, target)
        assertEquals("位置应直接到位", 1f, physics.position, 0.001f)
        assertEquals("不应有速度拉伸", 1f, physics.visualScaleX, 0.001f)
        assertEquals("不应有整栏位移", 0f, physics.panelOffsetPx, 0.001f)
        assertFalse("应已静止", physics.isActive)
    }

    private fun physics(initialTab: Int, reducedMotion: Boolean = false): NavLiquidPhysics =
        NavLiquidPhysics(
            initialTab = initialTab,
            tabCount = TAB_COUNT,
            reducedMotion = reducedMotion,
        ).apply {
            updateMetrics(
                barWidthPx = BAR_WIDTH_PX,
                tabWidthPx = TAB_WIDTH_PX,
                maxPanelOffsetPx = MAX_PANEL_OFFSET_PX,
            )
        }

    private fun settle(physics: NavLiquidPhysics, startNow: Long) {
        var now = startNow
        var frames = 0
        while (physics.isActive && frames < MAX_FRAMES) {
            now += FRAME_MS
            physics.step(FRAME_SECONDS, now)
            frames++
        }
    }

    private companion object {
        const val TAB_COUNT = 4
        const val BAR_WIDTH_PX = 1000f
        const val TAB_WIDTH_PX = 250f
        const val MAX_PANEL_OFFSET_PX = 4f
        const val FRAME_MS = 8L
        const val FRAME_SECONDS = 0.008f
        const val MAX_FRAMES = 600
    }
}
