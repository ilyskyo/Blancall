// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.cloze

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 默认配置名编号规则（纯函数守卫）。
 *
 * 为什么单独测：这条规则读的是**已存盘的显示名**。换语言后新名跟着界面语言（"Custom 3"），
 * 而旧数据仍是中文「自定义 2」—— 一旦只认当前前缀，max 会退化成 0，下一条就叫 "Custom 1"，
 * 与旧数据里可能存在的名字撞车，而重名检查会静默把用户挡在保存之外。
 * 编辑器又要有文章才进得去，设备上不一定看得见，所以逻辑必须能在 JVM 里红。
 */
class DefaultConfigNameTest {

    @Test
    fun 空列表从一条开始() {
        assertEquals("自定义 1", nextDefaultConfigName(emptyList(), "自定义"))
        assertEquals("Custom 1", nextDefaultConfigName(emptyList(), "Custom"))
    }

    @Test
    fun 旧中文名要给新语言的前缀让号() {
        // 只有历史中文数据：换到英文后不能从 1 重新开始
        assertEquals("Custom 4", nextDefaultConfigName(listOf("自定义 1", "自定义 3"), "Custom"))
    }

    @Test
    fun 同语言内取最大编号加一() {
        assertEquals("自定义 6", nextDefaultConfigName(listOf("自定义 5", "我的篇目", "自定义 2"), "自定义"))
    }

    @Test
    fun 编号被占用时继续跳过() {
        // max 算出 2，但 "Custom 2" 已在（畸形历史数据），要跳到 3
        assertEquals("Custom 3", nextDefaultConfigName(listOf("Custom 2"), "Custom"))
    }

    @Test
    fun 用户自取的名字不参与编号也不被吞掉() {
        // 「第 3 课」结尾是数字但不是我们的前缀 ⇒ 不该把 max 抬到 3
        assertEquals("自定义 1", nextDefaultConfigName(listOf("第 3 课", "2024 版"), "自定义"))
    }

    @Test
    fun 前后空白不影响识别() {
        assertEquals("自定义 3", nextDefaultConfigName(listOf("  自定义 2  "), "自定义"))
    }
}
