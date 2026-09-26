// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「下拉揭示品牌区」的松手收尾语义（回归真机 bug）：
 * 松手后的惯性帧不得再积累下拉位移 —— 否则惯性会把品牌区继续往外拽、抢占（取消）
 * 松手回弹动画（Animatable 互斥锁），惯性结束又无收尾回调 ⇒ 品牌区停在露出态
 * 不再自动收回。本测试守护「仅用户拖拽跟手」这一前提。
 */
class HomePullTest {

    @Test
    fun `手指按住拖拽时：允许跟手积累下拉位移`() {
        assertTrue(shouldAccumulateHomePull(NestedScrollSource.Drag))
    }

    @Test
    fun `松手后的惯性帧：不得再积累位移（保证回弹不被抢占取消）`() {
        assertFalse(shouldAccumulateHomePull(NestedScrollSource.Fling))
    }
}
