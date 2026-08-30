// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.ImmersiveSystemBarsEffect
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ThemeManager
import com.ilyskyo.blancall.ui.theme.ThemeMode
import com.qmdeve.liquidglass.widget.LiquidGlassView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ========== 阅读背景 / 配色 ==========

/** 米白纸张色 */
private val PaperBeige = Color(0xFFF6F0E6)
private val PaperBeigeText = Color(0xFF3A342B)
private val PaperBeigeSub = Color(0xFF9A9184)

/** 纯白背景文字色 */
private val PaperWhiteText = Color(0xFF212121)
private val PaperWhiteSub = Color(0xFF9E9E9E)

/** 深色模式（纯黑）配色 */
private val DarkBg = Color(0xFF000000)
private val DarkText = Color(0xFFE8E6E1)
private val DarkSub = Color(0xFF8F8D88)

// 液态玻璃参数（参考 iOS 26 Liquid Glass 观感 + AndroidLiquidGlassView 官方区间调校：
// 中强模糊撑起毛玻璃质感、轻微色散、宽采样折射；配合高光描边 + 柔和投影）
private const val LgBlurRadius = 24f         // 0-50dp：模糊半径，苹果毛玻璃观感需要明显模糊
private const val LgDispersion = 0.45f       // 0-1：色散，轻微即可，过重显脏
private const val LgRefractionHeightDp = 26f // 12-50dp：折射采样高度
private const val LgRefractionOffsetDp = 90f // 20-120dp：采样偏移，越大折射过渡越自然

/**
 * 沉浸阅读模式。
 *
 * 特性：
 * - 全屏沉浸：隐藏状态栏/导航栏，点按屏幕中央切换悬浮控件显隐
 * - 正文包装为可采样 ViewGroup，悬浮栏叠加真实「液态玻璃」（折射+色散+模糊，
 *   Android 13+ 完整效果，低版本自动回退半透明玻璃兜底）
 * - 章节分页：长文按段落自动分段，左右滑动翻页
 * - 排版可调：字号/行距滑杆，米白/纯白/纯黑背景（浅色下可选纸张色，深色永远纯黑）
 * - 进度记忆：按整篇比例保存断点，退出时累计阅读时长
 */
