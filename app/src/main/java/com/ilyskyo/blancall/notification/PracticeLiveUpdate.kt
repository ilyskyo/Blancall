// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「练习进行中」的常驻通知 / Android 16 Live Update（各家灵动岛）。
 *
 * 触发时机只有一个：练习页在答题、且应用退到后台。前台不显示 —— 用户正看着练习页，
 * 再挂一张岛卡是重复信息；回到前台即撤下，下一次退后台再按最新进度重发。
 *
 * 只走 Google 的公开标准，不写任何厂商私有参数。依据 developer.android.com
 * 《Create live update notifications》，一条通知要被提升为实时更新，必须同时满足：
 * 样式为 Standard/BigText/Call/Progress/Metric 之一、清单里有 POST_PROMOTED_NOTIFICATIONS、
 * 带提升请求（EXTRA_REQUEST_PROMOTED_ONGOING）、FLAG_ONGOING_EVENT、有 contentTitle、
 * 无自定义 RemoteViews、非群组摘要、未 setColorized(true)、渠道重要性非 MIN。
 *
 * 厂商侧现状（2026-10 查证，详见 dev.mi.com / open.oppomobile.com / developer.huawei.com 等）：
 *  - 小米：OS2 是「焦点通知」、OS3 是「超级岛」，客户端可用 `miui.focus.param` 私有 JSON 接入，
 *    但必须先邮件申请焦点通知权限 + 提报方案（dev.mi.com 常见 Q&A），未开通时通知退化为普通通知；
 *  - OPPO：私有流体云要 UPK 卡片工程 + 白名单；官方人员公开表态 ColorOS 16 起完整兼容本 API；
 *  - 华为：实况窗属 HarmonyOS NEXT 的 Live View Kit，需 AGC 开通权益，Android APK 无此通道；
 *  - 荣耀：灵动胶囊走「全局触达」云侧事件接口 + 场景审核，本地通知进不去；
 *  - vivo：原子通知有公开的本地 `notification.superx.*` 字段，但场景表里没有学习类。
 * 因此这里只发标准形态：新系统自己会把它接到岛上，旧系统退化为普通常驻进度通知。
 *
 * 真正落盘由 [Session.persistForExit]（PracticeViewModel.saveForAppExit）完成，
 * 这里只负责「退后台时保存成功之后再上通知」这条顺序：没存下来就上岛，点进去会是一场空。
 */
object PracticeLiveUpdate {

    /** 保存成功后用于构造通知的进度快照；由 ViewModel 在落盘同一时刻读取，避免晚一帧的计数 */
    data class ExitSnapshot(
        val articleId: Long,
        val articleTitle: String,
        val mode: BlancallMode,
        val totalBlanks: Int,
        val answeredBlanks: Int,
        val typedChars: Int,
    )

    /** 当前答题会话。`persistForExit` 返回 null 表示没有可续练的内容（未作答/跨文/已提交等） */
    class Session(
        val articleId: Long,
        val persistForExit: suspend () -> ExitSnapshot?,
    )

    /** Live Update 与普通进度通知共用一个 ID：同一场练习只该存在一张卡 */
    private const val NOTIFICATION_ID = 3100

    /**
     * 与 [Notification.EXTRA_REQUEST_PROMOTED_ONGOING] 同值。
     * 该常量 36.1 才公开，而编译目标是 36.1；设备可能是 36.0，用字面量避免字段链接进 36.0 的包。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var appBackgrounded = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 由练习页登记/注销。只改内存态；注销不撤通知（Activity 销毁也可能发生在退后台之后） */
    fun updateSession(newSession: Session?) {
        session = newSession
    }

    /** 通知点击时用于判断「应用是否还停在同一个练习里」，避免在返回栈上再压一层同样的页面 */
    fun hasActiveSession(articleId: Long): Boolean = session?.articleId == articleId

    fun onAppForegrounded() {
        appBackgrounded = false
        appContext?.let { NotificationManagerCompat.from(it).cancel(NOTIFICATION_ID) }
    }

    fun onAppBackgrounded() {
        appBackgrounded = true
        val active = session ?: return
        scope.launch {
            val snapshot = runCatching { active.persistForExit() }.getOrNull() ?: return@launch
            // 用户可能只是短暂拉起其它 Activity 就回来了：此时不再补发，免得在前台看到岛卡
            if (!appBackgrounded || session !== active) return@launch
            val context = appContext ?: return@launch
            if (!canPostNotifications(context)) return@launch
            post(context, snapshot)
        }
    }

