// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.StudyCoach
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.InProgressPracticeStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 每日学习提醒 Worker（CoroutineWorker）：
 * 1. 检查提醒开关、提醒日与免打扰时段
 * 2. 采集用户状态（今日练习次数、到期文章/句子、未完成练习、薄弱内容、句子卡）
 * 3. 交给纯函数 [ReminderPlan.decide] 决定该发哪几条，再用 [ReminderStateStore] 做当日去重后发送
 * 4. 调度下一次提醒（按提醒日跳到下一次）
 *
 * 判定为什么搬去 [ReminderPlan]：以前这里既取数又拍板，`analyzeUserState` 直接 new 出一个
 * `ReminderReason` —— 想加一条规则就得连 Android 上下文一起搬进测试，于是**根本没有测试**。
 * 现在「取数」（本类，依赖 Android）与「拍板」（[ReminderPlan]，纯函数）分开，规则全部可单测。
 */
class ReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 双保险：即使 BlancallApp 未在该进程初始化，也确保 Prefs 可用（init 可重入）
            runCatching { ReminderPrefs.init(applicationContext) }
            // 到期判定要读复习模板，AppPrefs 也必须可用（同一份 init 可重入约定）
            runCatching { AppPrefs.init(applicationContext) }

            if (!ReminderPrefs.enabled) {
                Log.d(TAG, "提醒已关闭，跳过")
                return Result.success()
            }

            // 检查今天是否在提醒日（根据频率）
            if (!isReminderDay()) {
                Log.d(TAG, "今天不是提醒日，直接调度下一次")
                scheduleNext(context)
                return Result.success()
            }

            val now = System.currentTimeMillis()

            // 免打扰：命中就**跳过本次**，不顺延到时段结束。
            // 顺延会把提醒堆到静默窗口结束的那一刻（用户刚睡醒就看到一串），反而更吵；
            // 而且「到点提醒」一旦不准时，就失去了它作为习惯锚点的意义 —— 宁可今天不发。
            if (ReminderPrefs.quietHoursEnabled && isInQuietHours(now)) {
                Log.d(TAG, "命中免打扰时段，跳过本次")
                scheduleNext(context)
                return Result.success()
            }

            // 通知总开关被关闭时跳过通知，但仍调度下一次（用户重新开启后可恢复）
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.d(TAG, "通知已被用户关闭，跳过本次通知")
                scheduleNext(context)
                return Result.success()
            }

            val plans = buildPlans(now)
            val stateStore = ReminderStateStore.getInstance(context.filesDir)
            var sent = 0
            for (item in plans.take(MAX_PER_RUN)) {
                if (!stateStore.shouldSend(item.reason, now)) {
                    Log.d(TAG, "今日已发过 ${item.reason}，跳过")
                    continue
                }
                NotificationHelper.showReminder(context, item)
                stateStore.markSent(item.reason, now)
                sent++
            }
            // 有子通知才发摘要（见 NotificationHelper.showGroupSummary 的说明）
            if (sent > 0) NotificationHelper.showGroupSummary(context, sent)

            // 调度下一次提醒
            scheduleNext(context)

            Log.d(TAG, "提醒已处理：候选 ${plans.size} 条，实发 $sent 条")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "发送提醒失败", e)
            Result.retry()
        }
    }

    /** 采集全部判定输入并交给纯函数拍板（本方法只做「取数」，不做规则判断）。 */
    private suspend fun buildPlans(now: Long): List<ReminderPlanItem> {
        val fsrs = runCatching {
            FsrsStateStore.getInstance(
                context.filesDir.resolve("fsrs_state.json").absolutePath
            )
        }.getOrNull()
        // 精确等待加载完成（带 2 秒上限），避免把「还没加载完」当成「没有到期」
        fsrs?.let { withTimeoutOrNull(2000L) { it.awaitLoaded() } }

        val articleStates = fsrs?.allStates().orEmpty()
        // 用户主动暂停的文章不再催：到期判定与启停状态同源（fsrs_state.json 的 paused 表）
        val pausedArticleIds = fsrs?.pausedArticleIds().orEmpty()
        val pauses = fsrs?.pausedArticles().orEmpty()
        // 加入计划但还没练的文章也要算进「今天该做什么」，否则用户加了计划却收不到提醒
        val enrolled = fsrs?.enrolledArticles().orEmpty()

        // 未来 72 小时内到期、但此刻还没到期：提前说「哪一篇快到期了」
        val upcomingTasks = articleStates
            .filterKeys { it !in pausedArticleIds }
            .filterValues { state ->
                state.reviewCount > 0 && state.due > now &&
                    state.due <= now + DUE_SOON_WINDOW_MS
            }
            .entries
            .sortedBy { it.value.due }
            .take(MAX_PREDICTED_TASKS)
            .map { (id, state) -> ReminderUpcomingTask(id, articleTitle(id), state.due) }

        // 「快要遗忘」= 还没有到期，但留存率已经贴近目标线。
        // 目标线是到期那一刻的留存率（standard 0.90 / deep 0.95 / sprint 0.85），
        // 所以留 3 个百分点的提前量：再拖一两天就会掉到目标以下。
        val dangerRetention = (FsrsEngine.targetRetention() + FORGETTING_MARGIN)
            .coerceAtMost(MAX_FORGETTING_RETENTION)
        val forgettingRisks = articleStates
            .filterKeys { it !in pausedArticleIds }
            .filterValues { state ->
                state.reviewCount > 0 && !FsrsEngine.isDue(state, now) &&
                    FsrsEngine.retentionRate(state, now) < dangerRetention
            }
            .entries
            .sortedBy { FsrsEngine.retentionRate(it.value, now) }
            .take(MAX_PREDICTED_TASKS)
            .map { (id, state) ->
                ReminderForgettingRisk(
                    articleId = id,
                    title = articleTitle(id),
                    retentionPercent = (FsrsEngine.retentionRate(state, now) * 100)
                        .roundToInt().coerceIn(0, 100),
                )
            }

        val sentenceStates = fsrs?.allSentenceStates().orEmpty()
        val today = DayBucket.localDate(now)
        val sentenceCardPracticedToday = sentenceStates.values.any { state ->
            state.reviewCount > 0 && DayBucket.localDate(state.lastReview) == today
        }

        // 与首页、统计页共用同一份取数：到期文章含模板兜底，句子数用「全部到期句」；
        // 未完成练习只保留未暂停、未删除、仍有剩余空的记录。
        val records = loadRecords()
        val snapshot = StudyCoach.snapshot(
            StudyCoach.Source(
                articles = loadArticles(),
                records = records,
                states = articleStates,
                pauses = pauses,
                sentenceStates = sentenceStates,
                template = ReviewTemplate.fromId(AppPrefs.reviewTemplateId),
                resumables = runCatching {
                    InProgressPracticeStore.scan(context.filesDir)
                }.getOrDefault(emptyList()),
                todaySentenceKey = runCatching {
                    SentenceCardStore.getInstance(context.filesDir).today()
                        ?.takeIf { it.date == today.toString() }?.key
                }.getOrNull(),
                enrolled = enrolled,
            ),
            now = now,
        )
        // 到期文章必须按「有没有练过」拆开：复习与第一次练习是两种通知语义。
        // 旧版/CSV 回灌只有 records 的文章也算练过（hasPractice 由 StudyCoach 统一给出）。
        val dueReviewArticles = snapshot.input.dueArticles.filter { it.hasPractice }
        val dueNewArticles = snapshot.input.dueArticles.filter { !it.hasPractice }
        val firstDue = dueReviewArticles.minByOrNull { it.due }
        val firstDueArticleId = firstDue?.articleId ?: -1L
        val firstNew = dueNewArticles.minByOrNull { it.due }
        val firstNewArticleId = firstNew?.articleId ?: -1L
        val incomplete = snapshot.resumables.firstOrNull()
        val todayCount = records.count { DayBucket.localDate(it.timestamp) == today }
        val weakCount = countWeakContent(records, pausedArticleIds)
        val lastPractice = records
            .filter { it.totalBlanks > 0 }
            .maxByOrNull { it.timestamp }
            ?.let { record ->
                ReminderLastPractice(
                    articleId = record.articleId,
                    title = articleTitle(record.articleId),
                    accuracyPercent = accuracyPercentOf(record),
                    practicedAt = record.timestamp,
                )
            }

        return ReminderPlan.decide(
            now = now,
            hasStudiedToday = ReminderPrefs.hasStudiedToday(),
            todayCount = todayCount,
            dailyGoal = ReminderPrefs.dailyPracticeGoal,
            dueArticleCount = dueReviewArticles.size,
            dueNewArticleCount = dueNewArticles.size,
            dueSentenceCount = snapshot.sentenceTotalDue,
            firstDueArticleId = firstDueArticleId,
            firstNewArticleId = firstNewArticleId,
            sentenceCardPracticedToday = sentenceCardPracticedToday,
            hasIncompletePractice = incomplete != null,
            incompleteArticleId = incomplete?.articleId ?: -1L,
            incompleteRemaining = incomplete?.remaining ?: 0,
            weakCount = weakCount,
            streakDays = ReminderPrefs.studyStreakDays,
            dueArticleTitle = firstDue?.title
                ?: if (firstDueArticleId > 0) articleTitle(firstDueArticleId) else "",
            firstNewArticleTitle = firstNew?.title
                ?: if (firstNewArticleId > 0) articleTitle(firstNewArticleId) else "",
            incompleteArticleTitle = incomplete?.let { articleTitle(it.articleId) }.orEmpty(),
            upcomingTasks = upcomingTasks,
            forgettingRisks = forgettingRisks,
            lastPractice = lastPractice,
            allowReasons = allowedReasons(),
        )
    }

    /** 全部文章快照：与首页同一份模板兜底输入；读盘失败按空库处理，不让提醒整体失败。 */
    private suspend fun loadArticles(): List<Article> {
        return try {
            val repo = ArticleRepository.getInstance(
                context.filesDir.resolve("articles.json").absolutePath
            )
            // 与记录同约定：等首载完成，否则 first() 可能拿到初始空表，模板兜底到期会被漏掉
            withTimeoutOrNull(2000L) { repo.awaitLoaded() }
            withTimeoutOrNull(2000L) { repo.allArticles.first() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 文章标题：取不到时返回空串，由通知文案回落到「未命名文章」，绝不让整条提醒发不出去。 */
    private suspend fun articleTitle(articleId: Long): String {
        if (articleId <= 0L) return ""
        return runCatching {
            val repo = ArticleRepository.getInstance(
                context.filesDir.resolve("articles.json").absolutePath
            )
            // 与仓储加载同一个上限：损坏的 articles.json 不能把整次提醒卡到 WorkManager 超时
            withTimeoutOrNull(2000L) { repo.getArticleById(articleId)?.title }.orEmpty()
        }.getOrDefault("")
    }

    /** 正确率（0-100）；调用前已保证 totalBlanks > 0。 */
    private fun accuracyPercentOf(record: PracticeRecord): Int =
        (record.correctCount.toFloat() / record.totalBlanks * 100)
            .roundToInt().coerceIn(0, 100)

    /**
     * 一次性读取练习记录：今日次数、薄弱内容、最近一次练习都要用它。
     * 精确等待加载完成（2 秒上限），避免把「还没加载完」当成「今天没练 / 没有记录」。
     */
    private suspend fun loadRecords(): List<PracticeRecord> {
        return try {
            val recordRepo = RecordRepository.getInstance(
                context.filesDir.resolve("records.json").absolutePath
            )
            withTimeoutOrNull(2000L) { recordRepo.awaitLoaded() }
            recordRepo.records.value
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 统计薄弱内容（错误过的题目数量，按文章+空号去重）；暂停文章的错题不再计入。 */
    private fun countWeakContent(records: List<PracticeRecord>, pausedArticleIds: Set<Long>): Int {
        if (records.isEmpty()) return 0
        val mistakeSet = mutableSetOf<String>()
        for (record in records) {
            if (record.articleId in pausedArticleIds) continue
            for (m in record.mistakes) {
                mistakeSet.add("${record.articleId}_${m.blankIndex}")
            }
        }
        return mistakeSet.size
    }

    /** 当前是否在免打扰窗口内（时刻取自同一份 [now]，与判定共用时钟）。 */
    private fun isInQuietHours(now: Long): Boolean {
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return ReminderPlan.isQuietMinute(
            minuteOfDay = local.hour * 60 + local.minute,
            startMinute = ReminderPrefs.quietStartMinutes,
            endMinute = ReminderPrefs.quietEndMinutes,
        )
    }

    /**
     * 本次允许发送的原因集合：**用户在系统里关掉的渠道，对应原因直接缺席**。
     * 交给 [ReminderPlan.decide] 的 allowReasons，让次高优先级的规则顶上，
     * 而不是「关掉一个渠道 → 整次提醒什么都不发」。
     */
    private fun allowedReasons(): Set<ReminderReason> {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return ReminderReason.entries.toSet()
        return ReminderReason.entries.filter { reason ->
            val channel = manager.getNotificationChannel(NotificationChannels.channelIdFor(reason))
            // 渠道尚未创建（null）视为可用；重要性 NONE 才是用户明确关闭
            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }.toSet()
    }

    /** 检查指定日期是否在提醒日范围内（默认今天）。 */
    private fun isReminderDay(date: LocalDate = LocalDate.now()): Boolean =
        ReminderSchedule.isReminderDay(date, ReminderPrefs.reminderDays)

    companion object {
        private const val TAG = "ReminderWorker"
        private const val WORK_NAME = "daily_study_reminder"

        /**
         * 单次最多发送条数。判定层（[ReminderPlan]）已把「一天该说几件事」收敛到很少，
         * 这里再设一道上限是兜底：将来规则变动也不至于一次刷屏。
         */
        private const val MAX_PER_RUN = 2

        /** 预警层最多扫描几篇（只需找出最早/最危险的一篇，扫描上限防止超大题库拖慢 Worker）。 */
        private const val MAX_PREDICTED_TASKS = 20

        /** 「即将遗忘」的提前量：留存率高于目标线不足 3 个百分点时开始预警。 */
        private const val FORGETTING_MARGIN = 0.03

        /** 提前量再大也不预警高于该留存率的内容，避免刚复习完就被说「快忘了」。 */
        private const val MAX_FORGETTING_RETENTION = 0.98

        /**
         * 重新调度下一次提醒（REPLACE 语义，设置变更与 Worker 自调度都走这里）。
         *
         * 为什么不接精确闹钟：学习提醒不属于「闹钟 / 日历 / 通话」这类 Google Play
         * 认可的精确闹钟正当用途，声明 `SCHEDULE_EXACT_ALARM` 属高风险类别；
         * 而不声明权限时 `AlarmManager.setExactAndAllowWhileIdle` 在 API 31+ 会抛
         * `SecurityException`，还得接 `canScheduleExactAlarms()` 降级与权限变更广播。
         * 收益（准几分钟）远小于改动面与审核风险，因此维持纯 WorkManager 自调度，
         * 并在设置页明确告知「省电模式下可能延迟」。
         *
         * - 时间与提醒日由 [ReminderSchedule] 统一计算，界面显示与实际调度同源
         * - [ReminderPrefs.enabled] 关闭时直接取消，不留下空转任务
         */
        fun scheduleNext(context: Context) {
            if (!ReminderPrefs.enabled) {
                cancel(context)
                return
            }

            val now = LocalDateTime.now()
            val nextReminder = ReminderSchedule.nextAt(
                now = now,
                hour = ReminderPrefs.hour,
                minute = ReminderPrefs.minute,
                days = ReminderPrefs.reminderDays,
            )
            val delayMillis = ChronoUnit.MILLIS.between(now, nextReminder)

            val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)  // 低电量也提醒
                        .build()
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(TAG, "下次提醒已调度：${nextReminder}, 延迟 ${delayMillis / 60000} 分钟")
        }

        /**
         * 只在没有待发任务时补一条（打开 App / 回到前台 / 权限恢复时调用）。
         *
         * 不能在这里用 [scheduleNext] 的 REPLACE：若 20:00 的任务被省电策略推迟、
         * 用户在 20:30 打开 App，REPLACE 会把「今天 20:00」改成「明天 20:00」
         * 并取消原任务，当天这次提醒就被静默吞掉。查询 WorkManager 只为判断
         * 「还有没有人在等」——有待发（含已过期但尚未执行）就什么都不做。
         *
         * 查询会阻塞，必须在 IO 线程调用；本函数已用 [withContext] 兜住。
         */
        suspend fun scheduleIfMissing(context: Context) {
            if (!ReminderPrefs.enabled) {
                cancel(context)
                return
            }
            val pending = withContext(Dispatchers.IO) {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }
            if (!pending) scheduleNext(context)
        }

        /** 取消所有提醒 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "提醒已取消")
        }
    }
}
