// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 闭区间 `[start, end]` —— 学习日历可选的时间维度。
 *
 * 为什么做成一个带不变量的类型而不是两个 `LocalDate`：区间被算反（start 晚于 end）是这类
 * 功能最容易出的错，而它一旦流进渲染层，症状是「格子全空」这种没有任何报错的空白页。
 * 这里把「start 不得晚于 end」钉在构造函数上，反着来的调用方只能走 [of]（自动交换）。
 */
data class DateRange(val start: LocalDate, val end: LocalDate) {

    init {
        require(!start.isAfter(end)) { "DateRange 的 start 不能晚于 end：$start > $end" }
    }

    /** 天数（含首尾）：同一天算 1 天，7 天窗口算 7 天 */
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(end)

    companion object {
        /** 容错构造：两个日期反了就交换。自定义选择里用户可能先点结束再点开始 */
        fun of(a: LocalDate, b: LocalDate): DateRange =
            if (a.isAfter(b)) DateRange(b, a) else DateRange(a, b)
    }
}

/**
 * 学习日历的时间维度预设。
 *
 * 取值与「自定义」并列而不是塞进同一个枚举值里：预设由 [presetRange] 现算，
 * 自定义是用户点出来的具体两天 —— 两者来源不同，混在一起就没法区分「用户选了自定义」
 * 与「用户选了一个恰好等于近 90 天的预设」。
 */
enum class RangePreset { LAST_7, LAST_30, LAST_90, LAST_365, THIS_MONTH, LAST_MONTH, CUSTOM }

/**
 * 预设 → 具体区间。`today` 由调用方传入而不是在这里读时钟：测试要能喂固定日期，
 * 页面开着跨过午夜时也要能跟着换（见 OverviewScreen 的分钟级 ticker）。
 *
 * 全部窗口都**右端到今天**：
 * - 滚动窗口 `近 N 天` 含今天，所以是 `today.minusDays(N - 1)`（近 7 天 = 今天 + 前 6 天）；
 * - `本月` 从 1 号到今天，不是到月末 —— 月末那些格子还没有数据，画成一片「没练」是把未来当成事实；
 * - `上月` 才是完整自然月（已经过完，1 号到月末）。
 */
fun presetRange(preset: RangePreset, today: LocalDate): DateRange = when (preset) {
    RangePreset.LAST_7 -> DateRange(today.minusDays(6), today)
    RangePreset.LAST_30 -> DateRange(today.minusDays(29), today)
    RangePreset.LAST_90 -> DateRange(today.minusDays(89), today)
    RangePreset.LAST_365 -> DateRange(today.minusDays(364), today)
    RangePreset.THIS_MONTH -> DateRange(today.withDayOfMonth(1), today)
    RangePreset.LAST_MONTH -> {
        // 先落到本月 1 号再减一个月：直接 minusMonths 遇到 31 号会溢出成 30/28 号，
        // 那样「上月」的起点就不是 1 号了。
        val first = today.withDayOfMonth(1).minusMonths(1)
        DateRange(first, first.withDayOfMonth(first.lengthOfMonth()))
    }
    // 自定义没有落盘区间时的兜底：与默认预设同宽，避免首帧画出一个 0 天区间
    RangePreset.CUSTOM -> DateRange(today.minusDays(89), today)
}
