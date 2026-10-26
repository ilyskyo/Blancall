// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.ui.common.StateSwap
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceCloze
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.TagData
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.ReviewLogStore
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.CoachMark
import com.ilyskyo.blancall.ui.common.CoachShape
import com.ilyskyo.blancall.ui.common.coachAnchor
import com.ilyskyo.blancall.ui.common.rememberCoachAnchor
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * 「句子卡片」大卡片界面（华为堆叠卡片形态的应用内复刻）。
 *
 * - 队列 = 今日句置顶 + 全部到期句 + **全部未学过的新句**（[DailySentenceCoordinator.buildQueue]），
 *   一次加载不重排：记完一个还有下一个，直到全部记完（新文章/首次使用不会只有 1 张卡）；
 *   范围由筛选（标签）限定；
 * - 手势：上滑 = 下一张（不评级），下滑 = 回看上一张（已评只读盖章），不足阈值回弹；
 *   边界（首张下滑/末张上滑）回弹复位；飞离途中按住会接住卡片、可立即反向，取消不提交索引；
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
fun SentenceCardScreen(navController: NavController, queueLimit: Int = 0) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
    // 右上角 ⋮ 菜单：遮挡开关 + 复习入口（与阅读页/练习页同款玻璃菜单）
    var showMoreMenu by remember { mutableStateOf(false) }
    // 「点卡片揭晓 / 上下滑切卡」一次性指引圈的锚点（整张顶层卡）
    val cardAnchor = rememberCoachAnchor()
    // 挖空遮挡偏好（用户可关：关掉即整句直出，只做快速浏览）
    val clozeEnabled by AppPrefs.sentenceClozeEnabledFlow.collectAsState()
    // 首次见到的句子是否也遮挡（默认关：新句先给全文认读）
    val clozeNewEnabled by AppPrefs.sentenceClozeNewEnabledFlow.collectAsState()
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
    // 任务流里暂停的文章：不抽今日句、不进队列、不参与主动复习（先过暂停再分队，
    // 否则暂停文章的句子会从「已暂停」这一层里漏回来）
    var pausedArticleIds by remember { mutableStateOf(fsrsStore.pausedArticleIds()) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        pausedArticleIds = fsrsStore.pausedArticleIds()
    }
    val activeSentencePool = remember(sentencePool, pausedArticleIds) {
        if (pausedArticleIds.isEmpty()) sentencePool
        else sentencePool.filterNot { it.id in pausedArticleIds }
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
    /**
     * 回忆面是否已揭晓（Anki 的 Show Answer）。键含 [currentIndex] 与 [reviewRound]：
     * 换卡 / 重排队即归位到「未揭晓」—— 否则上一张的揭晓状态会被下一张继承，直接漏答案。
     */
    var revealed by rememberSaveable(currentIndex, reviewRound) { mutableStateOf(false) }
    // 本次会话评级记录（完成页分布用；rememberSaveable 仅用于旋转恢复，不落盘）
    val sessionRatings = rememberSaveable(saver = SessionRatingsSaver) {
        mutableStateMapOf<String, FsrsEngine.Rating>()
    }

    LaunchedEffect(activeSentencePool, reviewRound, pausedArticleIds, queueLimit) {
        if (activeSentencePool.isEmpty()) {
            // 全部文章都被暂停（或筛选后为空）：给空态，而不是一直停在加载圈
            states = emptyMap()
            queue = emptyList()
            return@LaunchedEffect
        }
        fsrsStore.awaitLoaded()
        val allLearned = reviewAll
        val (loadedStates, loadedQueue) = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val s = fsrsStore.allSentenceStates()
            // 直接开屏兜底：确保今日句已抽（通常从首页进入时已抽过，此处幂等）
            DailySentenceCoordinator.ensureToday(
                sentenceStore, activeSentencePool, s, now,
                excludedArticleIds = pausedArticleIds,
            )
            val built = DailySentenceCoordinator.buildQueue(
                sentenceStore, activeSentencePool, s, now, includeAllLearned = allLearned,
                excludedArticleIds = pausedArticleIds,
            )
            // 时长预算入口承诺的是「本次 N 句」：截断整条队列（今日句与到期句在前）；
            // 用户点「复习全部」时 allLearned = true，不受该上限约束。
            s to if (allLearned || queueLimit <= 0) built else built.take(queueLimit)
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
    /** 第几次飞行：抢占后旧协程的 finally 不许再清掉新飞行的输入锁。 */
    var flyToken by remember { mutableIntStateOf(0) }
    // 飞离那张卡所在的协程：下一次按下要能抢占它（见 onDragStart）
    var flyJob by remember { mutableStateOf<Job?>(null) }

    // ── 点按评级的离场层（和拖动的 dragY 分开走）──
    // 点按是「确认」：索引当场提交，飞走的是**旧卡的一份独立副本**（画在最上层）。
    // 拖动的索引必须等动画播完才提交（否则按住就等于翻页），两条路径不能共用一份位移。
    val exitOffset = remember { Animatable(0f) }
    var exitItem by remember { mutableStateOf<DailySentenceCoordinator.QueueItem?>(null) }
    /** 第几次点按离场：被下一次点击顶掉时，旧协程不许把新的离场层撤掉。 */
    var exitToken by remember { mutableIntStateOf(0) }

    fun ratedToday(key: String): Boolean = isRatedToday(states[key], System.currentTimeMillis())

    /**
     * advance() 能否真的翻走。
     * 抽出来是为了让**提交回执**在拖拽松手那一刻就判得准：
     * 到头翻不动时该响的是「没走成」那一档，不是「走了」那一档。
     */
    fun canAdvance(forward: Boolean): Boolean {
        val items = queue ?: return false
        return if (forward) currentIndex < items.size else currentIndex > 0
    }

    /**
     * 翻卡：上滑离场前进 / 下滑离场回看（回看只读）。
     * 边界（首张下滑、末张上滑）：不做翻卡，只把拖出的卡**回弹复位**。
     *
     * 索引只在飞离动画**完整播完**后提交。动画被手指抢占时，这张卡停在当前偏移、
     * 索引不动，用户可以立刻反向拖回 —— 这才是「可中断」。旧实现把提交放在 finally，
     * 于是刚按住就被判成翻页，手还按着底层已经换了一张。
     *
     * 同一时刻只允许一个飞离动画。手势飞离途中点评级按钮仍是允许的：这时结算的
     * 就是正在离场的那张（currentIndex 还没提交），评完立刻取消飞离并把索引交出去，
     * 详见 [rate]。旧实现把它排队到下一张，用户会在没看清下一句时替它做了评价 ——
     * 那条路已经整个删掉了。
     */
    fun advance(forward: Boolean, releaseVelocity: Float = 0f) {
        if (animating) return
        val items = queue ?: return
        if (!canAdvance(forward)) {
            scope.launch { dragY.animateTo(0f, Motion.cardRebound(), initialVelocity = releaseVelocity) }
            return
        }
        animating = true
        val myToken = ++flyToken
        val nextIndex = advanceTargetIndex(currentIndex, forward, items.size)
        val targetOffset = if (forward) -flyDistance else flyDistance
        flyJob = scope.launch {
            try {
                // 初速度 = 松手那一刻的 finger velocity：飞离的快慢由手指决定，不是由定时器决定
                dragY.animateTo(targetOffset, Motion.cardFly(), initialVelocity = releaseVelocity)
                // 取消可能晚于动画完成：手指已经接管时 token 已推进，不能再提交索引。
                if (myToken != flyToken) return@launch
                dragY.snapTo(0f)
                currentIndex = nextIndex
            } catch (_: CancellationException) {
                // 手指接管：不提交索引，也不 snap —— 卡片从当前偏移继续跟手
            } finally {
                if (myToken == flyToken) animating = false
            }
        }
    }

    /**
     * 三键评级：FSRS 更新 → 本地即时展示 → IO 落盘 → **当场前进**。
     *
     * 前进不等飞离动画。旧实现把推进锁在 `animating` 里，而 `cardFly()` 要 300ms 才收敛：
     * 两次点击的间隔只要落在 160–300ms 就会有一半被吞，用户看到的是「点三次只翻两次」；
     * 更糟的是吞掉的那一次并没有白点 —— `currentIndex` 尚未提交，评级被重复记在同一张卡上。
     *
     * 现在索引在点击这一帧就提交，飞走的是旧卡的**一份独立副本**（[exitItem]，画在最上层，
     * 由 [exitOffset] 驱动）。于是下一张立刻成为按钮的主人：点几次就翻几张，
     * 每一次评级都落在当时屏幕上那张卡上。
     */
    fun rate(item: DailySentenceCoordinator.QueueItem, rating: FsrsEngine.Rating) {
        val items = queue ?: return
        // 同一帧内的第二次点击可能还持有上一张的 lambda（重组还没跑）：那不是连点，
        // 而是同一张卡的重复点击 —— 丢弃。不丢的话会在一张卡上连跳两格。
        if (!acceptsTapOn(currentIndex, items.size, item.key, items.getOrNull(currentIndex)?.key)) return
        haptic()
        val ts = System.currentTimeMillis()
        val prevState = states[item.key] ?: FsrsEngine.CardState()
        val next = FsrsEngine.review(prevState, rating, ts)
        // 记录本次所选评级名称：回看该卡时回显对应高亮（随 fsrs_state.json 持久化）
        next.lastRating = rating.name
        states = states + (item.key to next)
        sessionRatings[item.key] = rating
        scope.launch(Dispatchers.IO) {
            runCatching {
                fsrsStore.saveSentence(item.key, next)
                // 复习事件日志（B3-4）：句子卡的评级也是复习事件，与状态同批落盘
                ReviewLogStore.append(
                    context.filesDir.resolve(ReviewLogStore.FILE_NAME),
                    item.key, ts, rating.value,
                    elapsedMs = if (prevState.lastReview > 0) ts - prevState.lastReview else 0L,
                    state = next
                )
                // 桌面小组件的到期数立刻跟随本次评级，不等系统 15 分钟轮询
                com.ilyskyo.blancall.widget.BlancallWidgetRefresh.request(context)
            }
        }

        // ── 当场前进 ──
        // 手势飞离还没落地就点了按钮：先把那份位移收掉并解除输入锁。
        // 不解除的话新卡会带着上一张的 dragY 入场（卡片从半空开始）。
        if (animating) {
            flyJob?.cancel()
            flyToken++
            animating = false
            scope.launch { dragY.snapTo(0f) }
        }
        val leaving = items[currentIndex]
        exitToken++
        val myToken = exitToken
        exitItem = leaving
        currentIndex = advanceTargetIndex(currentIndex, forward = true, items.size)
        scope.launch {
            try {
                // snapTo 会取消上一段离场（Animatable 的 mutator 互斥）；被取消那份的
                // token 已过期，不会把这一份的离场层撤掉
                exitOffset.snapTo(0f)
                exitOffset.animateTo(-flyDistance, Motion.cardTapExit())
                if (myToken == exitToken) exitItem = null
            } catch (_: CancellationException) {
                // 被下一次点击顶掉：离场层已经换人，这里什么都不做
            }
        }
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
            // 首次进入教手势：点卡片 = 揭晓答案，上下滑 = 切上一张 / 下一张（看过即不再出现）
            CoachMark(
                coachKey = "sentence_card_gesture",
                anchor = cardAnchor,
                title = stringResource(com.ilyskyo.blancall.R.string.coach_sentence_card_title),
                body = stringResource(com.ilyskyo.blancall.R.string.coach_sentence_card_body),
                shape = CoachShape.Card,
            )
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
                    stringResource(com.ilyskyo.blancall.R.string.sentence_cards),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (items != null && items.isNotEmpty()) {
                    Text(
                        if (currentIndex >= items.size) stringResource(com.ilyskyo.blancall.R.string.done)
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
                            if (validSentenceFilter.isEmpty() && validArticleFilter.isEmpty()) stringResource(com.ilyskyo.blancall.R.string.filter) else stringResource(com.ilyskyo.blancall.R.string.filtered),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // ⋮ 三点菜单：顶栏只留「筛选」这一个高频动作，其余（记忆方式 / 全量复习）
                    // 收进菜单 —— 与阅读页、练习页同一套 ⋮ 组件与层级，不再为每个功能各占一格。
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            AppIcon(
                                kind = AppIconKind.MoreVert,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                                contentDescription = stringResource(com.ilyskyo.blancall.R.string.more),
                            )
                        }
                        GlassDropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            // 分组标题：这一组管的是「怎么复习」，不是某个孤立开关 ——
                            // 单看「遮挡回忆」四个字，用户不知道它属于哪套流程。
                            GlassMenuItem(
                                enabled = false,
                                onClick = {},
                                label = {
                                    Text(
                                        stringResource(com.ilyskyo.blancall.R.string.sentence_menu_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            // 遮挡开关：叫「功能名」而不是「取消遮挡 / 开启遮挡」——
                            // 用户选的是记忆方式，不是要不要执行一个开/关动作。
                            GlassMenuItem(
                                // 切方式即回到「未揭晓」：从"整句直出"切回"遮挡"时若沿用上一张的揭晓态，
                                // 用户会看到一道已经被答案填满的空格，等于遮挡没生效
                                onClick = {
                                    AppPrefs.sentenceClozeEnabled = !clozeEnabled
                                    revealed = false
                                },
                                label = {
                                    Column {
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.sentence_cloze_toggle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            stringResource(
                                                if (clozeEnabled) com.ilyskyo.blancall.R.string.sentence_cloze_on_desc
                                                else com.ilyskyo.blancall.R.string.sentence_cloze_off_desc
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailing = {
                                    GlassSwitch(
                                        checked = clozeEnabled,
                                        onCheckedChange = {
                                            AppPrefs.sentenceClozeEnabled = it
                                            revealed = false
                                        },
                                    )
                                },
                            )
                            // 「遮挡回忆」的子选项：首次见到的句子（没评过级的新句）是否也先挖空。
                            // 默认关 —— 第一次认读先给全文；打开后新句也走正反面（点「显示答案」读全文）。
                            // 总开关关掉时整行禁用：否则用户会以为「开了这个却毫无变化」，正是这个 bug 的由来。
                            GlassMenuItem(
                                enabled = clozeEnabled,
                                onClick = {
                                    AppPrefs.sentenceClozeNewEnabled = !clozeNewEnabled
                                    revealed = false
                                },
                                label = {
                                    Column {
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.sentence_cloze_new_toggle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = if (clozeEnabled) 1f else 0.38f,
                                            ),
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.sentence_cloze_new_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = if (clozeEnabled) 1f else 0.38f,
                                            ),
                                        )
                                    }
                                },
                                trailing = {
                                    GlassSwitch(
                                        checked = clozeNewEnabled,
                                        enabled = clozeEnabled,
                                        onCheckedChange = {
                                            AppPrefs.sentenceClozeNewEnabled = it
                                            revealed = false
                                        },
                                    )
                                },
                            )
                            GlassMenuDivider()
                            // 主动复习：从顶栏按钮移进菜单。它是一次「整轮重来」的重动作，
                            // 挂在顶栏上既挤标题，又容易被当成「刷新」误触。
                            GlassMenuItem(
                                onClick = {
                                    showMoreMenu = false
                                    sessionRatings.clear()
                                    if (reviewAll) {
                                        reviewAll = false
                                        positioned = false
                                    } else {
                                        reviewAll = true
                                        // 主动复习轻提示：系统 Toast 弹出（不占用页面高度，卡片尺寸与常态一致）
                                        Toast.makeText(
                                            context,
                                            resources.getString(com.ilyskyo.blancall.R.string.review_started_all),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    reviewRound++
                                },
                                leadingIcon = {
                                    AppIcon(
                                        kind = AppIconKind.Refresh,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                label = {
                                    Text(
                                        if (reviewAll) stringResource(com.ilyskyo.blancall.R.string.end_review)
                                        else stringResource(com.ilyskyo.blancall.R.string.review_action),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 筛选生效指示（透明可见的一行小字，避免"句子突然变少"无从解释）
            if (validSentenceFilter.isNotEmpty() || validArticleFilter.isNotEmpty()) {
                // 资源读取留在组合期：getQuantityString 在组合期读的是 Activity 快照，
                // 语言/字号配置变化时不会随 Compose 的资源订阅刷新；拆成三个 stringResource。
                val tagFilterText = if (validSentenceFilter.isNotEmpty()) {
                    pluralStringResource(
                        com.ilyskyo.blancall.R.plurals.tag_filter_count,
                        validSentenceFilter.size, validSentenceFilter.size
                    )
                } else ""
                val articleFilterText = if (validArticleFilter.isNotEmpty()) {
                    pluralStringResource(
                        com.ilyskyo.blancall.R.plurals.article_filter_count,
                        validArticleFilter.size, validArticleFilter.size
                    )
                } else ""
                val scopeSeparator = if (tagFilterText.isNotEmpty() && articleFilterText.isNotEmpty()) " · " else ""
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.filter_scope_prefix) +
                        tagFilterText + scopeSeparator + articleFilterText,
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
                        // ── 回忆面 / 答案面（Anki 的 front / back）──
                        // 「已学过但今天还没评」的句子先只给分句首字提示，逼一次主动检索；
                        // 新句（第一次认读）与已评卡（回看）不需要这道门，直接给整句。
                        // 挖不出空（极端短句）也直接放行，避免「点了没反应」。
                        val currentState = states[current.key]
                        // 「挖不挖得出空」只由句子文本决定，remember 之后重组帧不再重算
                        val currentHidesAnything = remember(current.text) {
                            SentenceCloze.hidesAnything(current.text)
                        }
                        // 这道门只在「今天还没评」时立：评过 = 回看/改判，直接给整句。
                        // 新句默认也直接给整句（第一次是输入）；`clozeNewEnabled` 打开后连新句也先挖空，
                        // 走 Anki 的正反面（先看提示 → 显示答案 → 评级）。
                        val needsReveal = clozeEnabled &&
                            !ratedToday(current.key) &&
                            currentHidesAnything &&
                            ((currentState?.reviewCount ?: 0) > 0 || clozeNewEnabled)
                        val showAnswer = !needsReveal || revealed
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
                                // 方向只在越过 0 时变化：收成 derivedStateOf，滑卡过程中不每帧重组
                                val towardBack by remember { derivedStateOf { dragY.value > 0f } }
                                val secondItem = items.getOrNull(
                                    if (towardBack) currentIndex - 1 else currentIndex + 1
                                )
                                val thirdItem = items.getOrNull(
                                    if (towardBack) currentIndex - 2 else currentIndex + 2
                                )
                                // 后层卡（depth=2 最后画底层；上移偏移让顶部露边）
                                thirdItem?.let { behind ->
                                    SentenceCardFace(
                                        item = behind,
                                        state = states[behind.key],
                                        now = now,
                                        tags = chipByArticle[behind.articleId].orEmpty(),
                                        // 背景卡（下一张/上一张）用各自的未揭晓态：拖动中它逐渐露出来，
                                        // 若跟着顶层卡的 revealed 走，拖动过半就会把下一句的答案先漏出去。
                                        revealed = false,
                                        clozeEnabled = clozeEnabled,
                                        clozeNewEnabled = clozeNewEnabled,
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
                                        // 背景卡（下一张/上一张）用各自的未揭晓态：拖动中它逐渐露出来，
                                        // 若跟着顶层卡的 revealed 走，拖动过半就会把下一句的答案先漏出去。
                                        revealed = false,
                                        clozeEnabled = clozeEnabled,
                                        clozeNewEnabled = clozeNewEnabled,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                // 拖动进度在绘相位读取，避免每帧重组三层卡面
                                                val p = (kotlin.math.abs(dragY.value) / flyDistance)
                                                    .coerceIn(0f, 1f)
                                                val base = 0.94f + (1f - 0.94f) * p
                                                scaleX = base
                                                scaleY = base
                                                translationY = -10.dp.toPx() * (1f - p)
                                                alpha = 0.85f + 0.15f * p
                                            },
                                    )
                                }
                                // 顶层卡：拖拽 + 点按揭晓 + 无障碍操作
                                SentenceCardFace(
                                    item = current,
                                    state = currentState,
                                    now = now,
                                    tags = chipByArticle[current.articleId].orEmpty(),
                                    revealed = revealed,
                                    clozeEnabled = clozeEnabled,
                                    clozeNewEnabled = clozeNewEnabled,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .coachAnchor(cardAnchor)
                                        .graphicsLayer {
                                            // 位移走绘相位：不触发布局，也不在组合期读 dragY
                                            translationY = dragY.value
                                            rotationZ = dragY.value / 56f
                                            alpha = 1f - (-dragY.value / flyDistance).coerceIn(0f, 1f) * 0.25f
                                        }
                                        // 点按揭晓：与「显示答案」按钮同一件事的另一条入口（Anki 里也是点卡片）。
                                        // 单独一个 pointerInput 不会和下面的竖直拖拽打架 —— 手指一旦移动超过
                                        // 触摸阈值，tap 检测器自己取消，拖动照旧由 detectVerticalDragGestures 接管。
                                        .pointerInput(currentIndex) {
                                            detectTapGestures { revealed = true }
                                        }
                                        .pointerInput(currentIndex, items.size) {
                                            detectVerticalDragGestures(
                                                onDragStart = {
                                                    // 手指接管：取消飞离协程，但保留卡片当前偏移 ——
                                                    // 卡片就从手指按下的地方继续跟手，可以立刻反向拖回。
                                                    // 旧实现这里先 snapTo(0)，卡片会在指尖下瞬移回原位；
                                                    // 而且被取消的协程仍会在 finally 提交索引，手还按着
                                                    // 底层就换成下一张了。现在取消不提交、不归零。
                                                    flyJob?.cancel()
                                                    // 即使旧协程先一步跑完动画，也不能再提交索引。
                                                    flyToken++
                                                    animating = false
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
                                                append(resources.getString(
                                                    com.ilyskyo.blancall.R.string.sentence_card_announce,
                                                    currentIndex + 1, items.size
                                                ))
                                                if (current.title.isNotBlank()) append(
                                                    resources.getString(
                                                        com.ilyskyo.blancall.R.string.sentence_card_from, current.title
                                                    )
                                                )
                                                append(resources.getString(com.ilyskyo.blancall.R.string.sentence_card_colon))
                                                    // 未揭晓时播报挖空提示而不是整句 —— 读屏用户也走同一套
                                                    // 「先回忆再揭晓」，直接把答案念出来等于替他把题做了
                                                    .append(if (showAnswer) current.text else SentenceCloze.cue(current.text))
                                                if (ratedToday(current.key)) append(
                                                    resources.getString(com.ilyskyo.blancall.R.string.sentence_card_rated)
                                                )
                                            }
                                            customActions = buildList {
                                                if (!showAnswer) add(
                                                    CustomAccessibilityAction(
                                                        resources.getString(com.ilyskyo.blancall.R.string.show_answer)
                                                    ) { revealed = true; true }
                                                )
                                                add(CustomAccessibilityAction(
                                                    resources.getString(com.ilyskyo.blancall.R.string.rate_remember)
                                                ) { rate(current, FsrsEngine.Rating.GOOD); true })
                                                add(CustomAccessibilityAction(
                                                    resources.getString(com.ilyskyo.blancall.R.string.rate_hard)
                                                ) { rate(current, FsrsEngine.Rating.HARD); true })
                                                add(CustomAccessibilityAction(
                                                    resources.getString(com.ilyskyo.blancall.R.string.rate_again)
                                                ) { rate(current, FsrsEngine.Rating.AGAIN); true })
                                                add(CustomAccessibilityAction(
                                                    resources.getString(com.ilyskyo.blancall.R.string.rate_next)
                                                ) { advance(forward = true); true })
                                            }
                                        },
                                )
                                // 点按离场的旧卡：一份独立副本，画在最上层，由 exitOffset 往上带。
                                // 它已经不在队列的位置上（currentIndex 已前进），只是刚评完那张的余影：
                                // 没有手势、也不吃事件，动画走完即撤；连点时被下一张直接顶掉。
                                // 底层那两张此刻已经按新索引重排，全被这张全尺寸副本盖住，看不见。
                                exitItem?.let { leaving ->
                                    SentenceCardFace(
                                        item = leaving,
                                        state = states[leaving.key],
                                        now = now,
                                        tags = chipByArticle[leaving.articleId].orEmpty(),
                                        // 评级键只在揭晓后出现，能点到它就说明这张是答案面
                                        revealed = true,
                                        clozeEnabled = clozeEnabled,
                                        clozeNewEnabled = clozeNewEnabled,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                translationY = exitOffset.value
                                                rotationZ = exitOffset.value / 56f
                                                alpha = 1f -
                                                    (-exitOffset.value / flyDistance)
                                                        .coerceIn(0f, 1f) * 0.35f
                                            },
                                    )
                                }
                            }
                        }

                        // ── 底部区 ──
                        // 回忆面未揭晓：只给一个「显示答案」（Anki 的 Show Answer），评级键这时候不出现 ——
                        // 先回忆再自评是这套卡片的规矩，把评级键摆在那儿等于默许用户跳过回忆。
                        // 揭晓后：三键评级（左→右 忘记 / 不熟 / 记住了），每键显示该档预测的下次间隔。
                        // 所选评级回显：会话内最新选择优先，否则取持久化的上次评级；未评卡不高亮
                        // （避免预设误导）。点击即时选中与回看回显共用同一 selected 样式；
                        // 按钮不因「今日已评」禁用（支持改判）；连点由 rate() 的「当场前进」支持，
                        // 不需要任何防抖 —— 见该函数说明。
                        val selectedRating: FsrsEngine.Rating? =
                            if (ratedToday(current.key)) {
                                resolveDisplayedRating(
                                    session = sessionRatings[current.key],
                                    persisted = currentState?.lastRating,
                                )
                            } else null
                        val bottomBar = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                            .navigationBarsPadding()
                            .padding(bottom = 18.dp)
                        if (!showAnswer) {
                            SentenceRevealButton(onClick = { revealed = true }, modifier = bottomBar)
                        } else {
                            // 间隔预览：与真实调度同公式、只跳过 ±5% 扰动，所以每档是个稳定可复现的数
                            val previewState = currentState ?: FsrsEngine.CardState()
                            val againDays = FsrsEngine.previewIntervalDays(previewState, FsrsEngine.Rating.AGAIN, now)
                            val hardDays = FsrsEngine.previewIntervalDays(previewState, FsrsEngine.Rating.HARD, now)
                            val goodDays = FsrsEngine.previewIntervalDays(previewState, FsrsEngine.Rating.GOOD, now)
                            Row(
                                modifier = bottomBar,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RatingButton(
                                    label = stringResource(com.ilyskyo.blancall.R.string.rate_again),
                                    desc = stringResource(com.ilyskyo.blancall.R.string.rate_again_desc),
                                    container = MaterialTheme.colorScheme.errorContainer,
                                    content = MaterialTheme.colorScheme.onErrorContainer,
                                    enabled = true,
                                    modifier = Modifier.weight(1f),
                                    selected = selectedRating == FsrsEngine.Rating.AGAIN,
                                    intervalLabel = pluralStringResource(
                                        com.ilyskyo.blancall.R.plurals.interval_days, againDays, againDays
                                    ),
                                ) { rate(current, FsrsEngine.Rating.AGAIN) }
                                RatingButton(
                                    label = stringResource(com.ilyskyo.blancall.R.string.rate_hard),
                                    desc = stringResource(com.ilyskyo.blancall.R.string.rate_hard_desc),
                                    container = MaterialTheme.colorScheme.secondaryContainer,
                                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                                    enabled = true,
                                    modifier = Modifier.weight(1f),
                                    selected = selectedRating == FsrsEngine.Rating.HARD,
                                    intervalLabel = pluralStringResource(
                                        com.ilyskyo.blancall.R.plurals.interval_days, hardDays, hardDays
                                    ),
                                ) { rate(current, FsrsEngine.Rating.HARD) }
                                RatingButton(
                                    label = stringResource(com.ilyskyo.blancall.R.string.rate_remember),
                                    desc = stringResource(com.ilyskyo.blancall.R.string.rate_remember_desc),
                                    container = MaterialTheme.colorScheme.primary,
                                    content = MaterialTheme.colorScheme.onPrimary,
                                    enabled = true,
                                    modifier = Modifier.weight(1f),
                                    selected = selectedRating == FsrsEngine.Rating.GOOD,
                                    // 主色底上的白色描边不可见：改黑色，与另两键的深色描边拉齐（仅颜色差异）
                                    borderColor = Color.Black,
                                    intervalLabel = pluralStringResource(
                                        com.ilyskyo.blancall.R.plurals.interval_days, goodDays, goodDays
                                    ),
                                ) { rate(current, FsrsEngine.Rating.GOOD) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 抽句范围筛选面板（标签 + 文章两个维度，多选；即时写入 AppPrefs，背后句子池实时刷新）──
    AnimatedOverlayPresence(visible = showFilterSheet) {
        val maxPanelHeight = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp()
        } * 0.5f
        GlassModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 6.dp, bottom = 20.dp),
            ) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.sentence_scope_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.sentence_scope_hint),
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
                        stringResource(com.ilyskyo.blancall.R.string.filter_by_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (tagData.tags.isEmpty()) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.no_tags_create_hint),
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
                                    stringResource(
                                        com.ilyskyo.blancall.R.string.article_count_chip,
                                        articles.count { a -> tagData.links[a.id]?.contains(tag.id) == true }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── 维度二：按文章（直接勾选具体文章）──
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.filter_by_article),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (articles.isEmpty()) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.no_articles),
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
                                    a.title.ifBlank { stringResource(com.ilyskyo.blancall.R.string.untitled_article) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    pluralStringResource(
                                        com.ilyskyo.blancall.R.plurals.char_count, a.content.length, a.content.length
                                    ),
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
                        Text(stringResource(com.ilyskyo.blancall.R.string.clear_filter))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showFilterSheet = false }) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.done))
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
 * 翻卡该落到第几张：只基于**已经提交**的 index。
 *
 * 两种提交时机不同，别混：
 * - 手势飞离：动画播完才提交，被手指抢占时也不提交（否则手还按着底层就换了一张）；
 * - 点按评级：点击这一帧就提交，飞走的是旧卡的一份副本（否则连点会被 `animating` 吞掉）。
 *
 * 单独抽成纯函数是为了能被 JVM 测到 —— 竞态的后果全部体现在这个数上，
 * 而它发生在协程与动画之间，屏幕上看不出来（少一张卡只是「划得挺顺」）。
 * 上界用 size（不是 size-1）：那一档是「完成」页。
 */
internal fun advanceTargetIndex(current: Int, forward: Boolean, size: Int): Int =
    (current + if (forward) 1 else -1).coerceIn(0, size)

/**
 * 这次评级键点击该不该采纳（回归测试见 SentenceAdvanceTest）。
 *
 * 采纳条件只有一条：点的是**此刻**在 [currentIndex] 的那张卡。
 *
 * 连点天然满足 —— 每点一下索引当场前进（不等动画），按钮随即绑到下一张，
 * 于是第二次点击结算的是第二张，而不是把第一张再评一遍。
 * 不满足的只有一种情况：同一帧内的重复点击（重组还没跑，lambda 仍握着上一张），
 * 那时必须丢弃，否则一张卡会被连翻两格，中间那张从没露过面。
 */
internal fun acceptsTapOn(
    currentIndex: Int,
    size: Int,
    tappedKey: String,
    currentKey: String?,
): Boolean = currentIndex in 0 until size && currentKey == tappedKey
