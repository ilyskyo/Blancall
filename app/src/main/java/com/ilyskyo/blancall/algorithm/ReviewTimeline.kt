// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 把复习事件日志翻成「真实间隔演化」。
 *
 * 记录里原本只有**预测**（FSRS 算出来的下次到期），没有**事实**（这次到底隔了几天、
 * 复习之后稳不稳定）。日志一直在写，但没有任何读取方 —— 这里只做纯计算，
 * 由统计页把结果画出来并注明「仅本机」（日志不进备份，换机归零）。
 *
 * 判定「稳住」用评级而不是术语：评级 <= HARD 视为还没稳住，之后连续 GOOD/EASY
 * 才算跨过去。文案层据此说「第 4 次才稳住」，不说稳定度。纯函数无时钟依赖。
 */
object ReviewTimeline {

    /** 单条复习事件（由调用方从 ReviewLogStore 映射，保持本层无存储依赖）。 */
    data class Event(
        val ts: Long,
        val rating: Int,
        val elapsedMs: Long,
        val stability: Double,
    )

    data class Point(
        /** 第几次复习（1 起） */
        val review: Int,
        val ts: Long,
        val rating: Int,
        /** 距上一次复习的天数；首次为 0 */
        val intervalDays: Double,
        /** 复习之后的稳定度（天） */
        val stabilityDays: Double,
    )

    enum class Summary {
        /** 没有日志 */
        NO_DATA,

        /** 只有一次，还看不出趋势 */
        ONLY_ONE,

        /** 第一次起就一直是 GOOD/EASY */
        STEADY_FROM_START,

        /** 中间有过 AGAIN/HARD，之后连续稳住 */
        STEADY_AFTER,

        /** 最近一次又没稳住 */
        STILL_SHAKY,
    }

    data class Series(
        val points: List<Point>,
        val summary: Summary,
        /** 从第几次复习开始连续稳住（1 起）；没有该时刻为 null */
        val steadyFromReview: Int?,
        /** 最近一次间隔相对前一次的变化（天）；不足两次为 null */
        val latestIntervalDeltaDays: Double?,
    )

    /**
     * @param events 同一张卡的事件（顺序不限，本函数按时间排序）
     */
    fun build(events: List<Event>): Series {
        if (events.isEmpty()) return Series(emptyList(), Summary.NO_DATA, null, null)
        val sorted = events.sortedBy { it.ts }.mapIndexed { idx, e ->
            Point(
                review = idx + 1,
                ts = e.ts,
                rating = e.rating,
                intervalDays = e.elapsedMs.coerceAtLeast(0L) / MILLIS_PER_DAY.toDouble(),
                stabilityDays = e.stability,
            )
        }
        if (sorted.size == 1) {
            return Series(sorted, Summary.ONLY_ONE, null, null)
        }
        // 评级 1/2 = 没稳住；找最后一次「没稳住」，它之后的第一次复习就是稳住的起点
        val lastShaky = sorted.indexOfLast { it.rating <= RATING_HARD }
        val summary: Summary
        val steadyFrom: Int?
        when {
            lastShaky < 0 -> {
                summary = Summary.STEADY_FROM_START
                steadyFrom = 1
            }
            lastShaky == sorted.lastIndex -> {
                summary = Summary.STILL_SHAKY
                steadyFrom = null
            }
            else -> {
                summary = Summary.STEADY_AFTER
                steadyFrom = lastShaky + 2
            }
        }
        val delta = sorted[sorted.lastIndex].intervalDays - sorted[sorted.lastIndex - 1].intervalDays
        return Series(sorted, summary, steadyFrom, delta)
    }

    const val RATING_HARD = 2
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
