// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ilyskyo.blancall.notification.ReminderSchedule
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 学习提醒频率。
 *
 * 只保留「每天 / 每周 5 天 / 每周 3 天 / 自定义」四个预设：
 * 开关本身负责「关」，不再用一个 OFF 档和总开关表达同一件事
 * （旧版本确实存在「已开启 + 频率关闭」的自相矛盾状态）。
 * 旧盘上的 `OFF` 会在 [ReminderPrefs.init] 迁移成「关闭 + 每天」。
 */
enum class ReminderFrequency(val labelRes: Int) {
    DAILY(com.ilyskyo.blancall.R.string.reminder_freq_daily),
    WEEKLY_FIVE(com.ilyskyo.blancall.R.string.reminder_freq_five),
    WEEKLY_THREE(com.ilyskyo.blancall.R.string.reminder_freq_three),
    CUSTOM(com.ilyskyo.blancall.R.string.custom),
}

/**
 * 学习提醒设置持久化存储
 */
object ReminderPrefs {
    private const val MINUTES_PER_DAY = 24 * 60

    private lateinit var prefs: SharedPreferences
    private lateinit var filesDir: File

    // 用于 recordStudyToday 原子读写的锁（避免连续天数计算竞态）
    private val streakLock = Any()

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private val _hourFlow = MutableStateFlow(20)
    val hourFlow: StateFlow<Int> = _hourFlow.asStateFlow()

    private val _minuteFlow = MutableStateFlow(0)
    val minuteFlow: StateFlow<Int> = _minuteFlow.asStateFlow()

    private val _goalMinutesFlow = MutableStateFlow(10)
    val goalMinutesFlow: StateFlow<Int> = _goalMinutesFlow.asStateFlow()

    // ── 考试日期（epochDay；0 = 未设置）──
    // 不挂在 Article 上：倒排是「一段时间的全局目标」，给每篇文章各填一个日期既难维护，
    // 也会让「按当前速度排不完」失去统一口径。存 epochDay 而非时间戳：跨时区/夏令时
    // 不会把「距考试 30 天」算成 29 或 31 天。
    private val _examEpochDayFlow = MutableStateFlow(0L)
    val examEpochDayFlow: StateFlow<Long> = _examEpochDayFlow.asStateFlow()

    private val _frequencyFlow = MutableStateFlow(ReminderFrequency.DAILY)
    val frequencyFlow: StateFlow<ReminderFrequency> = _frequencyFlow.asStateFlow()

    // ── 提醒日（真正参与调度的那份集合）──
    // 与 frequency 分开存：frequency 只决定界面上选中哪个预设，
    // 自定义星期必须有一份独立数据，否则「改成自定义」时用户刚选的日期会被预设覆盖。
    private val _reminderDaysFlow = MutableStateFlow(ReminderSchedule.ALL_DAYS)
    val reminderDaysFlow: StateFlow<Set<DayOfWeek>> = _reminderDaysFlow.asStateFlow()

    // ── 学习连续天数 ──
    private val _studyStreakDays = MutableStateFlow(0)
    val studyStreakDaysFlow: StateFlow<Int> = _studyStreakDays.asStateFlow()

    // ── 历史最长连续天数 ──
    private val _longestStreakDays = MutableStateFlow(0)
    val longestStreakDaysFlow: StateFlow<Int> = _longestStreakDays.asStateFlow()

    // ── 每日练习次数目标 ──
    private val _dailyPracticeGoalFlow = MutableStateFlow(3)
    val dailyPracticeGoalFlow: StateFlow<Int> = _dailyPracticeGoalFlow.asStateFlow()

    /**
     * 今日计划卡是否显示「今日目标」进度行（默认显示）。
     *
     * 与目标值本身分开存：用户可能想保留目标（通知仍按它算），只是不想让首页多一行。
     */
    private val _showDailyGoalOnCardFlow = MutableStateFlow(true)
    val showDailyGoalOnCardFlow: StateFlow<Boolean> = _showDailyGoalOnCardFlow.asStateFlow()

