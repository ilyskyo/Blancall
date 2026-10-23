// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.StatsRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.MemoryDecayChart
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.animateListItem
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * 学习任务流：把「FSRS 正在计划中的文章」铺成一条按时间推进的时间线。
 *
 * 页面上每个动词都落在真实调度上：
 * - 暂停：冻结此刻的剩余期，文章（含其句子卡）退出待复习、提醒与队列；
 * - 恢复：从暂停处接续，暂停期间手动练过则以那次推进为准；
 * - 调整时间：直接写下次 due（暂停中的任务写「恢复后的落点」）；
 * - 批量顺延 / 全部暂停 / 恢复：同一组存储接口，没有第二套调度真相。
 *
 * 数据全部来自 FsrsStateStore 的 revision 快照，所以从本页改完再回首页/列表/统计页，
 * 那三处不会仍挂着「待复习」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudyFlowScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
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

    // 跨午夜自动换桶：与统计页同款分钟 ticker
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    val tasks = remember(articles, states, pauses, sentenceStates, nowTick) {
        buildTasks(articles, states, pauses, sentenceStates, nowTick)
    }
    val todayCount = tasks.count {
        it.phase == StudyFlow.Phase.TODAY || it.phase == StudyFlow.Phase.OVERDUE
    }
    val overdueCount = tasks.count { it.phase == StudyFlow.Phase.OVERDUE }
    val pausedCount = tasks.count { it.phase == StudyFlow.Phase.PAUSED }
    val activeCount = tasks.size - pausedCount
    val nextTask = tasks.firstOrNull { it.phase != StudyFlow.Phase.PAUSED }
    val forecast = remember(tasks, nowTick) {
        StudyFlow.forecast(tasks.filter { it.phase != StudyFlow.Phase.PAUSED }.map { it.due }, nowTick)
    }

    var filterName by rememberSaveable { mutableStateOf(FlowFilter.ALL.name) }
    val filter = remember(filterName) { runCatching { FlowFilter.valueOf(filterName) }.getOrDefault(FlowFilter.ALL) }
    val rows = remember(tasks, filter) { buildRows(tasks, filter) }

    var sheetTaskId by rememberSaveable { mutableLongStateOf(0L) }
    var scheduleExpanded by rememberSaveable { mutableStateOf(false) }
    var showPauseAllConfirm by remember { mutableStateOf(false) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var actionSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(actionSeq) {
        if (actionSeq > 0) {
            delay(2400L)
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

    fun writeThenNote(message: String, block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            note(message)
        }
    }

    fun togglePause(task: FlowTask) {
        toggleHaptic()
        if (task.pause != null) {
            writeThenNote(resources.getString(R.string.flow_done_resumed)) {
                fsrsStore.resumeArticle(task.articleId)
            }
        } else {
            writeThenNote(resources.getString(R.string.flow_done_paused)) {
                fsrsStore.pauseArticle(task.articleId)
            }
        }
    }

    fun reschedule(task: FlowTask, targetDue: Long) {
        haptic()
        writeThenNote(resources.getString(R.string.flow_done_rescheduled)) {
            fsrsStore.rescheduleArticle(task.articleId, targetDue)
        }
    }

    val planId by AppPrefs.reviewTemplateFlow.collectAsState()
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
                    stringResource(R.string.flow_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(navBarConn)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)
            ) {
                item(key = "flow_summary") {
                    FlowSummaryCard(
                        activeCount = activeCount,
                        todayCount = todayCount,
                        overdueCount = overdueCount,
                        pausedCount = pausedCount,
                        nextTitle = nextTask?.title,
                        planId = planId,
                    )
                }
                item(key = "flow_schedule") {
                    FlowScheduleCard(
                        planId = planId,
                        expanded = scheduleExpanded,
                        onToggleExpanded = {
                            toggleHaptic()
                            scheduleExpanded = !scheduleExpanded
                        },
                        dueCount = todayCount,
                        pausedCount = pausedCount,
                        activeCount = activeCount,
                        actionNote = actionNote,
                        onPlanChange = { id ->
                            toggleHaptic()
                            AppPrefs.reviewTemplateId = id
                            note(resources.getString(R.string.flow_done_plan))
                        },
                        onPostponeDue = {
                            haptic()
                            val target = tomorrowStart(nowTick)
                            val dueIds = tasks
                                .filter { it.phase == StudyFlow.Phase.OVERDUE || it.phase == StudyFlow.Phase.TODAY }
                                .map { it.articleId }
                            writeThenNote(resources.getString(R.string.flow_done_postponed)) {
                                dueIds.forEach { fsrsStore.rescheduleArticle(it, target, nowTick) }
                            }
                        },
                        onResumeAll = {
                            haptic()
                            val ids = tasks.filter { it.phase == StudyFlow.Phase.PAUSED }.map { it.articleId }
                            writeThenNote(resources.getString(R.string.flow_done_resumed_all)) {
                                ids.forEach { fsrsStore.resumeArticle(it, nowTick) }
                            }
                        },
                        onPauseAll = { showPauseAllConfirm = true },
                    )
                }
                item(key = "flow_filters") {
                    FlowFilterRow(
                        filter = filter,
                        allCount = tasks.size,
                        dueCount = todayCount,
                        pausedCount = pausedCount,
                        onSelect = { filterName = it.name },
                    )
                }
                if (rows.isEmpty()) {
                    item(key = "flow_empty") {
                        FlowEmptyState(
                            hasAnyTask = tasks.isNotEmpty(),
                            onGoArticles = { navController.navigate("list") },
                        )
                    }
                } else {
                    if (filter != FlowFilter.PAUSED && tasks.any { it.phase != StudyFlow.Phase.PAUSED }) {
                        item(key = "flow_now") { FlowNowMarker() }
                    }
                    items(
                        items = rows,
                        key = { row ->
                            when (row) {
                                is FlowRowItem.Header -> "h_${row.phase.name}"
                                is FlowRowItem.Task -> "t_${row.task.articleId}"
                            }
                        },
                        contentType = { row -> if (row is FlowRowItem.Header) "header" else "task" }
                    ) { row ->
                        when (row) {
                            is FlowRowItem.Header -> {
                                Box(modifier = animateListItem()) {
                                    FlowGroupHeader(
                                        phase = row.phase,
                                        anchorDue = row.anchorDue,
                                        count = rows.count { it is FlowRowItem.Task && it.task.phase == row.phase },
                                    )
                                }
                            }
                            is FlowRowItem.Task -> {
                                val task = row.task
                                Box(modifier = animateListItem()) {
                                    FlowTaskRow(
                                        task = task,
                                        onOpen = {
                                            haptic()
                                            navController.navigate("study_task/${task.articleId}")
                                        },
                                        onAdjust = {
                                            haptic()
                                            sheetTaskId = task.articleId
                                        },
                                        onTogglePause = { togglePause(task) },
                                    )
                                }
                            }
                        }
                    }
                }
                // 7 日预测放在时间线之后：本页主角是「现在要做哪一件」，
                // 预测是回看全局的补充，不该把第一张任务卡挤出首屏。
                if (tasks.isNotEmpty()) {
                    item(key = "flow_forecast") {
                        FlowForecastCard(forecast = forecast, now = nowTick)
                    }
                }
            }
        }
    }

    val sheetTask = tasks.firstOrNull { it.articleId == sheetTaskId }
    if (sheetTask != null) {
        FlowAdjustSheet(
            task = sheetTask,
            now = nowTick,
            onDismiss = { sheetTaskId = 0L },
            onReschedule = { target -> reschedule(sheetTask, target) },
            onTogglePause = { togglePause(sheetTask) },
        )
    }

    if (showPauseAllConfirm) {
        val ids = tasks.filter { it.phase != StudyFlow.Phase.PAUSED }.map { it.articleId }
        BlancallAlertDialog(
            onDismissRequest = { showPauseAllConfirm = false },
            title = { Text(stringResource(R.string.flow_pause_all_confirm_title)) },
            text = { Text(stringResource(R.string.flow_pause_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showPauseAllConfirm = false
                    haptic()
                    writeThenNote(resources.getString(R.string.flow_done_paused_all)) {
                        ids.forEach { fsrsStore.pauseArticle(it, nowTick) }
                    }
                }) {
                    Text(stringResource(R.string.flow_pause_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPauseAllConfirm = false }) {
                    Text(stringResource(R.string.flow_cancel))
                }
            }
        )
    }
}

