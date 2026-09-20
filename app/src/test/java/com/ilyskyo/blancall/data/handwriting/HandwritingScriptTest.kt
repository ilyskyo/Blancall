// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按标准答案自动选文种」的判定。
 *
 * 这条判定决定练习页用哪套手写模型（汉字 3755 类 / EMNIST 47 类），
 * 选错的表现是「怎么写都认不出」——而且不会报错，只会静默给一堆乱码候选。
 */
class HandwritingScriptTest {

    @Test
    fun `中文答案走汉字模型`() {
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("学"))
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("学而时习之"))
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("温故而知新"))
    }

    @Test
    fun `纯拉丁答案走英语模型`() {
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("hello"))
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("Hello"))
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("count"))
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("a"))
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("3.14"))
    }

    @Test
    fun `中英混排按中文处理`() {
        // 典型：语文默写里夹英文缩写、或化学术语（本应用用户是理科生）
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("AI时代"))
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("pH值"))
    }

    @Test
    fun `中文标点算中文`() {
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("，。？！"))
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer("「引号」"))
    }

    @Test
    fun `空答案回退中文——拿不到答案时不要误判成英文`() {
        assertEquals(HandwritingScript.Chinese, HandwritingScript.forAnswer(""))
    }

    @Test
    fun `只有 ASCII 标点和空格也算英文`() {
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer("-- --"))
        assertEquals(HandwritingScript.Latin, HandwritingScript.forAnswer(" "))
    }
}