    // ── 免打扰时段（默认 22:00–08:00，可整体关闭）──
    // 默认**开启**：免打扰的价值在于「默认就对」，要用户先手动打开才生效等于默认不生效。
    // 默认窗口 22–08 不覆盖默认提醒时间 20:00，因此对未改动默认值的用户零影响。
    private val _quietHoursEnabledFlow = MutableStateFlow(true)
    val quietHoursEnabledFlow: StateFlow<Boolean> = _quietHoursEnabledFlow.asStateFlow()

    // 存「当天第几分钟」而不是小时：设置页的时间选择器本来就是分钟级，
    // 只存小时会让用户选 07:30 却按 07:00 判定，边界上差半小时。
    private val _quietStartMinutesFlow = MutableStateFlow(22 * 60)
    val quietStartMinutesFlow: StateFlow<Int> = _quietStartMinutesFlow.asStateFlow()

    private val _quietEndMinutesFlow = MutableStateFlow(8 * 60)
    val quietEndMinutesFlow: StateFlow<Int> = _quietEndMinutesFlow.asStateFlow()

    @SuppressLint("ApplySharedPref")
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("reminder_prefs", Context.MODE_PRIVATE)
        filesDir = context.applicationContext.filesDir

        // 旧版本的 OFF 频率与总开关表达同一件事，且能组合出「已开启 + 频率关闭」的矛盾状态。
        // 迁移时直接落到「关闭」，避免升级后出现一个开着却永不提醒的开关。
        val rawFrequency = prefs.getString("reminder_frequency", "DAILY") ?: "DAILY"
        val legacyOff = rawFrequency == "OFF"
        if (legacyOff) {
            prefs.edit {
                putBoolean("reminder_enabled", false)
                putString("reminder_frequency", ReminderFrequency.DAILY.name)
            }
        }
        val frequency = if (legacyOff) {
            ReminderFrequency.DAILY
        } else {
            runCatching { ReminderFrequency.valueOf(rawFrequency) }
                .getOrDefault(ReminderFrequency.DAILY)
        }

