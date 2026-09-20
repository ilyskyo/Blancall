// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import com.ilyskyo.blancall.data.handwriting.HandwritingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拉丁（英语单词）「整词一次性提交」判定（[latinWordCommitChars]）回归测试。
 *
 * 用户明确要求：写完一整个单词，一个整个地识别（像单词默写软件），而不是字母逐个上屏。
 * 但不能为此放松安全性：出现明显不可信的字母段（< 0.50）时必须放弃整词自动上屏，
 * 回落逐段点选 —— 这两条相反的边界都要被锁死。
 */
class LatinWordCommitTest {

    private fun c(ch: Char, conf: Float) =
        HandwritingResult.Candidate(index = 0, char = ch, confidence = conf)

    private fun r(ch: Char, conf: Float) =
        HandwritingResult(candidates = listOf(c(ch, conf)), elapsedMs = 1L)

    @Test
    fun `全段达标——整词一次提交`() {
        val word = latinWordCommitChars(listOf(r('h', 0.96f), r('e', 0.65f), r('o', 0.80f)))
        assertEquals(listOf('h', 'e', 'o'), word)
    }

    @Test
    fun `出现明显不可信段——拒绝整词提交`() {
        assertNull(latinWordCommitChars(listOf(r('h', 0.96f), r('e', 0.45f))))
    }

    @Test
    fun `平均不够——拒绝整词提交`() {
        assertNull(latinWordCommitChars(listOf(r('h', 0.72f), r('e', 0.60f))))
    }

    @Test
    fun `单字母放宽到 0_70 即可提交、0_65 拒绝`() {
        assertEquals(listOf('a'), latinWordCommitChars(listOf(r('a', 0.70f))))
        assertNull(latinWordCommitChars(listOf(r('a', 0.65f))))
    }

    @Test
    fun `空结果或存在未识别段——拒绝`() {
        assertNull(latinWordCommitChars(emptyList()))
        assertNull(latinWordCommitChars(listOf(r('h', 0.96f), null)))
    }

    @Test
    fun `最低段恰踩 0_50 线、平均有余量——通过`() {
        val word = latinWordCommitChars(listOf(r('a', 1.00f), r('b', 0.50f)))
        assertEquals(listOf('a', 'b'), word)
    }

    // ============ 答案先验：容错匹配（[matchLatinWordToAnswer]，o/0、1/l 混淆纠正） ============

    @Test
    fun `答案先验——o0 混淆纠正后按答案形式提交`() {
        // 真机：「Love」被模型输出为「L0Ve」（EMNIST 的 o/0 天生混淆）
        assertEquals("Love", matchLatinWordToAnswer(listOf('L', '0', 'V', 'e'), "Love"))
    }

    @Test
    fun `答案先验——大小写不一致按答案形式提交`() {
        assertEquals("Hello", matchLatinWordToAnswer(listOf('h', 'e', 'l', 'l', 'o'), "Hello"))
    }

    @Test
    fun `答案先验——1lI 混淆组等价`() {
        assertEquals(
            "iloveyou",
            matchLatinWordToAnswer(
                listOf('1', 'l', 'o', 'v', 'e', 'y', 'o', 'u'),
                "iloveyou"
            )
        )
    }

    @Test
    fun `答案先验——长度不符不劫持`() {
        assertNull(matchLatinWordToAnswer(listOf('L', '0', 'V'), "Love"))
    }

    @Test
    fun `答案先验——逐位不对不劫持（保守不猜）`() {
        assertNull(matchLatinWordToAnswer(listOf('L', '0', 'V', 'a'), "Love"))
    }

    @Test
    fun `答案先验——空答案或空输入返回 null`() {
        assertNull(matchLatinWordToAnswer(emptyList(), ""))
        assertNull(matchLatinWordToAnswer(listOf('a'), ""))
    }

    @Test
    fun `答案先验——双宽段（ll 合并段）按答案补齐`() {
        // 真机：hello 的「ll」被并成一段（模型输出 V:0.35），段宽≈2 倍单字 → 按答案吃两位
        assertEquals(
            "hello",
            matchLatinWordToAnswer(
                listOf('h', 'e', 'V', 'o'),
                "hello",
                listOf(false, false, true, false)
            )
        )
    }

    @Test
    fun `答案先验——双宽段越界或两位不同则拒绝`() {
        // 段吃 2 位会超出答案长度
        assertNull(matchLatinWordToAnswer(listOf('a', 'X'), "ab", listOf(false, true)))
        // 答案两位不同（b≠c）不允许一对二
        assertNull(matchLatinWordToAnswer(listOf('a', 'b'), "abc", listOf(false, true)))
    }

    @Test
    fun `答案先验——6b 混淆组（真机日志：写 bw 输出 6W）`() {
        assertEquals("bw", matchLatinWordToAnswer(listOf('6', 'W'), "bw"))
    }
}
