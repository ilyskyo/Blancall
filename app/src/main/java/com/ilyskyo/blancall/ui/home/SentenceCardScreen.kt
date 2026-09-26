// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import android.widget.Toast
import androidx.compose.animation.core.Animatable
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
            // 飞走降速：300ms + 缓入缓出（原 180ms 线性过快，不优雅）
            dragY.animateTo(
                if (forward) -flyDistance else flyDistance,
                tween(300, easing = FastOutSlowInEasing),
            )
            dragY.snapTo(0f)
            currentIndex = (currentIndex + if (forward) 1 else -1).coerceIn(0, items.size)
            animating = false
        }
    }

    /** 三键评级：FSRS 更新 → 本地即时展示 → IO 落盘 → 自动前进 */
    fun rate(item: DailySentenceCoordinator.QueueItem, rating: FsrsEngine.Rating) {
        // 防连点：动画期间的点击一律拦截（按钮视觉不受动画影响）；
        // 「今日已评」不再只读 —— 回看已评卡可再次点击改判（更新该卡评级与回显高亮）。
        if (animating) return
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
                                    .clickable {
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
