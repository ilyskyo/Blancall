// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.ArticleStats
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.StatsRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.MemoryDecayChart
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailAdviceKind {
    PAUSED, OVERDUE, LOW_RETENTION, LOW_ACCURACY, SENTENCE_DUE, STEADY
}

private fun adviceKind(task: FlowTask, totalPractices: Int, latestRate: Float): DetailAdviceKind = when {
    task.phase == StudyFlow.Phase.PAUSED -> DetailAdviceKind.PAUSED
    task.phase == StudyFlow.Phase.OVERDUE -> DetailAdviceKind.OVERDUE
    task.retention < 0.7f -> DetailAdviceKind.LOW_RETENTION
    totalPractices > 0 && latestRate >= 0f && latestRate < 0.7f -> DetailAdviceKind.LOW_ACCURACY
    task.sentenceDue > 0 -> DetailAdviceKind.SENTENCE_DUE
    else -> DetailAdviceKind.STEADY
}

/**
 * 任务详情：从任务卡以共享元素「一镜到底」展开。
 *
 * 信息顺序按「用户此刻要回答的问题」排：下一步做什么 → 这篇整体如何 → 记忆会怎么衰减 →
 * 最近练得怎样 → 调度怎么改 → 之前练过什么。所有数字都来自同一份 FSRS 状态与练习记录，
 * 不另算第二套口径。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyTaskDetailScreen(navController: NavController, articleId: Long) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    val allRecords by recordRepo.records.collectAsState()
    var states by remember { mutableStateOf(fsrsStore.allStates()) }
    var pauses by remember { mutableStateOf(fsrsStore.pausedArticles()) }
    var sentenceStates by remember { mutableStateOf(fsrsStore.allSentenceStates()) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        states = fsrsStore.allStates()
        pauses = fsrsStore.pausedArticles()
        sentenceStates = fsrsStore.allSentenceStates()
    }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    val article = articles.firstOrNull { it.id == articleId }
    val records = remember(allRecords, articleId) {
        allRecords.filter { it.articleId == articleId }.sortedByDescending { it.timestamp }
    }
    val stats = remember(records) { StatsRepository.aggregateArticle(records) }
    val task = remember(article, states, pauses, sentenceStates, nowTick) {
        article?.let {
            buildTasks(listOf(it), states, pauses, sentenceStates, nowTick).firstOrNull()
        }
    }
    val startedAt = remember(records, article) {
        records.minOfOrNull { it.timestamp } ?: article?.createdAt ?: 0L
    }
    val readingSeconds = remember(articleId) { AppPrefs.getReadingSeconds(articleId) }
    val curve = remember(task, nowTick) {
        val state = task?.state ?: return@remember emptyList<Float>()
        List(31) { dayOffset ->
            FsrsEngine.retentionRate(state, nowTick + dayOffset * StudyFlow.MILLIS_PER_DAY).toFloat()
        }
    }
    val intervalDays = remember(task) {
        val state = task?.state ?: return@remember 0
        ((state.due - state.lastReview).coerceAtLeast(0L) / StudyFlow.MILLIS_PER_DAY).toInt()
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var actionSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(actionSeq) {
        if (actionSeq > 0) {
            delay(2200L)
            actionNote = null
        }
    }
    val haptic = rememberHaptic(HapticTier.Confirm)
    val toggleHaptic = rememberHaptic(HapticTier.Toggle)
    val scope = rememberCoroutineScope()

    fun note(message: String) {
        actionNote = message
        actionSeq++
    }

    fun togglePause() {
        val t = task ?: return
        toggleHaptic()
        val resume = t.phase == StudyFlow.Phase.PAUSED
        scope.launch {
            withContext(Dispatchers.IO) {
                if (resume) fsrsStore.resumeArticle(t.articleId, nowTick)
                else fsrsStore.pauseArticle(t.articleId, nowTick)
            }
            note(resources.getString(if (resume) R.string.flow_done_resumed else R.string.flow_done_paused))
        }
    }

    fun reschedule(target: Long) {
        val t = task ?: return
        haptic()
        scope.launch {
            withContext(Dispatchers.IO) { fsrsStore.rescheduleArticle(t.articleId, target, nowTick) }
            note(resources.getString(R.string.flow_done_rescheduled))
        }
    }

    val navBarConn = rememberAutoHideNavBarOnScroll()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.flow_detail_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (article == null || task == null) {
                DetailMissing(onBack = { navController.popBackStack() })
            } else {
                val advice = remember(task, stats.totalPractices, stats.latestRate) {
                    adviceKind(task, stats.totalPractices, stats.latestRate)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(navBarConn)
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
                ) {
                    item(key = "detail_hero") {
                        DetailHeroCard(task = task, startedAt = startedAt)
                    }
                    item(key = "detail_advice") {
                        DetailSection(index = 0) {
                            DetailAdviceCard(
                                task = task,
                                kind = advice,
                                note = actionNote,
                                onStart = { navController.navigate("practice/${task.articleId}") },
                                onWeakness = {
                                    navController.navigate(
                                        "practice/${task.articleId}?mode=SENTENCE&sectionMode=WEAKNESS"
                                    )
                                },
                                onSentenceCards = { navController.navigate("sentence_cards") },
                                onResume = { togglePause() },
                                onAdjust = { sheetOpen = true },
                            )
                        }
                    }
                    item(key = "detail_overall") {
                        DetailSection(index = 1) {
                            DetailOverallCard(
                                task = task,
                                stats = stats,
                                intervalDays = intervalDays,
                                readingSeconds = readingSeconds,
                            )
                        }
                    }
                    item(key = "detail_curve") {
                        DetailSection(index = 2) {
                            DetailCurveCard(task = task, curve = curve)
                        }
                    }
                    if (stats.totalPractices > 0) {
                        item(key = "detail_trend") {
                            DetailSection(index = 3) { DetailTrendCard(stats = stats) }
                        }
                    }
                    if (stats.totalMistakes > 0) {
                        item(key = "detail_mistakes") {
                            DetailSection(index = 4) { DetailMistakesCard(stats = stats) }
                        }
                    }
                    item(key = "detail_schedule") {
                        DetailSection(index = 5) {
                            DetailScheduleCard(
                                task = task,
                                intervalDays = intervalDays,
                                onStart = { navController.navigate("practice/${task.articleId}") },
                                onAdjust = { sheetOpen = true },
                                onTogglePause = { togglePause() },
                            )
                        }
                    }
                    if (records.isNotEmpty()) {
                        item(key = "detail_records") {
                            DetailSection(index = 6) {
                                DetailRecordsCard(
                                    records = records,
                                    onViewAll = { navController.navigate("statistics/${task.articleId}") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        task?.let {
            FlowAdjustSheet(
                task = it,
                now = nowTick,
                onDismiss = { sheetOpen = false },
                onReschedule = { target -> reschedule(target) },
                onTogglePause = { togglePause() },
            )
        }
    }
}

@Composable
private fun DetailMissing(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppIcon(
            AppIconKind.Insights,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.flow_detail_missing_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.flow_detail_missing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        GlassButton(onClick = onBack) {
            Text(
                stringResource(R.string.back),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 详情卡的分段入场：共享元素头图不参与，只让下面的信息层一格一格浮现 */
