// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R

/**
 * 提醒上下文：根据用户不同状态生成不同通知内容
 */
sealed class ReminderReason {
    /** 当天没有学习 */
    data class NoStudyToday(val streakDays: Int) : ReminderReason()
    /** 存在薄弱内容 */
    data class WeakContent(val weakCount: Int) : ReminderReason()
    /** 存在未完成练习 */
    data class IncompletePractice(val articleId: Long, val remainingCount: Int) : ReminderReason()
    /** 连续学习 */
    data class StreakContinue(val streakDays: Int) : ReminderReason()
}

object NotificationHelper {

    const val CHANNEL_ID = "study_reminder"
    const val CHANNEL_NAME = "学习提醒"
    const val NOTIFICATION_ID = 1001

    /** Intent extra key：通知携带的导航目标 */
    const val EXTRA_NAV_ACTION = "nav_action"
    const val EXTRA_ARTICLE_ID = "article_id"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每日背诵学习提醒"
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 根据用户状态生成并发送智能提醒通知
     */
    fun showReminder(context: Context, reason: ReminderReason) {
        val (title, body, navAction, articleId) = when (reason) {
            is ReminderReason.NoStudyToday -> {
                val title = "📖 今日背诵提醒"
                val body = if (reason.streakDays > 0)
                    "今天还没有完成背诵任务，花几分钟复习一下吧。"
                else
                    "今天还没有完成背诵任务，开始你的第一次练习吧！"
                Quad(title, body, "home", null)
            }
            is ReminderReason.WeakContent -> Quad(
                "🎯 薄弱内容巩固",
                "你还有 ${reason.weakCount} 个易错内容需要巩固，针对性复习效果更好哦。",
                "home",
                null
            )
            is ReminderReason.IncompletePractice -> Quad(
                "📝 继续未完成的练习",
                "上次练习还剩 ${reason.remainingCount} 个内容未完成，继续完成吧。",
                "practice",
                reason.articleId
            )
            is ReminderReason.StreakContinue -> Quad(
                "🔥 连续学习第 ${reason.streakDays} 天",
                "太棒了！继续保持，坚持就是胜利。",
                "home",
                null
            )
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ACTION, navAction)
            articleId?.let { putExtra(EXTRA_ARTICLE_ID, it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            reason.hashCode(),  // 不同通知使用不同 requestCode，避免被覆盖
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 内部四元组 */
    private data class Quad(
        val title: String,
        val body: String,
        val navAction: String,
        val articleId: Long?
    )
}
