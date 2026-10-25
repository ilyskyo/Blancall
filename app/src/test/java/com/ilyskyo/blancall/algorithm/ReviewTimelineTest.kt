// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewTimelineTest {

    private val day = 24L * 60 * 60 * 1000

    private fun event(at: Long, rating: Int, interval: Long, stability: Double = 1.0) =
        ReviewTimeline.Event(at, rating, interval, stability)

    @Test
    fun `空日志没有趋势`() {
        val series = ReviewTimeline.build(emptyList())
        assertEquals(ReviewTimeline.Summary.NO_DATA, series.summary)
        assertNull(series.steadyFromReview)
    }

    @Test
    fun `只有一次复习时不下结论`() {
        val series = ReviewTimeline.build(listOf(event(day, 1, 0)))
        assertEquals(ReviewTimeline.Summary.ONLY_ONE, series.summary)
    }

    @Test
    fun `中间忘过之后连续稳住：从第几次开始`() {
        val series = ReviewTimeline.build(
            listOf(
                event(1 * day, 3, 0, 1.0),
                event(3 * day, 1, 2 * day, 0.5),
                event(5 * day, 2, 2 * day, 1.0),
                event(12 * day, 3, 7 * day, 4.0),
                event(30 * day, 4, 18 * day, 12.0),
            )
        )
        assertEquals(ReviewTimeline.Summary.STEADY_AFTER, series.summary)
        assertEquals(4, series.steadyFromReview)
        assertEquals(5, series.points.size)
        assertEquals(0.0, series.points.first().intervalDays, 0.001)
        assertEquals(2.0, series.points[1].intervalDays, 0.001)
    }

    @Test
    fun `第一次就稳`() {
        val series = ReviewTimeline.build(
            listOf(event(day, 3, 0), event(3 * day, 4, 2 * day))
        )
        assertEquals(ReviewTimeline.Summary.STEADY_FROM_START, series.summary)
        assertEquals(1, series.steadyFromReview)
    }

    @Test
    fun `最近一次又忘了`() {
        val series = ReviewTimeline.build(
            listOf(event(day, 3, 0), event(3 * day, 1, 2 * day))
        )
        assertEquals(ReviewTimeline.Summary.STILL_SHAKY, series.summary)
        assertNull(series.steadyFromReview)
        assertEquals(2.0, series.latestIntervalDeltaDays!!, 0.001)
    }

    @Test
    fun `乱序事件按时间排序`() {
        val series = ReviewTimeline.build(
            listOf(event(3 * day, 3, 2 * day), event(day, 1, 0))
        )
        assertEquals(1, series.points.first().rating)
        assertEquals(3, series.points.last().rating)
    }
}
