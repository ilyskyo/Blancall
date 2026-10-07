// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触感「语义档 × 用户强度」的叠档规则测试。
 *
 * 这里断言的是**关系**而不是具体毫秒：参数会调，
 * 但「弱不会更弱、强有天花板、单调不越界」这三条一旦破掉就是回归 ——
 * 而且它们只有靠代码才验得到：手感本身只能在真机上判。
 */
class HapticsTest {

    private val allTiers = listOf(
        HapticTier.Toggle,
        HapticTier.Confirm,
        HapticTier.Threshold,
        HapticTier.Destructive,
    )

    @Test
    fun `标准档是恒等映射`() {
        allTiers.forEach { tier ->
            assertEquals(tier, tier.withLevel(HapticLevel.Standard))
        }
    }

    @Test
    fun `弱档只会更轻或持平，绝不更重`() {
        allTiers.forEach { tier ->
            val weakened = tier.withLevel(HapticLevel.Light)
            assertTrue("弱档不应比标准档更重: $tier", weakened.ordinal <= tier.ordinal)
        }
        // 天花板：最轻的一档不会被再往下压成「没有反馈」
        assertEquals(HapticTier.Toggle, HapticTier.Toggle.withLevel(HapticLevel.Light))
        assertEquals(HapticTier.Toggle, HapticTier.Confirm.withLevel(HapticLevel.Light))
    }

    @Test
    fun `强档有上限，最重一档不会无限升级`() {
        assertEquals(HapticTier.Confirm, HapticTier.Toggle.withLevel(HapticLevel.Strong))
        assertEquals(HapticTier.Threshold, HapticTier.Confirm.withLevel(HapticLevel.Strong))
        assertEquals(HapticTier.Destructive, HapticTier.Threshold.withLevel(HapticLevel.Strong))
        // Destructive 已是顶，再加也不该变（否则「强」会造出第五种手感）
        assertEquals(HapticTier.Destructive, HapticTier.Destructive.withLevel(HapticLevel.Strong))
    }

    @Test
    fun `三档对任意语义档都是单调的`() {
        allTiers.forEach { tier ->
            val light = tier.withLevel(HapticLevel.Light).ordinal
            val standard = tier.withLevel(HapticLevel.Standard).ordinal
            val strong = tier.withLevel(HapticLevel.Strong).ordinal
            assertTrue("弱 ≤ 标准 ≤ 强: $tier", light <= standard && standard <= strong)
        }
    }

    /**
     * 「关」不走换档，而是调用点直接短路（见 rememberHaptic 里的 Off 分支）。
     * 这里钉住的是：叠档函数本身在 Off 时不会偷偷改变语义 —— 免得两条判据互相遮蔽。
     */
    @Test
    fun `关档不改动语义档，短路交给调用方`() {
        allTiers.forEach { tier ->
            assertEquals(tier, tier.withLevel(HapticLevel.Off))
        }
    }

    @Test
    fun `陌生字符串退回标准而不是静默失效`() {
        assertEquals(HapticLevel.Standard, "standard".toHapticLevel())
        assertEquals(HapticLevel.Off, "off".toHapticLevel())
        assertEquals(HapticLevel.Light, "light".toHapticLevel())
        assertEquals(HapticLevel.Strong, "strong".toHapticLevel())
        // 老版本残留 / 手改过的偏好文件都可能留下陌生值
        assertEquals(HapticLevel.Standard, "".toHapticLevel())
        assertEquals(HapticLevel.Standard, "ENABLED".toHapticLevel())
    }
}
