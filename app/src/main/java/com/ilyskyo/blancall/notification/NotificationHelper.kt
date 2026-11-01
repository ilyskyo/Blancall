// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * 提醒通知的构造与发送（Android 侧）。
 *
 * 与旧实现的三点差异，每一点都是修一个具体的坑：
 * 1. **通知 ID 按 reason 分配**：旧代码所有通知都挤在固定 1001，后一条把前一条顶掉 ——
 *    「到期复习」和「未完成练习」同时该发时用户只看得到最后那条。
 * 2. **`setGroup("study")` + groupSummary**：多条提醒按组折叠展示，而不是互相覆盖；
 *    用户一眼能看出「今天有几件事」，点开才是明细。
 * 3. **文案全部走 `strings.xml`**：旧实现把中文硬编码在 Kotlin 里，六语种里只有中文能看到字。
 *
 * 判定（该发哪几条）不在这里，见纯函数 [ReminderPlan]。
 */
object NotificationHelper {

    /**
     * 旧渠道 ID，仅为兼容保留（[NotificationChannels.CHANNEL_ID_LEGACY]）。
     * 新代码一律用 [NotificationChannels.channelIdFor]。
     */
    @Deprecated("旧单渠道 ID，仅为兼容存量设置保留")
    const val CHANNEL_ID = NotificationChannels.CHANNEL_ID_LEGACY

    /** Intent extra key：通知携带的导航目标 */
    const val EXTRA_NAV_ACTION = "nav_action"
    const val EXTRA_ARTICLE_ID = "article_id"

    /** 本次点击来自通知动作按钮（供未来埋点/行为区分；当前与正文点击同路由） */
    const val EXTRA_FROM_ACTION = "from_action"

    /** 动作 PendingIntent 的 requestCode 偏移：与内容点击（通知 ID）错开 */
    private const val ACTION_REQUEST_OFFSET = 100_000

    /** 深链 action 字符串（与 MainActivity.handleNotificationIntent 的映射约定，见交付报告） */
    const val NAV_HOME = "home"
    const val NAV_PRACTICE = "practice"
    const val NAV_SENTENCE_CARDS = "sentence_cards"

    /** 练习进行中通知：回到上次作答现场（resume=true），不是重新开始一篇 */
    const val NAV_RESUME_PRACTICE = "resume_practice"

    /** 学习任务详情：给「即将到期 / 即将遗忘」用，点进去能看到该篇的留存率与到期日 */
    const val NAV_STUDY_TASK = "study_task"

    /** 通知 ID 基数：`基数 + reason.ordinal`，保证不同 reason 不互相覆盖 */
    private const val NOTIFICATION_ID_BASE = 1001

    /** 分组摘要 ID（与各 reason 的 ID 不重叠） */
    private const val GROUP_SUMMARY_ID = 2000

    /** 测试通知 ID：不与任何 reason / 摘要重叠，重复点「发送测试通知」只更新这一条 */
    private const val TEST_NOTIFICATION_ID = 3000

    /** 通知分组键 */
    private const val GROUP_KEY = "study"

    /** 某 reason 对应的通知 ID（同时用作 PendingIntent 的 requestCode，保证互不覆盖） */
    fun notificationIdFor(reason: ReminderReason): Int = NOTIFICATION_ID_BASE + reason.ordinal

    /**
     * 保留旧入口名（[com.ilyskyo.blancall.BlancallApp] 启动时调用）：
     * 现在一次性创建全部渠道，创建是幂等的。
     */
    fun createChannel(context: Context) {
        NotificationChannels.createAll(context)
    }