@Composable
private fun DetailSection(index: Int, content: @Composable () -> Unit) {
    var played by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = MotionFade.number(MotionFade.itemEnter, minOf(index * 30, 180)),
        label = "detailSection$index"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 14.dp.toPx()
            }
    ) {
        content()
    }
}

@Composable
private fun DetailHeroCard(task: FlowTask, startedAt: Long) {
    val accent = phaseAccent(task.phase)
    val paused = task.phase == StudyFlow.Phase.PAUSED
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heroElement("flow_task_${task.articleId}"),
        containerColor = flowCardColor(task.phase),
        backdrop = false,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                SurfacePill(
                    text = stringResource(if (paused) R.string.plan_paused else R.string.flow_summary_active),
                    color = accent
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(task.retention * 100).toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.flow_detail_retention_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        phaseHeadline(task),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = accent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        dueDateLabel(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FlowDetailStat(stringResource(R.string.flow_detail_started_label), formatFullDate(startedAt))
                FlowDetailStat(stringResource(R.string.flow_detail_reviews_label), "${task.state.reviewCount}")
                FlowDetailStat(stringResource(R.string.flow_detail_sentences_label), "${task.sentenceLearned}")
            }
        }
    }
}

@Composable
private fun DetailAdviceCard(
    task: FlowTask,
    kind: DetailAdviceKind,
    note: String?,
    onStart: () -> Unit,
    onWeakness: () -> Unit,
    onSentenceCards: () -> Unit,
    onResume: () -> Unit,
    onAdjust: () -> Unit,
) {
    val hue = when (kind) {
        DetailAdviceKind.PAUSED -> Macaron.neutral()
        DetailAdviceKind.OVERDUE -> Macaron.review()
        DetailAdviceKind.LOW_RETENTION, DetailAdviceKind.LOW_ACCURACY -> Macaron.warn()
        DetailAdviceKind.SENTENCE_DUE -> Macaron.info()
        DetailAdviceKind.STEADY -> Macaron.continueP()
    }
    GlassCard(containerColor = hue.fill, backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SurfacePill(
                    text = stringResource(R.string.flow_detail_advice_label),
                    color = hue.accent
                )
                Spacer(Modifier.weight(1f))
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = hue.accent
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                detailAdviceTitle(kind),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detailAdviceBody(kind, task.sentenceDue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val actionLabel = when (kind) {
                DetailAdviceKind.PAUSED -> stringResource(R.string.flow_resume)
                DetailAdviceKind.OVERDUE -> stringResource(R.string.flow_start_review)
                DetailAdviceKind.LOW_RETENTION, DetailAdviceKind.LOW_ACCURACY ->
                    stringResource(R.string.flow_detail_weakness_action)
                DetailAdviceKind.SENTENCE_DUE -> stringResource(R.string.flow_detail_sentence_action)
                // 「节奏正稳」的正文说保持间隔，按钮就不该引导提前重练，给「调整时间」更一致
                DetailAdviceKind.STEADY -> stringResource(R.string.flow_adjust_schedule)
            }
            val action = when (kind) {
                DetailAdviceKind.PAUSED -> onResume
                DetailAdviceKind.OVERDUE -> onStart
                DetailAdviceKind.LOW_RETENTION, DetailAdviceKind.LOW_ACCURACY -> onWeakness
                DetailAdviceKind.SENTENCE_DUE -> onSentenceCards
                DetailAdviceKind.STEADY -> onAdjust
            }
            GlassButton(onClick = action) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun detailAdviceTitle(kind: DetailAdviceKind): String = stringResource(
    when (kind) {
        DetailAdviceKind.PAUSED -> R.string.flow_advice_paused_title
        DetailAdviceKind.OVERDUE -> R.string.flow_advice_overdue_title
        DetailAdviceKind.LOW_RETENTION -> R.string.flow_advice_low_retention_title
        DetailAdviceKind.LOW_ACCURACY -> R.string.flow_advice_low_accuracy_title
        DetailAdviceKind.SENTENCE_DUE -> R.string.flow_advice_sentence_title
        DetailAdviceKind.STEADY -> R.string.flow_advice_steady_title
    }
)

@Composable
private fun detailAdviceBody(kind: DetailAdviceKind, sentenceDue: Int): String = when (kind) {
    DetailAdviceKind.PAUSED -> stringResource(R.string.flow_advice_paused_body)
    DetailAdviceKind.OVERDUE -> stringResource(R.string.flow_advice_overdue_body)
    DetailAdviceKind.LOW_RETENTION -> stringResource(R.string.flow_advice_low_retention_body)
    DetailAdviceKind.LOW_ACCURACY -> stringResource(R.string.flow_advice_low_accuracy_body)
    DetailAdviceKind.SENTENCE_DUE -> pluralStringResource(R.plurals.flow_advice_sentence_body, sentenceDue, sentenceDue)
    DetailAdviceKind.STEADY -> stringResource(R.string.flow_advice_steady_body)
}

@Composable
private fun DetailOverallCard(
    task: FlowTask,
    stats: ArticleStats,
    intervalDays: Int,
    readingSeconds: Long,
) {
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.flow_detail_overall),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GaugeProgress(
                    progress = task.retention,
                    label = stringResource(R.string.flow_detail_retention_label),
                    modifier = Modifier.size(104.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatItem(stringResource(R.string.practice_count), "${stats.totalPractices}")
                        StatItem(
                            stringResource(R.string.overall_accuracy),
                            "${(stats.overallRate * 100).toInt()}%"
                        )
                        StatItem(
                            stringResource(R.string.flow_detail_best),
                            "${(stats.bestRate.coerceAtLeast(0f) * 100).toInt()}%"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FlowDetailStat(
                            stringResource(R.string.flow_detail_stability),
                            String.format(Locale.getDefault(), "%.1f", task.state.stability)
                        )
                        FlowDetailStat(
                            stringResource(R.string.flow_detail_difficulty),
                            String.format(Locale.getDefault(), "%.1f", task.state.difficulty)
                        )
                        FlowDetailStat(stringResource(R.string.flow_detail_interval), "$intervalDays")
                    }
                }
            }
            if (stats.totalBlanks > 0 || readingSeconds > 0) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (stats.totalBlanks > 0) {
                        Text(
                            stringResource(R.string.flow_detail_blanks, stats.totalBlanks),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (readingSeconds > 0) {
                        Text(
                            stringResource(
                                R.string.total_reading_time,
                                readingSeconds / 60,
                                readingSeconds % 60
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCurveCard(task: FlowTask, curve: List<Float>) {
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_detail_curve_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.flow_detail_curve_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            if (curve.isEmpty()) {
                Text(
                    stringResource(R.string.flow_detail_no_curve),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MemoryDecayChart(
                    decayCurve = curve,
                    retentionRate = task.retention,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FlowDetailStat(
                        stringResource(R.string.flow_detail_retention_now),
                        "${(curve.first() * 100).toInt()}%"
                    )
                    FlowDetailStat(
                        stringResource(R.string.flow_detail_retention_7),
                        curve.getOrNull(7)?.let { "${(it * 100).toInt()}%" } ?: "—"
                    )
                    FlowDetailStat(
                        stringResource(R.string.flow_detail_retention_30),
                        curve.lastOrNull()?.let { "${(it * 100).toInt()}%" } ?: "—"
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailTrendCard(stats: ArticleStats) {
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_detail_trend_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.flow_detail_trend_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            DailyTrendChart(dailyCounts = stats.dailyCounts.takeLast(14))
        }
    }
}

@Composable
private fun DetailMistakesCard(stats: ArticleStats) {
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.flow_detail_mistakes_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            MistakeBar(
                typo = stats.typoCount,
                glyph = stats.glyphCount,
                missing = stats.missingCount,
                extra = stats.extraCount,
                order = stats.orderCount,
                incorrect = stats.incorrectCount
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScheduleCard(
    task: FlowTask,
    intervalDays: Int,
    onStart: () -> Unit,
    onAdjust: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val paused = task.phase == StudyFlow.Phase.PAUSED
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.flow_detail_schedule_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FlowDetailStat(stringResource(R.string.flow_detail_next_due), dueDateLabel(task))
                FlowDetailStat(stringResource(R.string.flow_detail_interval), "$intervalDays")
                FlowDetailStat(stringResource(R.string.flow_detail_lapses), "${task.state.lapses}")
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(onClick = onStart) {
                    Text(
                        stringResource(R.string.flow_start_review),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                GlassButton(onClick = onAdjust) {
                    Text(
                        stringResource(R.string.flow_adjust_schedule),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                GlassButton(onClick = onTogglePause) {
                    Text(
                        stringResource(if (paused) R.string.flow_resume else R.string.flow_pause),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (paused) Macaron.continueP().accent else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRecordsCard(records: List<PracticeRecord>, onViewAll: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_detail_records_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onViewAll) {
                    Text(
                        stringResource(R.string.flow_detail_view_all_stats),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            records.take(5).forEachIndexed { index, record ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SurfacePill(
                        text = recordModeLabel(record.mode),
                        color = Macaron.info().accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeFormat.format(Date(record.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (record.totalBlanks > 0) {
                            "${record.correctCount * 100 / record.totalBlanks}%"
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun recordModeLabel(mode: String): String = stringResource(
    when (mode) {
        "SENTENCE" -> R.string.cloze_mode_sentence
        "REVERSE" -> R.string.cloze_mode_reverse
        else -> R.string.cloze_mode_word
    }
)

@Composable
private fun formatFullDate(epochMillis: Long): String {
    val locale = Locale.getDefault()
    val formatter = remember(locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
    }
    return remember(epochMillis, formatter) {
        if (epochMillis > 0L) formatter.format(Date(epochMillis)) else "—"
    }
}
