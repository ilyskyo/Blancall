// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R

/**
 * AI 回复完成通知（仅 PRO 版）。
 *
 * 三个关键点，每一点都对应一个系统行为：
 * 1. **只有应用在后台才发**：用户还盯着对话页时再弹一条通知是噪音；
 *    后台判定用 Activity 的 started 计数，不依赖已废弃的 `isRunning` 之类接口。
 * 2. **渠道重要性必须是 HIGH**：只有 HIGH 才会以「悬浮通知」形式弹出，
 *    而系统的小窗手势（长按悬浮通知下拉）正是作用在悬浮通知上的。
 * 3. **MainActivity 保持 resizeableActivity=true 且不锁方向**（见 Manifest），
 *    系统才能把这次点击放进自由窗口 / 分屏，而不是强制全屏。
 *
 * 通知点击走 `ai_resume/{sessionId}`：先读磁盘历史；历史开关关闭时，
 * 由 [AiStreamer.completedSnapshot] 用内存态兜底（保留 30 分钟）。
 */
object AiReplyNotifier {

    const val CHANNEL_ID = "ai_reply"

    /** 与 NotificationHelper 的 extra 同名：MainActivity 只有一条解析入口 */
    const val EXTRA_NAV_ACTION = "nav_action"
    const val EXTRA_SESSION_ID = "ai_session_id"

    /** 与 AppNavigation 的 `ai_resume/{sessionId}` 路由约定 */
    const val NAV_AI_RESUME = "ai_resume"

    private const val NOTIFICATION_ID_BASE = 4100
    private const val GROUP_KEY = "ai_reply"

    /** 正在进行（尚未 stop）的 Activity 数；0 表示应用在后台 */
    @Volatile
    private var startedActivities = 0

    @Volatile
    private var lifecycleRegistered = false

    /**
     * Application 启动时调用：创建渠道并注册前后台跟踪。
     * 此时还没有 Activity，计数从 0 开始，onActivityStarted 后会变成 1。
     */
    fun init(context: Context) {
        ensureChannel(context)
        registerLifecycle(context, assumeForeground = false)
    }

    /**
     * 兜底注册（例如 [AiStreamer.start] 在测试或非标准进程里被直接调用）。
     * 从界面发起请求时传入 true，避免「注册晚了一拍」把前台误判成后台。
     */
    fun ensureTracking(context: Context, assumeForeground: Boolean) {
        registerLifecycle(context, assumeForeground)
    }

    /** 应用当前是否在后台。跟踪尚未注册时返回 false（宁可少发，也不要在前台误弹）。 */
    fun isAppInBackground(): Boolean = lifecycleRegistered && startedActivities <= 0

    /** 发一条「AI 已完成回复」通知；权限/通知开关不可用时静默跳过。 */
    fun notifyCompleted(context: Context, sessionId: Long, sessionTitle: String) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val contentTitle = context.getString(R.string.ai_notif_complete_title)
        val conversation = sessionTitle.ifBlank { context.getString(R.string.ai_assistant_name) }
        // 正文只放会话名（例如文章标题），标题固定是「AI 已完成回复，点击查看」
        val body = context.getString(R.string.ai_notif_complete_body, conversation)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        NotificationManagerCompat.from(context).notify(notificationId(sessionId), builder.build())
    }

    /** 用户从通知或页面回到该会话时清掉提示，避免「已经看了还挂着」 */
    fun cancel(context: Context, sessionId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(sessionId))
    }

    /** 会话 id → 通知 id：同一会话复用同一个 id，不同会话互不覆盖 */
    internal fun notificationId(sessionId: Long): Int =
        NOTIFICATION_ID_BASE + ((sessionId xor (sessionId ushr 32)) and 0x3FF).toInt()

    /**
     * 高重要性渠道：悬浮通知 + 声音/振动。渠道建好后重要性不可改，
     * 所以 ID 用新的 `ai_reply`，不复用提醒渠道。
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ai_notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ai_notif_channel_desc)
            setShowBadge(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context, sessionId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAV_ACTION, NAV_AI_RESUME)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun registerLifecycle(context: Context, assumeForeground: Boolean) {
        if (lifecycleRegistered) return
        synchronized(this) {
            if (lifecycleRegistered) return
            val app = context.applicationContext as? Application ?: return
            if (assumeForeground) startedActivities = 1
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                }

                override fun onActivityStopped(activity: Activity) {
                    // coerceAtLeast(0)：注册时机造成的计数偏差不应把「前台」永久算成负数
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            lifecycleRegistered = true
        }
    }
}
