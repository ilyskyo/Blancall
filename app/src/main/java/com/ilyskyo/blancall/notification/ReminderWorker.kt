// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ilyskyo.blancall.data.model.PracticeStatus
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.theme.ReminderFrequency
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 每日学习提醒 Worker（CoroutineWorker）：
 * 1. 检查提醒设置是否开启
 * 2. 分析用户学习数据（今日是否学习、薄弱内容、未完成练习、连续天数）
 * 3. 发送智能通知
 * 4. 调度下一次提醒（按频率跳到下一个提醒日）
 */
class ReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 双保险：即使 BlancallApp 未在该进程初始化，也确保 Prefs 可用（init 可重入）
            runCatching { ReminderPrefs.init(applicationContext) }

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

            // 分析用户状态并发送通知
            val reason = analyzeUserState()

            // 通知总开关被关闭时跳过通知，但仍调度下一次（用户重新开启后可恢复）
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                NotificationHelper.showReminder(context, reason)
            } else {
                Log.d(TAG, "通知已被用户关闭，跳过本次通知")
            }

            // 调度下一次提醒
            scheduleNext(context)

            Log.d(TAG, "提醒已发送：${reason.javaClass.simpleName}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "发送提醒失败", e)
            Result.retry()
        }
    }

    /**
     * 分析用户学习数据，确定通知内容优先级：
     * 优先级：未完成练习 > 薄弱内容 > 连续学习 > 今天没学
     */
    private suspend fun analyzeUserState(): ReminderReason {
        // 1. 检查是否有未完成练习
        val incompletePractice = findIncompletePractice()
        if (incompletePractice != null) return incompletePractice

        // 2. 今天是否已学习
        val hasStudied = ReminderPrefs.hasStudiedToday()
        val streakDays = ReminderPrefs.studyStreakDays

        if (hasStudied && streakDays > 0) {
            // 3. 检查薄弱内容数量
            val weakCount = getWeakContentCount()
            if (weakCount > 0) {
                return ReminderReason.WeakContent(weakCount)
            }
            // 持续学习中
            return ReminderReason.StreakContinue(streakDays)
        }

        // 4. 今天没学
        return ReminderReason.NoStudyToday(streakDays)
    }

    /**
     * 查找第一个未完成的练习状态文件
     */
    private fun findIncompletePractice(): ReminderReason.IncompletePractice? {
        try {
            val filesDir = context.filesDir
            val stateFiles = filesDir.listFiles { file ->
                file.name.startsWith("practice_state_") && file.name.endsWith(".json")
            } ?: return null

            for (file in stateFiles) {
                try {
                    val json = JSONObject(file.readText())
                    val status = json.optString("status", "")
                    if (status != PracticeStatus.IN_PROGRESS.name) continue

                    val articleId = json.optLong("articleId", -1L)
                    if (articleId <= 0) continue

                    val totalBlanks = json.optInt("totalBlanks", 0)
                    val answeredCount = json.optInt("answeredCount", 0)
                    val remaining = totalBlanks - answeredCount
                    if (remaining > 0) {
                        return ReminderReason.IncompletePractice(articleId, remaining)
                    }
                } catch (_: Exception) { /* 跳过损坏文件 */ }
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * 统计薄弱内容（错误过的题目数量）。
     * RecordRepository 单例异步加载，首次可能为空，这里在 Worker 线程轮询等待最多 2 秒。
     */
    private suspend fun getWeakContentCount(): Int {
        return try {
            val recordRepo = RecordRepository.getInstance(
                context.filesDir.resolve("records.json").absolutePath
            )

            // 等待异步加载完成：records 非空即视为已加载（最多等待 2 秒，避免阻塞过久）
            val deadline = System.currentTimeMillis() + 2000L
            while (recordRepo.records.value.isEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                delay(100L)
            }

            val allRecords = recordRepo.records.value
            if (allRecords.isEmpty()) return 0

            // 统计所有出现过的错误（按 blankIndex+articleId 去重）
            val mistakeSet = mutableSetOf<String>()
            for (record in allRecords) {
                for (m in record.mistakes) {
                    mistakeSet.add("${record.articleId}_${m.blankIndex}")
                }
            }
            mistakeSet.size
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 检查指定日期是否在提醒日范围内（默认今天）。
     */
    private fun isReminderDay(date: LocalDate = LocalDate.now()): Boolean {
        val freq = ReminderPrefs.frequency
        return when (freq) {
            ReminderFrequency.DAILY -> true
            ReminderFrequency.WEEKLY_FIVE -> {
                val day = date.dayOfWeek
                day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
            }
            ReminderFrequency.WEEKLY_THREE -> {
                val day = date.dayOfWeek
                day == DayOfWeek.MONDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.FRIDAY
            }
            ReminderFrequency.OFF -> false
        }
    }

    companion object {
        private const val TAG = "ReminderWorker"
        private const val WORK_NAME = "daily_study_reminder"

        /**
         * 调度下一次提醒。计算从当前时间到下一个提醒日目标时间的延迟。
         * - hour/minute 做 coerceIn 校验，避免 SharedPreferences 损坏导致异常
         * - 非提醒日会向前跳到下一个提醒日（不再简单 plusDays(1) 空跑）
         */
        fun scheduleNext(context: Context) {
            if (!ReminderPrefs.enabled) {
                cancel(context)
                return
            }

            // 校验时间字段，防止 prefs 损坏抛出异常
            val hour = ReminderPrefs.hour.coerceIn(0, 23)
            val minute = ReminderPrefs.minute.coerceIn(0, 59)
            val targetTime = LocalTime.of(hour, minute)

            val now = LocalDateTime.now()
            var nextReminder = LocalDateTime.of(now.toLocalDate(), targetTime)

            // 如果今天的提醒时间已过，从明天开始寻找
            if (nextReminder <= now) {
                nextReminder = nextReminder.plusDays(1)
            }

            // 向前找到下一个提醒日（最多遍历 7 天，保证终止）
            var guard = 0
            while (!isReminderDayStatic(nextReminder.toLocalDate()) && guard < 7) {
                nextReminder = nextReminder.plusDays(1)
                guard++
            }

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
         * 取消所有提醒
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "提醒已取消")
        }

        // companion 中不能直接访问实例方法，这里复用一份静态判定
        private fun isReminderDayStatic(date: LocalDate): Boolean {
            val freq = ReminderPrefs.frequency
            return when (freq) {
                ReminderFrequency.DAILY -> true
                ReminderFrequency.WEEKLY_FIVE -> {
                    val day = date.dayOfWeek
                    day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                }
                ReminderFrequency.WEEKLY_THREE -> {
                    val day = date.dayOfWeek
                    day == DayOfWeek.MONDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.FRIDAY
                }
                ReminderFrequency.OFF -> false
            }
        }
    }
}
