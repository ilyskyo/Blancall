// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手写标点文种适配（[localizePunct]）测试。
 *
 * 边界：中文半角→全角；拉丁不映射；已是中文标点的字符原样保留；批量路径与单字一致。
 */
class PunctuationMapTest {

    @Test
    fun `中文半角标点映射为全角`() {
        assertEquals('，', localizePunct(HandwritingScript.Chinese, ','))
        assertEquals('？', localizePunct(HandwritingScript.Chinese, '?'))
        assertEquals('！', localizePunct(HandwritingScript.Chinese, '!'))
        assertEquals('：', localizePunct(HandwritingScript.Chinese, ':'))
        assertEquals('；', localizePunct(HandwritingScript.Chinese, ';'))
        assertEquals('（', localizePunct(HandwritingScript.Chinese, '('))
        assertEquals('）', localizePunct(HandwritingScript.Chinese, ')'))
    }

    @Test
    fun `已是中文标点或普通字符保持原样`() {
        // 。、…《》 是模型自带的中文标点类，不得二次映射
        assertEquals('。', localizePunct(HandwritingScript.Chinese, '。'))
        assertEquals('、', localizePunct(HandwritingScript.Chinese, '、'))
        assertEquals('…', localizePunct(HandwritingScript.Chinese, '…'))
        assertEquals('《', localizePunct(HandwritingScript.Chinese, '《'))
        assertEquals('》', localizePunct(HandwritingScript.Chinese, '》'))
        // 汉字与未纳入映射的符号（. 与 " 刻意不映射）
        assertEquals('哈', localizePunct(HandwritingScript.Chinese, '哈'))
        assertEquals('.', localizePunct(HandwritingScript.Chinese, '.'))
        assertEquals('"', localizePunct(HandwritingScript.Chinese, '"'))
    }

    @Test
    fun `拉丁文种不映射`() {
        assertEquals(',', localizePunct(HandwritingScript.Latin, ','))
        assertEquals('?', localizePunct(HandwritingScript.Latin, '?'))
        assertEquals('!', localizePunct(HandwritingScript.Latin, '!'))
    }

    @Test
    fun `批量映射与单字一致`() {
        assertEquals(
            listOf('，', '哈', '。'),
            localizePunct(HandwritingScript.Chinese, listOf(',', '哈', '。'))
        )
        // 无受映射字符时原样（短路路径）
        val plain = listOf('哈', '。', '《')
        assertEquals(plain, localizePunct(HandwritingScript.Chinese, plain))
        // 拉丁批量不映射
        assertEquals(
            listOf(',', 'a'),
            localizePunct(HandwritingScript.Latin, listOf(',', 'a'))
        )
    }
}