@Composable
fun ReadingModeScreen(article: Article, onExit: () -> Unit, aiOcclusion: AiOcclusionBridge? = null) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── 主题明暗（与 BlancallTheme 一致的路由）──
    val themeMode by ThemeManager.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val appBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()
    // 段落首行缩进：全局开关 && 该文章来源允许缩进（PDF/Word 等不动）
    val autoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
    val autoIndent = autoIndentEnabled && article.autoIndent

    // ── 排版设置 ──
    val fontPx by AppPrefs.readingFontFlow.collectAsState()
    val lineHeight by AppPrefs.readingLineHeightFlow.collectAsState()
    val bgMode by AppPrefs.readingBgModeFlow.collectAsState()
    // 阅读布局：0=整篇滚动（默认，一滚到底） 1=章节翻页（左右滑动）
    val layoutMode by AppPrefs.readingLayoutModeFlow.collectAsState()
    // ── 阅读字体：预设 / 系统字体 / 导入字体（按 id 解析，见 ReaderFonts）──
    val readingFontId by AppPrefs.readingFontIdFlow.collectAsState()
    val readingFontFamily = remember(context, readingFontId) {
        ReaderFonts.resolveFontFamily(context, readingFontId) ?: FontFamily.Default
    }

    // ── 背诵遮挡（物理遮挡背诵）设置 ──
    val occlusionEnabled by AppPrefs.readingOcclusionEnabledFlow.collectAsState()
    val occlusionMode by AppPrefs.readingOcclusionModeFlow.collectAsState()
    // AI 遮挡仅在 Pro（注入了 [AiOcclusionBridge] 实现）且已配置 AI 时可用
    val aiOcclusionAvailable = aiOcclusion?.available == true
    // AI 遮挡区间（在整篇原文上的全局空；AI 模式且未返回时回退本地算法）
    var aiOcclusionRanges by remember { mutableStateOf<List<OcclusionSpan>?>(emptyList()) }
    LaunchedEffect(occlusionEnabled, occlusionMode, aiOcclusionAvailable, article.content) {
        if (!occlusionEnabled || occlusionMode != "ai" || !aiOcclusionAvailable) {
            aiOcclusionRanges = emptyList()
            return@LaunchedEffect
        }
        val res = runCatching { aiOcclusion?.generate(article.content) }.getOrNull()
        aiOcclusionRanges = res ?: emptyList()
    }

    // 阅读背景：深色永远纯黑；浅色按 跟随主题/米白/纯白 三选
    val bgColor = when {
        isDark -> DarkBg
        bgMode == 1 -> PaperBeige
        bgMode == 2 -> Color.White
        else -> if (appBeige) PaperBeige else Color.White
    }
    val textColor = when {
        isDark -> DarkText
        bgMode == 1 || (bgMode == 0 && appBeige) -> PaperBeigeText
        else -> PaperWhiteText
    }
    val subColor = when {
        isDark -> DarkSub
        bgMode == 1 || (bgMode == 0 && appBeige) -> PaperBeigeSub
        else -> PaperWhiteSub
    }
    // 强调色：浅色用主题主色，深色用柔和提亮蓝灰，保证纯黑底上可读
    val accentColor = when {
        isDark -> Color(0xFF7C9ED1)
        else -> MaterialTheme.colorScheme.primary
    }

    // ── 章节分页（按段落聚合，字号变化不影响章节划分）──
    val sections = remember(article.content) { buildReadingSections(article.content) }

    // 断点续读：按整篇比例定位（滚动模式恢复滚动偏移，翻页模式定位目标章节）
    val savedFraction = remember(article.id) { AppPrefs.getReadingPos(article.id) }
    val initialPage = remember(sections.size, savedFraction) {
        if (sections.isEmpty()) 0
        else (savedFraction * sections.size).toInt().coerceIn(0, sections.size - 1)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { sections.size }
    // 整篇滚动模式的滚动状态（翻页模式下闲置）
    val scrollState = rememberScrollState()

    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    // 组装遮挡渲染参数：AI 不可用/未返回时降级本地算法
    val occlusionParams = OcclusionParams(
        enabled = occlusionEnabled,
        mode = if (occlusionEnabled && occlusionMode == "ai" && aiOcclusionAvailable) "ai" else "local",
        aiRanges = aiOcclusionRanges ?: emptyList(),
        articleContent = article.content,
        onToggleControls = { controlsVisible = !controlsVisible }
    )
    // 遮挡模式下由正文段落接管「点空白切换悬浮控件」，避免与容器手势双触发
    val occlusionActive = occlusionEnabled
    // 当前节内的滚动比例（0~1），由每页回调上报，用于更细的进度条
    var inPageFraction by remember { mutableFloatStateOf(0f) }
    // 最近一次落盘的整篇进度（dispose 时回写，避免快照丢失）
    var lastSavedFraction by remember(article.id) { mutableFloatStateOf(savedFraction) }

    // 整篇滚动模式的滚动比例（0~1）；内容一屏放得下（无需滚动）视为已读满
    val scrollFraction by remember { derivedStateOf {
        if (scrollState.maxValue <= 0) 1f else scrollState.value.toFloat() / scrollState.maxValue
    } }
    // 当前整篇进度（滚动/翻页各自折算）
    // - 整篇滚动 / 单页翻页：内容无需滚动即视为读完，进度满条
    // - 多页翻页：按 (当前页+页内比例)/总页数，末页读完封顶 0.999（避免未读完显示 100%）
    val progressFraction by remember { derivedStateOf {
        when {
            layoutMode == 0 -> scrollFraction.coerceIn(0f, 1f)
            sections.size <= 1 -> inPageFraction.coerceIn(0f, 1f)
            else -> ((pagerState.currentPage + inPageFraction) / sections.size).coerceIn(0f, 0.999f)
        }
    } }

    // ── 阅读计时与进度持久化 ──
    val startTime = remember { System.currentTimeMillis() }
    val baseSeconds = remember(article.id) { AppPrefs.getReadingSeconds(article.id) }
    var liveSeconds by remember { mutableLongStateOf(baseSeconds) }
    // 上次计时落盘时刻，dispose 时只补剩余增量，避免与自动落盘重复累计
    val lastTickRef = remember { Ref(startTime) }
    fun persistPosition() {
        lastSavedFraction = progressFraction
        AppPrefs.setReadingPos(article.id, progressFraction)
    }
    // 预计剩余阅读时间：按当前位置到文末的剩余字数估算（约 350 字/分钟），随滚动/翻页实时联动
    val totalChars = article.content.length.coerceAtLeast(1)
    // 累计已阅读时长：只显示分钟（向下取整，不做四舍五入）
    val elapsedMinText by remember { derivedStateOf {
        val m = liveSeconds / 60
        if (m >= 1) "已阅读：$m 分钟" else "已阅读：不足 1 分钟"
    } }
    val estimatedRemainingText by remember { derivedStateOf {
        // 剩余比例 = 1 - 当前进度：滑回（进度后退）剩余时间随之变多，不依赖累计已读时长
        val remainRatio = (1f - progressFraction).coerceIn(0f, 1f)
        val remainSec = (totalChars * remainRatio / 350f * 60f).toLong()
        when {
            remainRatio <= 0.005f -> "已读完"
            remainSec < 60L -> "预计还需：不足 1 分钟"
            else -> "预计还需：${(remainSec / 60).coerceAtLeast(1)} 分钟"
        }
    } }
    // 每 20 秒自动落盘计时（防异常退出丢时间）
    LaunchedEffect(article.id) {
        while (true) {
            delay(20_000)
            val now = System.currentTimeMillis()
            val gain = (now - lastTickRef.value) / 1000L
            if (gain > 0) {
                AppPrefs.addReadingSeconds(article.id, gain)
                lastTickRef.value = now
            }
        }
    }
    // 显示用：每秒刷新累计时长（分钟跳变即时可见；落盘仍按 20s 节流，避免频繁写盘）
    LaunchedEffect(article.id) {
        while (true) {
            delay(1_000)
            liveSeconds = baseSeconds + (System.currentTimeMillis() - startTime) / 1000L
        }
    }
    // 页面切换即保存断点
    LaunchedEffect(pagerState.currentPage) { persistPosition() }
    // 滚动模式：滚动即更新待落盘进度（dispose/定时器统一回写，避免频繁写盘）
    LaunchedEffect(scrollState) {
        snapshotFlow { progressFraction }.collect { lastSavedFraction = it }
    }
    // 整篇滚动模式：进入时按断点比例恢复滚动位置（等首帧布局完成，maxValue 才有值）
    LaunchedEffect(Unit) {
        if (layoutMode == 0 && savedFraction > 0f) {
            val max = snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo((max * savedFraction).toInt())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val now = System.currentTimeMillis()
            val gain = (now - lastTickRef.value) / 1000L
            if (gain > 0) AppPrefs.addReadingSeconds(article.id, gain)
            AppPrefs.setReadingPos(article.id, lastSavedFraction)
        }
    }

    // ── 全屏沉浸：隐藏系统栏/手势线，上滑可临时唤出，退出恢复 ──
    // 统一走公共 ImmersiveSystemBarsEffect（含 ColorOS/低版本兜底 + Theme.kt 冲突规避）
    ImmersiveSystemBarsEffect(enabled = true)

    // 打开设置时返回键优先关闭设置面板
    BackHandler(enabled = settingsVisible) { settingsVisible = false }

    // 进入后 3 秒自动隐藏控件，营造沉浸感
    LaunchedEffect(Unit) {
        delay(3000)
        controlsVisible = false
    }

    // 液态玻璃采样源：正文所在的 ViewGroup（悬浮栏只采样它，避免自采样的反馈环）
    val sourceRef = remember { Ref<FrameLayout?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // ── 正文层：包装成真实 ViewGroup 供液态玻璃采样 ──
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { frame -> sourceRef.value = frame }.apply {
                    addView(
                        ComposeView(ctx).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                            setContent {
                                if (sections.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = accentColor)
                                    }
                                    return@setContent
                                }
                                if (layoutMode == 0) {
                                    // 整篇滚动：一屏滚到底，全文完整可达（默认模式）。
                                    // 遮挡模式下正文段落接管点按（揭示遮块/切换控件），容器不再抢手势
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(scrollState)
                                            .then(
                                                if (!occlusionActive) Modifier.pointerInput(Unit) {
                                                    detectTapGestures { controlsVisible = !controlsVisible }
                                                } else Modifier
                                            )
                                    ) {
                                        if (occlusionActive) {
                                            OccludedReadingContent(
                                                text = article.content,
                                                fontPx = fontPx,
                                                lineHeight = lineHeight,
                                                textColor = textColor,
                                                fontFamily = readingFontFamily,
                                                indent = autoIndent,
                                                isDark = isDark,
                                                occlusion = occlusionParams
                                            )
                                        } else {
                                            ReadingTextContent(
                                                text = article.content,
                                                fontPx = fontPx,
                                                lineHeight = lineHeight,
                                                textColor = textColor,
                                                fontFamily = readingFontFamily,
                                                indent = autoIndent
                                            )
                                        }
                                    }
                                } else {
                                    // 章节翻页：按段聚合分节，左右滑动
                                    HorizontalPager(
                                        state = pagerState,
                                        beyondViewportPageCount = 1,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(
                                                if (!occlusionActive) Modifier.pointerInput(Unit) {
                                                    detectTapGestures { controlsVisible = !controlsVisible }
                                                } else Modifier
                                            )
                                    ) { page ->
                                        ReadingSectionPage(
                                            text = sections[page],
                                            fontPx = fontPx,
                                            lineHeight = lineHeight,
                                            textColor = textColor,
                                            fontFamily = readingFontFamily,
                                            onScrollFraction = { inPageFraction = it },
                                            indent = autoIndent,
                                            occlusion = occlusionParams,
                                            isDark = isDark
                                        )
                                    }
                                }
                            }
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            // 双指捏合调整字号（与练习页同款手势，挂在正文层覆盖两种布局模式；
            // 单指滑动交给 pager/滚动容器，不冲突）
            modifier = Modifier
                .fillMaxSize()
                .pinchZoom { zoom ->
                    AppPrefs.readingFont = (AppPrefs.readingFont * zoom).coerceIn(14f, 24f)
                }
        )

        // ── 底部细进度条（常驻，液态玻璃胶囊）──
        ReadingProgressCapsule(
            fraction = progressFraction,
            isDark = isDark,
            accent = accentColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )
    }

    // ── 悬浮玻璃控件（点击屏幕切换显隐）──
    // 重要：本层 Box 必须**永远留在组合树**中，只做 alpha 显隐，绝不按条件移除——
    // 子树内含多个 AndroidView(LiquidGlassView)，移除时其 PreDraw 监听会在 detach
    // 状态下触发测量，抛 "LayoutNode should be attached to an owner"（已在真机复现两次）。
    // 隐藏时仅不组合可点击按钮，触摸自然穿透到正文层（正文 tap 再唤回控件）。
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(180),
        label = "ctrlAlpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .graphicsLayer { alpha = controlsAlpha }
    ) {
        // 顶部：单条玻璃胶囊栏（返回 + 标题 + 设置）——控件聚合为一条，iOS 风格
        LiquidGlassPill(
            sourceRef = sourceRef,
            isDark = isDark,
            cornerPx = LgCornerPx(23f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .height(46.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (controlsVisible) {
                    IconButton(onClick = onExit, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                            contentDescription = "退出阅读",
                            tint = subColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(38.dp))
                }
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                if (controlsVisible) {
                    IconButton(onClick = { settingsVisible = true }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.FormatSize,
                            contentDescription = "阅读设置",
                            tint = accentColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(38.dp))
                }
            }
        }

        // 底部：单条玻璃胶囊栏（章节导航[仅翻页] / 进度 / 预计剩余阅读时间）
        LiquidGlassPill(
            sourceRef = sourceRef,
            isDark = isDark,
            cornerPx = LgCornerPx(22f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // 上浮：让开底部细进度条（位于 bottom 14dp），避免重叠/贴屏底
                .padding(bottom = 34.dp)
                .height(46.dp)
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (controlsVisible && layoutMode == 1) {
                    IconButton(
                        onClick = {
                            if (pagerState.currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                            contentDescription = "上一节",
                            tint = subColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (layoutMode == 1) "${pagerState.currentPage + 1}/${sections.size}" else "全文",
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(progressFraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = elapsedMinText,
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor
                    )
                    Text(
                        text = estimatedRemainingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
                if (controlsVisible && layoutMode == 1) {
                    IconButton(
                        onClick = {
                            if (pagerState.currentPage < sections.size - 1) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        enabled = pagerState.currentPage < sections.size - 1,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = "下一节",
                            tint = subColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
            }
        }
    }

    // ── 阅读设置面板（液态玻璃底部面板）──
    ReadingSettingsSheet(
        visible = settingsVisible,
        onDismiss = { settingsVisible = false },
        fontPx = fontPx,
        onFontChange = { AppPrefs.readingFont = it },
        lineHeight = lineHeight,
        onLineHeightChange = { AppPrefs.readingLineHeight = it },
        bgMode = bgMode,
        onBgModeChange = { AppPrefs.readingBgMode = it },
        layoutMode = layoutMode,
        onLayoutModeChange = { AppPrefs.readingLayoutMode = it },
        fontId = readingFontId,
        onFontIdChange = { AppPrefs.readingFontId = it },
        occlusionEnabled = occlusionEnabled,
        onOcclusionEnabledChange = { AppPrefs.readingOcclusionEnabled = it },
        occlusionMode = occlusionMode,
        onOcclusionModeChange = { AppPrefs.readingOcclusionMode = it },
        occlusionAiAvailable = aiOcclusionAvailable,
        isDark = isDark,
        accent = accentColor
    )
}

// ========== 章节分页与进度辅助 ==========

/** 轻量可变引用（供闭包写入共享状态） */
private class Ref<T>(var value: T)

/** 单节目标字符数（分割太碎翻页频繁，太大失去章节感） */
private const val ReadingSectionTarget = 420

/**
 * 将正文按段落聚合为若干「节」：
 * - 先按空行拆段；超长段落再按单行拆小
 * - 小块按目标长度聚合，形成适合翻页的章节
 */
internal fun buildReadingSections(content: String): List<String> {
    val normalized = content.trim().replace("\r\n", "\n")
    if (normalized.isEmpty()) return emptyList()
    val paras = normalized.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.isEmpty()) return listOf(content.trim())

    val blocks = mutableListOf<String>()
    for (p in paras) {
        if (p.length > 500) {
            blocks += p.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            blocks += p
        }
    }
    if (blocks.isEmpty()) return listOf(content.trim())

    val sections = mutableListOf<String>()
    val current = StringBuilder()
    for (b in blocks) {
        if (current.isNotEmpty() && current.length + b.length > ReadingSectionTarget) {
            sections += current.toString().trim()
            current.setLength(0)
        }
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(b)
    }
    if (current.isNotBlank()) sections += current.toString().trim()
    return sections.ifEmpty { listOf(content.trim()) }
}

/** dp → px（液态玻璃参数用） */
@Composable
private fun LgCornerPx(dp: Float): Float = with(LocalDensity.current) { dp.dp.toPx() }

/**
 * 双指捏合缩放（与练习页同款）：仅当屏幕上有 ≥2 个触点时按缩放增量回调 [onZoomChange]，
 * 单指滑动完全交给下层滚动/翻页容器处理，不产生任何手势冲突。
 */
private fun Modifier.pinchZoom(onZoomChange: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var prevDist = -1f
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (prevDist > 0f && dist > 0f) {
                    val factor = dist / prevDist
                    if (factor.isFinite()) onZoomChange(factor)
                }
                prevDist = dist
            }
        } while (event.changes.any { it.pressed })
    }
}

// ========== 正文渲染（整篇滚动 / 章节翻页共用） ==========

/**
 * 正文段落渲染：按空行分段，段落间留出间距。
 * 不含滚动容器——由调用方决定外层是整篇滚动还是节内滚动。
 */
@Composable
private fun ReadingTextContent(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily = FontFamily.Default,
    indent: Boolean = true
) {
    val paragraphs = remember(text) { text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 40.dp)
    ) {
        paragraphs.forEachIndexed { index, para ->
            if (index > 0) Spacer(Modifier.height((fontPx * 0.9f).dp))
            Text(
                text = para,
                fontSize = fontPx.sp,
                lineHeight = (fontPx * lineHeight).sp,
                color = textColor,
                fontFamily = fontFamily,
                // 仅对允许缩进的来源统一补两格首行缩进（正文段落已 trim，天然避免重复叠加）；
                // PDF/Word 等来源或全局关闭时不缩进，保持原文
                style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(72.dp)) // 底部留白，避免最后一行被进度胶囊遮挡
    }
}

// ========== 章节单页（翻页模式） ==========

@Composable
private fun ReadingSectionPage(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    onScrollFraction: (Float) -> Unit,
    indent: Boolean = true,
    occlusion: OcclusionParams = OcclusionParams(enabled = false),
    isDark: Boolean = false
) {
    val scrollState = rememberScrollState()
    // 页内滚动比例上报（maxValue 变化时自动重算；无需滚动 → 视为已读完，直接 1f）
    val current by remember { derivedStateOf {
        if (scrollState.maxValue <= 0) 1f else scrollState.value.toFloat() / scrollState.maxValue
    } }
    LaunchedEffect(scrollState) {
        snapshotFlow { current }.collect { onScrollFraction(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        if (occlusion.enabled) {
            OccludedReadingContent(
                text = text,
                fontPx = fontPx,
                lineHeight = lineHeight,
                textColor = textColor,
                fontFamily = fontFamily,
                indent = indent,
                isDark = isDark,
                occlusion = occlusion
            )
        } else {
            ReadingTextContent(
                text = text,
                fontPx = fontPx,
                lineHeight = lineHeight,
                textColor = textColor,
                fontFamily = fontFamily,
                indent = indent
            )
        }
    }
}

// ========== 背诵遮挡正文渲染 ==========

/**
 * 背诵遮挡正文渲染：按空行分段，每段以 [OccludedParagraph] 绘制——
 * 底层面板是完整原文（保证换行/缩进与正常阅读一致），上方叠加圆角遮块，
 * 点一下遮块即像揭开挡卡一样露出原文。段内外观与 [ReadingTextContent] 完全对齐。
 */
@Composable
private fun OccludedReadingContent(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    indent: Boolean,
    isDark: Boolean,
    occlusion: OcclusionParams
) {
    val paragraphs = remember(text) { text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() } }
    val isAi = occlusion.mode == "ai"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 40.dp)
    ) {
        paragraphs.forEachIndexed { index, para ->
            if (index > 0) Spacer(Modifier.height((fontPx * 0.9f).dp))
            // 每段的遮挡空：AI 模式按整篇坐标映射到本段，否则（或映射失败）走本地算法
            val ranges = remember(para, occlusion) {
                ReaderOcclusion.rangesForPara(para, occlusion.articleContent, isAi, occlusion.aiRanges)
            }
            OccludedParagraph(
                text = para,
                hidden = ranges,
                fontPx = fontPx,
                lineHeight = lineHeight,
                textColor = textColor,
                fontFamily = fontFamily,
                indent = indent,
                isDark = isDark,
                onToggleControls = occlusion.onToggleControls
            )
        }
        Spacer(Modifier.height(72.dp)) // 底部留白，避免最后一行被进度胶囊遮挡
    }
}

// ========== 液态玻璃组件 ==========

/**
 * 单颗液态玻璃胶囊：底层半透明玻璃兜底（低版本），中层真实液态折射玻璃
 * （仅 API 33+ 渲染，采样 [sourceRef] 指向的正文容器），最上层绘制控件内容。
 *
 * 顺序关键：玻璃效果绘制在「控件内容」之下，保证图标/文字清晰不被折射模糊。
 */
@Composable
private fun LiquidGlassPill(
    sourceRef: Ref<FrameLayout?>,
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 持有真实 LiquidGlassView 引用：退出组合时 bind(null) 释放采样跟踪器（库内部 recycle），
    // 避免视图移除后 PreDraw 监听残留在 ViewTreeObserver 上。
    val glassViewRef = remember { Ref<LiquidGlassView?>(null) }
    // 柔和投影：玻璃悬浮的"离地感"（visionOS/iOS 26 玻璃元素都有软阴影）
    val density = LocalDensity.current
    val shape = remember(cornerPx) { RoundedCornerShape(with(density) { cornerPx.toDp() }) }
    Box(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.26f)
            )
    ) {
        // 1) 兜底玻璃板：半透明底色 + 顶部高光 + 描边（API<33 时的完整外观）
        FallbackGlassPlate(isDark = isDark, cornerPx = cornerPx, modifier = Modifier.matchParentSize())
        // 2) 真实液态玻璃（折射 + 色散 + 模糊，仅 Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val refractPx = with(LocalDensity.current) { LgRefractionHeightDp.dp.toPx() }
            val offsetPx = with(LocalDensity.current) { LgRefractionOffsetDp.dp.toPx() }
            AndroidView(
                factory = { ctx ->
                    LiquidGlassView(ctx).apply {
                        setCornerRadius(cornerPx)
                        setRefractionHeight(refractPx)
                        setRefractionOffset(offsetPx)
                        setBlurRadius(LgBlurRadius)
                        setDispersion(LgDispersion)
                        if (isDark) {
                            // 深色：暖黑玻璃，略微提透明度撑起质感
                            setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                            setTintAlpha(0.30f)
                        } else {
                            // 浅色：白玻璃轻染，让"玻璃"本身可见而不喧宾夺主
                            setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                            setTintAlpha(0.14f)
                        }
                        setDraggableEnabled(false)
                        setElasticEnabled(false)
                        setTouchEffectEnabled(false)
                        glassViewRef.value = this
                        // 延迟到本帧测量/布局完成后再绑定采样源：
                        // 挂载帧内同步 bind 会 invalidate + 重建采样配置，与正在进行的
                        // measure pass 竞争（真机复现 requireOwner 崩溃的诱因之一）。
                        post {
                            if (isAttachedToWindow && getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                                sourceRef.value?.let { bind(it) }
                                setTag(com.ilyskyo.blancall.R.id.lg_bound_tag, true)
                            }
                        }
                    }
                },
                update = { view ->
                    if (view.getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                        view.post {
                            if (view.isAttachedToWindow && view.getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                                sourceRef.value?.let { view.bind(it) }
                                view.setTag(com.ilyskyo.blancall.R.id.lg_bound_tag, true)
                            }
                        }
                    }
                },
                modifier = Modifier.matchParentSize()
            )
        }
        // 3) 控件内容：绘于玻璃之上
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { content() }
    }
    // 退出组合：解绑采样（bind(null) → 库内 LiquidTracker.recycle()），清除残留绘制监听
    DisposableEffect(Unit) {
        onDispose {
            runCatching { glassViewRef.value?.bind(null) }
        }
    }
}

