// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord

/**
 * 成就徽章管理器：定义徽章规则，并按当前数据实时判定解锁状态。
 *
 * 设计说明：解锁状态由当前数据派生计算，不持久化"已解锁"标记，
 * 保证数据与状态强一致（记录只增不减类徽章一旦达成即永久解锁）。
 */
object AchievementManager {

    /** 单个成就定义与当前解锁状态 */
    data class Achievement(
        val id: String,
        val icon: String,
        val title: String,
        val description: String,
        val unlocked: Boolean,
        /** 解锁进度 0f..1f；已解锁恒为 1f */
        val progress: Float,
        /** 进度描述（如 "3/7 天"） */
        val progressText: String
    )

    /**
     * 评估全部成就的解锁状态。
     *
     * @param records        全部练习记录
     * @param longestStreak   历史最长连续天数
     * @param currentStreak  当前连续天数
     */
    fun evaluate(
        records: List<PracticeRecord>,
        longestStreak: Int,
        currentStreak: Int
    ): List<Achievement> {
        val totalPractices = records.size
        val perfectCount = records.count { it.totalBlanks > 0 && it.correctCount == it.totalBlanks }
        val modesUsed = records.map { it.mode }.distinct().size
        val totalDurationMin = records.sumOf { it.duration } / 60_000L
        // 单篇最高练习次数
        val maxArticlePractices = records.groupBy { it.articleId }
            .maxOfOrNull { it.value.size } ?: 0

        return listOf(
            ach("first_practice", "🌱", "初心", "完成首次练习",
                unlocked = totalPractices >= 1,
                progress = (totalPractices.toFloat() / 1f).coerceIn(0f, 1f),
                progressText = "$totalPractices/1 次"
            ),
            ach("practice_10", "📚", "勤学", "累计 10 次练习",
                unlocked = totalPractices >= 10,
                progress = (totalPractices / 10f).coerceIn(0f, 1f),
                progressText = "$totalPractices/10 次"
            ),
            ach("practice_100", "💪", "百炼", "累计 100 次练习",
                unlocked = totalPractices >= 100,
                progress = (totalPractices / 100f).coerceIn(0f, 1f),
                progressText = "$totalPractices/100 次"
            ),
            ach("streak_7", "🔥", "连击一周", "连续学习 7 天",
                unlocked = longestStreak >= 7,
                progress = (longestStreak / 7f).coerceIn(0f, 1f),
                progressText = "$longestStreak/7 天"
            ),
            ach("streak_30", "🔥", "连击一月", "连续学习 30 天",
                unlocked = longestStreak >= 30,
                progress = (longestStreak / 30f).coerceIn(0f, 1f),
                progressText = "$longestStreak/30 天"
            ),
            ach("streak_100", "👑", "百日坚持", "连续学习 100 天",
                unlocked = longestStreak >= 100,
                progress = (longestStreak / 100f).coerceIn(0f, 1f),
                progressText = "$longestStreak/100 天"
            ),
            ach("perfect_one", "✨", "满分时刻", "获得一次满分",
                unlocked = perfectCount >= 1,
                progress = (perfectCount / 1f).coerceIn(0f, 1f),
                progressText = "$perfectCount/1 次"
            ),
            ach("perfect_ten", "💎", "完美主义", "累计 10 次满分",
                unlocked = perfectCount >= 10,
                progress = (perfectCount / 10f).coerceIn(0f, 1f),
                progressText = "$perfectCount/10 次"
            ),
            ach("all_modes", "🎯", "全才", "体验全部三种练习模式",
                unlocked = modesUsed >= 3,
                progress = (modesUsed / 3f).coerceIn(0f, 1f),
                progressText = "$modesUsed/3 种"
            ),
            ach("focus_60", "⏱️", "专注", "累计练习 60 分钟",
                unlocked = totalDurationMin >= 60,
                progress = (totalDurationMin / 60f).coerceIn(0f, 1f),
                progressText = "$totalDurationMin/60 分"
            ),
            ach("deep_10", "🧠", "深度钻研", "单篇练习 10 次",
                unlocked = maxArticlePractices >= 10,
                progress = (maxArticlePractices / 10f).coerceIn(0f, 1f),
                progressText = "$maxArticlePractices/10 次"
            )
        )
    }

    private fun ach(
        id: String, icon: String, title: String, description: String,
        unlocked: Boolean, progress: Float, progressText: String
    ) = Achievement(id, icon, title, description, unlocked, progress, progressText)
}
