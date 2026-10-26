// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日工作快照的口径守卫：暂停穿透、模板兜底、精确到期、句子总数/排除今日一句、
 * 全部暂停时不补每日目标。这些数字一旦分叉，首页、统计页、任务流会各说各话。
 */
class TodaySnapshotTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_760_000_000_000L // 2025-10-09 10:13 本地

    private fun article(id: Long, title: String = "篇$id", chars: Int = 400) =
        Article(id = id, title = title, content = "文".repeat(chars))

    private fun state(due: Long, reviewCount: Int = 3) = FsrsEngine.CardState(
        difficulty = 4.0,
        stability = 3.0,
        due = due,
        lastReview = now - day,
        reviewCount = reviewCount,
    )

    private fun record(
        articleId: Long,
        total: Int = 10,
        correct: Int = 6,
        at: Long = now - 2 * day,
        mistakes: List<MistakeDetail> = emptyList(),
    ) = PracticeRecord(
        articleId = articleId,
        mode = "SENTENCE",
        totalBlanks = total,
        correctCount = correct,
        mistakes = mistakes,
        timestamp = at,
        duration = 60_000L,
    )

    private fun mistake() = MistakeDetail(
        blankIndex = 0,
        correctAnswer = "甲",
        userAnswer = "乙",
        errorType = "TYPO",
    )

    private fun snapshot(
        source: StudyCoach.Source,
        goal: Int = 0,
    ) = StudyCoach.snapshot(
        source = source,
        now = now,
        dailyPracticeGoal = goal,
        zone = zone,
    )

    @Test
    fun `暂停文章的到期_未完成_薄弱_错题_句子全部被排除`() {
        val paused = StudyCoach.Source(
            articles = listOf(article(1), article(2)),
            records = listOf(record(1, mistakes = listOf(mistake()))),
            states = mapOf(
                1L to state(now - day),
                2L to state(now + 2 * day),
            ),
            pauses = mapOf(1L to StudyFlow.Pause(now - day, 0L)),
            sentenceStates = mapOf(
                "s:1:aaa" to state(now - day),
                "s:2:bbb" to state(now - day),
            ),
            resumables = listOf(
                StudyCoach.InProgressPractice(1, "SENTENCE", 3, 10, now),
            ),
        )
        val snap = snapshot(paused, goal = 3)

        assertEquals(0, snap.dueArticleCount)
        assertEquals(1, snap.pausedArticleCount)
        assertNull(snap.firstResumable)
        assertTrue(snap.resumables.isEmpty())
        assertNull(snap.weakest)
        assertEquals(0, snap.mistakeCount)
        assertEquals(1, snap.sentenceTotalDue)
        assertEquals(0, snap.input.dueArticles.size)
        assertEquals(1, snap.input.dueSentenceCount)
    }

    @Test
    fun `模板兜底文章按模板到期进入清单且FSRS按精确due判定`() {
        val startToday = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(now), zone
        ).atStartOfDay(zone).toInstant().toEpochMilli()
        val source = StudyCoach.Source(
            articles = listOf(article(1), article(2), article(3), article(4)),
            records = listOf(record(2, at = now - 2 * day)),
            states = mapOf(
                1L to state(now + 2 * day),       // 还没到
                3L to state(now + 3_600_000L),    // 今天晚些时候
                4L to state(startToday - 3_600_000L), // 逾期
            ),
            template = ReviewTemplate.STANDARD,
        )
        val snap = snapshot(source)

        assertEquals(2, snap.dueArticleCount) // 模板兜底的 2 + 逾期的 4
        assertEquals(2, snap.overdueArticleCount)
        assertEquals(1, snap.laterTodayArticleCount)
        assertEquals(setOf(2L, 4L), snap.input.dueArticles.map { it.articleId }.toSet())
        // 模板到期时间由记录反推：最后一条记录 + 1 天，早于 now
        val templateDue = snap.input.dueArticles.first { it.articleId == 2L }.due
        assertTrue(templateDue <= now)
    }

    @Test
    fun `句子总数含今日一句_今日计划只算其余句`() {
        val todayKey = "s:2:today"
        val source = StudyCoach.Source(
            articles = listOf(article(2), article(3)),
            states = mapOf(2L to state(now + day), 3L to state(now + day)),
            sentenceStates = mapOf(
                todayKey to state(now - 3_600_000L),
                "s:2:other" to state(now - 7_200_000L),
                "s:3:later" to state(now + 3_600_000L),
                "s:3:paused" to state(now - day),
            ),
            pauses = mapOf(3L to StudyFlow.Pause(now - day, 0L)),
            todaySentenceKey = todayKey,
        )
        val snap = snapshot(source)

        assertEquals(2, snap.sentenceTotalDue)
        assertEquals(1, snap.sentenceOtherDue)
        assertEquals(1, snap.input.dueSentenceCount)
    }

    @Test
    fun `全部暂停且无其他内容时不补每日目标`() {
        val source = StudyCoach.Source(
            articles = listOf(article(1)),
            states = mapOf(1L to state(now + 3 * day)),
            pauses = mapOf(1L to StudyFlow.Pause(now - day, 0L)),
        )
        val snap = snapshot(source, goal = 3)

        assertTrue(snap.allScheduledPaused)
        assertEquals(1, snap.pausedArticleCount)
        assertEquals(0, snap.input.dailyPracticeGoal)
        assertTrue(StudyCoach.buildPlan(snap.input).items.isEmpty())
    }

    @Test
    fun `存在未暂停内容时每日目标仍保留`() {
        val source = StudyCoach.Source(
            articles = listOf(article(1), article(2)),
            states = mapOf(
                1L to state(now + 3 * day),
                2L to state(now + 2 * day),
            ),
            pauses = mapOf(1L to StudyFlow.Pause(now - day, 0L)),
        )
        val snap = snapshot(source, goal = 3)
        val kinds = StudyCoach.buildPlan(snap.input).items.map { it.kind }

        assertTrue(!snap.allScheduledPaused)
        assertEquals(3, snap.input.dailyPracticeGoal)
        assertEquals(listOf(StudyCoach.Kind.DAILY_GOAL), kinds)
    }

    @Test
    fun `薄弱篇使用最小空数门槛并排除暂停文章`() {
        val source = StudyCoach.Source(
            articles = listOf(article(1), article(2), article(3)),
            records = listOf(
                // 1：样本够且错得多，应入选
                record(1, total = 10, correct = 4, mistakes = listOf(mistake())),
                // 2：样本不足 8 空，不能因为一次全错就上榜
                record(2, total = 7, correct = 0, mistakes = listOf(mistake())),
                // 3：样本够且全对，没有理由入选
                record(3, total = 12, correct = 12),
            ),
            states = mapOf(
                1L to state(now + day),
                2L to state(now + day),
                3L to state(now + day),
            ),
        )
        val snap = snapshot(source)

        assertEquals(1L, snap.weakest?.articleId)
        assertEquals(1, snap.weakest?.practiceCount)
        assertEquals(0.4f, snap.weakest?.correctRate ?: -1f, 0.0001f)
        assertEquals(1, snap.weakest?.mistakeCount)
    }

    @Test
    fun `未完成练习按最近练习倒序且只取未暂停未删除的一条`() {
        val source = StudyCoach.Source(
            articles = listOf(article(1), article(2)),
            resumables = listOf(
                StudyCoach.InProgressPractice(1, "WORD", 1, 10, now - 5_000L),
                StudyCoach.InProgressPractice(2, "SENTENCE", 4, 10, now - 1_000L),
                StudyCoach.InProgressPractice(99, "SENTENCE", 1, 10, now),
            ),
        )
        val snap = snapshot(source)

        assertEquals(2L, snap.firstResumable?.articleId)
        assertEquals(listOf(2L, 1L), snap.resumables.map { it.articleId })
        assertEquals(6, snap.firstResumable?.remaining)
    }

    @Test
    fun `23点到期前不出现_过点后出现且只算今天晚些时候`() {
        val dayStart = LocalDate.of(2026, 10, 9)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val dueAt = dayStart + 23 * 3_600_000L
        val source = StudyCoach.Source(
            articles = listOf(article(1)),
            states = mapOf(1L to state(dueAt)),
        )

        val before = StudyCoach.snapshot(source, now = dayStart + 22 * 3_600_000L, zone = zone)
        assertEquals(0, before.dueArticleCount)
        assertEquals(1, before.laterTodayArticleCount)
        assertTrue(before.input.dueArticles.isEmpty())

        val after = StudyCoach.snapshot(source, now = dueAt + 1_000L, zone = zone)
        assertEquals(1, after.dueArticleCount)
        assertEquals(0, after.laterTodayArticleCount)
        assertEquals(1L, after.input.dueArticles.single().articleId)
    }

    @Test
    fun `今日次数按设备时区的日历天换日`() {
        val dayStart = LocalDate.of(2026, 10, 9)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val source = StudyCoach.Source(
            articles = listOf(article(1)),
            records = listOf(record(1, at = dayStart + 23 * 3_600_000L)),
        )

        val lateNight = StudyCoach.snapshot(source, now = dayStart + 23 * 3_600_000L + 60_000L, zone = zone)
        assertEquals(1, lateNight.input.todayPracticeCount)

        val nextMorning = StudyCoach.snapshot(source, now = dayStart + 24 * 3_600_000L + 60_000L, zone = zone)
        assertEquals(0, nextMorning.input.todayPracticeCount)
    }
}
