// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.ui.common.StateSwap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.TagData
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagDot
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「句子卡片」大卡片界面（华为堆叠卡片形态的应用内复刻）。
 *
 * - 队列 = 今日句置顶 + 全部到期句 + **全部未学过的新句**（[DailySentenceCoordinator.buildQueue]），
 *   一次加载不重排：记完一个还有下一个，直到全部记完（新文章/首次使用不会只有 1 张卡）；
 *   范围由筛选（标签）限定；
 * - 手势：上滑 = 下一张（不评级），下滑 = 回看上一张（已评只读盖章），不足阈值回弹；
 *   边界（首张下滑/末张上滑）与飞出动画期间的输入一律回弹/忽略，卡不会停在半途；
 * - 三键评级（忘记/不熟/记住了 → AGAIN/HARD/GOOD）写入句子级 FSRS 状态（[FsrsStateStore.saveSentence]），
 *   评完自动前进；完成页展示本次会话的评级分布（会话内记录，不落盘）；
 * - 主动复习：顶栏「复习」按钮用户可随时自行点击，进入全量再学一轮（含已学过未到期的句子，
 *   可对任意一张重复评级），「结束复习」回到日常间隔复习；完成页另有「再复习一轮」入口；
 * - 评级只更新本地状态用于展示，落盘在 IO 线程，失败不影响继续记忆。
 *
 * 视图组件见 [SentenceCardViews]（卡片脸/按钮/空态/完成页）；文案与时间口径见 [SentenceCardTexts]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceCardScreen(navController: NavController) {
    val context = LocalContext.current
    val articleViewModel: ArticleViewModel = viewModel()
    val articles by articleViewModel.articles.collectAsState()
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val sentenceStore = remember { SentenceCardStore.getInstance(context.filesDir) }

    // ── 抽句范围筛选（AppPrefs 持久化）：标签 + 文章两个维度，空 = 该维度不限 ──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val rawSentenceFilter by AppPrefs.sentenceTagFilterFlow.collectAsState()
    val rawArticleFilter by AppPrefs.sentenceArticleFilterFlow.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    // 与现有数据对账：已删标签 / 已删文章的筛选 id 仅本次忽略
    val validSentenceFilter = remember(rawSentenceFilter, tagData) {
        rawSentenceFilter.filterTo(mutableSetOf()) { id -> tagData.tags.any { it.id == id } }
    }
    val validArticleFilter = remember(rawArticleFilter, articles) {
        rawArticleFilter.filterTo(mutableSetOf()) { id -> articles.any { it.id == id } }
    }
    // 筛选后的文章池（组合与回退语义见 [resolveSentencePool]：任何维度命中为空都回退
    // 上一级，保证「选了文章却抽不出句子」的空池不会出现）
    val sentencePool = remember(articles, tagData, validSentenceFilter, validArticleFilter) {
        resolveSentencePool(articles, tagData, validSentenceFilter, validArticleFilter)
    }
    // 来源文章 id → 标签 chips（句卡展示）
    val chipByArticle = remember(tagData) {
        TagOps.tagsByArticle(tagData).mapValues { (_, list) -> list.toChipUis() }
    }

    // 队列（一次加载）；评级态 = states 快照 + 是否今天已评（从状态重 derive，旋转后可恢复）
    var queue by remember { mutableStateOf<List<DailySentenceCoordinator.QueueItem>?>(null) }
    var states by remember { mutableStateOf<Map<String, FsrsEngine.CardState>>(emptyMap()) }
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var positioned by rememberSaveable { mutableStateOf(false) }
    // ── 主动复习（用户自行发起）──
    // reviewAll = true：本轮为「全量复习轮」——全部句子入队（含已学过未到期），
    // 且允许对任意一张（含今天已评的）再次评级；顶栏「复习 / 结束复习」可随时切换。
    // reviewRound 自增触发队列重建（点「复习」/「结束复习」/ 完成页「再复习一轮」）。
    var reviewAll by rememberSaveable { mutableStateOf(false) }
    var reviewRound by rememberSaveable { mutableIntStateOf(0) }
    // 本次会话评级记录（完成页分布用；rememberSaveable 仅用于旋转恢复，不落盘）
    val sessionRatings = rememberSaveable(saver = SessionRatingsSaver) {
        mutableStateMapOf<String, FsrsEngine.Rating>()
    }
    // 划卡手势提示：仅首次使用展示一次（无论是否真的划过，之后不再出现；本次会话保持展示，旋转不闪失）
    val showSwipeHint by rememberSaveable { mutableStateOf(!AppPrefs.sentenceHintSeen) }
    LaunchedEffect(Unit) {
        if (!AppPrefs.sentenceHintSeen) AppPrefs.sentenceHintSeen = true
    }

    LaunchedEffect(sentencePool, reviewRound) {
        if (sentencePool.isEmpty()) return@LaunchedEffect
        fsrsStore.awaitLoaded()
        val allLearned = reviewAll
        val (loadedStates, loadedQueue) = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val s = fsrsStore.allSentenceStates()
            // 直接开屏兜底：确保今日句已抽（通常从首页进入时已抽过，此处幂等）
            DailySentenceCoordinator.ensureToday(sentenceStore, sentencePool, s, now)
            s to DailySentenceCoordinator.buildQueue(
                sentenceStore, sentencePool, s, now, includeAllLearned = allLearned,
            )
        }
        states = loadedStates
        queue = loadedQueue
        if (allLearned) {
            // 主动复习：从头过一遍（不论今天是否已评）
            positioned = true
            currentIndex = 0
        } else if (!positioned) {
            positioned = true
            // 打开即定位到第一个未评（今日句已评则跳到下一张；全部已评 → 完成页）
            val firstUnrated = loadedQueue.indexOfFirst { item ->
                !isRatedToday(loadedStates[item.key], System.currentTimeMillis())
            }
            currentIndex = if (firstUnrated >= 0) firstUnrated else loadedQueue.size
        }
    }

    val now = System.currentTimeMillis()
    val haptic = rememberConfirmHaptic()
    // 回弹（没跨过阈值，卡片自己回到原位）是轻的一下：它说的是「没走成」，
    // 和提交那一下的 Confirm 不是同一句话，共用一档会把两件事混成一个。
    val reboundHaptic = rememberHaptic(HapticTier.Toggle)
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    /**
     * flick 提交的速度线（词表里与横向标题切换共用同一条 [Motion.flingCommitDpPerSec]）。
     * 改造前提交**只看距离**，于是「用力一甩但没甩过 56dp」什么都不发生，
     * 而缓慢拖过阈值却整张飞走 —— 距离是手指的位移，速度才是手指的意图（法则一）。
     */
    val flingCommitPx = with(LocalDensity.current) { Motion.flingCommitDpPerSec.dp.toPx() }
    val dragVelocity = remember { VelocityTracker() }
    var areaHeightPx by remember { mutableIntStateOf(0) }
    val flyDistance = if (areaHeightPx > 0) areaHeightPx.toFloat() * 1.1f else 2000f
    var animating by remember { mutableStateOf(false) }
    /**
     * 已发出但尚未提交的推进数。飞行可以被手指抢占（见 onDragStart），而 index 要等
     * 各自协程的 finally 才落 —— 没有这个计数，连滑两次会算出同一个目标、第二张被吞掉。
     * 只由 advance() 自己加减，别处不碰，所以不需要和 193/200/444 那几处直接写 index 的地方同步。
     */
    var inFlight by remember { mutableIntStateOf(0) }
    /** 第几次飞行：只有最新那次有权清 animating，否则被抢占的旧飞行会提前解锁输入。 */
    var flyToken by remember { mutableIntStateOf(0) }
    /**
     * 飞离过程中收到的一次评分先暂存这里（最多一个，后来的不覆盖先来的）。
     * 见 [rate]：不在飞行途中结算，等这一张落位后应用到"露出来的那张"。
     */
    var pendingRating by remember { mutableStateOf<FsrsEngine.Rating?>(null) }
    // 飞离那张卡所在的协程：下一次按下要能抢占它（见 onDragStart）
    var flyJob by remember { mutableStateOf<Job?>(null) }

    fun ratedToday(key: String): Boolean = isRatedToday(states[key], System.currentTimeMillis())

    /**
     * advance() 能否真的翻走。
     * 抽出来是为了让**提交回执**在拖拽松手那一刻就判得准：
     * 到头翻不动时该响的是「没走成」那一档，不是「走了」那一档。
     *
     * 基准是 `currentIndex + inFlight` 而不是 currentIndex：飞离协程要等动画结束才落 index，
     * 只看 currentIndex 的话连滑两次会算出同一个目标（第二张被吞掉）。
     */
    fun canAdvance(forward: Boolean): Boolean {
        val items = queue ?: return false
        val base = (currentIndex + inFlight).coerceIn(0, items.size)
        return if (forward) base < items.size else base > 0
    }

    /**
     * 翻卡：上滑离场前进 / 下滑离场回看（回看只读）。
     * 边界（首张下滑、末张上滑）：不做翻卡，只把拖出的卡**回弹复位**，
     * 避免停在半途看起来像"卡死"。
     *
     * 两处物理：
     * 1. 松手速度带进弹簧（[Motion.cardFly]）—— 甩得越快飞得越快，
     *    这是改造前 `tween(300)` 永远给不出的「有重量」；
     * 2. 飞行途中被下一次按下抢占时（见 `onDragStart` 里的 `flyJob?.cancel()`），
     *    这一张的推进**照旧完成**（finally 里落 index）。
     *    改造前的 `animating` 是一把 300ms 的输入锁：动画期间拖拽与评分一律被挡掉，
     *    那是「可中断」的字面反面。现在动画可以被手指打断，而且打断不丢卡片。
     */
    fun advance(forward: Boolean, releaseVelocity: Float = 0f) {
        val items = queue ?: return
        if (!canAdvance(forward)) {
            scope.launch { dragY.animateTo(0f, Motion.cardRebound(), initialVelocity = releaseVelocity) }
            return
        }
        animating = true
        val myToken = ++flyToken
        // 基准带上在飞的推进数：连滑两次才不会算出同一个目标、把第二张吞掉
        val nextIndex = advanceTargetIndex(currentIndex, inFlight, forward, items.size)
        inFlight++
        val targetOffset = if (forward) -flyDistance else flyDistance
        flyJob = scope.launch {
            try {
                // 初速度 = 松手那一刻的 finger velocity：飞离的快慢由手指决定，不是由定时器决定
                dragY.animateTo(targetOffset, Motion.cardFly(), initialVelocity = releaseVelocity)
                dragY.snapTo(0f)
            } catch (_: CancellationException) {
                // 被下一次按下抢占：归位与索引推进分别在 onDragStart（snapTo）与 finally 里完成
            } finally {
                currentIndex = nextIndex
                inFlight--
                // 只有最新那次飞行有权清标志。被抢占的旧飞行提前清的话，
                // 排队中的评分（LaunchedEffect(animating) 靠它结算）会落在还没落位的卡上。
                if (myToken == flyToken) animating = false
            }
        }
    }

    /** 三键评级：FSRS 更新 → 本地即时展示 → IO 落盘 → 自动前进 */
    fun rate(item: DailySentenceCoordinator.QueueItem, rating: FsrsEngine.Rating) {
        // 飞行途中按钮仍绑定在**正在离场那张卡**上，此刻直接结算会把同一张卡评两次
        // （currentIndex 要等飞离协程的 finally 才落）。所以这里不吞输入，而是**排队**：
        // 手指落下的这一次评分照样算数，只是等这一张真正翻走之后，落到"当时露出来的那张"上。
        // 旧写法 `if (animating) return` 是直接把点击丢掉，用户读成"点了没反应"。
        if (animating) {
            pendingRating = pendingRating ?: rating
            haptic()
            return
        }
        haptic()
        val ts = System.currentTimeMillis()
        val next = FsrsEngine.review(states[item.key] ?: FsrsEngine.CardState(), rating, ts)
        // 记录本次所选评级名称：回看该卡时回显对应高亮（随 fsrs_state.json 持久化）
        next.lastRating = rating.name
        states = states + (item.key to next)
        sessionRatings[item.key] = rating
        scope.launch(Dispatchers.IO) {
            runCatching { fsrsStore.saveSentence(item.key, next) }
        }
        advance(forward = true)
    }

    // 这一张真正落位（currentIndex 已在飞离协程的 finally 里提交）之后，结算排队的那次评分。
    // 用 LaunchedEffect 而不是在 finally 里直接调 rate：Kotlin 的局部函数之间不能互相前向引用，
    // 且在那里调用会让 currentIndex 的读写缠进同一段协程。
    LaunchedEffect(animating) {
        if (animating) return@LaunchedEffect
        val r = pendingRating ?: return@LaunchedEffect
        pendingRating = null
        queue?.getOrNull(currentIndex)?.let { rate(it, r) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── 顶栏：返回 + 标题 + 进度 ──
            val items = queue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "句子卡片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (items != null && items.isNotEmpty()) {
                    Text(
                        if (currentIndex >= items.size) "完成"
                        else "${currentIndex + 1}/${items.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    // 抽句范围筛选入口（多选标签；背后句子池实时刷新）
                    GlassButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.height(34.dp),
                    ) {
                        AppIcon(
                            kind = AppIconKind.Filter,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (validSentenceFilter.isEmpty() && validArticleFilter.isEmpty()) "筛选" else "已筛选",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 主动复习入口：用户随时可自行点击「复习」再学一轮（全量、可重复评级）；
                    // 复习中再点「结束复习」回到日常间隔复习并重新定位到第一个未评
                    GlassButton(
                        onClick = {
                            sessionRatings.clear()
                            if (reviewAll) {
                                reviewAll = false
                                positioned = false
                            } else {
                                reviewAll = true
                                // 主动复习轻提示：系统 Toast 弹出（不再占用页面高度，卡片尺寸与常态一致）
                                Toast.makeText(
                                    context,
                                    "已进入主动复习：全部句子已入队",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            reviewRound++
                        },
                        modifier = Modifier.height(34.dp),
                    ) {
                        AppIcon(
                            kind = AppIconKind.Refresh,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (reviewAll) "结束复习" else "复习",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // 筛选生效指示（透明可见的一行小字，避免"句子突然变少"无从解释）
            if (validSentenceFilter.isNotEmpty() || validArticleFilter.isNotEmpty()) {
                Text(
                    buildString {
                        append("已限定抽句范围：")
                        if (validSentenceFilter.isNotEmpty()) append("${validSentenceFilter.size} 个标签")
                        if (validSentenceFilter.isNotEmpty() && validArticleFilter.isNotEmpty()) append(" · ")
                        if (validArticleFilter.isNotEmpty()) append("${validArticleFilter.size} 篇文章")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                )
            }
            // 主动复习提示已改为系统 Toast（见顶栏「复习」按钮）——页面不再渲染提示行，
            // 复习态与常态的卡片布局因此完全一致（不会因提示行占位而变矮）。

            val deck = items
            StateSwap(
                targetState = when {
                    deck == null -> 0
                    deck.isEmpty() -> 1
                    currentIndex >= deck.size -> 2
                    else -> 3
                },
                label = "sentenceCardState"
            ) { state ->
                when (state) {
                    0 -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    1 -> {
                        SentenceEmptyView(onBack = { navController.popBackStack() })
                    }
                    2 -> {
                        // 智能转换在 when(state) 里没了：这一臂要读 items.count/indexOfFirst，
                        // 所以先取一份非空副本。退场那一帧 deck 可能已是 null，
                        // 给空表而不是崩：完成页这时最多把统计显示成 0。
                        val items = deck ?: emptyList()
                        val ratedCount = items.count { ratedToday(it.key) }
                        val firstUnrated = items.indexOfFirst { !ratedToday(it.key) }
                        SentenceDoneView(
                            ratedCount = ratedCount,
                            againCount = sessionRatings.values.count { it == FsrsEngine.Rating.AGAIN },
                            hardCount = sessionRatings.values.count { it == FsrsEngine.Rating.HARD },
                            goodCount = sessionRatings.values.count { it == FsrsEngine.Rating.GOOD },
                            firstUnrated = firstUnrated,
                            // 再复习一轮：全量重新排队并从头过一遍（用户主动复习的主入口之一）
                            onReviewAgain = {
                                sessionRatings.clear()
                                reviewAll = true
                                reviewRound++
                            },
                            onContinue = { currentIndex = firstUnrated },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    // 卡组臂今天有两个根（堆叠 Box + 评分 Row），AnimatedContent 每态只许一根，
                    // 所以收进一个 fillMaxSize 的普通 Column —— 堆叠 Box 的 weight(1f) 留在原位，
                    // 它的父级现在是这个 Column，weight 仍然有效（直接挂在 swap 盒子下面才会失效）。
                    // 退场那一帧 items 可能已被重载成 null：卫语句让这一臂渲染成空盒，
                    // 而不是让下面 items[currentIndex] 越界崩。
                    3 -> Column(Modifier.fillMaxSize()) {
                        val items = deck ?: return@Column
                        if (currentIndex >= items.size) return@Column
                        val current = items[currentIndex]
                        // ── 堆叠区：当前卡可拖拽；后两张卡缩放任后（华为堆叠形态） ──
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 20.dp)
                                .onSizeChanged { areaHeightPx = it.height }
                                .clipToBounds(),
                        ) {
                            Box(Modifier.fillMaxSize().padding(top = 22.dp)) {
                                // 层叠方向按拖动方向选择：前进（上滑）露「下一张」、回看（下拉）露「上一张」；
                                // 缩放进度统一取 |dragY|：即将接棒的卡从 0.94 平滑放大到 1.0，动画结束
                                // 正好以全尺寸成为顶层 —— 修复「回看时底层显示成下一张」与「卡片从
                                // 被挡住的卡尺寸（0.94）突然跳大」的尺寸跳变（真机反馈）。
                                val towardBack = dragY.value > 0f
                                val secondItem = items.getOrNull(
                                    if (towardBack) currentIndex - 1 else currentIndex + 1
                                )
                                val thirdItem = items.getOrNull(
                                    if (towardBack) currentIndex - 2 else currentIndex + 2
                                )
                                val stackP = (kotlin.math.abs(dragY.value) / flyDistance).coerceIn(0f, 1f)
                                // 后层卡（depth=2 最后画底层；上移偏移让顶部露边）
                                thirdItem?.let { behind ->
                                    SentenceCardFace(
                                        item = behind,
                                        state = states[behind.key],
                                        now = now,
                                        tags = chipByArticle[behind.articleId].orEmpty(),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val base = 0.88f
                                                scaleX = base
                                                scaleY = base
                                                translationY = -18.dp.toPx()
                                                alpha = 0.6f
                                            },
                                    )
                                }
                                // 次层卡（depth=1）：随拖动进度渐渐顶到最前（前进/回看两方向一致）
                                secondItem?.let { behind ->
                                    SentenceCardFace(
                                        item = behind,
                                        state = states[behind.key],
                                        now = now,
                                        tags = chipByArticle[behind.articleId].orEmpty(),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val p = stackP
                                                val base = 0.94f + (1f - 0.94f) * p
                                                scaleX = base
                                                scaleY = base
                                                translationY = -10.dp.toPx() * (1f - p)
                                                alpha = 0.85f + 0.15f * p
                                            },
                                    )
                                }
                                // 顶层卡：拖拽 + 无障碍操作
                                SentenceCardFace(
                                    item = current,
                                    state = states[current.key],
                                    now = now,
                                    tags = chipByArticle[current.articleId].orEmpty(),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(0, dragY.value.roundToInt()) }
                                        .graphicsLayer {
                                            rotationZ = dragY.value / 56f
                                            alpha = 1f - (-dragY.value / flyDistance).coerceIn(0f, 1f) * 0.25f
                                        }
                                        .pointerInput(currentIndex, items.size) {
                                            detectVerticalDragGestures(
                                                onDragStart = {
                                                    // 手指接管即作废排队的评分：用户注意力已经回到这张卡上，
                                                    // 再按"上一次点击"结算会翻到他没想看的那张。
                                                    pendingRating = null
                                                    // 动画可以被手指打断：新按下先抢占上一次飞离，
                                                    // 被打断那张的推进在 advance 的 finally 里照常完成。
                                                    // 这里立刻归零 —— 否则新拖动会从旧卡飞出的半途偏移接着算。
                                                    flyJob?.cancel()
                                                    scope.launch { dragY.snapTo(0f) }
                                                },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    // 采样手指位置，松手时算出速度喂给弹簧
                                                    @Suppress("DEPRECATION")
                                                    dragVelocity.addPosition(change.uptimeMillis, change.position)
                                                    scope.launch {
                                                        dragY.snapTo(
                                                            (dragY.value + dragAmount)
                                                                .coerceIn(-flyDistance, flyDistance)
                                                        )
                                                    }
                                                },
                                                onDragEnd = {
                                                    @Suppress("DEPRECATION")
                                                    val v = dragVelocity.calculateVelocity().y
                                                    dragVelocity.resetTracking()
                                                    val offset = dragY.value
                                                    // 距离够 **或** 甩得快 —— 两条任一成立即提交
                                                    val goForward = offset <= -swipeThreshold || v <= -flingCommitPx
                                                    val goBackward = offset >= swipeThreshold || v >= flingCommitPx
                                                    when {
                                                        goForward -> {
                                                            if (canAdvance(true)) haptic() else reboundHaptic()
                                                            advance(forward = true, releaseVelocity = v)
                                                        }
                                                        goBackward -> {
                                                            if (canAdvance(false)) haptic() else reboundHaptic()
                                                            advance(forward = false, releaseVelocity = v)
                                                        }
                                                        else -> {
                                                            // 距离与速度都不够：回到原位，并把松手的动量一起还回去
                                                            reboundHaptic()
                                                            scope.launch { dragY.animateTo(0f, Motion.cardRebound(), initialVelocity = v) }
                                                        }
                                                    }
                                                },
                                                onDragCancel = {
                                                    dragVelocity.resetTracking()
                                                    scope.launch { dragY.animateTo(0f, Motion.cardRebound()) }
                                                },
                                            )
                                        }
                                        .semantics {
                                            contentDescription = buildString {
                                                append("句子卡片，第 ").append(currentIndex + 1)
                                                    .append(" / ").append(items.size).append(" 张")
                                                if (current.title.isNotBlank()) {
                                                    append("，来自《").append(current.title).append("》")
                                                }
                                                append("：").append(current.text)
                                                if (ratedToday(current.key)) append("。已记录")
                                            }
                                            customActions = listOf(
                                                CustomAccessibilityAction("记住了") {
                                                    rate(current, FsrsEngine.Rating.GOOD); true
                                                },
                                                CustomAccessibilityAction("不熟") {
                                                    rate(current, FsrsEngine.Rating.HARD); true
                                                },
                                                CustomAccessibilityAction("忘记") {
                                                    rate(current, FsrsEngine.Rating.AGAIN); true
                                                },
                                                CustomAccessibilityAction("下一个") {
                                                    advance(forward = true); true
                                                },
                                            )
                                        },
                                )
                            }
                        }

                        // 手势提示（仅首次使用展示一次；操作细节在帮助文档）
                        if (showSwipeHint) {
                            Text(
                                "上滑看下一张 · 下滑回看上一张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }

                        // ── 三键评级（左→右：忘记 / 不熟 / 记住了，记住了主色强调） ──
                        // 所选评级回显：会话内最新选择优先，否则取持久化的上次评级；未评卡不高亮
                        // （避免预设误导）。点击即时选中与回看回显共用同一 selected 样式；
                        // 按钮不再因「今日已评」禁用（支持改判），动画防连点由 rate() 内部 guard 兜底。
                        val selectedRating: FsrsEngine.Rating? =
                            if (ratedToday(current.key)) {
                                resolveDisplayedRating(
                                    session = sessionRatings[current.key],
                                    persisted = states[current.key]?.lastRating,
                                )
                            } else null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                                .navigationBarsPadding()
                                .padding(bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RatingButton(
                                label = "忘记",
                                desc = "忘记了：下次复习间隔会缩短",
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                selected = selectedRating == FsrsEngine.Rating.AGAIN,
                            ) { rate(current, FsrsEngine.Rating.AGAIN) }
                            RatingButton(
                                label = "不熟",
                                desc = "不熟：下次复习间隔略短",
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                selected = selectedRating == FsrsEngine.Rating.HARD,
                            ) { rate(current, FsrsEngine.Rating.HARD) }
                            RatingButton(
                                label = "记住了",
                                desc = "记住了：下次复习间隔将变长",
                                container = MaterialTheme.colorScheme.primary,
                                content = MaterialTheme.colorScheme.onPrimary,
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                selected = selectedRating == FsrsEngine.Rating.GOOD,
                                // 主色底上的白色描边不可见：改黑色，与另两键的深色描边拉齐（仅颜色差异）
                                borderColor = Color.Black,
                            ) { rate(current, FsrsEngine.Rating.GOOD) }
                        }
                    }
                }
            }
        }
    }

    // ── 抽句范围筛选面板（标签 + 文章两个维度，多选；即时写入 AppPrefs，背后句子池实时刷新）──
    if (showFilterSheet) {
        val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        GlassModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 6.dp, bottom = 20.dp),
            ) {
                Text(
                    "抽句范围",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "按标签 / 文章限定抽句范围；两者都选时取交集，都不选 = 全部文章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // 面板内容整体可滚动：标签与文章列表都可能较长
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxPanelHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // ── 维度一：按标签 ──
                    Text(
                        "按标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (tagData.tags.isEmpty()) {
                        Text(
                            "还没有标签，可在「设置 → 文章标签」中创建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else {
                        tagData.tags.forEach { tag ->
                            val checked = tag.id in validSentenceFilter
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressClick {
                                        val next = validSentenceFilter.toMutableSet()
                                        if (!next.add(tag.id)) next.remove(tag.id)
                                        AppPrefs.setSentenceTagFilter(next)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(4.dp))
                                TagDot(tag = TagChipUi(tag.name, tag.color))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    tag.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${articles.count { a -> tagData.links[a.id]?.contains(tag.id) == true }} 篇",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── 维度二：按文章（直接勾选具体文章）──
                    Text(
                        "按文章",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (articles.isEmpty()) {
                        Text(
                            "还没有文章",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    } else {
                        articles.sortedByDescending { it.updatedAt }.forEach { a ->
                            val checked = a.id in validArticleFilter
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressClick {
                                        val next = validArticleFilter.toMutableSet()
                                        if (!next.add(a.id)) next.remove(a.id)
                                        AppPrefs.setSentenceArticleFilter(next)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    a.title.ifBlank { "未命名文章" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${a.content.length} 字符",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        // 清除筛选 = 两个维度一起清（否则仍被另一维度限定，不符合直觉）
                        AppPrefs.setSentenceTagFilter(emptySet())
                        AppPrefs.setSentenceArticleFilter(emptySet())
                    }) {
                        Text("清除筛选")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showFilterSheet = false }) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

/**
 * 回显所选评级：会话内最新选择优先，否则解析持久化的上次评级名称；
 * 空/未知 → null（不显示高亮）。回归测试见 RateButtonStateTest。
 */
internal fun resolveDisplayedRating(
    session: FsrsEngine.Rating?,
    persisted: String?,
): FsrsEngine.Rating? =
    session ?: persisted?.let { name ->
        FsrsEngine.Rating.entries.firstOrNull { it.name == name }
    }

/**
 * 抽句文章池：文章维度先行限定（不选 = 全部），标签维度再取交集；
 * 任一维度命中为空都回退上一级结果（交集空回退文章维度、文章命中空回退全部），
 * 保证「选了文章却抽不出句子」的空池不会出现。回归测试见 SentenceCardPoolTest。
 */
internal fun resolveSentencePool(
    articles: List<Article>,
    tagData: TagData,
    tagFilter: Set<Long>,
    articleFilter: Set<Long>,
): List<Article> {
    val byArticle = if (articleFilter.isEmpty()) articles
    else articles.filter { it.id in articleFilter }.ifEmpty { articles }
    return if (tagFilter.isEmpty()) byArticle
    else TagOps.filterArticles(byArticle, tagData, tagFilter, includeUntagged = false)
        .ifEmpty { byArticle }
}

/** 会话评级记录的 Saveable：序列化为 "key|RATING" 字符串列表（仅旋转恢复用，不落盘） */
private val SessionRatingsSaver = Saver<SnapshotStateMap<String, FsrsEngine.Rating>, ArrayList<String>>(
    save = { map -> ArrayList(map.map { (k, v) -> "$k|${v.name}" }) },
    restore = { list ->
        mutableStateMapOf<String, FsrsEngine.Rating>().apply {
            list.forEach { s ->
                val i = s.lastIndexOf('|')
                if (i > 0) {
                    val rating = runCatching { FsrsEngine.Rating.valueOf(s.substring(i + 1)) }.getOrNull()
                    if (rating != null) put(s.substring(0, i), rating)
                }
            }
        }
    },
)

/**
 * 翻卡该落到第几张：基准 = 已提交的 index + 还在飞的推进数。
 *
 * 单独抽成纯函数是为了能被 JVM 测到 —— 竞态的后果全部体现在这个数上，
 * 而它发生在协程与动画之间，屏幕上看不出来（少一张卡只是「划得挺顺」）。
 * 上界用 size（不是 size-1）：那一档是「完成」页。
 */
internal fun advanceTargetIndex(current: Int, inFlight: Int, forward: Boolean, size: Int): Int =
    (current + inFlight + if (forward) 1 else -1).coerceIn(0, size)
