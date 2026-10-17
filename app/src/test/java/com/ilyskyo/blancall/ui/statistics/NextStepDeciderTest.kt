// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NextStepDecider] 的规则锁定：优先级（薄弱篇 > 复习 > 空态）是一条产品规则，
 * 改坏了界面不会崩、只是推荐的东西悄悄变了 —— 所以必须有一条测试盯着。
 */
class NextStepDeciderTest {

    @Test
    fun `有最弱篇时优先薄弱训练（即使也有到期句）`() {
        assertEquals(
            NextStep.WeaknessDrill(42L),
            NextStepDecider.decide(dueSentences = 10, weakestArticleId = 42L)
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
