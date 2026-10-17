// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.ilyskyo.blancall.R

/**
 * 通知渠道拆分。
 *
 * 旧实现只有一个 `study_reminder` 渠道，用户要么全收、要么全关 —— 一个只想关掉「句子卡」
 * 但保留「到期复习」的人无处可去，最后往往是把整个提醒关掉。拆成三个语义清晰的渠道后，
 * 每种打扰都能被单独关掉，这也是 Android 8+ 渠道机制存在的本意。
 *
 * 渠道一旦用某个 ID 建过，后续只能改名字/描述，**不能改重要性**（系统会忽略），
 * 所以三个渠道的重要性在首次创建时就定死为 DEFAULT，不引入高优先级以免打扰。
 */
object NotificationChannels {

    /** 到期复习（文章 / 句子到期） */
    const val CHANNEL_DUE_REVIEW = "due_review"

    /** 句子卡（每日一句 / 到期句） */
    const val CHANNEL_SENTENCE_CARD = "sentence_card"

    /** 继续完成与今日目标（未完成练习、今日未学习、薄弱内容、连续学习、目标未达） */
    const val CHANNEL_GOAL = "continue_goal"

    /**
     * 旧渠道 ID，**保留不删**：用户可能在系统设置里对它有自定义（关掉 / 静音），
     * 直接删除会连同用户的选择一起消失；继续创建它并让旧通知落在原渠道上，
     * 升级后才不会出现「明明没关却静默失效」的错觉。
     *
     * 不加 `@Deprecated`：本文件内部仍要创建它，标注只会制造无意义的警告噪声；
     * 「别再往这里加新通知」的约束由 [channelIdFor] 单一映射来保证。
     */
    const val CHANNEL_ID_LEGACY = "study_reminder"

    /** 该 reason 应落在哪个渠道。同一条 reason 永远映射到同一渠道，避免用户关了一个渠道又被另一个顶上来。 */
    fun channelIdFor(reason: ReminderReason): String = when (reason) {
        ReminderReason.DueReview -> CHANNEL_DUE_REVIEW
        ReminderReason.SentenceCardDue -> CHANNEL_SENTENCE_CARD
        ReminderReason.NoStudyToday,
        ReminderReason.WeakContent,
        ReminderReason.IncompletePractice,
        ReminderReason.StreakContinue,
        ReminderReason.DailyGoalMissed -> CHANNEL_GOAL
    }

    /**
     * 创建全部渠道（含旧渠道）。创建是幂等的，重复调用安全，
     * 因此可直接由 [com.ilyskyo.blancall.BlancallApp] 在启动时调用，无需判断是否已建。
     */
    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // minSdk 26 ⇒ 渠道 API 恒可用，无需 Build.VERSION 判断
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_DUE_REVIEW,
                R.string.notif_channel_due_name,
                R.string.notif_channel_due_desc,
            )
        )
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_SENTENCE_CARD,
                R.string.notif_channel_sentence_name,
                R.string.notif_channel_sentence_desc,
            )
        )
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_GOAL,
                R.string.notif_channel_goal_name,
                R.string.notif_channel_goal_desc,
            )
        )
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_ID_LEGACY,
                R.string.notif_channel_legacy_name,
                R.string.notif_channel_legacy_desc,
            )
        )
    }

    private fun channel(
        context: Context,
        id: String,
        nameRes: Int,
        descRes: Int,
    ): NotificationChannel = NotificationChannel(
        id,
        context.getString(nameRes),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = context.getString(descRes)
        setShowBadge(true)
    }
}
