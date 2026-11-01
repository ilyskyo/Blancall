// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.StudyCoach
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.Macaron
import java.time.Instant
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
    plan: StudyCoach.Plan,
    dueArticleCount: Int,
    pausedArticleCount: Int,
    allScheduledPaused: Boolean,
    onOpenFlow: () -> Unit,
    onStartItem: (StudyCoach.Item) -> Unit,
    /** 点卡片主体（标题区/空白处）→ 英雄转场打开「今日计划」全部清单；卡内条目仍是直接开练 */
    onOpenAll: () -> Unit,
    /** 今天已完成的练习次数（按本地日）；与刷次数的目标一起决定是否显示进度行 */
    todayPracticeCount: Int,
    dailyPracticeGoal: Int,
    /** 面板里的「在今日计划卡上显示目标进度」，关掉后这一行不渲染（目标本身仍参与提醒口径） */
    showDailyGoal: Boolean,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            // 一镜到底的源节点：key 与「今日计划」展开页头部一致
            .heroElement(TODAY_PLAN_HERO_KEY, renderInOverlay = false),
        onClick = onOpenAll,
    ) {
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
            }
            if (plan.examPace == StudyCoach.ExamPace.BEHIND) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.today_plan_exam_behind),
                    style = MaterialTheme.typography.labelSmall,
                    color = Macaron.warn().accent
                )
            }
            // 今日目标进度：只有非零目标才显示，达成后换成强调色（不加新图标，保持一行）
            if (showDailyGoal && dailyPracticeGoal > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.today_plan_goal_progress,
                        dailyPracticeGoal,
                        todayPracticeCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (todayPracticeCount >= dailyPracticeGoal) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
        }
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
    // 加入计划但一次没练：措辞换成「开始学习」，别对没读过的文章说「复习」
    StudyCoach.Kind.START_ARTICLE -> stringResource(R.string.today_plan_start_article, item.title)
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
    StudyCoach.Kind.START_ARTICLE -> stringResource(R.string.today_plan_start_detail)
    StudyCoach.Kind.WEAK_ARTICLE ->
        if (item.count > 0) stringResource(R.string.today_plan_weak_detail, item.count)
        else stringResource(R.string.weak_articles_title)
    StudyCoach.Kind.MISTAKE_DRILL -> stringResource(R.string.today_plan_mistake_detail, item.count)
    StudyCoach.Kind.SENTENCE_CARDS -> stringResource(R.string.today_plan_sentence_detail, item.count)
    StudyCoach.Kind.DAILY_GOAL -> stringResource(R.string.today_plan_goal_detail, item.count)
}
