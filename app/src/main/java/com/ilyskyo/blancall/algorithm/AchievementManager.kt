// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeMode
import com.ilyskyo.blancall.data.model.PracticeRecord

/**
 * 成就徽章管理器：定义徽章规则，并按当前数据实时判定解锁状态。
 *
 * 设计说明：解锁状态由当前数据派生计算，不持久化"已解锁"标记，
 * 保证数据与状态强一致（记录只增不减类徽章一旦达成即永久解锁）。
 * 「什么时候拿到的」另有 [com.ilyskyo.blancall.data.repository.AchievementTimelineStore] 记一份时间戳，
 * 两者职责不冲突：派生负责「是不是」，落盘只负责「何时」。
 *
 * 这里**不写任何展示文案**：以前徽章名与进度文案直接是 `.kt` 里的中文字面量，
 * 结果六语用户的统计页上整片徽章是简体，而所有多语言守卫都是绿的（res 目录守卫看不见 .kt）。
 * 现在数据层只给 `id` + 阈值 + 进度分子分母 + 单位，文案与量词由渲染层按 id 取资源。
 */
object AchievementManager {

    /** 徽章族：同一个族内保持「初心→勤学→百炼」这种阶梯顺序，族与族之间不再互相打散 */
    enum class Family { PRACTICE, STREAK, ACCURACY, BREADTH, MEMORY }

    /** 进度单位：决定渲染层用哪条 plurals（俄语的「1 次/2 次」复数形态无法从一个字符串里推） */
    enum class Unit { COUNT, DAY, KIND, MINUTE, PERCENT }

    /** 单个成就定义与当前解锁状态 */
    data class Achievement(
        val id: String,
        val family: Family,
        /** 进度分子（已达成量） */
        val current: Int,
        /** 进度分母（解锁门槛） */
        val target: Int,
        val unit: Unit,
        /** 解锁进度 0f..1f；已解锁恒为 1f */
        val progress: Float,
        val unlocked: Boolean
    )

    /**
     * 记忆侧统计（本产品的内核量）：来自句子级 FSRS 状态，与「句子记忆」卡同一份数据。
     *
     * 为什么必须有它：原来 11 枚徽章的判据全是过程量（次数、天数、分钟、满分次数），
     * 一枚「记住了什么」都没有 —— 而这个 app 的价值主张是记忆。留存率、到期清零这些数据
     * 早就算得出来，只是没人把它们做成成就。
     */
    data class MemoryStats(
        /** 已有 FSRS 状态（练过）的句子数 */
        val learnedSentences: Int = 0,
        /** 已练句子的平均留存率（0..1）；一句都没练过时为 null，不该被当成 0% */
        val avgRetention: Float? = null,
        /** 当前到期未复习的句子数 */
        val dueSentences: Int = 0
    )

    /** 模式类徽章的门槛 = 模式枚举全集，不写死数字（写死会在加第 4 种模式时提前发奖） */
    private val ALL_MODES: Int = PracticeMode.entries.size

    /** 记忆向徽章的门槛 */
    private const val RETENTION_STANDARD_PERCENT = 90
    private const val RETENTION_HIGH_PERCENT = 95
    private const val DUE_CLEARED_MIN_SENTENCES = 20

    /**
     * 评估全部成就的解锁状态。
     *
     * @param records       全部练习记录
     * @param longestStreak 历史最长连续天数，**必须由 [DayBucket.longestRun] 从 records 反推**：
     *   这里刻意不再接收「当前连击」那第二个参数。它过去是死参（声明了、函数体从不读），
     *   而三枚连击徽章只看 longestStreak，于是断签对徽章毫无影响 —— 传了没用、key 又少一个，
     *   两个隐患正好互相掩盖。当前连击是顶栏要显示的量（各自用 [DayBucket.consecutiveDays] 现算），
     *   不是一项成就，就不该混进成就的判据里。
     * @param memory        句子级 FSRS 汇总，产出记忆向那一族
     */
    fun evaluate(
        records: List<PracticeRecord>,
        longestStreak: Int,
        memory: MemoryStats = MemoryStats()
    ): List<Achievement> {
        val totalPractices = records.size
        val perfectCount = records.count { it.totalBlanks > 0 && it.correctCount == it.totalBlanks }
        val modesUsed = records.map { it.mode }.distinct().size
        val totalDurationMin = records.sumOf { it.duration } / 60_000L
        // 单篇最高练习次数
        val maxArticlePractices = records.groupBy { it.articleId }
            .maxOfOrNull { it.value.size } ?: 0

        val retentionPercent = memory.avgRetention?.let { (it * 100).toInt() }

        return listOf(
            ach("first_practice", Family.PRACTICE, totalPractices, 1, Unit.COUNT,
                unlocked = totalPractices >= 1),
            ach("practice_10", Family.PRACTICE, totalPractices, 10, Unit.COUNT,
                unlocked = totalPractices >= 10),
            ach("practice_100", Family.PRACTICE, totalPractices, 100, Unit.COUNT,
                unlocked = totalPractices >= 100),

            ach("streak_7", Family.STREAK, longestStreak, 7, Unit.DAY,
                unlocked = longestStreak >= 7),
            ach("streak_30", Family.STREAK, longestStreak, 30, Unit.DAY,
                unlocked = longestStreak >= 30),
            ach("streak_100", Family.STREAK, longestStreak, 100, Unit.DAY,
                unlocked = longestStreak >= 100),

            ach("perfect_one", Family.ACCURACY, perfectCount, 1, Unit.COUNT,
                unlocked = perfectCount >= 1),
            ach("perfect_ten", Family.ACCURACY, perfectCount, 10, Unit.COUNT,
                unlocked = perfectCount >= 10),

            ach("all_modes", Family.BREADTH, modesUsed, ALL_MODES, Unit.KIND,
                unlocked = modesUsed >= ALL_MODES),
            ach("focus_60", Family.BREADTH, totalDurationMin.toInt(), 60, Unit.MINUTE,
                unlocked = totalDurationMin >= 60),
            ach("deep_10", Family.BREADTH, maxArticlePractices, 10, Unit.COUNT,
                unlocked = maxArticlePractices >= 10),

            // 记忆向：门槛用「句数下限 + 留存率」两个条件，避免只练过两句的新号白拿
            ach("memory_90", Family.MEMORY, retentionPercent ?: 0, RETENTION_STANDARD_PERCENT, Unit.PERCENT,
                unlocked = retentionPercent != null && retentionPercent >= RETENTION_STANDARD_PERCENT &&
                    memory.learnedSentences >= DUE_CLEARED_MIN_SENTENCES),
            ach("memory_95", Family.MEMORY, retentionPercent ?: 0, RETENTION_HIGH_PERCENT, Unit.PERCENT,
                unlocked = retentionPercent != null && retentionPercent >= RETENTION_HIGH_PERCENT &&
                    memory.learnedSentences >= DUE_CLEARED_MIN_SENTENCES),
            ach("due_cleared", Family.MEMORY, memory.learnedSentences, DUE_CLEARED_MIN_SENTENCES, Unit.COUNT,
                unlocked = memory.learnedSentences >= DUE_CLEARED_MIN_SENTENCES && memory.dueSentences == 0)
        )
    }

    private fun ach(
        id: String, family: Family, current: Int, target: Int, unit: Unit, unlocked: Boolean
    ) = Achievement(
        id = id,
        family = family,
        current = current,
        target = target,
        unit = unit,
        progress = if (target <= 0) 1f else (current.toFloat() / target).coerceIn(0f, 1f),
        unlocked = unlocked
    )
}
