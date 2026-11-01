// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 任务详情展示口径：没有练习记录时，间隔与开始时间必须为空，而不是拿 0 当起点算出一个假数字。
 *
 * 守的是两个真实坑：
 * ① 未开始成员的临时代码状态 lastReview=0，直接 due-lastReview 会显示两万多天；
 * ② 文章创建时间不能冒充「开始学习」时间。
 */
class TaskDisplayLogicTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `未开始没有间隔`() {
        val state = FsrsEngine.CardState(due = 1_700_000_000_000L, reviewCount = 0)
        assertNull("0 次练习不能算出间隔", TaskDisplayLogic.intervalDays(state))
    }

    @Test
    fun `有次数但 lastReview 无效时也不猜间隔`() {
        val state = FsrsEngine.CardState(due = 1_700_000_000_000L, reviewCount = 3, lastReview = 0L)
        assertNull("缺 lastReview 的脏状态不能回退到 1970 年", TaskDisplayLogic.intervalDays(state))
    }

    @Test
    fun `练过时按真实两次练习之间算间隔`() {
        val last = 1_700_000_000_000L
        val state = FsrsEngine.CardState(
            due = last + 3 * day,
            lastReview = last,
            reviewCount = 2,
        )
        assertEquals(3, TaskDisplayLogic.intervalDays(state))
    }

    @Test
    fun `开始学习时间只认第一次练习记录`() {
        val first = 1_700_000_000_000L
        val records = listOf(
            PracticeRecord(articleId = 1L, mode = "SENTENCE", totalBlanks = 10, correctCount = 8, timestamp = first + day),
            PracticeRecord(articleId = 1L, mode = "SENTENCE", totalBlanks = 10, correctCount = 7, timestamp = first),
        )
        assertEquals(first, TaskDisplayLogic.startedAt(records))
    }

    @Test
    fun `没有练习记录时开始学习时间为空`() {
        assertNull(TaskDisplayLogic.startedAt(emptyList()))
    }
}
