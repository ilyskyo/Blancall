// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.ui.geometry.Offset
import com.ilyskyo.blancall.data.handwriting.HandwritingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉丁多词（词组）支持的单测：词间空隙分组 [splitInkIntoWords] 与
 * 单词提交统一入口 [latinWordChars]。
 *
 * 守两条相反的边界：
 * - 词间大空隙**必须**分组（否则 "give up" 的段数估算会被词间空地污染）；
 * - 词内字母间距**绝不**分组（误分 = 一个词被拆成两个假词、提交出错误空格）。
 * 所以拒绝用例（不分组）与接受用例同样重要。
 */
class LatinWordSplitTest {

    private val h = 180f
    private val letterW = CHAR_ASPECT_LATIN * h // ≈111.6

    /** 与 StrokeGesturesTest 同构的「字母」：三笔铺满 [xStart, xEnd]，高 120→300 */
    private fun glyph(xStart: Float, xEnd: Float): List<List<Offset>> = listOf(
        listOf(Offset(xStart, 120f), Offset(xEnd, 120f)),
        listOf(Offset(xStart, 300f), Offset(xEnd, 300f)),
        listOf(Offset((xStart + xEnd) / 2, 120f), Offset((xStart + xEnd) / 2, 300f))
    )

    /** 造一个词：[letters] 个字母、字母间距 gapRatio×字高 */
    private fun word(xStart: Float, letters: Int, gapRatio: Float = 0.06f): List<List<Offset>> {
        val step = letterW + gapRatio * h
        return (0 until letters)
            .map { i -> glyph(xStart + i * step, xStart + i * step + letterW) }
            .flatten()
    }

    /** 词宽（含末尾字母右缘），用于计算下一词起点 */
    private fun wordSpan(letters: Int, gapRatio: Float = 0.06f): Float =
        (letters - 1) * (letterW + gapRatio * h) + letterW

    // ────────────── splitInkIntoWords：必须分组 ──────────────

    @Test
    fun `两个词间大空隙被分成两组`() {
        // "give"（4 字母）+ 词间距 0.6×字高（> 阈值 0.55×字母宽≈0.34×字高）+ "up"（2 字母）
        val w1 = word(50f, 4)
        val gap = 0.6f * h
        val w2 = word(50f + wordSpan(4) + gap, 2)
        val words = splitInkIntoWords(w1 + w2, CHAR_ASPECT_LATIN)
        assertEquals(2, words?.size)
        // 分组只是归属划分：组内笔画数量必须是原笔画的子集划分（不重不漏）
        assertEquals(w1.size, words!![0].size)
        assertEquals(w2.size, words[1].size)
    }

    @Test
    fun `乱序书写（先右后左）也按 x 位置从左到右分组`() {
        // 先写右边的词、再写左边的词：分组结果必须仍是「左词在前」
        val w1 = word(50f, 2)
        val w2 = word(600f, 2)
        val words = splitInkIntoWords(w2 + w1, CHAR_ASPECT_LATIN)
        assertEquals(2, words?.size)
        val firstAvg = words!![0].flatten().map { it.x }.average()
        val secondAvg = words[1].flatten().map { it.x }.average()
        assertTrue("左词应排前（实际 $firstAvg vs $secondAvg）", firstAvg < secondAvg)
    }

    // ────────────── splitInkIntoWords：绝不误分 ──────────────

    @Test
    fun `词内字母间距不分组——好hello这类紧排词必须是单组`() {
        // 字母间距 8% 字高（真机紧排上限）：远小于词间阈值，绝不能分
        assertNull(splitInkIntoWords(word(50f, 5, gapRatio = 0.08f), CHAR_ASPECT_LATIN))
    }

    @Test
    fun `单个词不分组`() {
        assertNull(splitInkIntoWords(word(50f, 4), CHAR_ASPECT_LATIN))
    }

    @Test
    fun `空墨迹与单笔不分组`() {
        assertNull(splitInkIntoWords(emptyList(), CHAR_ASPECT_LATIN))
        assertNull(
            splitInkIntoWords(
                listOf(listOf(Offset(0f, 0f), Offset(100f, 0f))),
                CHAR_ASPECT_LATIN
            )
        )
    }

    // ────────────── latinWordChars：整词提交统一入口 ──────────────

    private fun c(ch: Char, conf: Float) =
        HandwritingResult.Candidate(index = 0, char = ch, confidence = conf)

    private fun r(ch: Char, conf: Float) =
        HandwritingResult(candidates = listOf(c(ch, conf)), elapsedMs = 1L)

    private val noDouble = listOf(false, false, false, false)

    @Test
    fun `全段达标——返回整词字符列`() {
        assertEquals(
            listOf('g', 'i', 'v', 'e'),
            latinWordChars(
                listOf(r('g', 0.96f), r('i', 0.90f), r('v', 0.90f), r('e', 0.90f)),
                null,
                noDouble
            )
        )
    }

    @Test
    fun `存在未识别段或不可信段——返回 null（回落逐段点选）`() {
        // 缺一段（未识别）
        assertNull(latinWordChars(listOf(r('g', 0.96f), null), null, listOf(false, false)))
        // 明显不可信段（< 0.50）
        assertNull(
            latinWordChars(listOf(r('g', 0.96f), r('i', 0.40f)), null, listOf(false, false))
        )
    }

    @Test
    fun `答案先验——按答案形式提交（大小写与 o0 混淆纠正）`() {
        // 真机：写 Love 输出 L0Ve；答案先验不依赖置信度阈值
        assertEquals(
            listOf('L', 'o', 'v', 'e'),
            latinWordChars(
                listOf(r('L', 0.90f), r('0', 0.35f), r('V', 0.90f), r('e', 0.90f)),
                "Love",
                noDouble
            )
        )
    }

    @Test
    fun `词内双宽段按答案吃两位（ll 并段）`() {
        // "all"：ll 被并成一段（段宽 ≈ 2 倍单字），模型输出 V，由答案先验补齐
        assertEquals(
            listOf('a', 'l', 'l'),
            latinWordChars(listOf(r('a', 0.90f), r('V', 0.30f)), "all", listOf(false, true))
        )
    }

    @Test
    fun `答案与识别不符——回落置信判据，不硬套答案`() {
        // 写的是 "ab"、答案是 "xyz"：先验不匹配 ⇒ 回落整词置信（达标则按识别提交）
        assertEquals(
            listOf('a', 'b'),
            latinWordChars(listOf(r('a', 0.90f), r('b', 0.90f)), "xyz", listOf(false, false))
        )
    }
}