// tag key 改用 res/values/ids.xml 中的 com.ilyskyo.blancall.R.id.lg_bound_tag：
// View.setTag(key, tag) 的 key 必须是应用资源 id，裸常量（如 0x4C47）会直接抛
// IllegalArgumentException("The key must be an application-specific resource id")，真机闪退。

/** 兜底玻璃板（半透明底色 + 高光描边 + 圆角，已移除顶部高光 verticalGradient 装饰） */
@Composable
private fun FallbackGlassPlate(
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerPx)
    val glassActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    // 真玻璃存在时底色极淡（质感交给折射层）；无玻璃时用半透明底撑住可读性
                    isDark -> if (glassActive) Color(0x1FFFFFFF) else Color(0xE61A1A1A)
                    else -> if (glassActive) Color(0x14FFFFFF) else Color(0xC8FFFFFF)
                }
            )
            // 高光描边：玻璃边缘的亮线（Apple 液态玻璃的标志性边缘），深色下提亮、浅色下用白
            .border(BorderStroke(1.dp, if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF)), shape)
    )
}

/** 底部细进度胶囊（阅读进度） */
@Composable
private fun ReadingProgressCapsule(
    fraction: Float,
    isDark: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(168.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (isDark) Color(0x33FFFFFF) else Color(0x26000000))
    ) {
        val animated = animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(220),
            label = "readProgress"
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated.value)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.85f))
        )
    }
}

