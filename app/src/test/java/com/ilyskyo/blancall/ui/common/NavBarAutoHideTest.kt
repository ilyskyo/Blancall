// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavBarAutoHide] 键集合语义测试：多来源请求互不打架、最后一个释放才恢复；
 * 幂等（重复请求 / 重复释放不破坏状态）。
 *
 * 注意：单例全局状态 —— 用例内只用临时键并在 finally 里释放，保证不污染其它用例。
 */
class NavBarAutoHideTest {

    @Test
    fun `键集合语义：全部释放后才复位`() {
        val a = "test_key_a"
        val b = "test_key_b"
        try {
            NavBarAutoHide.request(a)
            assertTrue("任一来源请求即收起", NavBarAutoHide.hidden.value)
            NavBarAutoHide.request(b)
            assertTrue(NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(a)
            assertTrue("仍有来源请求时保持收起", NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(b)
            assertFalse("全部释放后恢复显示", NavBarAutoHide.hidden.value)
        } finally {
            NavBarAutoHide.release(a)
            NavBarAutoHide.release(b)
        }
    }

    @Test
    fun `蓄力手势键能压住底栏_滚动键提前释放也不会把圆环露出来`() {
        // 回归：任务流底部上拉返回时，蓄力连接吃掉了滚动量，滚动键被误判为「停止」
        // 并在 350ms 后释放 → 底栏滑出盖住圆环。手势键必须独立压住底栏。
        try {
            NavBarAutoHide.request(NavBarAutoHide.KEY_SCROLL)
            NavBarAutoHide.request(NavBarAutoHide.KEY_PULL_GESTURE)
            NavBarAutoHide.release(NavBarAutoHide.KEY_SCROLL)
            assertTrue("手势未结束时底栏必须保持收起", NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(NavBarAutoHide.KEY_PULL_GESTURE)
            assertFalse("手势结束后才恢复", NavBarAutoHide.hidden.value)
        } finally {
            NavBarAutoHide.release(NavBarAutoHide.KEY_SCROLL)
            NavBarAutoHide.release(NavBarAutoHide.KEY_PULL_GESTURE)
        }
    }

    @Test
    fun `幂等：重复请求同一键只算一次，释放即复位`() {
        val k = "test_key_idem"
        try {
            NavBarAutoHide.request(k)
            NavBarAutoHide.request(k)
            NavBarAutoHide.release(k)
            assertFalse(NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(k)
            assertFalse("重复释放不改变状态", NavBarAutoHide.hidden.value)
        } finally {
            NavBarAutoHide.release(k)
        }
    }

    @Test
    fun `释放未请求过的键不改变状态`() {
        NavBarAutoHide.release("test_key_missing")
        assertFalse(NavBarAutoHide.hidden.value)
    }

    @Test
    fun `长按操作退出后能正常恢复 —— 回归：onDispose 状态快照陷阱（v7_1_2）`() {
        // 回归背景：手写 DisposableEffect(x){ if(x) request; onDispose{ if(x) release } } 在退出
        // 状态时 onDispose 读到已复位的 false、跳过释放 ⇒ 键永久残留、底栏不再出现。
        // 正确配对（AutoHideNavBarOnFlag 快照语义）：长按操作结束 ⇒ 恢复显示；多来源互不误伤。
        val listKey = NavBarAutoHide.KEY_LIST_MULTI_SELECT
        val editKey = NavBarAutoHide.KEY_HOME_CARD_EDIT
        try {
            // ① 「我的文章」多选退出 → 恢复
            NavBarAutoHide.request(listKey)
            assertTrue(NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(listKey)
            assertFalse("多选退出后底栏应恢复显示", NavBarAutoHide.hidden.value)

            // ② 首页卡片编辑态退出 → 恢复
            NavBarAutoHide.request(editKey)
            assertTrue(NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(editKey)
            assertFalse("首页编辑态退出后底栏应恢复显示", NavBarAutoHide.hidden.value)

            // ③ 两个来源并存：先退一个仍收起，全退才恢复（互不误伤）
            NavBarAutoHide.request(listKey)
            NavBarAutoHide.request(editKey)
            NavBarAutoHide.release(listKey)
            assertTrue("另一来源仍在请求时保持收起", NavBarAutoHide.hidden.value)
            NavBarAutoHide.release(editKey)
            assertFalse("全部长按操作结束 → 恢复显示", NavBarAutoHide.hidden.value)
        } finally {
            NavBarAutoHide.release(listKey)
            NavBarAutoHide.release(editKey)
        }
    }

    @Test
    fun `不可滚页面的上滑不得被误判「到底」（底栏不得保持收起）`() {
        // 回归：「我的文章」文章数不足、完全不可滚时，滑动 consumed 恒 0 —— 绝不标记「到底」
        assertFalse(isScrollAtEnd(scrollable = false, availableY = -10f))
    }

    @Test
    fun `可滚页面：向上滑到底标记「到底」保持收起，仍有余量则不标记`() {
        assertTrue(isScrollAtEnd(scrollable = true, availableY = -10f))
        assertFalse(isScrollAtEnd(scrollable = true, availableY = 0f))
        assertFalse(isScrollAtEnd(scrollable = true, availableY = 10f))
    }

    @Test
    fun `真实滚动消费（含 fling 惯性）标识页面可滚动`() {
        assertTrue(hasScrollConsumed(-5f))
        assertTrue(hasScrollConsumed(5f))
        assertFalse(hasScrollConsumed(0f))
    }

    @Test
    fun `滚动方向：向上滚（从底部滑往顶部、回看）为正，向下滚为负`() {
        // 回归锚点：改版前「只要滚动就收起」—— 回看时底栏也不见了，想切 tab 得反向下滑。
        // 符号别改反：手指向下滑（回看更早内容）= 正；手指向上滑（看后续内容）= 负。
        assertTrue("回看：底栏不收起", isScrollingTowardTop(12f))
        assertTrue(isScrollingTowardTop(0.5f))
        assertFalse("看后续：允许收起", isScrollingTowardTop(-12f))
        assertFalse("零位移不构成方向", isScrollingTowardTop(0f))
    }
}
