// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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

    // ── 抽句范围标签筛选（AppPrefs 持久化；空 = 全部文章）──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val rawSentenceFilter by AppPrefs.sentenceTagFilterFlow.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    // 与现有标签对账：已删标签的筛选 id 仅本次忽略
    val validSentenceFilter = remember(rawSentenceFilter, tagData) {
        rawSentenceFilter.filterTo(mutableSetOf()) { id -> tagData.tags.any { it.id == id } }
    }
    // 筛选后的文章池：筛选为空或命中为空 → 回退全部文章（防「每日一句」凭空消失）
    val sentencePool = remember(articles, tagData, validSentenceFilter) {
        if (validSentenceFilter.isEmpty()) articles
        else TagOps.filterArticles(articles, tagData, validSentenceFilter, includeUntagged = false)
            .ifEmpty { articles }
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
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    var areaHeightPx by remember { mutableIntStateOf(0) }
    val flyDistance = if (areaHeightPx > 0) areaHeightPx.toFloat() * 1.1f else 2000f
    var animating by remember { mutableStateOf(false) }

    fun ratedToday(key: String): Boolean = isRatedToday(states[key], System.currentTimeMillis())

    /**
     * 翻卡：上滑离场前进 / 下滑离场回看（回看只读）。
     * 边界（首张下滑、末张上滑）或动画进行中：不做翻卡，只把拖出的卡**回弹复位**，
     * 避免停在半途看起来像"卡死"。
     */
    fun advance(forward: Boolean) {
        val items = queue ?: return
        val canGo = if (forward) currentIndex < items.size else currentIndex > 0
        if (!canGo || animating) {
            scope.launch { dragY.animateTo(0f, spring()) }
            return
        }
        scope.launch {
            animating = true
            dragY.animateTo(if (forward) -flyDistance else flyDistance, tween(180))
            dragY.snapTo(0f)
            currentIndex = (currentIndex + if (forward) 1 else -1).coerceIn(0, items.size)
            animating = false
        }
    }

    /** 三键评级：FSRS 更新 → 本地即时展示 → IO 落盘 → 自动前进 */
    fun rate(item: DailySentenceCoordinator.QueueItem, rating: FsrsEngine.Rating) {
        // 主动复习轮允许对任意一张再次评级（含今天已评的）；日常模式保持「今天已评只读」
        if (animating || (!reviewAll && ratedToday(item.key))) return
        haptic()
        val ts = System.currentTimeMillis()
        val next = FsrsEngine.review(states[item.key] ?: FsrsEngine.CardState(), rating, ts)
        states = states + (item.key to next)
        sessionRatings[item.key] = rating
        scope.launch(Dispatchers.IO) {
            runCatching { fsrsStore.saveSentence(item.key, next) }
        }
        advance(forward = true)
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
                            if (validSentenceFilter.isEmpty()) "筛选" else "已筛选",
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
            if (validSentenceFilter.isNotEmpty()) {
                Text(
                    "已按标签筛选抽句范围（${validSentenceFilter.size} 个标签）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                )
            }
            // 主动复习指示（让「复习」按钮的状态与可重复评级行为可解释）
            if (reviewAll) {
                Text(
                    "主动复习中：全部句子均已入队，可对任意一张再次评级",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                )
            }

            when {
                items == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }

                items.isEmpty() -> {
                    SentenceEmptyView(onBack = { navController.popBackStack() })
                }

                currentIndex >= items.size -> {
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

                else -> {
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
                            // 后层卡（depth=2 最后画底层；上移偏移让顶部露边）
                            items.getOrNull(currentIndex + 2)?.let { behind ->
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
                            // 次层卡（depth=1）：随上滑进度渐渐顶到最前
                            items.getOrNull(currentIndex + 1)?.let { behind ->
                                SentenceCardFace(
                                    item = behind,
                                    state = states[behind.key],
                                    now = now,
                                    tags = chipByArticle[behind.articleId].orEmpty(),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val p = (-dragY.value / flyDistance).coerceIn(0f, 1f)
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
                                            onVerticalDrag = { change, dragAmount ->
                                                // 飞出动画期间忽略拖拽输入：避免 snapTo 与 animateTo 互抢同一 Animatable
                                                if (!animating) {
                                                    change.consume()
                                                    scope.launch {
                                                        dragY.snapTo(
                                                            (dragY.value + dragAmount)
                                                                .coerceIn(-flyDistance, flyDistance)
                                                        )
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                val v = dragY.value
                                                when {
                                                    v <= -swipeThreshold -> advance(forward = true)
                                                    v >= swipeThreshold -> advance(forward = false)
                                                    else -> scope.launch { dragY.animateTo(0f, spring()) }
                                                }
                                            },
                                            onDragCancel = {
                                                scope.launch { dragY.animateTo(0f, spring()) }
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
                    val canRate = (reviewAll || !ratedToday(current.key)) && !animating
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
                            enabled = canRate,
                            modifier = Modifier.weight(1f),
                        ) { rate(current, FsrsEngine.Rating.AGAIN) }
                        RatingButton(
                            label = "不熟",
                            desc = "不熟：下次复习间隔略短",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            enabled = canRate,
                            modifier = Modifier.weight(1f),
                        ) { rate(current, FsrsEngine.Rating.HARD) }
                        RatingButton(
                            label = "记住了",
                            desc = "记住了：下次复习间隔将变长",
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            enabled = canRate,
                            modifier = Modifier.weight(1f),
                        ) { rate(current, FsrsEngine.Rating.GOOD) }
                    }
                }
            }
        }
    }

    // ── 抽句范围筛选面板（多选标签；即时写入 AppPrefs，背后句子池实时刷新）──
    if (showFilterSheet) {
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
                    "只从所选标签的文章中抽句；不选 = 全部文章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

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
                                .clickable {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { AppPrefs.setSentenceTagFilter(emptySet()) }) {
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
