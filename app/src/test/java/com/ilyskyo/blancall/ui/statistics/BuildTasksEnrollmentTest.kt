// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务流成员资格：练过的（reviewCount > 0）∪ 主动加入计划的（v3 enrolled）。
 *
 * 这里守的是「加入计划」这条新路的三个边界：新成员要有合理的到期时间、
 * 练过又加入不能出现两张卡、没练也没加入的文章不能凭空冒出来。
 */
class BuildTasksEnrollmentTest {

    private val articles = listOf(
        Article(id = 1L, title = "练过的", content = "内容一"),
        Article(id = 2L, title = "只加入未练", content = "内容二"),
        Article(id = 3L, title = "碰都没碰", content = "内容三"),
    )

    private val now = 1_700_000_000_000L

    @Test
    fun `成员是练过的与加入的并集`() {
        val states = mapOf(
            1L to FsrsEngine.CardState(due = now + 86_400_000L, reviewCount = 2, lastReview = now)
        )
        val tasks = buildTasks(articles, states, emptyMap(), emptyMap(), mapOf(2L to now), now)

        assertEquals("练过的 + 加入的，共两张", 2, tasks.size)
        assertNotNull(tasks.firstOrNull { it.articleId == 1L })
        val added = tasks.first { it.articleId == 2L }
        assertEquals("未练成员的到期时间 = 加入时刻", now, added.due)
        assertEquals(0, added.state.reviewCount)
        assertEquals("未开始不该显示留存率", 0f, added.retention, 0f)
        assertEquals(StudyFlow.Phase.TODAY, added.phase)
    }

    @Test
    fun `有旧练习记录但无FSRS状态的成员算练过`() {
        val record = PracticeRecord(
            articleId = 2L,
            mode = "SENTENCE",
            totalBlanks = 2,
            correctCount = 2,
            timestamp = now - 3_600_000L,
        )
        val tasks = buildTasks(
            articles = articles,
            states = emptyMap(),
            pauses = emptyMap(),
            sentenceStates = emptyMap(),
            enrolled = mapOf(2L to now),
            now = now,
            records = listOf(record),
        )
        val task = tasks.single { it.articleId == 2L }
        assertTrue("有记录就不是未开始", task.hasPractice)
        assertEquals("次数应回落到记录条数", 1, task.practiceCount)
        assertEquals("没有 FSRS 状态仍是 0，不能伪造", 0, task.state.reviewCount)
    }

    @Test
    fun `练过之后再加入不会出现两张卡`() {
        val states = mapOf(
            1L to FsrsEngine.CardState(due = now + 3_600_000L, reviewCount = 1, lastReview = now)
        )
        val tasks = buildTasks(articles, states, emptyMap(), emptyMap(), mapOf(1L to now), now)
        assertEquals(1, tasks.size)
        assertEquals("应保留练习写入的到期时间", now + 3_600_000L, tasks.first().due)
    }

    @Test
    fun `既没练也没加入的文章不进入任务流`() {
        val tasks = buildTasks(articles, emptyMap(), emptyMap(), emptyMap(), emptyMap(), now)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `文章被删后即使成员表还有记录也不出现`() {
        val tasks = buildTasks(
            articles = emptyList(),
            states = emptyMap(),
            pauses = emptyMap(),
            sentenceStates = emptyMap(),
            enrolled = mapOf(2L to now),
            now = now,
        )
        assertTrue("列表里没有这篇文章就不能凭空造任务", tasks.isEmpty())
    }

    @Test
    fun `未练成员暂停后归入暂停分组`() {
        val pause = StudyFlow.Pause(pausedAt = now, remainingMs = 0L)
        val tasks = buildTasks(
            articles = articles,
            states = emptyMap(),
            pauses = mapOf(2L to pause),
            sentenceStates = emptyMap(),
            enrolled = mapOf(2L to now),
            now = now,
        )
        assertEquals(StudyFlow.Phase.PAUSED, tasks.first().phase)
    }

    @Test
    fun `旧数据的成员判定与升级前一致`() {
        // 没有 enrolled 表时，只有 reviewCount > 0 的文章出现 —— 这正是 v3 之前的行为
        val threeDays = 3 * 24 * 60 * 60 * 1000L
        val states = mapOf(
            1L to FsrsEngine.CardState(
                due = now - threeDays,
                reviewCount = 1,
                lastReview = now - 4 * threeDays,
            ),
            3L to FsrsEngine.CardState(due = now, reviewCount = 0),
        )
        val tasks = buildTasks(articles, states, emptyMap(), emptyMap(), emptyMap(), now)
        assertEquals("reviewCount=0 的残留状态不该冒出来", 1, tasks.size)
        assertEquals(1L, tasks.first().articleId)
        assertEquals(StudyFlow.Phase.OVERDUE, tasks.first().phase)
    }
}
