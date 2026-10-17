// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.algorithm.FsrsEngine.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐句评级：graded 练习第一次把「哪句生疏」告诉调度器。
 *
 * 以前一场练习几十个空只产出一个文章级评级，句子级状态是句子卡片自评专用的命名空间，
 * 两套评级体系互不相交 —— 统计页能说「这篇练熟了」，却说不出「这句该复习了」。
 */
class SentencePracticeRatingsTest {

    // 两个句子，各自两个空位：句子 0 是空位 0、1；句子 1 是空位 2、3
    private val owner: (Int) -> Int? = { blank -> if (blank < 2) 0 else 1 }

    private fun map(vararg pairs: Pair<Int, AnswerChecker.Result>) = pairs.toMap()

    @Test
    fun `全对的句子给 GOOD 起步而不是 EASY`() {
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT, 1 to AnswerChecker.Result.CORRECT,
                2 to AnswerChecker.Result.CORRECT, 3 to AnswerChecker.Result.CORRECT),
            owner
        )
        assertEquals(2, ratings.size)
        // ratingFromAccuracy：≥0.9 是 EASY。逐句只看这几个空写没写对，
        // 一句全对不等于「轻松记住」，但沿用同一把尺子比另立一档更可信
        assertEquals(Rating.EASY, ratings[0])
        assertEquals(Rating.EASY, ratings[1])
    }

    @Test
    fun `一句错一半落在 HARD`() {
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT, 1 to AnswerChecker.Result.TYPO),
            owner
        )
        assertEquals(Rating.HARD, ratings[0])
    }

    @Test
    fun `按句归账而不是把整场塌成一个评级`() {
        // 这场练习整体 3/4 正确（会话级会算成 GOOD），
        // 但句子 1 一句都没写对 —— 只有逐句账才肯把它标成AGAIN，这正是本次改动的意义
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT, 1 to AnswerChecker.Result.CORRECT,
                2 to AnswerChecker.Result.MISSING, 3 to AnswerChecker.Result.INCORRECT),
            owner
        )
        assertEquals(Rating.EASY, ratings[0])
        assertEquals(Rating.AGAIN, ratings[1])
    }

    @Test
    fun `认不出归属的空位不进任何一句`() {
        // 段落模式恢复等场景拿不到「空位属于哪句」：宁可少记一句的账，
        // 也不要把错处算到猜出来的句子上（与热力图那条闸同一个原则）
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT, 9 to AnswerChecker.Result.TYPO)
        ) { null }
        assertTrue(ratings.isEmpty())
    }

    @Test
    fun `没判分的句子不被记成全对`() {
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT),
            owner
        )
        assertEquals(setOf(0), ratings.keys)
        assertFalse("句子 1 一个空都没答，不该出现在账上", ratings.containsKey(1))
    }

    @Test
    fun `近似对不算对与正确数同一口径`() {
        // correctCount 只数 CORRECT，这里也必须只数 CORRECT：
        // 一处把「仅标点差异/近似」算对、另一处不算，统计与调度就会各说各话
        val ratings = SentencePracticeRatings.bySentence(
            map(0 to AnswerChecker.Result.CORRECT, 1 to AnswerChecker.Result.WRONG_ORDER),
            owner
        )
        assertEquals(Rating.HARD, ratings[0])
    }

    @Test
    fun `句子键与每日一句同一套`() {
        // 逐句状态写进的是同一个键空间，所以句子卡片与练习页看到的是同一张卡
        val a = SentenceSelector.sentenceKey(7L, "先天下之忧而忧")
        val b = SentenceSelector.sentenceKey(7L, "  先天下之忧而忧  ")
        assertEquals(a, b)
        assertEquals(7L, SentenceSelector.articleIdOf(a)!!)
    }
}