    /**
     * 按判定结果发一条通知。文案与渠道由 [ReminderPlanItem.reason] 决定。
     */
    fun showReminder(context: Context, item: ReminderPlanItem) {
        val res = context.resources
        val reason = item.reason

        val title: String
        val body: String
        val navAction: String
        val articleId: Long
        when (reason) {
            ReminderReason.NoStudyToday -> {
                title = context.getString(R.string.notif_no_study_title)
                // count = 连续天数；有记录时把天数写进正文，否则用「第一次」的说法
                body = if (item.count > 0) {
                    context.getString(R.string.notif_no_study_body, item.count)
                } else {
                    context.getString(R.string.notif_no_study_body_first)
                }
                navAction = NAV_HOME
                articleId = -1L
            }

            ReminderReason.WeakContent -> {
                title = context.getString(R.string.notif_weak_title)
                body = res.getQuantityString(R.plurals.notif_weak_body, item.count, item.count)
                navAction = NAV_HOME
                articleId = -1L
            }

            ReminderReason.IncompletePractice -> {
                title = context.getString(R.string.notif_incomplete_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = if (item.articleTitle.isNotBlank()) {
                    res.getQuantityString(
                        R.plurals.notif_incomplete_named, item.count, name, item.count
                    )
                } else {
                    res.getQuantityString(
                        R.plurals.notif_incomplete_body, item.count, item.count
                    )
                }
                // 未完成练习：恢复现场（resume=true），不是从第一空重新开始
                navAction = NAV_RESUME_PRACTICE
                articleId = item.articleId
            }

            ReminderReason.StreakContinue -> {
                title = context.getString(R.string.notif_streak_title, item.count)
                body = context.getString(R.string.notif_streak_body)
                navAction = NAV_HOME
                articleId = -1L
            }

            ReminderReason.DueReview -> {
                title = context.getString(R.string.notif_due_review_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = if (item.articleTitle.isNotBlank()) {
                    // 具体到哪一篇：只有一项时说「《X》到了」，多项时说「《X》等 N 项」
                    if (item.count <= 1) {
                        context.getString(R.string.notif_due_review_named_one, name)
                    } else {
                        res.getQuantityString(
                            R.plurals.notif_due_review_named, item.count, name, item.count
                        )
                    }
                } else {
                    res.getQuantityString(
                        R.plurals.notif_due_review_body, item.count, item.count
                    )
                }
                // 有具体文章就直接进练习；只有句子到期时去句子卡
                navAction = if (item.articleId > 0) NAV_PRACTICE else NAV_SENTENCE_CARDS
                articleId = item.articleId
            }

            ReminderReason.StartArticle -> {
                title = context.getString(R.string.notif_start_article_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = if (item.articleTitle.isNotBlank()) {
                    if (item.count <= 1) {
                        context.getString(R.string.notif_start_article_named_one, name)
                    } else {
                        res.getQuantityString(
                            R.plurals.notif_start_article_named, item.count, name, item.count
                        )
                    }
                } else {
                    res.getQuantityString(
                        R.plurals.notif_start_article_body, item.count, item.count
                    )
                }
                // 未开始直接进第一次练习，不是去复习
                navAction = NAV_PRACTICE
                articleId = item.articleId
            }

            ReminderReason.TaskDueSoon -> {
                title = context.getString(R.string.notif_due_soon_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = context.getString(
                    R.string.notif_due_soon_body, name, formatDateTime(context, item.dueAt)
                )
                navAction = NAV_STUDY_TASK
                articleId = item.articleId
            }

            ReminderReason.ForgettingSoon -> {
                title = context.getString(R.string.notif_forgetting_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = context.getString(
                    R.string.notif_forgetting_body, name, item.retentionPercent
                )
                navAction = NAV_STUDY_TASK
                articleId = item.articleId
            }

            ReminderReason.PracticeAccuracy -> {
                title = context.getString(R.string.notif_practice_accuracy_title)
                val name = item.articleTitle.ifBlank { context.getString(R.string.untitled_article) }
                body = context.getString(
                    R.string.notif_practice_accuracy_body,
                    name,
                    formatDateTime(context, item.practicedAt),
                    item.accuracyPercent,
                )
                navAction = NAV_PRACTICE
                articleId = item.articleId
            }

            ReminderReason.SentenceCardDue -> {
                title = context.getString(R.string.notif_sentence_card_title)
                body = context.getString(R.string.notif_sentence_card_body)
                navAction = NAV_SENTENCE_CARDS
                articleId = -1L
            }

            ReminderReason.DailyGoalMissed -> {
                title = context.getString(R.string.notif_daily_goal_title)
                body = context.getString(R.string.notif_daily_goal_body, item.count)
                navAction = NAV_HOME
                articleId = -1L
            }
        }

        val builder = NotificationCompat.Builder(context, NotificationChannels.channelIdFor(reason))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, navAction, articleId, reason))
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // 「开始」只做开始，不做「跳过」：跳过要写调度状态（今天不练这篇），
        // 语义影响远大于一个按钮的收益。只有已经指向具体内容（文章/句子卡）时才挂
        // ——首页类提醒没有明确的第一件，挂上去只会多一次无效点击。
        if (navAction != NAV_HOME) {
            // 动作词必须跟提醒语义一致：继续练习 / 开始学习 / 开始复习 / 查看任务
            val actionLabel = when (reason) {
                ReminderReason.StartArticle -> R.string.notif_action_start_learning
                ReminderReason.IncompletePractice -> R.string.notif_action_resume
                ReminderReason.PracticeAccuracy -> R.string.go_practice
                ReminderReason.TaskDueSoon, ReminderReason.ForgettingSoon ->
                    R.string.notif_action_view_task
                ReminderReason.SentenceCardDue -> R.string.notif_action_sentence
                else -> R.string.notif_action_start
            }
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(actionLabel),
                actionIntent(context, navAction, articleId, reason)
            )
        }

        manager(context)?.notify(notificationIdFor(reason), builder.build())
    }

    /**
     * 发一条分组摘要，使同组多条提醒折叠展示（而不是各自为政）。
     *
     * 只在本次真的发了 ≥1 条时调用：没有子通知时摘要会以空组的形式挂在通知栏上，反而更乱。
     */
    fun showGroupSummary(context: Context, count: Int) {
        if (count <= 0) return
        val res = context.resources
        val summary = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_GOAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_group_summary_title))
            .setContentText(res.getQuantityString(R.plurals.notif_group_summary_body, count, count))
            .setContentIntent(contentIntent(context, NAV_HOME, -1L, null))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager(context)?.notify(GROUP_SUMMARY_ID, summary)
    }

