// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RevealNav] 锚点登记表回归测试（纯 JVM，可注入时钟）。
 *
 * 这些行为直接决定浮起转场是否"点谁从谁浮出、返回缩回原处"：
 * 路由基础段匹配、消费即清空、过期防护、非消耗探测、条目绑定与清理、变换原点换算。
 */
class RevealNavTest {

    private var fakeNow = 10_000L

    @Before
    fun setUp() {
        RevealNav.reset()
        fakeNow = 10_000L
        RevealNav.clock = { fakeNow }
    }

    @After
    fun tearDown() {
        RevealNav.reset()
    }

    @Test
    fun `post 后按基础路由消费并绑定条目且消费即清空`() {
        RevealNav.post("reader/12", TouchAnchor(100f, 200f))
        // 实际路由与路由模板（含参数）都按基础段匹配
        val got = RevealNav.consume("reader/{articleId}", "entry-1")
        assertEquals(TouchAnchor(100f, 200f), got)
        assertEquals(TouchAnchor(100f, 200f), RevealNav.bound("entry-1"))
        // 消费即清空：同路由的下一次普通导航不会被残留锚点污染
        assertNull(RevealNav.consume("reader/{articleId}", "entry-2"))
        assertNull(RevealNav.pendingFor("reader/{articleId}"))
    }

    @Test
    fun `路由不匹配时消费返回 null 并清除待用`() {
        RevealNav.post("reader/12", TouchAnchor(1f, 2f))
        assertNull(RevealNav.consume("settings", "entry-1"))
        assertNull(RevealNav.pendingFor("reader/{articleId}"))
    }

    @Test
    fun `过期锚点消费与探测均不生效`() {
        RevealNav.post("search", TouchAnchor(1f, 2f))
        fakeNow += 5_000L // 超过 2s 有效期
        assertNull(RevealNav.consume("search", "entry-1"))
        assertNull(RevealNav.pendingFor("search"))
    }

    @Test
    fun `pendingFor 为非消耗探测`() {
        RevealNav.post("reader/9", TouchAnchor(5f, 6f))
        assertNotNull(RevealNav.pendingFor("reader/{articleId}"))
        assertNull(RevealNav.pendingFor("settings"))
        // 探测不消费：随后仍可正常被入口转场取走
        assertNotNull(RevealNav.consume("reader/{articleId}", "entry-2"))
    }

    @Test
    fun `played 标记与 forget 清理`() {
        RevealNav.post("help", TouchAnchor(0f, 0f))
        RevealNav.consume("help", "entry-1")
        assertFalse(RevealNav.hasPlayed("entry-1"))
        RevealNav.markPlayed("entry-1")
        assertTrue(RevealNav.hasPlayed("entry-1"))
        // 条目销毁：锚点与标记一并清理
        RevealNav.forget("entry-1")
        assertNull(RevealNav.bound("entry-1"))
        assertFalse(RevealNav.hasPlayed("entry-1"))
    }

    @Test
    fun `originOf 归一化坐标并扣除侧栏偏移`() {
        RevealNav.updateSurface(width = 1000, height = 2000, offsetX = 100f)
        val o = RevealNav.originOf(TouchAnchor(300f, 1000f))
        assertEquals(0.2f, o.pivotFractionX, 1e-4f) // (300-100)/1000
        assertEquals(0.5f, o.pivotFractionY, 1e-4f) // 1000/2000
        // 尺寸未知（进程重建等）退化为屏幕中心，不产生 NaN/越界
        RevealNav.updateSurface(0, 0, 0f)
        val c = RevealNav.originOf(TouchAnchor(10f, 10f))
        assertEquals(0.5f, c.pivotFractionX, 1e-4f)
        assertEquals(0.5f, c.pivotFractionY, 1e-4f)
    }

    @Test
    fun `baseRoute 提取基础段`() {
        assertEquals("reader", RevealNav.baseRoute("reader/12"))
        assertEquals("custom_cloze_list", RevealNav.baseRoute("custom_cloze_list/3?pick=true"))
        assertEquals("search", RevealNav.baseRoute("search"))
    }
}