// ========== 阅读设置面板 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSettingsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    fontPx: Float,
    onFontChange: (Float) -> Unit,
    lineHeight: Float,
    onLineHeightChange: (Float) -> Unit,
    bgMode: Int,
    onBgModeChange: (Int) -> Unit,
    layoutMode: Int,
    onLayoutModeChange: (Int) -> Unit,
    fontId: String,
    onFontIdChange: (String) -> Unit,
    occlusionEnabled: Boolean,
    onOcclusionEnabledChange: (Boolean) -> Unit,
    occlusionMode: String,
    onOcclusionModeChange: (String) -> Unit,
    occlusionAiAvailable: Boolean,
    isDark: Boolean,
    accent: Color
) {
    if (!visible) return
    val context = LocalContext.current

    // ── 字体候选：预置 + 系统字体（IO 线程扫描，避免卡顿）+ 导入字体 ──
    val sysFonts by produceState<List<ReaderFont>>(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) { ReaderFonts.scanSystemFonts() }
    }
    var imported by remember { mutableStateOf(ReaderFonts.listImportedFonts(context)) }
    val allFonts = remember(sysFonts, imported) { ReaderFonts.presets + sysFonts + imported }
    val currentFontName = remember(allFonts, fontId) {
        allFonts.firstOrNull { it.id == fontId }?.name ?: "默认"
    }
    var fontsExpanded by remember { mutableStateOf(false) }

    // 导入字体：系统文件选择器，导入成功后自动选中并刷新列表
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "字体"
        val importedFont = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { ReaderFonts.importFont(context, displayName, it) }
        }.getOrNull() ?: null
        if (importedFont != null) {
            imported = ReaderFonts.listImportedFonts(context)
            onFontIdChange(importedFont.id)
        }
    }

    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (isDark) Color(0x66FFFFFF) else Color(0x33000000)))
        } }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleMedium,
                color = if (isDark) DarkText else PaperWhiteText,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(20.dp))

            // 字号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FormatSize,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "字号 ${fontPx.roundToInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
            }
            Slider(
                value = fontPx,
                onValueChange = onFontChange,
                valueRange = 14f..24f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                )
            )

            Spacer(Modifier.height(8.dp))

            // 行距
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FormatLineSpacing,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "行距 ${(lineHeight * 10).roundToInt() / 10f}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
            }
            Slider(
                value = lineHeight,
                onValueChange = onLineHeightChange,
                valueRange = 1.4f..2.4f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                )
            )

            Spacer(Modifier.height(16.dp))

            // ── 字体：点击展开候选列表（预置 / 系统 / 导入），可从系统选择器导入 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { fontsExpanded = !fontsExpanded }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "字体",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = currentFontName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkSub else PaperWhiteSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = fontsExpanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 4.dp)
                ) {
                    // 预置
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderFonts.presets.forEach { f ->
                            val selected = fontId == f.id
                            FilterChip(
                                selected = selected,
                                onClick = { onFontIdChange(f.id) },
                                label = { Text(f.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = when {
                                        selected && isDark -> Color(0x334B5563)
                                        selected -> accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                    labelColor = if (isDark) DarkText else PaperWhiteText,
                                    selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected,
                                    borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                    selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    // 系统字体
                    if (sysFonts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "系统字体（${sysFonts.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) DarkSub else PaperWhiteSub
                        )
                        Spacer(Modifier.height(4.dp))
                        sysFonts.forEach { f ->
                            FontPickerRow(
                                name = f.name,
                                selected = fontId == f.id,
                                isDark = isDark,
                                accent = accent,
                                onClick = { onFontIdChange(f.id) }
                            )
                        }
                    }
                    // 导入字体（可删除）
                    if (imported.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "导入字体（${imported.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) DarkSub else PaperWhiteSub
                        )
                        Spacer(Modifier.height(4.dp))
                        imported.forEach { f ->
                            FontPickerRow(
                                name = f.name,
                                selected = fontId == f.id,
                                isDark = isDark,
                                accent = accent,
                                onClick = { onFontIdChange(f.id) },
                                trailing = {
                                    if (fontId != f.id) {
                                        IconButton(
                                            onClick = {
                                                if (ReaderFonts.deleteImportedFont(context, f.id)) {
                                                    imported = ReaderFonts.listImportedFonts(context)
                                                    if (fontId == f.id) onFontIdChange("0")
                                                }
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "删除字体",
                                                tint = if (isDark) Color(0x99FF7A7A) else Color(0xFFD9534F),
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    // 导入入口
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                importLauncher.launch(
                                    arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")
                                )
                            }
                            .padding(vertical = 11.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "导入字体",
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 布局：整篇滚动 / 章节翻页
            Text(
                "布局",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) DarkText else PaperWhiteText
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "整篇滚动", 1 to "章节翻页").forEach { (mode, label) ->
                    val selected = layoutMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onLayoutModeChange(mode) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = when {
                                selected && isDark -> Color(0x334B5563)
                                selected -> accent.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            },
                            labelColor = if (isDark) DarkText else PaperWhiteText,
                            selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                            selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 背景选择（深色模式永远纯黑，浅色项禁用）
            Text(
                "背景",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) DarkText else PaperWhiteText
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "跟随主题", 1 to "米白", 2 to "纯白").forEach { (mode, label) ->
                    val selected = bgMode == mode
                    val enabled = !isDark || mode == 0
                    FilterChip(
                        selected = selected,
                        onClick = { if (enabled) onBgModeChange(mode) },
                        enabled = enabled,
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = when {
                                selected && isDark -> Color(0x334B5563)
                                selected -> accent.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            },
                            labelColor = if (isDark) DarkText else PaperWhiteText,
                            selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = enabled,
                            selected = selected,
                            borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                            selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                        )
                    )
                }
            }
            if (isDark) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "深色模式阅读背景为纯黑，仅支持跟随主题",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkSub
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 背诵遮挡：开关 + 算法子项（AI / 本地） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "背诵遮挡",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDark) DarkText else PaperWhiteText
                    )
                    Text(
                        "点按遮块揭开原文，辅助背诵",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) DarkSub else PaperWhiteSub
                    )
                }
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = occlusionEnabled,
                    onCheckedChange = { v -> onOcclusionEnabledChange(v) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent,
                        uncheckedThumbColor = if (isDark) Color(0xFFB4B4B4) else Color(0xFFE9E9E9),
                        uncheckedTrackColor = if (isDark) Color(0x33FFFFFF) else Color(0x1F000000)
                    )
                )
            }
            // 开启后浮现两个算法子项
            AnimatedVisibility(visible = occlusionEnabled) {
                Column(Modifier.padding(top = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val options = buildList {
                            if (occlusionAiAvailable) add("ai" to "AI 遮挡")
                            add("local" to "本地算法遮挡")
                        }
                        options.forEach { (m, label) ->
                            val selected = occlusionMode == m
                            FilterChip(
                                selected = selected,
                                onClick = { onOcclusionModeChange(m) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (m == "ai") {
                                            Text(label)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Pro",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = accent,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(accent.copy(alpha = 0.14f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        } else {
                                            Text(label)
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = when {
                                        selected && isDark -> Color(0x334B5563)
                                        selected -> accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                    labelColor = if (isDark) DarkText else PaperWhiteText,
                                    selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected,
                                    borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                    selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    if (!occlusionAiAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AI 遮挡需在 AI 设置中配置并开启后可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) DarkSub else PaperWhiteSub
                        )
                    }
                }
            }
        }
    }
}

/**
 * 字体列表的单行选择项：选中态用强调色高亮。可选的尾部内容（如删除）。
 */
@Composable
private fun FontPickerRow(
    name: String,
    selected: Boolean,
    isDark: Boolean,
    accent: Color,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) accent else (if (isDark) DarkText else PaperWhiteText),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}