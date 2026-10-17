// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面语言判定的纯函数守卫（不需要设备，两条通道都测得到）。
 *
 * 为什么单独测这个：Android 13 以下的切换靠 `attachBaseContext` 注入本地 tag，
 * 而我手上没有 API<33 的机器 —— 如果判定逻辑写错（比如 zh-Hans 与 zh-Hant 混成一条），
 * 装机验证也看不见，只有这条测试能红。
 */
class AppLocaleResolveTest {

    private fun legacy(tag: String) = AppLocale.resolve(null, tag, false)
    private fun framework(tag: String?) = AppLocale.resolve(tag, "ja", true)

    @Test
    fun 空值就是跟随系统() {
        assertEquals(AppLocale.FollowSystem, AppLocale.resolve(null, "", false))
        assertEquals(AppLocale.FollowSystem, AppLocale.resolve("", "", false))
        assertEquals(AppLocale.FollowSystem, framework(null))
        assertEquals(AppLocale.FollowSystem, framework(""))
    }

    @Test
    fun 两条通道各自读自己的来源且框架优先() {
        // 13 以上：框架说了算，本地那份是升级前的遗留值，不能盖过它
        assertEquals(AppLocale.Korean, framework("ko"))
        // 13 以下：读本地 tag
        assertEquals(AppLocale.Japanese, legacy("ja"))
        // 框架报空（用户没单独设过）时也是跟随系统，不回落到本地遗留值
        assertEquals(AppLocale.FollowSystem, framework(null))
    }

    @Test
    fun 简体与繁体不许混成一条() {
        assertEquals(AppLocale.ChineseSimplified, legacy("zh-Hans"))
        assertEquals(AppLocale.ChineseTraditional, legacy("zh-Hant"))
        assertFalse(AppLocale.matches("zh-Hans", "zh-Hant"))
        assertFalse(AppLocale.matches("zh-Hant", "zh-Hans"))
        // 系统常见的写法是地区码，要各自认对
        assertEquals(AppLocale.ChineseSimplified, framework("zh-Hans-CN"))
        assertEquals(AppLocale.ChineseTraditional, framework("zh-Hant-TW"))
        assertEquals(AppLocale.ChineseTraditional, framework("zh-Hant-HK"))
        assertEquals(AppLocale.ChineseSimplified, framework("zh-CN"))
    }

    @Test
    fun 写法差异与多语言列表都要认() {
        assertEquals(AppLocale.Russian, framework("ru_RU"))          // 下划线写法
        assertEquals(AppLocale.English, framework("en-GB"))          // want 不带子标签 → 按语言匹配
        assertEquals(AppLocale.Japanese, legacy("ja,ko"))            // 列表取第一个
        assertEquals(AppLocale.FollowSystem, framework("tlh"))       // 不认识的语种回落跟随系统
        assertTrue(AppLocale.matches("en", "en-US"))
    }

    @Test
    fun 每个可选项都有唯一tag且清单里声明过() {
        val tags = AppLocale.entries.map { it.tag }
        assertEquals("语言项不许重复 tag", tags.size, tags.toSet().size)
        assertEquals(7, tags.size)                        // 跟随系统 + 6 语
        assertTrue(tags.first().isEmpty())                // 第一项必须是跟随系统
    }
}
