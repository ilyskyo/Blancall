// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord
import java.util.Calendar
import kotlin.math.ceil

/**
 * 复习模板：可自定义间隔的艾宾浩斯复习计划
 */
data class ReviewTemplate(
    val id: String,
    val name: String,
    val intervals: List<Int>,   // 每次复习间隔（天）
    val autoAdjust: Boolean = false,  // 是否根据正确率自动微调
    val isPreset: Boolean = false     // 是否为系统预设模板
) {
    companion object {
        val SPRINT  = ReviewTemplate("sprint",  "冲刺备考", listOf(1, 1, 2, 3, 5, 7),           autoAdjust = true,  isPreset = true)
        val STANDARD = ReviewTemplate("standard","标准记忆", listOf(1, 2, 4, 7, 15, 30),        autoAdjust = false, isPreset = true)
        val DEEP     = ReviewTemplate("deep",    "深度长期", listOf(1, 2, 4, 7, 15, 30, 60, 90),autoAdjust = false, isPreset = true)

        val PRESETS = listOf(SPRINT, STANDARD, DEEP)
    }
}

/**
 * 艾宾浩斯遗忘曲线复习调度器
 * 支持自定义模板 + 自适应微调
 */
object EbbinghausScheduler {

    /** 一天的毫秒数，用于剩余天数计算 */
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 计算下次复习日期（毫秒时间戳）
     * @param template 复习模板
     * @param studyCount 已学习次数
     * @param lastStudyTime 最近一次学习时间
     * @param lastAccuracy 最近一次正确率 (0-1)，用于自适应微调
     * @return 下次应复习的时间戳，已全部完成返回 null
     */
    fun nextReviewTime(
        template: ReviewTemplate,
        studyCount: Int,
        lastStudyTime: Long,
        lastAccuracy: Float = 1f
    ): Long? {
        if (studyCount >= template.intervals.size) return null
        var interval = template.intervals[studyCount]

        // 自适应微调：正确率低 → 提前复习；正确率高 → 延后
        // 对 interval==1 单独处理，避免 *0.5 maxOf1=1 / *1.3=1 的无效微调
        if (template.autoAdjust && studyCount > 0) {
            interval = when {
                lastAccuracy < 0.4f -> if (interval <= 1) 1 else maxOf(1, (interval * 0.5).toInt())
                lastAccuracy < 0.7f -> if (interval <= 1) 1 else maxOf(1, (interval * 0.75).toInt())
                lastAccuracy > 0.95f -> if (interval <= 1) 2 else (interval * 1.3).toInt()
                lastAccuracy > 0.85f -> if (interval <= 1) 2 else (interval * 1.15).toInt()
                else -> interval
            }
        }

        val cal = Calendar.getInstance().apply { timeInMillis = lastStudyTime }
        cal.add(Calendar.DAY_OF_YEAR, interval)
        return cal.timeInMillis
    }

    /**
     * 判断是否需要复习
     * @param clock 可注入的当前时间源（默认 System.currentTimeMillis()），便于测试
     */
    fun isDue(
        template: ReviewTemplate,
        records: List<PracticeRecord>,
        clock: () -> Long = { System.currentTimeMillis() }
    ): Boolean {
        if (records.isEmpty()) return false
        val sorted = records.sortedBy { it.timestamp }
        val count = sorted.size
        // count 达到/超过复习轮次总数即视为已完成，不再 due
        if (count >= template.intervals.size) return false
        val lastTime = sorted.last().timestamp
        val lastAcc = if (sorted.last().totalBlanks > 0)
            sorted.last().correctCount.toFloat() / sorted.last().totalBlanks else 1f
        val nextTime = nextReviewTime(template, count - 1, lastTime, lastAcc) ?: return false
        return clock() >= nextTime
    }

    /**
     * 获取复习状态
     * @param clock 可注入的当前时间源（默认 System.currentTimeMillis()），便于测试
     */
    fun getReviewStatus(
        template: ReviewTemplate,
        records: List<PracticeRecord>,
        clock: () -> Long = { System.currentTimeMillis() }
    ): ReviewStatus {
        if (records.isEmpty()) return ReviewStatus.NOT_STARTED
        val sorted = records.sortedBy { it.timestamp }
        val count = sorted.size
        // count 达到/超过复习轮次总数即视为已完成
        if (count >= template.intervals.size) return ReviewStatus.COMPLETED

        val lastTime = sorted.last().timestamp
        val lastAcc = if (sorted.last().totalBlanks > 0)
            sorted.last().correctCount.toFloat() / sorted.last().totalBlanks else 1f
        val nextTime = nextReviewTime(template, count - 1, lastTime, lastAcc) ?: return ReviewStatus.COMPLETED

        val now = clock()
        if (now >= nextTime) return ReviewStatus.DUE

        val remainingMs = nextTime - now
        // 向上取整：剩余不足一天也显示「1 天后」，恰好整除则显示实际天数
        val remainingDays = ceil(remainingMs.toDouble() / MILLIS_PER_DAY).toInt().coerceAtLeast(0)
        return ReviewStatus.PENDING(remainingDays)
    }

    /**
     * 获取复习状态（FSRS 优先）：
     * 文章已有 FSRS 记忆状态时，到期判断完全由 FSRS 自适应调度决定；
     * 无 FSRS 状态（升级前的存量练习）时回退到模板间隔调度。
     */
    fun getReviewStatus(
        fsrsState: FsrsEngine.CardState?,
        records: List<PracticeRecord>,
        template: ReviewTemplate = ReviewTemplate.STANDARD
    ): ReviewStatus {
        // FSRS 状态存在且已有练习 → 完全采用 FSRS 调度
        if (fsrsState != null && fsrsState.reviewCount > 0) {
            val now = System.currentTimeMillis()
            if (now >= fsrsState.due) return ReviewStatus.DUE
            val remainingMs = fsrsState.due - now
            val remainingDays = ceil(remainingMs.toDouble() / MILLIS_PER_DAY).toInt().coerceAtLeast(0)
            return ReviewStatus.PENDING(remainingDays)
        }
        return getReviewStatus(records)
    }

    /** 向后兼容：使用默认标准模板 */
    fun getReviewStatus(records: List<PracticeRecord>): ReviewStatus =
        getReviewStatus(ReviewTemplate.STANDARD, records)

    fun isDue(records: List<PracticeRecord>): Boolean =
        isDue(ReviewTemplate.STANDARD, records)

    sealed class ReviewStatus {
        data object NOT_STARTED : ReviewStatus()
        data object DUE : ReviewStatus()
        data class PENDING(val daysLeft: Int) : ReviewStatus()
        data object COMPLETED : ReviewStatus()
    }
}
