// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.algorithm.FsrsEngine
import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * 「句子卡片」文案与时间口径的单一来源（首页小卡片与大卡片界面共用）。
 *
 * 迁移约束：状态行文案与既有实现**逐字一致**（首页观感零变化）：
 * - 第一次记忆 / 待复习 · 上次 N 天前 / 已记录 · 下次 M 天后
 */

/** 两个时间戳是否处于同一日历日（系统默认时区；与 FSRS 同日复习判定同口径） */
internal fun isSameLocalDay(a: Long, b: Long): Boolean =
    Instant.ofEpochMilli(a).atZone(ZoneId.systemDefault()).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(ZoneId.systemDefault()).toLocalDate()

/** 该句子状态是否"今天已评"（句子卡评级态判定；无状态返回 false） */
internal fun isRatedToday(state: FsrsEngine.CardState?, now: Long): Boolean =
    isSameLocalDay(state?.lastReview ?: -1L, now)

/** 距上次复习天数（下限 0） */
internal fun daysAgo(lastReview: Long, now: Long): Long =
    ((now - lastReview) / 86_400_000L).coerceAtLeast(0)

/** 距下次复习天数（下限 0） */
internal fun nextDueDays(state: FsrsEngine.CardState, now: Long): Int =
    FsrsEngine.daysUntilDue(state, now).coerceAtLeast(0)

/** 首页小卡片状态行：第一次记忆 / 待复习 · 上次 N 天前 / 已记录 · 下次 M 天后 */
internal fun sentenceStatusLine(context: Context, state: FsrsEngine.CardState?, now: Long): String {
    if (state == null || state.reviewCount <= 0) {
        return context.getString(com.ilyskyo.blancall.R.string.sentence_status_first)
    }
    if (isSameLocalDay(state.lastReview, now)) {
        return context.getString(
            com.ilyskyo.blancall.R.string.sentence_status_recorded_next, nextDueDays(state, now)
        )
    }
    return context.getString(
        com.ilyskyo.blancall.R.string.sentence_status_due_last, daysAgo(state.lastReview, now)
    )
}

/** 大卡片底部状态：已记录 · 下次 M 天后 / 新句子 · 第一次记忆 / 第 N 次记忆 · 上次 X 天前 */
internal fun sentenceCardStatus(context: Context, state: FsrsEngine.CardState?, now: Long): String = when {
    // 先写 `state != null &&` 再判，而不是靠 `state!!`：`isRatedToday(null, now)` 恒为 false，
    // 所以两者语义相同，但前者让编译器**自己**知道 state 非空（智能转换），
    // 后者是把「前提」写成断言 —— isRatedToday 的实现哪天改了，这里就当场 NPE。
    state != null && isRatedToday(state, now) -> context.getString(
        com.ilyskyo.blancall.R.string.sentence_status_recorded_next, nextDueDays(state, now)
    )
    state == null || state.reviewCount <= 0 -> context.getString(
        com.ilyskyo.blancall.R.string.sentence_card_new
    )
    else -> context.getString(
        com.ilyskyo.blancall.R.string.sentence_card_nth,
        state.reviewCount, daysAgo(state.lastReview, now)
    )
}
