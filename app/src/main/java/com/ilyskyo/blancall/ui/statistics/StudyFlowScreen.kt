// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import android.text.format.DateFormat
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.algorithm.StudyCoach
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
import com.ilyskyo.blancall.ui.common.AutoHideNavBarOnFlag
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.MemoryDecayChart
import com.ilyskyo.blancall.ui.common.NavBarAutoHide
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.PullReleaseDirection
import com.ilyskyo.blancall.ui.common.PullReleaseIndicator
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.UndoToast
import com.ilyskyo.blancall.ui.common.animateListItem
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.pullReleaseLatched
import com.ilyskyo.blancall.ui.common.pullReleaseReached
import com.ilyskyo.blancall.ui.common.pullReleaseTravel
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicBoolean

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
/**
 * 滑到底再上拉多远算「满格」：抬手即返回上一页（1:1 跟手，不含阻尼）。
 * 与首页下拉进入学习任务流的阈值同量级，两个方向的手感一致。
 */
private val FLOW_EXIT_PULL_THRESHOLD = 88.dp

/**
 * 满格后「回退多少才算撤回」的容差（与进入方向同一套语义）。
 * 抬手时手指的回带不该把「已画满」判成「没拉满」。
 */
private val FLOW_EXIT_PULL_CANCEL_SLACK = 12.dp

/**
 * 满格后的阻尼余量：再多推也只移动这么点，手感是「到顶了、推不动」。
 * 留得很小，是为了让手往回退一点点就能看见圈环倒转（撤回必须立刻有反馈）。
 */
private val FLOW_EXIT_PULL_OVERSHOOT = 8.dp

/** 满格后继续上拉的阻尼系数（1 = 跟手，0 = 完全推不动）。 */
private const val FLOW_EXIT_PULL_OVERSHOOT_RATIO = 0.08f

/**
 * 底部浮条的两档停留时长。
 *
 * 可撤销的那一档给 5 秒：读一句话、想清楚要不要点「撤销」，五秒是普通人来得及的；
 * 只是告知（已撤销 / 已切换复习强度）沿用原来的 2.4 秒 —— 没有动作可做，就该赶紧让位。
 */
private const val FLOW_NOTE_HOLD_MS = 2400L
private const val FLOW_NOTE_UNDO_MS = 5200L

/**
 * 任务流里的一条底部浮条：文案 +（可选）撤销所需的现场快照。
 *
 * 快照跟着提示走，而不是存在别处：浮条消失的那一刻，这次操作就不再可撤销，
 * 两件事共用一个生命周期，就不会出现「提示没了还能撤」的幽灵状态。
 */