    /**
     * 发一条测试通知，返回是否真的投递出去。
     *
     * 与每日提醒共用同一套权限/渠道判断：系统通知被关、或所有提醒渠道都被关时返回 false，
     * 由设置页明确提示「去开启」，而不是制造「已发送」的假象。
     * 不参与 [ReminderStateStore] 去重，也不受免打扰限制——这是用户主动点的即时反馈。
     */
    fun showTest(context: Context, nextAtText: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channelId = NotificationChannels.firstEnabledReminderChannel(context) ?: return false
        val nm = manager(context) ?: return false
        val body = context.getString(R.string.reminder_test_body, nextAtText)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_test_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context, NAV_HOME, -1L, null))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        nm.notify(TEST_NOTIFICATION_ID, builder.build())
        return true
    }

    /**
     * 「下次提醒」的本地化时间。
     *
     * 用系统推荐的日期骨架而不是硬编码 pattern：12/24 小时制跟着语言走
     * （英文 8:00 PM、中文 20:00），星期与月份名也不会混进另一种语言。
     */
    fun formatReminderTime(context: Context, at: LocalDateTime): String {
        val locale = context.resources.configuration.locales[0]
        // 跟随系统的 12/24 小时制；只靠骨架里的 j 会按语言默认走，
        // 出现「英文界面 + 系统 24 小时制」时与设置页的时间行不一致。
        val skeleton = if (android.text.format.DateFormat.is24HourFormat(context)) {
            "MMMdEEHmm"
        } else {
            "MMMdEEhm"
        }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    /**
     * 只显示「几点」时的格式化（提醒时间行、免打扰起止）。
     *
     * 用 [android.text.format.DateFormat.getTimeFormat] 而不是手写 `%02d:%02d`：
     * 它与「下次提醒」一样跟随系统 12/24 小时制。否则英文界面上方写着 8:00 PM、
     * 下面一行却是 20:00，同一时刻出现两种写法。
     */
    fun formatTimeOfDay(context: Context, minutesOfDay: Int): String {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, (minutesOfDay / 60).coerceIn(0, 23))
            set(java.util.Calendar.MINUTE, (minutesOfDay % 60).coerceIn(0, 59))
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
    }

    private fun contentIntent(
        context: Context,
        navAction: String,
        articleId: Long,
        reason: ReminderReason?,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ACTION, navAction)
            if (articleId > 0) putExtra(EXTRA_ARTICLE_ID, articleId)
        }
        // requestCode 按 reason 区分，避免不同通知的 PendingIntent 互相覆盖（摘要用固定值）
        val requestCode = reason?.let { notificationIdFor(it) } ?: GROUP_SUMMARY_ID
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知动作的 PendingIntent：与内容点击走同一条深链，但 requestCode 必须错开。
     * 若与内容点击共用 requestCode + 相同 extras，系统会把它当成同一个 PendingIntent
     * （动作点了等于点正文），在部分 ROM 上还会互相覆盖。
     */
    private fun actionIntent(
        context: Context,
        navAction: String,
        articleId: Long,
        reason: ReminderReason,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ACTION, navAction)
            if (articleId > 0) putExtra(EXTRA_ARTICLE_ID, articleId)
            putExtra(EXTRA_FROM_ACTION, true)
        }
        return PendingIntent.getActivity(
            context,
            ACTION_REQUEST_OFFSET + notificationIdFor(reason),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知里展示「什么时候到期 / 哪一次练习」用的本地化时间。
     * 用应用当前语言（configuration.locales[0]）而不是 JVM 默认 Locale：
     * 应用内切换语言后，系统 Locale 可能还没变，用默认 Locale 会混进另一种语言的月份名。
     */
    private fun formatDateTime(context: Context, epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        val locale = context.resources.configuration.locales[0]
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(epochMillis))
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
}
