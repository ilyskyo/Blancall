// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 今日计划 / 时间预算 / 调度理由的规则守卫：改优先级或阈值必须在这里显性改断言。 */
class StudyCoachTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun due(id: Long, dueAt: Long = now - day, blanks: Int = 10, lapses: Int = 0) =
        StudyCoach.DueArticle(
            articleId = id,
            title = "篇$id",
            due = dueAt,
            reviewCount = 3,
            lapses = lapses,
            estimatedBlanks = blanks,
        )

    @Test
    fun `未完成练习排第一，到期文章其次，最多三条`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                dueArticles = listOf(due(1), due(2), due(3)),
                resumables = listOf(
                    StudyCoach.Resumable(9, "未完成", "SENTENCE", 3, 10, now - 1000)
                ),
                dueSentenceCount = 4,
            )
        )
        assertEquals(
            listOf(StudyCoach.Kind.RESUME, StudyCoach.Kind.REVIEW_ARTICLE, StudyCoach.Kind.SENTENCE_CARDS),
            plan.items.map { it.kind }
        )
        assertEquals(3, plan.items.size)
        assertEquals(9L, plan.items[0].articleId)
        assertEquals(1L, plan.items[1].articleId)
        assertEquals(StudyCoach.Kind.SENTENCE_CARDS, plan.items[2].kind)
    }

    @Test
    fun `同一篇文章不会同时以到期和薄弱出现`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                dueArticles = listOf(due(7)),
                weakestArticleId = 7L,
                weakestArticleTitle = "篇7",
                weakMistakeCount = 3,
            )
        )
        assertEquals(1, plan.items.count { it.articleId == 7L })
        assertEquals(StudyCoach.Kind.REVIEW_ARTICLE, plan.items.first().kind)
    }

    @Test
    fun `没有到期内容时按薄弱、句子卡、错题集训依次补位`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                weakestArticleId = 5L,
                weakestArticleTitle = "薄弱",
                weakMistakeCount = 4,
                mistakeCount = 4,
                dueSentenceCount = 6,
                dailyPracticeGoal = 3,
                todayPracticeCount = 1,
            )
        )
        assertEquals(3, plan.items.size)
        assertEquals(StudyCoach.Kind.WEAK_ARTICLE, plan.items[0].kind)
        assertEquals(StudyCoach.Kind.SENTENCE_CARDS, plan.items[1].kind)
        assertEquals(StudyCoach.Kind.MISTAKE_DRILL, plan.items[2].kind)
    }

    @Test
    fun `只剩次数目标时补一条每日目标`() {
        val plan = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = now,
                dailyPracticeGoal = 3,
                todayPracticeCount = 1,
            )
        )
        assertEquals(listOf(StudyCoach.Kind.DAILY_GOAL), plan.items.map { it.kind })
        assertEquals(2, plan.items.single().count)
    }

    @Test
    fun `时间预算宁可少排也不超：85% 阈值`() {
        // 每篇 10 空 × 8 秒 × 1.1 ≈ 1.47 分钟；15 分钟预算应只装到接近 12.75 分钟
        val input = StudyCoach.Input(
            now = now,
            dueArticles = (1L..20L).map { due(it, dueAt = now - it * 1000, blanks = 10) },
            secondsPerBlank = 8.0,
        )
        val budget = StudyCoach.budget(input, 15)
        assertEquals(15, budget.requestedMinutes)
        assertTrue("应装下至少一篇", budget.articleIds.isNotEmpty())
        assertTrue("不应把 15 分钟预算排成 20 分钟", budget.estimatedMinutes <= 15)
        assertTrue("应按到期先后", budget.articleIds.first() == 20L)
        assertEquals(0, budget.sentenceCardCount)
    }

    @Test
    fun `只有句子卡时按句数估算并给出本次上限`() {
        // 40 句到期、无文章、5 分钟：每句 0.4 分钟 → 12 句 ≈ 4.8 分钟
        val budget = StudyCoach.budget(
            StudyCoach.Input(now = now, dueSentenceCount = 40),
            5,
        )
        assertTrue(budget.articleIds.isEmpty())
        assertEquals(12, budget.sentenceCardCount)
        assertEquals(5, budget.estimatedMinutes)
        assertFalse(budget.empty)
    }

    @Test
    fun `有文章可排时预算只承诺文章不承诺句子卡`() {
        val budget = StudyCoach.budget(
            StudyCoach.Input(
                now = now,
                dueArticles = listOf(due(1)),
                dueSentenceCount = 20,
            ),
            15,
        )
        assertEquals(listOf(1L), budget.articleIds)
        assertEquals(0, budget.sentenceCardCount)
    }

    @Test
    fun `没有文章也没有句子时预算为空`() {
        val budget = StudyCoach.budget(StudyCoach.Input(now = now), 10)
        assertTrue(budget.empty)
        assertEquals(0, budget.sentenceCardCount)
        assertEquals(0, budget.estimatedMinutes)
    }

    @Test
    fun `时间预算先装未完成练习`() {
        val budget = StudyCoach.budget(
            StudyCoach.Input(
                now = now,
                dueArticles = listOf(due(1)),
                resumables = listOf(
                    StudyCoach.Resumable(9, "未完成", "WORD", 9, 10, now)
                ),
            ),
            5
        )
        assertEquals(listOf(9L, 1L), budget.articleIds)
    }

    @Test
    fun `速度估算按空数加权并夹在 3 到 30 秒`() {
        assertEquals(5.0, StudyCoach.secondsPerBlank(listOf(10_000L to 2, 50_000L to 10)), 0.001)
        assertEquals(3.0, StudyCoach.secondsPerBlank(listOf(100L to 10)), 0.001)
        assertEquals(30.0, StudyCoach.secondsPerBlank(listOf(600_000L to 10)), 0.001)
        assertEquals(
            StudyCoach.DEFAULT_SECONDS_PER_BLANK,
            StudyCoach.secondsPerBlank(listOf(0L to 10)),
            0.001
        )
    }

    @Test
    fun `调度理由：忘过多次优先于间隔与逾期`() {
        val state = FsrsEngine.CardState(
            due = now - 10 * day,
            lastReview = now - 30 * day,
            reviewCount = 5,
            lapses = 3,
        )
        val reason = StudyCoach.reasonOf(state, now)
        assertEquals(StudyCoach.ReasonKind.LAPSED, reason.kind)
        assertEquals(3, reason.count)
    }

    @Test
    fun `调度理由：没有忘过的新卡是第一次`() {
        assertEquals(
            StudyCoach.ReasonKind.FIRST,
            StudyCoach.reasonOf(null, now).kind
        )
        assertEquals(
            StudyCoach.ReasonKind.FIRST,
            StudyCoach.reasonOf(FsrsEngine.CardState(reviewCount = 0), now).kind
        )
    }

    @Test
    fun `考试倒排只给两态且按每日容量判断`() {
        val today = LocalDate.of(2026, 10, 25)
        val nowAt = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val onTrack = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = nowAt,
                dueArticles = List(5) { due(it.toLong() + 1) },
                dailyPracticeGoal = 3,
                examEpochDay = today.plusDays(30).toEpochDay(),
                zone = ZoneOffset.UTC,
            )
        )
        assertEquals(30, onTrack.examDaysLeft)
        assertEquals(StudyCoach.ExamPace.ON_TRACK, onTrack.examPace)

        val behind = StudyCoach.buildPlan(
            StudyCoach.Input(
                now = nowAt,
                dueArticles = List(5) { due(it.toLong() + 1) },
                weakMistakeCount = 10,
                mistakeCount = 100,
                dailyPracticeGoal = 1,
                examEpochDay = today.plusDays(2).toEpochDay(),
                zone = ZoneOffset.UTC,
            )
        )
        assertEquals(StudyCoach.ExamPace.BEHIND, behind.examPace)
    }
}
