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
 * 路由基础段匹配、幂等解析与条目认领（同一导航多次求值结果一致）、过期防护、
 * 非消耗探测、条目绑定与清理、变换原点换算。
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
    fun `同一导航内重复求值返回一致锚点且幂等绑定`() {
        RevealNav.post("reader/12", TouchAnchor(100f, 200f))
        // 框架对一次导航会多次求值转场（源页退出 + 目标页进入）：结果必须逐次一致，
        // 否则真正生效的那次会拿到 null 回退成旧滑入动画（历史缺陷回归点）
        val first = RevealNav.anchorFor("reader/{articleId}", "entry-1")
        val second = RevealNav.anchorFor("reader/{articleId}", "entry-1")
        val third = RevealNav.anchorFor("reader/{articleId}", "entry-1")
        assertEquals(TouchAnchor(100f, 200f), first)
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(TouchAnchor(100f, 200f), RevealNav.bound("entry-1"))
    }

    @Test
    fun `待用锚点被首条目认领后不被其它条目复用`() {
        RevealNav.post("reader/12", TouchAnchor(1f, 2f))
        assertNotNull(RevealNav.anchorFor("reader/{articleId}", "entry-1"))
        // 同路由的后续普通导航（未登记新锚点）不得复用残留锚点
        assertNull(RevealNav.anchorFor("reader/{articleId}", "entry-2"))
        assertNull(RevealNav.peek("reader/{articleId}", "entry-2"))
        // 已绑定条目仍可重复解析（幂等）
        assertNotNull(RevealNav.anchorFor("reader/{articleId}", "entry-1"))
        // 重新登记后解锁下一次浮起
        RevealNav.post("reader/13", TouchAnchor(3f, 4f))
        assertNotNull(RevealNav.anchorFor("reader/{articleId}", "entry-3"))
    }

    @Test
    fun `路由不匹配时解析返回 null 且不破坏待用锚点`() {
        RevealNav.post("reader/12", TouchAnchor(1f, 2f))
        assertNull(RevealNav.anchorFor("settings", "entry-1"))
        assertNull(RevealNav.peek("settings", "entry-1"))
        // 不匹配不消耗：正确路由随后仍可命中
        assertNotNull(RevealNav.anchorFor("reader/{articleId}", "entry-2"))
    }

    @Test
    fun `过期锚点解析与探测均不生效`() {
        RevealNav.post("search", TouchAnchor(1f, 2f))
        fakeNow += 5_000L // 超过 2s 有效期
        assertNull(RevealNav.peek("search", "entry-1"))
        assertNull(RevealNav.anchorFor("search", "entry-1"))
    }

    @Test
    fun `peek 为非破坏探测`() {
        RevealNav.post("reader/9", TouchAnchor(5f, 6f))
        assertNotNull(RevealNav.peek("reader/{articleId}", "entry-1"))
        assertNotNull(RevealNav.peek("reader/{articleId}", "entry-1"))
        // 探测不认领不消耗：随后入口转场仍可正常绑定
        assertNotNull(RevealNav.anchorFor("reader/{articleId}", "entry-2"))
    }

    @Test
    fun `绑定条目销毁后残留待用锚点不会污染后续普通导航`() {
        RevealNav.post("settings", TouchAnchor(1f, 2f))
        assertNotNull(RevealNav.anchorFor("settings", "entry-1"))
        RevealNav.forget("entry-1")
        assertNull(RevealNav.bound("entry-1"))
        assertNull(RevealNav.anchorFor("settings", "entry-2"))
        // 重新登记（post）重置认领状态
        RevealNav.post("settings", TouchAnchor(3f, 4f))
        assertNotNull(RevealNav.anchorFor("settings", "entry-3"))
    }

    @Test
    fun `played 标记与 forget 清理`() {
        RevealNav.post("help", TouchAnchor(0f, 0f))
        RevealNav.anchorFor("help", "entry-1")
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
