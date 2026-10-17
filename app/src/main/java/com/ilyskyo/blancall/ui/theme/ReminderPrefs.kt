// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 学习提醒频率
 */
enum class ReminderFrequency(val label: String, val daysPerWeek: Int) {
    DAILY("每天", 7),
    WEEKLY_FIVE("每周5天", 5),
    WEEKLY_THREE("每周3天", 3),
    OFF("关闭", 0)
}

/**
 * 学习提醒设置持久化存储
 */
object ReminderPrefs {
    private lateinit var prefs: SharedPreferences

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

    private val _frequencyFlow = MutableStateFlow(ReminderFrequency.DAILY)
    val frequencyFlow: StateFlow<ReminderFrequency> = _frequencyFlow.asStateFlow()

    // ── 学习连续天数 ──
    private val _studyStreakDays = MutableStateFlow(0)
    val studyStreakDaysFlow: StateFlow<Int> = _studyStreakDays.asStateFlow()

    // ── 历史最长连续天数 ──
    private val _longestStreakDays = MutableStateFlow(0)
    val longestStreakDaysFlow: StateFlow<Int> = _longestStreakDays.asStateFlow()

    // ── 每日练习次数目标 ──
    private val _dailyPracticeGoalFlow = MutableStateFlow(3)
    val dailyPracticeGoalFlow: StateFlow<Int> = _dailyPracticeGoalFlow.asStateFlow()

    // ── 免打扰时段（默认 22:00–08:00，可整体关闭）──
    // 默认**开启**：免打扰的价值在于「默认就对」，要用户先手动打开才生效等于默认不生效。
    // 默认窗口 22–08 不覆盖默认提醒时间 20:00，因此对未改动默认值的用户零影响。
    private val _quietHoursEnabledFlow = MutableStateFlow(true)
    val quietHoursEnabledFlow: StateFlow<Boolean> = _quietHoursEnabledFlow.asStateFlow()

    private val _quietStartHourFlow = MutableStateFlow(22)
    val quietStartHourFlow: StateFlow<Int> = _quietStartHourFlow.asStateFlow()

    private val _quietEndHourFlow = MutableStateFlow(8)
    val quietEndHourFlow: StateFlow<Int> = _quietEndHourFlow.asStateFlow()

    // ── 「尽量准时」开关（默认关，见 punctualEnabled 的说明）──
    private val _punctualEnabledFlow = MutableStateFlow(false)
    val punctualEnabledFlow: StateFlow<Boolean> = _punctualEnabledFlow.asStateFlow()

    @SuppressLint("ApplySharedPref")
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("reminder_prefs", Context.MODE_PRIVATE)
        _enabledFlow.value = prefs.getBoolean("reminder_enabled", false)
        _hourFlow.value = prefs.getInt("reminder_hour", 20)
        _minuteFlow.value = prefs.getInt("reminder_minute", 0)
        _goalMinutesFlow.value = prefs.getInt("reminder_goal_minutes", 10)
        val freqName = prefs.getString("reminder_frequency", "DAILY") ?: "DAILY"
        _frequencyFlow.value = try { ReminderFrequency.valueOf(freqName) } catch (_: Exception) { ReminderFrequency.DAILY }
        _studyStreakDays.value = prefs.getInt("study_streak_days", 0)
        _longestStreakDays.value = prefs.getInt("longest_streak_days", 0)
        _dailyPracticeGoalFlow.value = prefs.getInt("daily_practice_goal", 3)
        _quietHoursEnabledFlow.value = prefs.getBoolean("quiet_hours_enabled", true)
        _quietStartHourFlow.value = prefs.getInt("quiet_start_hour", 22)
        _quietEndHourFlow.value = prefs.getInt("quiet_end_hour", 8)
        _punctualEnabledFlow.value = prefs.getBoolean("punctual_enabled", false)
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

    var frequency: ReminderFrequency
        get() {
            if (!::prefs.isInitialized) return ReminderFrequency.DAILY
            val name = prefs.getString("reminder_frequency", "DAILY") ?: "DAILY"
            return try { ReminderFrequency.valueOf(name) } catch (_: Exception) { ReminderFrequency.DAILY }
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("reminder_frequency", value.name) }
                _frequencyFlow.value = value
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

    /** 免打扰时段是否启用（默认开启，窗口默认 22:00–08:00） */
    var quietHoursEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("quiet_hours_enabled", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("quiet_hours_enabled", value) }
                _quietHoursEnabledFlow.value = value
            }
        }

    /** 免打扰起始小时（0–23，默认 22） */
    var quietStartHour: Int
        get() = if (::prefs.isInitialized) prefs.getInt("quiet_start_hour", 22) else 22
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("quiet_start_hour", value) }
                _quietStartHourFlow.value = value
            }
        }

    /** 免打扰结束小时（0–23，默认 8；起=止 视为不静默，见 ReminderPlan.isQuietHour） */
    var quietEndHour: Int
        get() = if (::prefs.isInitialized) prefs.getInt("quiet_end_hour", 8) else 8
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("quiet_end_hour", value) }
                _quietEndHourFlow.value = value
            }
        }

    /**
     * 「尽量准时」开关（默认关）。
     *
     * **当前刻意只保留开关位，不实现精确路径。** 理由：
     * 学习提醒不属于「闹钟 / 日历 / 通话」这类 Google Play 认可的精确闹钟正当用途，
     * 为此声明 `SCHEDULE_EXACT_ALARM` 属高风险类别、上架审核会被打回；
     * 而不声明权限时 `AlarmManager.setExactAndAllowWhileIdle` 在 API 31+ 会抛
     * `SecurityException`，还得额外接 `canScheduleExactAlarms()` 降级、注册
     * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 接收器并改 Manifest ——
     * 收益（提醒准几分钟）远小于改动面与合规风险。因此维持纯 WorkManager 自调度。
     * 留这个开关位是为了：将来若定位变化（如用户可选「用作闹钟」），UI 与存储已就位。
     */
    var punctualEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("punctual_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("punctual_enabled", value) }
                _punctualEnabledFlow.value = value
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