    private fun post(context: Context, snapshot: ExitSnapshot) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val title = context.getString(
            R.string.notif_ongoing_title,
            snapshot.articleTitle.ifBlank { context.getString(R.string.untitled_article) },
        )
        val isDictation = snapshot.mode == BlancallMode.REVERSE
        val total = snapshot.totalBlanks.coerceAtLeast(0)
        val answered = snapshot.answeredBlanks.coerceIn(0, total)
        // 模式名进副标题：同样一条「继续练习」，读者一眼知道回去是填空还是默写
        val modeLabel = context.getString(
            when (snapshot.mode) {
                BlancallMode.SENTENCE -> R.string.notif_mode_sentence
                BlancallMode.WORD -> R.string.notif_mode_word
                BlancallMode.REVERSE -> R.string.notif_mode_reverse
            }
        )
        val body = if (isDictation) {
            context.getString(R.string.notif_ongoing_body_reverse, snapshot.typedChars)
        } else {
            // 报「还剩多少」而不是复述「点按回到练习」——整张卡本来就能点，那句话是占位符
            context.getString(
                R.string.notif_ongoing_body,
                answered,
                total,
                (total - answered).coerceAtLeast(0),
            )
        }
        val shortText = if (isDictation) {
            context.getString(R.string.notif_ongoing_short_reverse)
        } else {
            context.getString(R.string.notif_ongoing_short, answered, total)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdate(
                context, snapshot, title, body, shortText, modeLabel, isDictation, total, answered,
                requestPromotion = canRequestPromotion(context),
            )
        } else {
            buildCompat(context, snapshot.articleId, title, body, modeLabel, isDictation, total, answered)
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Android 16：用 ProgressStyle 参与 Live Updates 提升；默写没有逐空进度，用文字卡 */
    private fun buildLiveUpdate(
        context: Context,
        snapshot: ExitSnapshot,
        title: String,
        body: String,
        shortText: String,
        modeLabel: String,
        isDictation: Boolean,
        total: Int,
        answered: Int,
        requestPromotion: Boolean,
    ): Notification {
        val builder = Notification.Builder(context, NotificationChannels.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(modeLabel)
            .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setContentIntent(contentIntent(context, snapshot.articleId))
            .addAction(platformAction(context, snapshot.articleId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setShortCriticalText(shortText)

        if (isDictation) {
            builder.setStyle(Notification.BigTextStyle().bigText(body))
        } else {
            val max = total.coerceAtLeast(1)
            builder.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgress(answered.coerceIn(0, max))
                    // ProgressStyle 的满刻度由分区长度之和决定，没有单独的 setMax
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(max)))
            )
        }

        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, requestPromotion)
        })
        return builder.build()
    }

    /**
     * 用户可以在「设置 → 通知 → 本应用 → 实时更新」里单独关掉提升（API 36 起有该开关）。
     * 关掉时系统本来就不会提升，这里主动不发请求：通知照旧以普通常驻卡展示，
     * 行为可预期，也免得在系统侧留一条「请求了但被驳回」的记录。
     */
    private fun canRequestPromotion(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    /** Android 16 以下：普通常驻通知，保留进度条与点击续练 */
    private fun buildCompat(
        context: Context,
        articleId: Long,
        title: String,
        body: String,
        modeLabel: String,
        isDictation: Boolean,
        total: Int,
        answered: Int,
    ): Notification {
        val max = total.coerceAtLeast(1)
        return NotificationCompat.Builder(context, NotificationChannels.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(modeLabel)
            .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(max, answered.coerceIn(0, max), isDictation)
            .addAction(compatAction(context, articleId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setContentIntent(contentIntent(context, articleId))
            .build()
    }

    /**
     * 卡片上的「继续练习」按钮：和整卡点击同一个 PendingIntent。
     * 只为了让这条常驻通知不像一张只能看的静态卡 —— 岛收起来时，通知栏里仍有一个明确的动作。
     */
    private fun platformAction(context: Context, articleId: Long): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            context.getString(R.string.notif_ongoing_action),
            contentIntent(context, articleId),
        ).build()

    private fun compatAction(context: Context, articleId: Long): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_ongoing_action),
            contentIntent(context, articleId),
        ).build()

    private fun contentIntent(context: Context, articleId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // SINGLE_TASK 的 MainActivity 已在运行时走 onNewIntent，不会重建练习页
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationHelper.EXTRA_NAV_ACTION, NotificationHelper.NAV_RESUME_PRACTICE)
            if (articleId > 0L) putExtra(NotificationHelper.EXTRA_ARTICLE_ID, articleId)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
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
}
