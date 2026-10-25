// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NextStepDecider] 的规则锁定：优先级（到期文章 > 薄弱篇 > 复习 > 空态）是一条产品规则，
 * 改坏了界面不会崩、只是推荐的东西悄悄变了 —— 所以必须有一条测试盯着。
 * 到期文章排第一与 [com.ilyskyo.blancall.algorithm.StudyCoach] 的今日计划同序。
 */
class NextStepDeciderTest {

    @Test
    fun `有到期文章时优先复习它（即使也有最弱篇与到期句）`() {
        assertEquals(
            NextStep.DueArticle(9L),
            NextStepDecider.decide(dueSentences = 10, weakestArticleId = 42L, dueArticleId = 9L)
        )
    }

    @Test
    fun `没有到期文章时最弱篇优先于到期句`() {
        assertEquals(
            NextStep.WeaknessDrill(42L),
            NextStepDecider.decide(dueSentences = 10, weakestArticleId = 42L, dueArticleId = null)
        )
    }

    @Test
    fun `只有最弱篇没有到期句也走薄弱训练`() {
        assertEquals(
            NextStep.WeaknessDrill(7L),
            NextStepDecider.decide(dueSentences = 0, weakestArticleId = 7L)
        )
    }

    @Test
    fun `无最弱篇有到期句时去复习`() {
        assertEquals(
            NextStep.Review,
            NextStepDecider.decide(dueSentences = 3, weakestArticleId = null)
        )
    }

    @Test
    fun `都没有时空态`() {
        assertEquals(
            NextStep.AllClear,
            NextStepDecider.decide(dueSentences = 0, weakestArticleId = null)
        )
    }
}
