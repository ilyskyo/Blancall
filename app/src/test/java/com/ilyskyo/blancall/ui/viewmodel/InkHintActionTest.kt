// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 板面墨迹变化对提示计时的处置判定（[inkHintAction]）。
 *
 * 这是本次改动的核心决策：手写态**照常**走强弱提示，只在板面真有墨迹时拦下。
 */
class InkHintActionTest {

    @Test
    fun `板上有墨迹时取消提示`() {
        assertEquals(
            InkHintAction.CANCEL,
            inkHintAction(hasInkOnBoard = true, submitted = false, hintEnabled = true)
        )
    }

    @Test
    fun `板空后恢复提示`() {
        assertEquals(
            InkHintAction.RESUME,
            inkHintAction(hasInkOnBoard = false, submitted = false, hintEnabled = true)
        )
    }

    @Test
    fun `已提交时不恢复也不取消`() {
        // 提交后板空不该把提示计时重新拉起来（答案已定，再提示就是干扰）
        assertEquals(
            InkHintAction.IDLE,
            inkHintAction(hasInkOnBoard = false, submitted = true, hintEnabled = true)
        )
        assertEquals(
            InkHintAction.IDLE,
            inkHintAction(hasInkOnBoard = true, submitted = true, hintEnabled = true)
        )
    }

    @Test
    fun `提示开关关闭时什么都不做`() {
        assertEquals(
            InkHintAction.IDLE,
            inkHintAction(hasInkOnBoard = false, submitted = false, hintEnabled = false)
        )
        assertEquals(
            InkHintAction.IDLE,
            inkHintAction(hasInkOnBoard = true, submitted = false, hintEnabled = false)
        )
    }

    @Test
    fun `手写模式开着但板空时照常恢复提示`() {
        // 回归守卫：早先的判定是 handwritingInputEnabled || isWriting，
        // 只要模式是手写就永远为真 —— 手写全程拿不到提示。改成只看板面墨迹后，
        // 「模式=手写 + 板空」必须能拿到提示。
        assertEquals(
            InkHintAction.RESUME,
            inkHintAction(hasInkOnBoard = false, submitted = false, hintEnabled = true)
        )
    }

    @Test
    fun `抬笔但墨迹未上屏时仍然取消提示`() {
        // 回归守卫：抬笔后到识别完成前、以及低置信候选等点选期间，墨迹都留在板上，
        // 用户仍在写那一个字。此时强提示自动填入会与板上那个字重复 ⇒ 必须继续拦。
        // 判据若写成「笔尖是否按着」，这里就会误判成 RESUME。
        assertEquals(
            InkHintAction.CANCEL,
            inkHintAction(hasInkOnBoard = true, submitted = false, hintEnabled = true)
        )
    }
}