// ────────────────────────────────────────────────
// 数据模型
// ────────────────────────────────────────────────

internal data class FlowTask(
    val articleId: Long,
    val title: String,
    val state: FsrsEngine.CardState,
    val pause: StudyFlow.Pause?,
    /** 展示与排序用的到期时间：暂停项 = 恢复后的预计落点 */
    val due: Long,
    val phase: StudyFlow.Phase,
    val retention: Float,
    val sentenceDue: Int,
    val sentenceLearned: Int,
)

private enum class FlowFilter { ALL, DUE, PAUSED }

private sealed interface FlowRowItem {
    data class Header(val phase: StudyFlow.Phase, val anchorDue: Long) : FlowRowItem
    data class Task(val task: FlowTask) : FlowRowItem
}

internal fun buildTasks(
    articles: List<Article>,
    states: Map<Long, FsrsEngine.CardState>,
    pauses: Map<Long, StudyFlow.Pause>,
    sentenceStates: Map<String, FsrsEngine.CardState>,
    now: Long,
): List<FlowTask> {
    val byId = articles.associateBy { it.id }
    val learnedByArticle = HashMap<Long, Int>()
    val dueByArticle = HashMap<Long, Int>()
    sentenceStates.forEach { (key, state) ->
        val id = SentenceSelector.articleIdOf(key) ?: return@forEach
        if (state.reviewCount > 0) learnedByArticle[id] = (learnedByArticle[id] ?: 0) + 1
        if (FsrsEngine.isDue(state, now) && id !in pauses) {
            dueByArticle[id] = (dueByArticle[id] ?: 0) + 1
        }
    }

    val out = ArrayList<FlowTask>(states.size)
    states.forEach { (id, state) ->
        if (state.reviewCount <= 0) return@forEach
        val article = byId[id] ?: return@forEach
        val pause = pauses[id]
        val due = StudyFlow.projectedDue(state, pause, now)
        out += FlowTask(
            articleId = id,
            title = article.title,
            state = state,
            pause = pause,
            due = due,
            phase = StudyFlow.phase(due, pause != null, now),
            retention = FsrsEngine.retentionRate(state, now).toFloat(),
            sentenceDue = dueByArticle[id] ?: 0,
            sentenceLearned = learnedByArticle[id] ?: 0,
        )
    }
    return out.sortedWith(
        compareBy<FlowTask> { if (it.phase == StudyFlow.Phase.PAUSED) 1 else 0 }
            .thenBy { it.due }
            .thenBy { it.articleId }
    )
}

