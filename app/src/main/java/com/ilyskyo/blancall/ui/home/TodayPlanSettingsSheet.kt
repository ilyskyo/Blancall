// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.SingleDateCalendar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.common.AdaptiveChipRow
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import java.time.LocalDate

/**
 * 「今日计划设置」面板：考试日期 + 每日学习时长。
 *
 * 入口在「今日计划」页头部（设置）：首页卡片只回答「今天做什么」，不再摊开
 * 配置项，也不在卡片上留设置链接 —— 点进卡片就能设置。
 *
 * 只放真正属于「今日计划」的配置：
 * - 考试日期：决定副标题的倒排提示与节奏判断，不动 FSRS 的复习安排；
 * - 每日学习时长：直接决定今日计划的总时长（`StudyCoach` 的预算来源）。
 * 「每日练习次数」属于提醒口径（通知里的「今天还差几次」），仍留在设置页，避免两处重复。
 *
 * @param estimatedMinutes 今天全部任务预计耗时，与首页卡片副标题同一份 [StudyCoach.Plan]
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TodayPlanSettingsSheet(
    visible: Boolean,
    estimatedMinutes: Int,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 退场与 ReadingSettingsSheet 同一套判据：拖到底、点遮罩、程序化关闭最终都落到 Hidden，
    // 一处判据就能收干净，不会留下挡住整页的孤儿面板。
    var alive by remember { mutableStateOf(visible) }
    LaunchedEffect(visible, sheetState.currentValue) {
        when {
            visible -> alive = true
            !alive -> Unit
            sheetState.currentValue == SheetValue.Hidden || !Motion.exitsAreAnimated -> alive = false
            else -> {
                runCatching { sheetState.hide() }
                if (!visible && sheetState.currentValue != SheetValue.Hidden) alive = false
            }
        }
    }
    if (!alive) return

    val examEpochDay by ReminderPrefs.examEpochDayFlow.collectAsState()
    val goalMinutes by ReminderPrefs.goalMinutesFlow.collectAsState()
    val dailyGoal by ReminderPrefs.dailyPracticeGoalFlow.collectAsState()
    val showGoalOnCard by ReminderPrefs.showDailyGoalOnCardFlow.collectAsState()
    var showCustomMinutes by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("") }
    var showCustomGoal by remember { mutableStateOf(false) }
    var customGoalInput by remember { mutableStateOf("") }

    // 考试日期：用自己的月历（内嵌在面板里），不再弹系统 DatePickerDialog
    val examToday = remember { LocalDate.now() }
    var showExamPicker by remember { mutableStateOf(false) }
    var pendingExamDate by remember { mutableStateOf(examToday.plusMonths(1)) }

    GlassModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                stringResource(R.string.plan_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.plan_settings_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))

            // ── 考试日期：整行可点（展开下方自有月历），已设置时行尾给「清除」 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick {
                        pendingExamDate = if (examEpochDay > 0L) {
                            LocalDate.ofEpochDay(examEpochDay)
                        } else {
                            examToday.plusMonths(1)
                        }
                        showExamPicker = !showExamPicker
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (examEpochDay > 0L) {
                        stringResource(
                            R.string.today_plan_exam_set,
                            LocalDate.ofEpochDay(examEpochDay).toString()
                        )
                    } else {
                        stringResource(R.string.today_plan_set_exam)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (examEpochDay > 0L) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                if (examEpochDay > 0L) {
                    TextButton(onClick = { ReminderPrefs.examEpochDay = 0L }) {
                        Text(
                            stringResource(R.string.today_plan_clear_exam),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                AppIcon(
                    AppIconKind.ChevronRight,
                    modifier = Modifier.width(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.plan_settings_exam_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(
                visible = showExamPicker,
                enter = expandVertically(Motion.contentSize()) +
                    fadeIn(MotionFade.alpha(MotionFade.enter)),
                exit = shrinkVertically(Motion.contentSize()) +
                    fadeOut(MotionFade.alpha(MotionFade.exit))
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SingleDateCalendar(
                        selected = pendingExamDate,
                        today = examToday,
                        futureEnabled = true,
                        onSelect = { pendingExamDate = it },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showExamPicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassButton(onClick = {
                            ReminderPrefs.examEpochDay = pendingExamDate.toEpochDay()
                            showExamPicker = false
                        }) {
                            Text(
                                stringResource(R.string.ok),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(16.dp))

            // ── 每日学习时长：三个常用档 + 自定义，与设置页同一份数据源 ──
            Text(
                stringResource(R.string.plan_settings_duration_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            // 预估耗时与首页卡片副标题同源：同一份 StudyCoach.Plan，不给用户两个数
            Text(
                stringResource(R.string.plan_settings_estimate, estimatedMinutes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.plan_settings_duration_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            // 当前值若恰好是放不下的那个预设，也必须显示，否则整行会像“没选中”
            val durationPresets = listOf(5, 10, 20)
            AdaptiveChipRow(
                modifier = Modifier.fillMaxWidth(),
                spacing = 8.dp,
                alwaysTrailing = 1,
                keepIndices = if (goalMinutes in durationPresets) {
                    setOf(durationPresets.indexOf(goalMinutes))
                } else {
                    emptySet()
                },
            ) {
                durationPresets.forEach { mins ->
                    FilterChip(
                        selected = goalMinutes == mins,
                        onClick = { ReminderPrefs.goalMinutes = mins },
                        label = {
                            Text(
                                stringResource(R.string.minutes_unit, mins),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                val presets = listOf(5, 10, 20)
                FilterChip(
                    selected = goalMinutes !in presets,
                    onClick = {
                        customMinutesInput = if (goalMinutes in presets) "" else goalMinutes.toString()
                        showCustomMinutes = true
                    },
                    label = {
                        Text(
                            if (goalMinutes in presets) {
                                stringResource(R.string.custom)
                            } else {
                                stringResource(R.string.minutes_unit, goalMinutes)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(16.dp))

            // ── 每日练习次数：通知里的「今天还差几次」按它算，与学习时长是两个目标 ──
            Text(
                stringResource(R.string.custom_goal_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.reminder_daily_goal_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val goalPresets = listOf(1, 3, 5, 10)
            AdaptiveChipRow(
                modifier = Modifier.fillMaxWidth(),
                spacing = 8.dp,
                alwaysTrailing = 1,
                keepIndices = if (dailyGoal in goalPresets) {
                    setOf(goalPresets.indexOf(dailyGoal))
                } else {
                    emptySet()
                },
            ) {
                goalPresets.forEach { g ->
                    FilterChip(
                        selected = dailyGoal == g,
                        onClick = { ReminderPrefs.dailyPracticeGoal = g },
                        label = {
                            Text(
                                stringResource(R.string.goal_count_chip, g),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                val goalPresets = listOf(1, 3, 5, 10)
                FilterChip(
                    selected = dailyGoal !in goalPresets,
                    onClick = {
                        customGoalInput = if (dailyGoal in goalPresets) "" else dailyGoal.toString()
                        showCustomGoal = true
                    },
                    label = {
                        Text(
                            if (dailyGoal in goalPresets) {
                                stringResource(R.string.custom)
                            } else {
                                stringResource(R.string.goal_count_chip, dailyGoal)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.plan_settings_show_on_card),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.plan_settings_show_on_card_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassSwitch(
                    checked = showGoalOnCard,
                    onCheckedChange = { ReminderPrefs.showDailyGoalOnCard = it }
                )
            }
        }
    }

    AnimatedOverlayPresence(visible = showCustomMinutes) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomMinutes = false },
            title = { Text(stringResource(R.string.custom_daily_goal_title)) },
            text = {
                OutlinedTextField(
                    value = customMinutesInput,
                    onValueChange = { input ->
                        customMinutesInput = input.filter { it.isDigit() }.take(4)
                    },
                    label = { Text(stringResource(R.string.minutes_field_label)) },
                    suffix = { Text(stringResource(R.string.minutes_word)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customMinutesInput.toIntOrNull() != null,
                    onClick = {
                        customMinutesInput.toIntOrNull()?.let {
                            ReminderPrefs.goalMinutes = it.coerceIn(1, 1440)
                        }
                        showCustomMinutes = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMinutes = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    AnimatedOverlayPresence(visible = showCustomGoal) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomGoal = false },
            title = { Text(stringResource(R.string.custom_goal_title)) },
            text = {
                OutlinedTextField(
                    value = customGoalInput,
                    onValueChange = { input ->
                        customGoalInput = input.filter { it.isDigit() }.take(2)
                    },
                    label = { Text(stringResource(R.string.custom_goal_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customGoalInput.toIntOrNull() != null,
                    onClick = {
                        customGoalInput.toIntOrNull()?.let {
                            ReminderPrefs.dailyPracticeGoal = it.coerceIn(1, 99)
                        }
                        showCustomGoal = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGoal = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