        _enabledFlow.value = if (legacyOff) false else prefs.getBoolean("reminder_enabled", false)
        _hourFlow.value = prefs.getInt("reminder_hour", 20)
        _minuteFlow.value = prefs.getInt("reminder_minute", 0)
        _goalMinutesFlow.value = prefs.getInt("reminder_goal_minutes", 10)
        _examEpochDayFlow.value = readExamGoalFromFile() ?: prefs.getLong("exam_epoch_day", 0L)
        _frequencyFlow.value = frequency
        // 提醒日缺失（旧版本只有三档预设）时按频率现推，升级后第一次调度就能用。
        _reminderDaysFlow.value = ReminderSchedule.parseDays(prefs.getString("reminder_days", null))
            .ifEmpty { ReminderSchedule.daysFor(frequency) }
        _studyStreakDays.value = prefs.getInt("study_streak_days", 0)
        _longestStreakDays.value = prefs.getInt("longest_streak_days", 0)
        _dailyPracticeGoalFlow.value = prefs.getInt("daily_practice_goal", 3)
        _showDailyGoalOnCardFlow.value = prefs.getBoolean("show_daily_goal_on_card", true)
        _quietHoursEnabledFlow.value = prefs.getBoolean("quiet_hours_enabled", true)
        // 分钟级键优先；旧版本只有小时键，按整点迁移
        _quietStartMinutesFlow.value = prefs.getInt(
            "quiet_start_minutes",
            prefs.getInt("quiet_start_hour", 22) * 60,
        ).coerceIn(0, MINUTES_PER_DAY - 1)
        _quietEndMinutesFlow.value = prefs.getInt(
            "quiet_end_minutes",
            prefs.getInt("quiet_end_hour", 8) * 60,
        ).coerceIn(0, MINUTES_PER_DAY - 1)
    }

    var enabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("reminder_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("reminder_enabled", value) }
                _enabledFlow.value = value
            }
        }

    var hour: Int
        get() = if (::prefs.isInitialized) prefs.getInt("reminder_hour", 20) else 20
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("reminder_hour", value) }
                _hourFlow.value = value
            }
        }

    var minute: Int
        get() = if (::prefs.isInitialized) prefs.getInt("reminder_minute", 0) else 0
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("reminder_minute", value) }
                _minuteFlow.value = value
            }
        }

    var goalMinutes: Int
        get() = if (::prefs.isInitialized) prefs.getInt("reminder_goal_minutes", 10) else 10
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("reminder_goal_minutes", value) }
                _goalMinutesFlow.value = value
            }
        }

    /**
     * 考试日期（epochDay，0 = 未设置）。
     *
     * 权威来源是 filesDir/exam_goal.json（在备份清单里），SharedPreferences 只作旧版本迁移源：
     * 偏好文件不在「一键备份」的承诺范围内，只存 pref 会让恢复后倒排目标凭空消失。
     */
    var examEpochDay: Long
        get() = if (::prefs.isInitialized) _examEpochDayFlow.value else 0L
        set(value) {
            if (::prefs.isInitialized) {
                val v = value.coerceAtLeast(0L)
                prefs.edit { putLong("exam_epoch_day", v) }
                _examEpochDayFlow.value = v
                writeExamGoalToFile(v)
            }
        }

    /** 备份恢复后重读考试日期（LearningDataReloader 调用）。 */
    fun reloadExamGoal() {
        if (!::prefs.isInitialized) return
        _examEpochDayFlow.value = readExamGoalFromFile() ?: prefs.getLong("exam_epoch_day", 0L)
    }

    private fun examGoalFile(): File? =
        if (::filesDir.isInitialized) File(filesDir, "exam_goal.json") else null

    private fun readExamGoalFromFile(): Long? = try {
        val f = examGoalFile()
        if (f == null || !f.isFile) null
        else JSONObject(f.readText()).optLong("epochDay", 0L).coerceAtLeast(0L)
    } catch (_: Exception) {
        null
    }

    private fun writeExamGoalToFile(epochDay: Long) {
        try {
            val f = examGoalFile() ?: return
            AtomicFiles.writeTextAtomic(
                f,
                JSONObject().put("version", 1).put("epochDay", epochDay).toString()
            )
        } catch (_: Exception) {
            // 目标日期是可选数据；写失败不阻断设置，内存值仍生效
        }
    }

    var frequency: ReminderFrequency
        get() {
            if (!::prefs.isInitialized) return ReminderFrequency.DAILY
            val name = prefs.getString("reminder_frequency", "DAILY") ?: "DAILY"
            // 旧盘上的 OFF 已由 init 迁移；这里再兜一次，避免异常数据让枚举解析失败
            if (name == "OFF") return ReminderFrequency.DAILY
            return try { ReminderFrequency.valueOf(name) } catch (_: Exception) { ReminderFrequency.DAILY }
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("reminder_frequency", value.name) }
                _frequencyFlow.value = value
                // 选预设时同步提醒日；自定义必须保留用户已经勾好的集合
                if (value != ReminderFrequency.CUSTOM) {
                    reminderDays = ReminderSchedule.daysFor(value)
                }
            }
        }

    /**
     * 真正参与调度的提醒日集合。
     *
     * 空集合一律回落到频率预设：偏好损坏时宁可多提醒一次，也不要让用户从此收不到提醒。
     */
    var reminderDays: Set<DayOfWeek>
        get() {
            if (!::prefs.isInitialized) return ReminderSchedule.daysFor(frequency)
            return ReminderSchedule.parseDays(prefs.getString("reminder_days", null))
                .ifEmpty { ReminderSchedule.daysFor(frequency) }
        }
        set(value) {
            if (::prefs.isInitialized) {
                val normalized = value.ifEmpty { ReminderSchedule.daysFor(frequency) }
                prefs.edit { putString("reminder_days", ReminderSchedule.encodeDays(normalized)) }
                _reminderDaysFlow.value = normalized
            }
        }

    /** 连续学习天数 */
    var studyStreakDays: Int
        get() = if (::prefs.isInitialized) prefs.getInt("study_streak_days", 0) else 0
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("study_streak_days", value) }
                _studyStreakDays.value = value
                // 同步刷新历史最长连续天数
                if (value > longestStreakDays) {
                    prefs.edit { putInt("longest_streak_days", value) }
                    _longestStreakDays.value = value
                }
            }
        }

    /** 历史最长连续学习天数 */
    val longestStreakDays: Int
        get() = if (::prefs.isInitialized) prefs.getInt("longest_streak_days", 0) else 0

    /** 每日练习次数目标（可选 1/3/5/10，默认 3） */
    var dailyPracticeGoal: Int
        get() = if (::prefs.isInitialized) prefs.getInt("daily_practice_goal", 3) else 3
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("daily_practice_goal", value) }
                _dailyPracticeGoalFlow.value = value
            }
        }

    /** 今日计划卡是否显示「今日目标」进度行（默认显示，只影响首页展示，不影响提醒口径） */
    var showDailyGoalOnCard: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("show_daily_goal_on_card", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("show_daily_goal_on_card", value) }
                _showDailyGoalOnCardFlow.value = value
            }
        }

    /** 免打扰时段是否启用（默认开启，窗口默认 22:00–08:00） */
    var quietHoursEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("quiet_hours_enabled", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("quiet_hours_enabled", value) }
                _quietHoursEnabledFlow.value = value
            }
        }

    /** 免打扰起始（当天第几分钟，默认 22:00 = 1320） */
    var quietStartMinutes: Int
        get() = if (::prefs.isInitialized) {
            prefs.getInt("quiet_start_minutes", prefs.getInt("quiet_start_hour", 22) * 60)
                .coerceIn(0, MINUTES_PER_DAY - 1)
        } else {
            22 * 60
        }
        set(value) {
            if (::prefs.isInitialized) {
                val v = value.coerceIn(0, MINUTES_PER_DAY - 1)
                prefs.edit { putInt("quiet_start_minutes", v) }
                _quietStartMinutesFlow.value = v
            }
        }

    /**
     * 免打扰结束（当天第几分钟，默认 08:00 = 480）。
     * 起 = 止 视为不静默，见 [com.ilyskyo.blancall.notification.ReminderPlan.isQuietMinute]。
     */
    var quietEndMinutes: Int
        get() = if (::prefs.isInitialized) {
            prefs.getInt("quiet_end_minutes", prefs.getInt("quiet_end_hour", 8) * 60)
                .coerceIn(0, MINUTES_PER_DAY - 1)
        } else {
            8 * 60
        }
        set(value) {
            if (::prefs.isInitialized) {
                val v = value.coerceIn(0, MINUTES_PER_DAY - 1)
                prefs.edit { putInt("quiet_end_minutes", v) }
                _quietEndMinutesFlow.value = v
            }
        }

    /** 上次学习日期（ISO yyyy-MM-dd），用于判断是否连续 */


    var lastStudyDate: String
        get() = if (::prefs.isInitialized) prefs.getString("last_study_date", "") ?: "" else ""
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("last_study_date", value) }
            }
        }

    /**
     * 记录今天已学习，自动更新连续天数。
     * 使用 synchronized 保证「读取 lastStudyDate → 计算 streak → 写回」的原子性，
     * 避免并发调用导致连续天数计算错误。
     */
    fun recordStudyToday() {
        synchronized(streakLock) {
            val today = java.time.LocalDate.now().toString()  // yyyy-MM-dd
            val last = lastStudyDate
            if (last == today) return  // 今天已记录

            val yesterday = java.time.LocalDate.now().minusDays(1).toString()
            studyStreakDays = if (last == yesterday) studyStreakDays + 1 else 1
            lastStudyDate = today
        }
    }

    /**
     * 检查今天是否已学习
     */
    fun hasStudiedToday(): Boolean {
        val today = java.time.LocalDate.now().toString()
        return lastStudyDate == today
    }
}
