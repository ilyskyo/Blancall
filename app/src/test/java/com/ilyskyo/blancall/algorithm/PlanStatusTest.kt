// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表角标的状态判定：`inPlan` 这一维必须让「加入计划但没练过」和「从没碰过」分开。
 *
 * 守的是一条一致性：首页今日计划、学习任务流、提醒都已经把新加入的文章当成今天的任务，
 * 列表不能是唯一看不见它的地方；反过来，有历史记录但没有 FSRS 状态的老文章
 * 也绝不能被说成「待开始」。
 */
class PlanStatusTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()

    private fun record(ts: Long) = PracticeRecord(
        articleId = 1L,
        mode = "SENTENCE",
        totalBlanks = 10,
        correctCount = 8,
        timestamp = ts,
    )

    private fun status(
        state: FsrsEngine.CardState? = null,
        records: List<PracticeRecord> = emptyList(),
        paused: Boolean = false,
        inPlan: Boolean = false,
    ) = EbbinghausScheduler.getReviewStatus(
        fsrsState = state,
        records = records,
        template = ReviewTemplate.STANDARD,
        paused = paused,
        inPlan = inPlan,
    )

    @Test
    fun `加入计划但没练过显示待开始`() {
        assertEquals(EbbinghausScheduler.ReviewStatus.PLANNED, status(inPlan = true))
    }

    @Test
    fun `没加入也没练过仍是未开始`() {
        assertEquals(EbbinghausScheduler.ReviewStatus.NOT_STARTED, status())
    }

    @Test
    fun `加入计划的新成员也能显示已暂停`() {
        // v3 之前 pauseArticle 要求 reviewCount > 0，这条路径根本不存在；
        // 现在能暂停了，角标必须跟着变，否则用户会以为暂停没生效
        assertEquals(
            EbbinghausScheduler.ReviewStatus.PAUSED,
            status(inPlan = true, paused = true),
        )
    }

    @Test
    fun `练过的文章暂停仍是已暂停`() {
        val state = FsrsEngine.CardState(due = now + 3 * day, reviewCount = 2, lastReview = now - day)
        assertEquals(
            EbbinghausScheduler.ReviewStatus.PAUSED,
            status(state = state, paused = true, inPlan = true),
        )
    }

    @Test
    fun `有旧记录没有 FSRS 状态不会被说成待开始`() {
        val s = status(records = listOf(record(now - day)), inPlan = true)
        // 旧口径下「练过但模板间隔未到」是 PENDING，「早已到期」是 DUE —— 两者都不能是 PLANNED
        assertTrue(
            "有历史记录的文不该显示「待开始」，实际 $s",
            s !is EbbinghausScheduler.ReviewStatus.PLANNED,
        )
    }

    @Test
    fun `练过的文章按 FSRS 到期时间给结论`() {
        val due = FsrsEngine.CardState(due = now - day, reviewCount = 1, lastReview = now - 3 * day)
        assertEquals(EbbinghausScheduler.ReviewStatus.DUE, status(state = due, inPlan = true))

        val future = FsrsEngine.CardState(due = now + 3 * day, reviewCount = 1, lastReview = now)
        val pending = status(state = future)
        assertEquals(
            EbbinghausScheduler.ReviewStatus.PENDING(3),
            pending,
        )
    }
}