private fun buildRows(tasks: List<FlowTask>, filter: FlowFilter): List<FlowRowItem> {
    val phases = when (filter) {
        FlowFilter.ALL -> listOf(
            StudyFlow.Phase.OVERDUE,
            StudyFlow.Phase.TODAY,
            StudyFlow.Phase.TOMORROW,
            StudyFlow.Phase.THIS_WEEK,
            StudyFlow.Phase.LATER,
            StudyFlow.Phase.PAUSED,
        )
        FlowFilter.DUE -> listOf(StudyFlow.Phase.OVERDUE, StudyFlow.Phase.TODAY)
        FlowFilter.PAUSED -> listOf(StudyFlow.Phase.PAUSED)
    }
    val out = ArrayList<FlowRowItem>(tasks.size + phases.size)
    phases.forEach { phase ->
        val group = tasks.filter { it.phase == phase }.sortedBy { it.due }
        if (group.isNotEmpty()) {
            out += FlowRowItem.Header(phase, anchorDue = group.first().due)
            group.forEach { out += FlowRowItem.Task(it) }
        }
    }
    return out
}

private fun tomorrowStart(now: Long): Long {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return StudyFlow.startOfDay(today.plusDays(1), zone)
}

// ────────────────────────────────────────────────
// 顶层卡片
// ────────────────────────────────────────────────

