// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StylusActivity] 板面墨迹计数测试。
 *
 * 纯计数器逻辑，不触碰 View / Compose 运行时。
 */
class InkActivityTest {

    /**
     * [StylusActivity] 是进程级单例，测试跑在同一个 JVM 里、用例顺序不定，
     * 先把计数归零再断言「板空」。只调公开方法，不为测试另开复位入口；
     * notifyBoardCleared 已把存储值夹在 0，所以这个循环必然收敛。
     */
    @Before
    fun drainInkState() {
        repeat(8) {
            if (!StylusActivity.hasInkOnBoard) return@repeat
            StylusActivity.notifyBoardCleared()
        }
    }

    @Test
    fun `空闲时既没有笔在接触也没有墨迹`() {
        assertFalse(StylusActivity.isWriting)
        assertFalse(StylusActivity.hasInkOnBoard)
        assertEquals(false, StylusActivity.inkFlow.value)
    }

    @Test
    fun `板上有墨迹后置位且流发出 true`() {
        StylusActivity.notifyBoardHasInk()
        assertTrue(StylusActivity.hasInkOnBoard)
        assertEquals(true, StylusActivity.inkFlow.value)
        StylusActivity.notifyBoardCleared()
    }

    @Test
    fun `板面清空后复位且流发出 false`() {
        StylusActivity.notifyBoardHasInk()
        StylusActivity.notifyBoardCleared()
        assertFalse(StylusActivity.hasInkOnBoard)
        assertEquals(false, StylusActivity.inkFlow.value)
    }

    @Test
    fun `计数式配对：多块板同时有墨迹时 一块清空不会误报空闲`() {
        // A 板落笔
        StylusActivity.notifyBoardHasInk()
        // B 板落笔（两块板同时收到不同触点的 DOWN）
        StylusActivity.notifyBoardHasInk()
        // A 板清空 —— B 板上还有墨迹，绝不能就此判定为「板空」
        StylusActivity.notifyBoardCleared()
        assertTrue("B 板仍有墨迹，hasInkOnBoard 必须保持 true", StylusActivity.hasInkOnBoard)
        assertEquals(true, StylusActivity.inkFlow.value)

        // B 板清空
        StylusActivity.notifyBoardCleared()
        assertFalse(StylusActivity.hasInkOnBoard)
        assertEquals(false, StylusActivity.inkFlow.value)
    }

    @Test
    fun `重复报清空不会把计数带成负数`() {
        // View 生命周期异常时 finishStroke 与 onDetachedFromWindow 可能都跑一遍清空路径，
        // 计数变负会让 hasInkOnBoard 永远置不回 true —— 提示再也停不下来，且无任何报错。
        StylusActivity.notifyBoardCleared()
        StylusActivity.notifyBoardCleared()
        assertFalse(StylusActivity.hasInkOnBoard)
        // 之后仍能正常配对（这条断言就是上面那个 bug 的回归守卫）
        StylusActivity.notifyBoardHasInk()
        assertTrue(StylusActivity.hasInkOnBoard)
        StylusActivity.notifyBoardCleared()
        assertFalse(StylusActivity.hasInkOnBoard)
    }
}