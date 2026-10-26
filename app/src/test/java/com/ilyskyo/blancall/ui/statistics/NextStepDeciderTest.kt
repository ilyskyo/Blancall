// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.algorithm.StudyCoach
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计页「下一步」只是今日计划第一条的映射：六种 Kind 必须一一对应。
 * 以前这里自己排优先级，改坏后界面不会崩、只是两页推荐不同的事 —— 所以逐种钉住。
 */
class NextStepDeciderTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `继续未完成练习映射到Resume`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                resumables = listOf(
                    StudyCoach.Resumable(9, "未完成", "SENTENCE", 3, 10, now - 1_000)
                ),
                dueArticles = listOf(
                    StudyCoach.DueArticle(1, "到期", now - 1_000, reviewCount = 3, estimatedBlanks = 10)
                ),
            )
        )
        assertEquals(NextStep.Resume(9L), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `到期文章映射到DueArticle`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                dueArticles = listOf(
                    StudyCoach.DueArticle(1, "到期", now - 1_000, reviewCount = 3, estimatedBlanks = 10)
                ),
                weakestArticleId = 42L,
            )
        )
        assertEquals(NextStep.DueArticle(1L), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `薄弱篇映射到WeaknessDrill`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                weakestArticleId = 42L,
                weakestArticleTitle = "弱",
                weakMistakeCount = 5,
                dueSentenceCount = 10,
            )
        )
        assertEquals(NextStep.WeaknessDrill(42L), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `句子卡映射到SentenceCards并带上句数`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(now = now, dueSentenceCount = 10, mistakeCount = 5)
        )
        assertEquals(NextStep.SentenceCards(10), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `错题集训映射到MistakeDrill`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(now = now, mistakeCount = 7, dailyPracticeGoal = 3)
        )
        assertEquals(NextStep.MistakeDrill(7), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `每日目标映射到DailyGoal并带上差额`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(now = now, dailyPracticeGoal = 3, todayPracticeCount = 1)
        )
        assertEquals(NextStep.DailyGoal(2), NextStepDecider.fromPlan(plan))
    }

    @Test
    fun `空计划映射到空态`() {
        assertEquals(NextStep.AllClear, NextStepDecider.fromPlan(StudyCoach.Plan()))
        assertEquals(NextStep.AllClear, NextStepDecider.fromItem(null))
    }

    @Test
    fun `暂停文章的薄弱篇不会变成下一步`() {
        val day = 24L * 60 * 60 * 1000
        val snapshot = StudyCoach.snapshot(
            StudyCoach.Source(
                articles = listOf(
                    com.ilyskyo.blancall.data.model.Article(
                        id = 1L, title = "暂停的弱篇", content = "文".repeat(400)
                    )
                ),
                records = listOf(
                    com.ilyskyo.blancall.data.model.PracticeRecord(
                        articleId = 1L,
                        mode = "SENTENCE",
                        totalBlanks = 10,
                        correctCount = 3,
                        timestamp = now - day,
                    )
                ),
                states = mapOf(
                    1L to com.ilyskyo.blancall.algorithm.FsrsEngine.CardState(
                        due = now + day, lastReview = now - day, reviewCount = 3
                    )
                ),
                pauses = mapOf(
                    1L to com.ilyskyo.blancall.algorithm.StudyFlow.Pause(now - day, 0L)
                ),
            ),
            now = now,
        )
        val plan = StudyCoach.buildPlan(snapshot.input)
        assertEquals(null, snapshot.weakest)
        assertEquals(NextStep.AllClear, NextStepDecider.fromPlan(plan))
    }
}