@Composable
private fun FlowSummaryCard(
    activeCount: Int,
    todayCount: Int,
    overdueCount: Int,
    pausedCount: Int,
    nextTitle: String?,
    planId: String,
) {
    GlassCard(containerColor = Macaron.continueP().fill, backdrop = false) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                SurfacePill(text = planLabel(planId), color = Macaron.continueP().accent)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$todayCount",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.flow_summary_due_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FlowMiniStat(stringResource(R.string.flow_summary_active), activeCount)
                FlowMiniStat(stringResource(R.string.flow_summary_overdue), overdueCount, Macaron.review().accent)
                FlowMiniStat(stringResource(R.string.flow_summary_paused), pausedCount, Macaron.neutral().accent)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(8.dp))
            Text(
                nextTitle?.let { stringResource(R.string.flow_next_task, it) }
                    ?: stringResource(R.string.flow_no_next_task),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FlowMiniStat(label: String, value: Int, accent: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SurfacePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun planLabel(id: String): String = stringResource(
    when (id) {
        "sprint" -> R.string.plan_name_sprint
        "deep" -> R.string.plan_name_deep
        else -> R.string.plan_name_standard
    }
)

@Composable
private fun FlowForecastCard(forecast: List<Int>, now: Long) {
    val maxCount = forecast.maxOrNull() ?: 0
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(forecast) {
        appeared = false
        // 跨一帧再翻 true：同一次组合里连赋两次不会触发动画
        withFrameTick()
        appeared = true
    }
    val dayFormat = remember { SimpleDateFormat("E", Locale.getDefault()) }
    val today = remember(now) { LocalDate.now() }
    val labels = remember(today, dayFormat) {
        listOf(today) + (1L..6L).map { today.plusDays(it) }
    }
    GlassCard(backdrop = false) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_forecast_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.flow_forecast_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                forecast.forEachIndexed { index, count ->
                    val fraction = if (maxCount <= 0) 0f else count.toFloat() / maxCount
                    val progress by animateFloatAsState(
                        targetValue = if (appeared) fraction else 0f,
                        animationSpec = MotionFade.number(MotionFade.scoreReveal, staggerDelayMs(index)),
                        label = "flowBar$index"
                    )
                    val accent = if (index == 0) Macaron.continueP().accent else MaterialTheme.colorScheme.primary
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (count > 0) "$count" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height((5f + 46f * progress).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (count > 0) accent.copy(alpha = if (index == 0) 0.85f else 0.45f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (index == 0) stringResource(R.string.flow_today)
                            else dayFormat.format(Date(StudyFlow.startOfDay(labels[index], ZoneId.systemDefault()))),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun staggerDelayMs(index: Int): Int = minOf(index * 30, 180)

private suspend fun withFrameTick() {
    androidx.compose.runtime.withFrameNanos { }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowScheduleCard(
    planId: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    dueCount: Int,
    pausedCount: Int,
    activeCount: Int,
    actionNote: String?,
    onPlanChange: (String) -> Unit,
    onPostponeDue: () -> Unit,
    onResumeAll: () -> Unit,
    onPauseAll: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = MotionFade.number(MotionFade.enter),
        label = "scheduleChevron"
    )
    GlassCard(backdrop = false) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick(onClick = onToggleExpanded)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.flow_schedule_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                SurfacePill(text = planLabel(planId), color = Macaron.continueP().accent)
                Spacer(Modifier.width(6.dp))
                AppIcon(
                    AppIconKind.ChevronRight,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevron },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(Motion.contentSize()) + fadeIn(MotionFade.alpha(MotionFade.enter)),
                exit = shrinkVertically(Motion.contentSize()) + fadeOut(MotionFade.alpha(MotionFade.exit))
            ) {
                Column {
                    Text(
                        stringResource(R.string.flow_plan_effect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(ReviewTemplate.SPRINT, ReviewTemplate.STANDARD, ReviewTemplate.DEEP).forEach { template ->
                            FlowChoiceChip(
                                label = planLabel(template.id),
                                selected = template.id == planId,
                                onClick = { onPlanChange(template.id) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassButton(onClick = onPostponeDue, enabled = dueCount > 0) {
                            Text(
                                stringResource(R.string.flow_postpone_due),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        GlassButton(onClick = onResumeAll, enabled = pausedCount > 0) {
                            Text(
                                stringResource(R.string.flow_resume_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        GlassButton(onClick = onPauseAll, enabled = activeCount > 0) {
                            Text(
                                stringResource(R.string.flow_pause_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = actionNote != null,
                        enter = expandVertically(Motion.contentSize()) + fadeIn(MotionFade.alpha(MotionFade.enter)),
                        exit = shrinkVertically(Motion.contentSize()) + fadeOut(MotionFade.alpha(MotionFade.exit))
                    ) {
                        Text(
                            actionNote.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Macaron.continueP().accent,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun FlowChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape
            )
            .pressClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FlowFilterRow(
    filter: FlowFilter,
    allCount: Int,
    dueCount: Int,
    pausedCount: Int,
    onSelect: (FlowFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = filter == FlowFilter.ALL,
            onClick = { onSelect(FlowFilter.ALL) },
            label = { Text(stringResource(R.string.flow_filter_all, allCount), style = MaterialTheme.typography.labelSmall) }
        )
        FilterChip(
            selected = filter == FlowFilter.DUE,
            onClick = { onSelect(FlowFilter.DUE) },
            label = { Text(stringResource(R.string.flow_filter_due, dueCount), style = MaterialTheme.typography.labelSmall) }
        )
        FilterChip(
            selected = filter == FlowFilter.PAUSED,
            onClick = { onSelect(FlowFilter.PAUSED) },
            label = { Text(stringResource(R.string.flow_filter_paused, pausedCount), style = MaterialTheme.typography.labelSmall) }
        )
    }
}

// ────────────────────────────────────────────────
// 时间线
// ────────────────────────────────────────────────

@Composable
private fun FlowGroupHeader(phase: StudyFlow.Phase, anchorDue: Long, count: Int) {
    val accent = phaseAccent(phase)
    val formatter = dateFormatter()
    val weekdayFormatter = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val dateText = remember(anchorDue, formatter, weekdayFormatter) {
        formatter.format(Date(anchorDue)) + " · " + weekdayFormatter.format(Date(anchorDue))
    }
    val dayText = remember(anchorDue) {
        when (phase) {
            StudyFlow.Phase.OVERDUE -> "!"
            StudyFlow.Phase.PAUSED -> "—"
            else -> StudyFlow.localDate(anchorDue).dayOfMonth.toString()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 21.dp.toPx()
                drawLine(
                    color = accent.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    dayText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phaseLabel(phase),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(1.dp))
            Text(
                if (phase == StudyFlow.Phase.PAUSED) {
                    stringResource(R.string.flow_resume_on, dateText)
                } else {
                    dateText
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

/** 时间轴起点：它让「今天」以上的空间有一个明确的「现在」锚点，而不是凭空从第一张卡开始 */
@Composable
private fun FlowNowMarker() {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 21.dp.toPx()
                drawLine(
                    color = accent.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent)
            )
        }
        Spacer(Modifier.width(2.dp))
        Text(
            stringResource(R.string.flow_now),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = accent.copy(alpha = 0.25f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowTaskRow(
    task: FlowTask,
    onOpen: () -> Unit,
    onAdjust: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val accent = phaseAccent(task.phase)
    val paused = task.phase == StudyFlow.Phase.PAUSED
    val cardColor = flowCardColor(task.phase)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 21.dp.toPx()
                drawLine(
                    color = accent.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(color = accent, radius = 3.5.dp.toPx(), center = Offset(x, 26.dp.toPx()))
            }
            .padding(start = 44.dp)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                // 一镜到底的源节点：key 与详情页头部一致，列表卡直接长大成详情头
                .heroElement("flow_task_${task.articleId}"),
            containerColor = cardColor,
            backdrop = false,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressClick(onClick = onOpen)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (paused) {
                                Spacer(Modifier.width(6.dp))
                                SurfacePill(
                                    text = stringResource(R.string.plan_paused),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.flow_task_meta,
                                task.state.reviewCount,
                                (task.retention * 100).toInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (task.sentenceDue > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.flow_sentence_due, task.sentenceDue),
                                style = MaterialTheme.typography.labelSmall,
                                color = Macaron.info().accent
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
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
                    Spacer(Modifier.width(4.dp))
                    AppIcon(
                        AppIconKind.ChevronRight,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowInlineAction(
                        label = stringResource(R.string.flow_adjust_schedule),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onAdjust,
                    )
                    Spacer(Modifier.width(8.dp))
                    FlowInlineAction(
                        label = stringResource(if (paused) R.string.flow_resume else R.string.flow_pause),
                        color = if (paused) Macaron.continueP().accent else MaterialTheme.colorScheme.error,
                        onClick = onTogglePause,
                    )
                }
            }
        }
    }
}

@Composable
internal fun flowCardColor(phase: StudyFlow.Phase): Color? = when (phase) {
    StudyFlow.Phase.OVERDUE -> Macaron.review().fill
    StudyFlow.Phase.TODAY -> Macaron.warn().fill
    StudyFlow.Phase.TOMORROW -> Macaron.info().fill
    StudyFlow.Phase.THIS_WEEK, StudyFlow.Phase.LATER -> null
    StudyFlow.Phase.PAUSED -> Macaron.neutral().fill
}

@Composable
private fun FlowInlineAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .pressClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun FlowDetailStat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun phaseAccent(phase: StudyFlow.Phase): Color = when (phase) {
    StudyFlow.Phase.OVERDUE -> Macaron.review().accent
    StudyFlow.Phase.TODAY -> Macaron.warn().accent
    StudyFlow.Phase.TOMORROW -> Macaron.info().accent
    StudyFlow.Phase.THIS_WEEK, StudyFlow.Phase.LATER -> MaterialTheme.colorScheme.primary
    StudyFlow.Phase.PAUSED -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun phaseLabel(phase: StudyFlow.Phase): String = stringResource(
    when (phase) {
        StudyFlow.Phase.OVERDUE -> R.string.flow_group_overdue
        StudyFlow.Phase.TODAY -> R.string.flow_group_today
        StudyFlow.Phase.TOMORROW -> R.string.flow_group_tomorrow
        StudyFlow.Phase.THIS_WEEK -> R.string.flow_group_week
        StudyFlow.Phase.LATER -> R.string.flow_group_later
        StudyFlow.Phase.PAUSED -> R.string.flow_group_paused
    }
)

@Composable
internal fun phaseHeadline(task: FlowTask): String = when (task.phase) {
    StudyFlow.Phase.OVERDUE -> stringResource(R.string.flow_due_overdue)
    StudyFlow.Phase.TODAY -> stringResource(R.string.flow_due_today)
    StudyFlow.Phase.TOMORROW -> stringResource(R.string.flow_due_tomorrow)
    StudyFlow.Phase.THIS_WEEK, StudyFlow.Phase.LATER -> dueDateLabel(task)
    StudyFlow.Phase.PAUSED -> stringResource(R.string.plan_paused)
}

@Composable
internal fun dueDateLabel(task: FlowTask): String {
    val formatter = dateFormatter()
    val formatted = remember(task.due, formatter) { formatter.format(Date(task.due)) }
    return if (task.phase == StudyFlow.Phase.PAUSED) {
        stringResource(R.string.flow_resume_on, formatted)
    } else {
        formatted
    }
}

@Composable
private fun dateFormatter(): SimpleDateFormat {
    val locale = Locale.getDefault()
    return remember(locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
        SimpleDateFormat(pattern, locale)
    }
}

@Composable
private fun FlowEmptyState(hasAnyTask: Boolean, onGoArticles: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIcon(
                AppIconKind.Insights,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (hasAnyTask) R.string.flow_empty_filtered_title else R.string.flow_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (hasAnyTask) R.string.flow_empty_filtered_body else R.string.flow_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (!hasAnyTask) {
                Spacer(Modifier.height(18.dp))
                GlassButton(onClick = onGoArticles) {
                    Text(
                        stringResource(R.string.flow_empty_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────
// 调整面板
// ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FlowAdjustSheet(
    task: FlowTask,
    now: Long,
    onDismiss: () -> Unit,
    onReschedule: (Long) -> Unit,
    onTogglePause: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic(HapticTier.Confirm)
    var showPicker by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = task.due)
    val paused = task.phase == StudyFlow.Phase.PAUSED

    fun closeWithAnimation() {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    fun applyDue(target: Long) {
        haptic()
        onReschedule(target)
        closeWithAnimation()
    }

    val zone = ZoneId.systemDefault()
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }

    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.flow_adjust_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            SurfacePill(
                text = stringResource(
                    R.string.flow_adjust_current,
                    dueDateLabel(task)
                ),
                color = phaseAccent(task.phase)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.flow_quick_pick),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowChoiceChip(
                    label = stringResource(R.string.flow_today),
                    selected = false,
                    onClick = { applyDue(now) },
                )
                FlowChoiceChip(
                    label = stringResource(R.string.flow_tomorrow),
                    selected = false,
                    onClick = { applyDue(StudyFlow.startOfDay(today.plusDays(1), zone)) },
                )
                listOf(3L, 7L, 14L, 30L).forEach { days ->
                    FlowChoiceChip(
                        label = pluralStringResource(R.plurals.flow_days_later, days.toInt(), days),
                        selected = false,
                        onClick = { applyDue(StudyFlow.startOfDay(today.plusDays(days), zone)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(onClick = { showPicker = !showPicker }) {
                Text(
                    stringResource(R.string.flow_custom_date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            AnimatedVisibility(
                visible = showPicker,
                enter = expandVertically(Motion.contentSize()) + fadeIn(MotionFade.alpha(MotionFade.enter)),
                exit = shrinkVertically(Motion.contentSize()) + fadeOut(MotionFade.alpha(MotionFade.exit))
            ) {
                Column {
                    DatePicker(
                        state = pickerState,
                        showModeToggle = false,
                        title = null,
                        headline = null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = DatePickerDefaults.colors(containerColor = Color.Transparent)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showPicker = false }) {
                            Text(stringResource(R.string.flow_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassButton(
                            onClick = {
                                val selected = pickerState.selectedDateMillis
                                if (selected != null) {
                                    // DatePicker 选中的是 UTC 当日 00:00：先还原成日历日，再落到本地日界
                                    val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                                    val target = StudyFlow.startOfDay(day, zone).coerceAtLeast(now)
                                    applyDue(target)
                                }
                            },
                            enabled = pickerState.selectedDateMillis != null,
                        ) {
                            Text(
                                stringResource(R.string.flow_apply),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.flow_pause_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (paused) R.string.flow_resume else R.string.flow_pause),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                GlassSwitch(
                    checked = paused,
                    onCheckedChange = {
                        haptic()
                        onTogglePause()
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
