// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 提醒能否真正送达的现状。
 *
 * 为什么单独抽出来：设置页的开关只反映「用户想不想要提醒」，
 * 但系统通知被关、某个渠道被静音时，提醒同样不会出现。
 * 旧界面在任何情况下都显示「已开启」，用户没有任何线索知道是系统把它拦了。
 * 这里把「系统级可否发送」与「有几个提醒渠道被关」收敛成一次查询，界面只负责展示。
 */
object ReminderNotifications {

    data class Status(
        val notificationsEnabled: Boolean,
        val disabledChannels: Int,
    ) {
        /** 系统允许通知，但有提醒渠道被单独关掉。 */
        val channelsBlocked: Boolean get() = notificationsEnabled && disabledChannels > 0

        /** 全链路可用。 */
        val allGood: Boolean get() = notificationsEnabled && disabledChannels == 0
    }

    fun status(context: Context): Status {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val manager = context.getSystemService(NotificationManager::class.java)
        // 渠道还没创建时列表为空：按「未关闭」处理，Application 启动时会幂等创建
        val channels = manager?.notificationChannels.orEmpty()
        val disabled = NotificationChannels.REMINDER_CHANNEL_IDS.count { id ->
            channels.firstOrNull { it.id == id }?.importance == NotificationManager.IMPORTANCE_NONE
        }
        return Status(notificationsEnabled = enabled, disabledChannels = disabled)
    }
}
