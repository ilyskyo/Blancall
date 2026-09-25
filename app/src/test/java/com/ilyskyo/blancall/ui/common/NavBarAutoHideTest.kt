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
}
