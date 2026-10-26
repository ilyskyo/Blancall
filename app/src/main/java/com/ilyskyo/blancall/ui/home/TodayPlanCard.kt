// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.StudyCoach
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.Macaron
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日计划：把「到期 / 未完成 / 薄弱 / 句子卡 / 目标」排成 3 条行动。
 *
 * 为什么放在首页而不是新开一页：用户打开应用要的是「练这个」，不是先做一次页面选择。
 * 卡片只消费 [StudyCoach] 的结果，不自己判断优先级 —— 判断口径只有调度层一处。
 * 考试倒排只给「正常 / 落后」两态，不做精确倒计时（预测失准会制造焦虑，也会失信）。
 */
@Composable
internal fun TodayPlanCard(
    input: StudyCoach.Input,
    plan: StudyCoach.Plan,
    dueArticleCount: Int,
    pausedArticleCount: Int,
    allScheduledPaused: Boolean,
    onOpenFlow: () -> Unit,
    onStartItem: (StudyCoach.Item) -> Unit,
    onStartBudget: (Int) -> Unit,
    onExamEpochDayChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    var showBudget by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.today_plan_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            val days = plan.examDaysLeft
                            val hasExam = days != null && days >= 0
                            when {
                                hasExam && dueArticleCount > 0 -> append(
                                    stringResource(
                                        R.string.today_plan_subtitle_exam_due,
                                        days ?: 0,
                                        dueArticleCount,
                                        plan.estimatedMinutes
                                    )
                                )
                                hasExam -> append(
                                    stringResource(
                                        R.string.today_plan_subtitle_exam,
                                        days ?: 0,
                                        plan.estimatedMinutes
                                    )
                                )
                                dueArticleCount > 0 -> append(
                                    stringResource(
                                        R.string.today_plan_subtitle_due,
                                        dueArticleCount,
                                        plan.estimatedMinutes
                                    )
                                )
                                else -> append(
                                    stringResource(
                                        R.string.today_plan_minutes,
                                        plan.estimatedMinutes
                                    )
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassButton(onClick = { showBudget = true }, modifier = Modifier.height(34.dp)) {
                    Text(
                        stringResource(R.string.today_plan_budget, input.goalMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (plan.examPace == StudyCoach.ExamPace.BEHIND) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.today_plan_exam_behind),
                    style = MaterialTheme.typography.labelSmall,
                    color = Macaron.warn().accent
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            if (plan.items.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                if (allScheduledPaused && pausedArticleCount > 0) {
                    TextButton(
                        onClick = onOpenFlow,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(R.string.today_plan_empty_paused, pausedArticleCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.today_plan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                plan.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressClick { onStartItem(item) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SurfaceIndex(index + 1)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                itemTitle(item),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                itemDetail(item),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.today_plan_item_minutes, item.estimatedMinutes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppIcon(
                            AppIconKind.ChevronRight,
                            modifier = Modifier.width(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 暂停入口只在确有暂停时露出：平时不增加常驻按钮，空态单独给一句可点的提示
            if (pausedArticleCount > 0 && plan.items.isNotEmpty()) {
                TextButton(
                    onClick = onOpenFlow,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        stringResource(R.string.today_plan_paused_hint, pausedArticleCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val examDay = input.examEpochDay
                TextButton(onClick = {
                    val now = LocalDate.now()
                    val initial = examDay?.takeIf { it > 0 }?.let { LocalDate.ofEpochDay(it) } ?: now.plusMonths(1)
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            onExamEpochDayChange(LocalDate.of(year, month + 1, dayOfMonth).toEpochDay())
                        },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).show()
                }) {
                    Text(
                        if (examDay != null && examDay > 0L) {
                            stringResource(
                                R.string.today_plan_exam_set,
                                LocalDate.ofEpochDay(examDay).toString()
                            )
                        } else {
                            stringResource(R.string.today_plan_set_exam)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (examDay != null && examDay > 0L) {
                    TextButton(onClick = { onExamEpochDayChange(0L) }) {
                        Text(
                            stringResource(R.string.today_plan_clear_exam),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showBudget) {
        TimeBudgetDialog(
            defaultMinutes = input.goalMinutes,
            input = input,
            onDismiss = { showBudget = false },
            onStart = { minutes ->
                showBudget = false
                onStartBudget(minutes)
            }
        )
    }
}

@Composable
private fun SurfaceIndex(index: Int) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            index.toString(),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun itemTitle(item: StudyCoach.Item): String = when (item.kind) {
    StudyCoach.Kind.RESUME -> stringResource(R.string.today_plan_resume, item.title)
    StudyCoach.Kind.REVIEW_ARTICLE -> item.title
    StudyCoach.Kind.WEAK_ARTICLE -> item.title
    StudyCoach.Kind.MISTAKE_DRILL -> stringResource(R.string.today_plan_mistakes)
    StudyCoach.Kind.SENTENCE_CARDS -> stringResource(R.string.today_plan_sentence_cards)
    StudyCoach.Kind.DAILY_GOAL -> stringResource(R.string.today_plan_daily_goal)
}

@Composable
private fun itemDetail(item: StudyCoach.Item): String = when (item.kind) {
    StudyCoach.Kind.RESUME -> stringResource(
        R.string.today_plan_resume_detail, item.answered, item.total
    )
    StudyCoach.Kind.REVIEW_ARTICLE -> stringResource(R.string.today_plan_review_detail)
    StudyCoach.Kind.WEAK_ARTICLE ->
        if (item.count > 0) stringResource(R.string.today_plan_weak_detail, item.count)
        else stringResource(R.string.weak_articles_title)
    StudyCoach.Kind.MISTAKE_DRILL -> stringResource(R.string.today_plan_mistake_detail, item.count)
    StudyCoach.Kind.SENTENCE_CARDS -> stringResource(R.string.today_plan_sentence_detail, item.count)
    StudyCoach.Kind.DAILY_GOAL -> stringResource(R.string.today_plan_goal_detail, item.count)
}

/**
 * 时长选择：默认值来自设置里的「今日目标时长」（此前只存不用），
 * 选项按该值就近生成，避免出现用户没设过的奇怪数字。
 */
@Composable
private fun TimeBudgetDialog(
    defaultMinutes: Int,
    input: StudyCoach.Input,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit,
) {
    val options = remember(defaultMinutes) {
        val base = defaultMinutes.coerceIn(1, 180)
        (listOf(5, 10, 15, 20, 30) + base).distinct().sorted()
    }
    var selected by remember(defaultMinutes) { mutableIntStateOf(defaultMinutes.coerceIn(1, 180)) }
    val budget = remember(selected, input) { StudyCoach.budget(input, selected) }
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_plan_budget_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.today_plan_budget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { minutes ->
                        FilterChip(
                            selected = selected == minutes,
                            onClick = { selected = minutes },
                            label = { Text(stringResource(R.string.today_plan_minutes_unit, minutes)) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        budget.empty -> stringResource(R.string.today_plan_budget_empty)
                        budget.articleIds.isNotEmpty() -> stringResource(
                            R.string.today_plan_budget_result_articles,
                            budget.articleIds.size,
                            budget.estimatedMinutes
                        )
                        else -> stringResource(
                            R.string.today_plan_budget_result_cards,
                            budget.sentenceCardCount,
                            budget.estimatedMinutes
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(selected) }, enabled = !budget.empty) {
                Text(stringResource(R.string.today_plan_budget_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
