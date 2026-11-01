// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.data.model.PracticeRecord

/**
 * 任务详情里「没有练习记录就不能算」的展示口径。
 *
 * reviewCount 是练习次数；未开始成员的临时代码状态 lastReview=0，直接用 due-lastReview
 * 会得到从 1970 年起的间隔（两万多天）。这里把无效状态收敛为 null，由界面显示「—」。
 */
internal object TaskDisplayLogic {

    /** 只有练过且 lastReview 有效时，本次间隔才有意义。 */
    fun intervalDays(state: FsrsEngine.CardState): Int? {
        if (state.reviewCount <= 0 || state.lastReview <= 0L) return null
        return ((state.due - state.lastReview).coerceAtLeast(0L) / StudyFlow.MILLIS_PER_DAY).toInt()
    }

    /** 开始学习时间只认第一次练习记录；文章创建时间不是学习开始时间。 */
    fun startedAt(records: List<PracticeRecord>): Long? =
        records.minOfOrNull { it.timestamp }
}
