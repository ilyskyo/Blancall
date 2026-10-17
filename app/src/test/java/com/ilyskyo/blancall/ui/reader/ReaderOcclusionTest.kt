// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderOcclusion.localRangesInPara] 的算法遮挡回归测试（纯字符串逻辑，JVM 可测）。
 *
 * 存在的理由是一个真机反馈过的缺陷：旧实现的 long / mixed / short 三条路径全部先问
 * 「这段里有没有汉字」，纯英文段落直接跳过，于是英文文章永远遮不出东西，用户只能手动画
 * 自定义遮挡。修复（pickHardWords / hasLatinWordInRange）曾只落在 Pro，NoAI 版带着旧实现
 * 连发三个版本。本文件就是那段时间里唯一会红的东西，别删。
 */
class ReaderOcclusionTest {

    private fun spans(para: String, mode: String) = ReaderOcclusion.localRangesInPara(para, mode)

    private fun covered(para: String, mode: String) =
        spans(para, mode).map { para.substring(it.start, it.end) }

    @Test
    fun `英文段落长遮挡整句盖住`() {
        val para = "The examination system shaped careers."
        val got = spans(para, "long")
        assertEquals("整句应合成一块", 1, got.size)
        assertEquals(para, para.substring(got[0].start, got[0].end))
    }

    @Test
    fun `英文段落混合遮挡也能出块`() {
        assertTrue("mixed 对纯英文不该空手而归", spans("Reading widens the mind.", "mixed").isNotEmpty())
    }

    @Test
    fun `英文短遮挡挑最长的两个词且不遮短词`() {
        val para = "He did the examination systematically"
        val got = covered(para, "short")
        // 词长按降序取两颗；He / did / the 太短，对背词无意义
        assertEquals(listOf("systematically", "examination"), got)
    }

    @Test
    fun `只有虚词的短句什么都不遮`() {
        val para = "a is to of"
        assertTrue("short 不该遮短词", spans(para, "short").isEmpty())
        assertTrue("long 也不该把无信息短句整块盖住", spans(para, "long").isEmpty())
        assertTrue("mixed 同上", spans(para, "mixed").isEmpty())
    }

    @Test
    fun `中文分句仍按逗号切分逐句遮挡`() {
        val para = "学而时习之，不亦说乎。"
        val got = covered(para, "long")
        assertEquals("两个分句两块", 2, got.size)
        assertEquals("学而时习之，", got[0])
        assertEquals("不亦说乎。", got[1])
    }

    @Test
    fun `中英混排的短遮挡至少能遮住英文长词`() {
        val para = "孔子说 knowledge is power"
        val got = covered(para, "short")
        assertTrue("混排行应含英文长词：$got", got.any { it == "knowledge" })
    }

    @Test
    fun `遮挡区间都落在原文范围内`() {
        val para = "Practice makes perfect，熟能生巧。"
        for (mode in listOf("long", "short", "mixed")) {
            spans(para, mode).forEach {
                assertTrue("区间越界：$it", it.start in 0..para.length && it.end <= para.length && it.start < it.end)
            }
        }
    }
}