private data class FlowNote(
    val message: String,
    val undo: List<FsrsStateStore.UndoSnapshot>? = null,
)

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
    // 旧版/CSV 回灌只有练习记录、没有 FSRS 状态；判断「练过没有」必须把记录带上
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    var states by remember { mutableStateOf(fsrsStore.allStates()) }
    var pauses by remember { mutableStateOf(fsrsStore.pausedArticles()) }
    var sentenceStates by remember { mutableStateOf(fsrsStore.allSentenceStates()) }
    var enrolled by remember { mutableStateOf(fsrsStore.enrolledArticles()) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        states = fsrsStore.allStates()
        pauses = fsrsStore.pausedArticles()
        sentenceStates = fsrsStore.allSentenceStates()
        enrolled = fsrsStore.enrolledArticles()
    }

    // 跨午夜自动换桶：与统计页同款分钟 ticker
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    val tasks = remember(articles, states, pauses, sentenceStates, enrolled, allRecords, nowTick) {
        buildTasks(articles, states, pauses, sentenceStates, enrolled, nowTick, records = allRecords)
    }
    // 「现在可练」只认精确到期（含逾期）；今天晚些时候到期的单独标注，不混进大数字。
    // 未开始的成员也算「可练」，但必须单独报数：不能把「开始第一遍」混称成复习。
    val dueNowCount = tasks.count { it.phase != StudyFlow.Phase.PAUSED && it.due <= nowTick }
    val dueNowNewCount = tasks.count {
        it.phase != StudyFlow.Phase.PAUSED && it.due <= nowTick && !it.hasPractice
    }
    val todayDueNow = tasks.count { it.phase == StudyFlow.Phase.TODAY && it.due <= nowTick }
    val laterTodayCount = tasks.count {
        it.phase == StudyFlow.Phase.TODAY && it.due > nowTick
    }
    val todayGroupCount = tasks.count {
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
    var flowNote by remember { mutableStateOf<FlowNote?>(null) }
    var noteSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(noteSeq) {
        if (noteSeq <= 0) return@LaunchedEffect
        delay(if (flowNote?.undo != null) FLOW_NOTE_UNDO_MS else FLOW_NOTE_HOLD_MS)
        flowNote = null
    }
    val haptic = rememberHaptic(HapticTier.Confirm)
    val toggleHaptic = rememberHaptic(HapticTier.Toggle)
    val scope = rememberCoroutineScope()

    /** 换一条浮条：序号 +1 让上一条的计时作废，连点两次不会出现「先来的先消失」。 */
    fun note(message: String, undo: List<FsrsStateStore.UndoSnapshot>? = null) {
        flowNote = FlowNote(message, undo)
        noteSeq++
    }

    /**
     * 先取快照、再落盘、最后报一条可撤销的浮条。
     *
     * 快照必须在 [block] 之前：撤的是「按下按钮之前」的样子，
     * 慢一步取到的是操作后的状态，撤销就变成原地不动。
     */
    fun writeThenNote(
        message: String,
        undoIds: Collection<Long> = emptyList(),
        block: () -> Unit,
    ) {
        scope.launch {
            val snapshot = if (undoIds.isEmpty()) null else withContext(Dispatchers.IO) {
                fsrsStore.snapshotArticles(undoIds)
            }
            withContext(Dispatchers.IO) { block() }
            note(message, snapshot)
        }
    }

    /** 撤销上一条：文案与按钮先换掉（防连点），数据再写回盘。 */
    fun undoLastNote() {
        val snapshot = flowNote?.undo ?: return
        toggleHaptic()
        note(resources.getString(R.string.flow_undone))
        scope.launch {
            withContext(Dispatchers.IO) { fsrsStore.restoreArticles(snapshot) }
        }
    }

    fun togglePause(task: FlowTask) {
        toggleHaptic()
        val one = listOf(task.articleId)
        if (task.pause != null) {
            writeThenNote(resources.getString(R.string.flow_done_resumed), one) {
                fsrsStore.resumeArticle(task.articleId)
            }
        } else {
            writeThenNote(resources.getString(R.string.flow_done_paused), one) {
                fsrsStore.pauseArticle(task.articleId)
            }
        }
    }

    fun reschedule(task: FlowTask, targetDue: Long) {
        haptic()
        writeThenNote(resources.getString(R.string.flow_done_rescheduled), listOf(task.articleId)) {
            fsrsStore.rescheduleArticle(task.articleId, targetDue)
        }
    }

    val planId by AppPrefs.reviewTemplateFlow.collectAsState()
    val navBarConn = rememberAutoHideNavBarOnScroll()

    // ── 滑到底再上拉：退出任务流 ──
    // 与首页「下拉进任务流」完全对称的一套手感：滑到底后继续上拉，圆环随位移填充。
    // **手指不松开就不会退出** —— 拉满只给一次 Threshold 触感，抬手那一刻才判定：
    // 拉满 → 返回上一页；没拉满 → 回弹归零。
    // 中途往回退（哪怕已经转满），圆环跟着倒转，始终留得出撤回空间。
    val flowListState = rememberLazyListState()
    // 与「下拉进任务流」同一套真值纪律：位移同步写入 exitPullPx，Animatable 只驱动回弹。
    val exitPullPx = remember { mutableFloatStateOf(0f) }
    val exitPullOffset = remember { Animatable(0f) }
    var exitPullRecoilJob by remember { mutableStateOf<Job?>(null) }
    val exitPullScope = rememberCoroutineScope()
    val exitPullThresholdPx = with(LocalDensity.current) { FLOW_EXIT_PULL_THRESHOLD.toPx() }
    val exitPullOvershootPx = with(LocalDensity.current) { FLOW_EXIT_PULL_OVERSHOOT.toPx() }
    val exitPullCancelSlackPx = with(LocalDensity.current) { FLOW_EXIT_PULL_CANCEL_SLACK.toPx() }
    val exitPullArmed = remember { AtomicBoolean(true) }
    val exitPullReady = remember { AtomicBoolean(false) }
    val exitPullNavigated = remember { AtomicBoolean(false) }
    // 蓄力圆环就在屏幕底部，悬浮底栏会把它整个盖住。手势期间用独立键按住底栏：
    // 滚动键会被蓄力连接「吃掉」滚动量、误判为停止并在 350ms 后放回底栏，这个键不会。
    // derivedStateOf 只在「有没有拉起」翻转时触发重组，不跟每一帧位移。
    val exitPullActive by remember { derivedStateOf { exitPullPx.floatValue > 0.5f } }
    AutoHideNavBarOnFlag(NavBarAutoHide.KEY_PULL_GESTURE, exitPullActive)
    // 触感强度在设置里改完要立刻生效，所以经 rememberUpdatedState 透传。
    val exitPullHaptic by rememberUpdatedState(rememberHaptic(HapticTier.Threshold))

    /**
     * 按「拉出增量」推进位移（正 = 继续上拉，负 = 手指往回收），返回位移变化量。
     * 只改位移与触感，**绝不导航**：退出一律由 [releaseExitPull] 在抬手时决定。
     */
    fun advanceExitPull(delta: Float): Float {
        val current = exitPullPx.floatValue
        if (current <= 0f && delta < 0f) return 0f
        // 从零重新上推 = 新一次手势：解除上一次的一次性返回锁
        if (current <= 0.5f && delta > 0f) {
            exitPullNavigated.set(false)
            exitPullReady.set(false)
        }
        exitPullRecoilJob?.cancel()
        exitPullRecoilJob = null
        val next = pullReleaseTravel(
            current = current,
            delta = delta,
            threshold = exitPullThresholdPx,
            overshootMax = exitPullOvershootPx,
            overshootRatio = FLOW_EXIT_PULL_OVERSHOOT_RATIO,
        )
        if (next != current) exitPullPx.floatValue = next
        exitPullReady.set(
            pullReleaseLatched(
                wasLatched = exitPullReady.get(),
                pullPx = next,
                thresholdPx = exitPullThresholdPx,
                cancelSlackPx = exitPullCancelSlackPx,
            )
        )
        if (pullReleaseReached(next, exitPullThresholdPx)) {
            if (exitPullArmed.compareAndSet(true, false)) exitPullHaptic()
        } else {
            // 退回阈值以内 ⇒ 下次再拉满还能再响一次
            exitPullArmed.set(true)
        }
        return next - current
    }

    /** 没拉满（或已回退取消）：从当前位移回弹归零。 */
    fun settleExitPull() {
        val pull = exitPullPx.floatValue
        if (pull <= 0.5f) return
        exitPullRecoilJob?.cancel()
        exitPullRecoilJob = exitPullScope.launch {
            try {
                exitPullOffset.snapTo(pull)
                exitPullOffset.animateTo(0f, Motion.pullRecoil()) {
                    exitPullPx.floatValue = value
                }
                exitPullReady.set(false)
            } finally {
                exitPullArmed.set(true)
            }
        }
    }

    /** 抬手判定：本次手势满过格就返回上一页，没满则回弹。拖动过程中永远不走到这里。 */
    fun releaseExitPull() {
        if (exitPullNavigated.get()) return
        if (exitPullPx.floatValue <= 0.5f) return
        if (exitPullReady.get()) {
            if (exitPullNavigated.compareAndSet(false, true)) {
                if (!navController.popBackStack()) {
                    // 已经不在栈上（极端情况）：解锁并回弹，别把页面卡在满格
                    exitPullNavigated.set(false)
                    exitPullReady.set(false)
                    settleExitPull()
                }
            }
            return
        }
        settleExitPull()
    }

    val exitPullConnection = remember(flowListState, exitPullThresholdPx, exitPullOvershootPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 松手后的惯性帧不参与蓄力：只有手指拖拽才算数
                if (source != NestedScrollSource.Drag) return Offset.Zero
                val dy = available.y
                if (dy < 0f && !flowListState.canScrollForward) {
                    // 已在底部还继续上拉：整段转成蓄力位移，不交给系统过度滚动光效
                    advanceExitPull(-dy)
                    return Offset(0f, dy)
                }
                if (dy > 0f && exitPullPx.floatValue > 0f) {
                    // 手指往回收：先抵掉蓄力位移（圆环倒转），抵完剩下的才让列表往回滚
                    val pulled = advanceExitPull(-dy)
                    return Offset(0f, -pulled)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 本帧列表把位移吃到底后还剩的上拉余量，接到蓄力上：从列表中间一路推到底也连贯
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (available.y >= 0f || flowListState.canScrollForward) return Offset.Zero
                advanceExitPull(-available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                releaseExitPull()
                return Velocity.Zero
            }
        }
    }

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
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    // 松手兜底：慢松手时嵌套滚动不会派发 fling 回调，这里在 Final pass
                    // 仅观察、不消费：指针全抬起时做一次抬手判定。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.none { it.pressed }) releaseExitPull()
                            }
                        }
                    }
            ) {
                // 指示器画在列表下层：内容跟手上移后，底部让出的空隙里露出来。
                // 读到的是同步位移真值（绘相位），不会每帧重组整页。
                PullReleaseIndicator(
                    pullPx = { exitPullPx.floatValue },
                    thresholdPx = exitPullThresholdPx,
                    direction = PullReleaseDirection.Up,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                LazyColumn(
                    state = flowListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = -exitPullPx.floatValue }
                            .nestedScroll(exitPullConnection)
                            .nestedScroll(navBarConn)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
                    ) {
                    item(key = "flow_summary") {
                        FlowSummaryCard(
                            activeCount = activeCount,
                            dueNowCount = dueNowCount,
                            dueNowNewCount = dueNowNewCount,
                            laterTodayCount = laterTodayCount,
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
                            dueCount = todayGroupCount,
                            pausedCount = pausedCount,
                            activeCount = activeCount,
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
                                writeThenNote(resources.getString(R.string.flow_done_postponed), dueIds) {
                                    dueIds.forEach { fsrsStore.rescheduleArticle(it, target, nowTick) }
                                }
                            },
                            onResumeAll = {
                                haptic()
                                val ids = tasks.filter { it.phase == StudyFlow.Phase.PAUSED }.map { it.articleId }
                                writeThenNote(resources.getString(R.string.flow_done_resumed_all), ids) {
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
                            dueCount = todayGroupCount,
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
                                            todayDueNow = todayDueNow,
                                            todayLater = laterTodayCount,
                                        )
                                    }
                                }
                                is FlowRowItem.Task -> {
                                    val task = row.task
                                    Box(modifier = animateListItem()) {
                                        FlowTaskRow(
                                            task = task,
                                            now = nowTick,
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

        // 底部浮条：贴着悬浮底栏上沿，不参与列表滚动，也不会被「滑到底再上拉」挤出屏幕。
        // 抬 92dp = 底栏 64 + 底边距 14 + 与底栏之间留 14；底栏自己带了导航栏 inset，这里不重复加。
        UndoToast(
            message = flowNote?.message.orEmpty(),
            visible = flowNote != null,
            undoLabel = if (flowNote?.undo != null) stringResource(R.string.flow_undo) else null,
            onUndo = if (flowNote?.undo != null) ({ undoLastNote() }) else null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 先限宽再填满：反过来写 fillMaxWidth().widthIn(max=…) 会因 minWidth 已被撑到父宽而失效
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 92.dp)
        )
    }

    val sheetTask = tasks.firstOrNull { it.articleId == sheetTaskId }
    AnimatedOverlayPresence(target = sheetTask) { task ->
        FlowAdjustSheet(
            task = task,
            now = nowTick,
            onDismiss = { sheetTaskId = 0L },
            onReschedule = { target -> reschedule(task, target) },
            onTogglePause = { togglePause(task) },
        )
    }

    AnimatedOverlayPresence(visible = showPauseAllConfirm) {
        val ids = tasks.filter { it.phase != StudyFlow.Phase.PAUSED }.map { it.articleId }
        BlancallAlertDialog(
            onDismissRequest = { showPauseAllConfirm = false },
            title = { Text(stringResource(R.string.flow_pause_all_confirm_title)) },
            text = { Text(stringResource(R.string.flow_pause_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showPauseAllConfirm = false
                    haptic()
                    writeThenNote(resources.getString(R.string.flow_done_paused_all), ids) {
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
    /** 是否练过：FSRS 状态或历史练习记录任一存在 */
    val hasPractice: Boolean,
    /** 展示用练习次数：FSRS reviewCount 与历史记录条数取大者 */
    val practiceCount: Int,
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
    enrolled: Map<Long, Long> = emptyMap(),
    now: Long,
    records: List<PracticeRecord> = emptyList(),
): List<FlowTask> {
    val byId = articles.associateBy { it.id }
    // 旧版/CSV 回灌可能只有 records、没有 FSRS 状态；这些也算练过
    val practicesByArticle = HashMap<Long, Int>()
    records.forEach { record ->
        if (record.articleId > 0L) {
            practicesByArticle[record.articleId] = (practicesByArticle[record.articleId] ?: 0) + 1
        }
    }
    val learnedByArticle = HashMap<Long, Int>()
    val dueByArticle = HashMap<Long, Int>()
    sentenceStates.forEach { (key, state) ->
        val id = SentenceSelector.articleIdOf(key) ?: return@forEach
        if (state.reviewCount > 0) learnedByArticle[id] = (learnedByArticle[id] ?: 0) + 1
        if (FsrsEngine.isDue(state, now) && id !in pauses) {
            dueByArticle[id] = (dueByArticle[id] ?: 0) + 1
        }
    }

    // 成员 = 练过的（reviewCount > 0）∪ 主动加入计划的（v3 enrolled 表）。
    // 用集合去重：练过之后再"加入"一次不会出现两张卡。
    val memberIds = LinkedHashSet<Long>(states.size + enrolled.size)
    states.forEach { (id, state) -> if (state.reviewCount > 0) memberIds += id }
    memberIds += enrolled.keys

    val out = ArrayList<FlowTask>(memberIds.size)
    memberIds.forEach { id ->
        val article = byId[id] ?: return@forEach
        val stored = states[id]
        val trained = stored != null && stored.reviewCount > 0
        val recordPractices = practicesByArticle[id] ?: 0
        val hasPractice = trained || recordPractices > 0
        // 还没练过的成员：临时造一个空状态只用于展示，due 取「加入时刻」。
        // 故意不写盘 —— states 保持干净，统计、提醒、待复习判定仍把「没练过」当没练过。
        val state = if (trained) {
            stored
        } else {
            FsrsEngine.CardState(due = enrolled[id] ?: 0L)
        }
        val pause = pauses[id]
        val due = StudyFlow.projectedDue(state, pause, now)
        out += FlowTask(
            articleId = id,
            title = article.title,
            state = state,
            pause = pause,
            due = due,
            phase = StudyFlow.phase(due, pause != null, now),
            // 没练过就没有留存率可言：0f 在这里表示「未开始」，不是「马上要忘光」
            retention = if (trained) FsrsEngine.retentionRate(state, now).toFloat() else 0f,
            hasPractice = hasPractice,
            // 旧记录没有 FSRS 次数：用记录条数兜底，避免详情显示 0 次
            practiceCount = maxOf(state.reviewCount, recordPractices),
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
    dueNowCount: Int,
    dueNowNewCount: Int,
    laterTodayCount: Int,
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
                    "$dueNowCount",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.flow_summary_due_now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            if (dueNowNewCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.flow_summary_due_new, dueNowNewCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Macaron.info().accent
                )
            }
            if (laterTodayCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.flow_summary_later_today, laterTodayCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Macaron.info().accent
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
                nextTitle?.let { stringResource(R.string.flow_next_task_article, it) }
                    ?: stringResource(R.string.flow_no_next_task),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.flow_scope_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.flow_postpone_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
private fun FlowGroupHeader(
    phase: StudyFlow.Phase,
    anchorDue: Long,
    count: Int,
    todayDueNow: Int = 0,
    todayLater: Int = 0,
) {
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
                when {
                    phase == StudyFlow.Phase.PAUSED -> stringResource(R.string.flow_resume_on, dateText)
                    phase == StudyFlow.Phase.TODAY && todayLater > 0 ->
                        stringResource(R.string.flow_group_today_split, todayDueNow, todayLater)
                    else -> dateText
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
    now: Long,
    onOpen: () -> Unit,
    onAdjust: () -> Unit,
    onTogglePause: () -> Unit,
    /**
     * false = 只读清单（首页「今日计划」展开页）：不渲染行内的「调整时间 / 暂停」。
     * 那两个动作属于「管理」，留给学习任务流，别让只想来做任务的页面背上管理负担。
     */
    showManagement: Boolean = true,
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
                            // 0 次练习没有留存率可言；「练习 0 次 · 留存 0%」会把未开始读成已遗忘。
                            // 旧记录有练习次数但没有 FSRS 留存，只报练习次数。
                            when {
                                !task.hasPractice -> stringResource(R.string.flow_task_meta_new)
                                task.state.reviewCount <= 0 -> stringResource(
                                    R.string.flow_task_meta_no_retention,
                                    task.practiceCount
                                )
                                else -> stringResource(
                                    R.string.flow_task_meta,
                                    task.practiceCount,
                                    (task.retention * 100).toInt()
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 「为什么今天轮到它」：只写事实（忘过几次 / 隔了几天），不露 FSRS 术语。
                        // 没有这一行，用户看到到期只会怀疑算法在乱推，然后开始无视提醒。
                        Spacer(Modifier.height(2.dp))
                        Text(
                            schedulingReasonText(StudyCoach.reasonOf(task.state, now, task.hasPractice)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
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
                if (showManagement) {
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

/** 调度理由的展示文案：纯事实、无算法术语。 */
@Composable
internal fun schedulingReasonText(reason: StudyCoach.Reason): String = when (reason.kind) {
    StudyCoach.ReasonKind.FIRST_PRACTICE -> stringResource(R.string.reason_first_practice)
    StudyCoach.ReasonKind.LAPSED -> stringResource(R.string.reason_lapsed, reason.count)
    StudyCoach.ReasonKind.LONG_GAP -> stringResource(R.string.reason_long_gap, reason.days)
    StudyCoach.ReasonKind.OVERDUE -> stringResource(R.string.reason_overdue, reason.days)
    StudyCoach.ReasonKind.STABLE -> stringResource(R.string.reason_stable, reason.count)
    StudyCoach.ReasonKind.SCHEDULED -> stringResource(R.string.reason_scheduled)
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
    // 年份下拉 / 日历滚到尽头后，剩下那点位移不要再交给外层 sheet ——
    // 否则继续滑会把整个底部面板拖起来，页面跟着抖。只吞「没人要的余量」，列表自己的滚动不受影响。
    val pickerScrollGuard = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                available
        }
    }

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
                Column(Modifier.nestedScroll(pickerScrollGuard)) {
                    DatePicker(
                        state = pickerState,
                        showModeToggle = false,
                        title = null,
                        headline = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        // 必须是实底：年份下拉沿用同一个 containerColor，
                        // 透明时它会和下面的日历叠在一起，两层文字完全分不开（真机反馈）。
                        colors = DatePickerDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
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
