// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.ReaderPrefs
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.ImmersiveSystemBarsEffect
import com.ilyskyo.blancall.ui.common.ReadingMaxWidth
import com.ilyskyo.blancall.ui.common.pinchZoomByTouch
import com.ilyskyo.blancall.ui.common.tapGesturesPenAware
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
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
internal val PaperWhiteText = Color(0xFF212121)
internal val PaperWhiteSub = Color(0xFF9E9E9E)

/** 深色模式（纯黑）配色 */
private val DarkBg = Color(0xFF000000)
internal val DarkText = Color(0xFFE8E6E1)
internal val DarkSub = Color(0xFF8F8D88)

// 液态玻璃参数 —— 与底部导航栏（BottomNavBar 的 LgBar*）保持同一套调校，
// 保证全 App 玻璃质感统一；本组数值即取自导航栏的实机观感。
// 要点：模糊要轻（重模糊会把内容糊成一团、显脏），折射真实采样即可撑起玻璃感；
// 配合 FallbackGlassPlate 的高光描边 + 柔和投影完成"离地感"。
internal const val LgBlurRadius = 6f          // 0-50dp：轻模糊，与导航栏 LgBarBlur 一致
internal const val LgDispersion = 0.3f        // 0-1：色散，降低避免边缘彩边过重
internal const val LgRefractionHeightDp = 20f // 12-50dp：折射采样高度（对齐导航栏 20dp）
internal const val LgRefractionOffsetDp = 70f // 20-120dp：采样偏移（对齐导航栏 70dp）

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingModeScreen(article: Article, onExit: () -> Unit) {
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

    // ── 排版设置：**按文章独立保存**（ReaderPrefsStore）──
    // 有存档用存档；没有则以全局 AppPrefs 的当前值作为起点（兼容既有偏好）。
    // 所有改动只写本文章的存档，不再污染全局基线
    // （真机反馈：在 A 文章调完「米白+霞雾文楷+超大+遮挡」，打开 B 也带同一套）。
    val prefsStore = remember { ReaderPrefsStore.getInstance(context) }
    val articleKey = article.id
    // readerPrefsState 实例稳定、可被下方「正文独立 ComposeView」跨组合订阅（桥接）——
    // 因此用 val State + by 委托，而不是把 State 藏在委托里。
    val readerPrefsState = remember(articleKey) {
        mutableStateOf(
            prefsStore.get(articleKey) ?: ReaderPrefs(
                bgMode = AppPrefs.readingBgMode,
                fontId = AppPrefs.readingFontId,
                fontWeight = AppPrefs.readingFontWeight,
                fontPx = AppPrefs.readingFont,
                lineHeight = AppPrefs.readingLineHeight,
                layoutMode = AppPrefs.readingLayoutMode,
                occlusionEnabled = AppPrefs.readingOcclusionEnabled,
                occlusionMode = AppPrefs.readingOcclusionMode,
                occlusionColor = AppPrefs.readingOcclusionColor,
                occlusionCustomConfigId = AppPrefs.readingOcclusionCustomConfigId
            )
        )
    }
    var readerPrefs by readerPrefsState
    fun updateReaderPrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        // 基准取 store 最新值（而非内存快照）：遮挡浮层（自定义配置列表/编辑器）内的
        // 「使用 / 保存 / 删除」只写 store + 磁盘，若基于旧内存对象整体回写，会把浮层
        // 刚写入的字段（自定义配置 id / 粒度 / 开关）覆盖回旧值 —— 表现为「选过自定义
        // 配置后一改设置就失效、配置名也丢」（真机反馈）。
        val next = transform(loadArticleReaderPrefs(prefsStore, articleKey))
        readerPrefs = next
        prefsStore.save(articleKey, next)
    }

    // ── 排版设置（读取自本文章存档）──
    val fontPx = readerPrefs.fontPx
    val lineHeight = readerPrefs.lineHeight
    val bgMode = readerPrefs.bgMode
    // 阅读布局：0=整篇滚动（默认，一滚到底） 1=章节翻页（左右滑动）
    val layoutMode = readerPrefs.layoutMode
    // ── 阅读字体：预设 / 系统字体 / 导入字体（按 id 解析，见 ReaderFonts）──
    val readingFontId = readerPrefs.fontId
    val readingFontWeight = readerPrefs.fontWeight

    // ── 背诵遮挡（物理遮挡背诵）设置 ──
    val occlusionEnabled = readerPrefs.occlusionEnabled
    val occlusionMode = readerPrefs.occlusionMode
    // 挡片颜色索引（正文体在独立 ComposeView 中自行订阅计算，见 body 内 bodyMaskColor）
    val occlusionColorIndex = readerPrefs.occlusionColor

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
    // 遮挡自定义浮层：null=无 / "list"=配置列表 / "edit"=编辑器（编辑器从列表进出）
    var maskOverlay by remember { mutableStateOf<String?>(null) }
    var maskEditConfigId by remember { mutableLongStateOf(-1L) }
    // 编辑会话 token：每次从列表进入编辑（含新建）自增，并 key 到编辑页上 ——
    // 不同会话的 rememberSaveable 槽位完全隔离，防上一套配置的编辑现场 / 保存目标
    // 泄漏到下一次编辑（用户反馈「点旧配置进去内容不对、保存没进去」）。
    // ⚠️ 必须 rememberSaveable（而非 remember）：进程被系统回收后恢复时，若 token 归零
    // 重走 1、2、3…，会再次命中「上次被杀时的会话槽位」——编辑页的 rememberSaveable
    // （savedId / 编辑现场快照 / 名称）被跨会话注入，出现「保存不生效、回填丢失」。
    // saveable 保证 token 单调向前、从不复用已消费的槽位键。
    var maskEditSession by rememberSaveable { mutableLongStateOf(0L) }
    // 当前「使用中」的自定义遮挡配置名：设置面板该粒度的 chip 直接以它显示
    //（无配置时 chip 回退默认文案「自定义」）。列表/编辑器关闭或切换会话后重新读取
    //（prefs 存的是 configId，名字需从 store 反查）
    var maskConfigName by remember { mutableStateOf("") }
    LaunchedEffect(maskOverlay, maskEditSession, article.id, readerPrefs.occlusionCustomConfigId) {
        val id = readerPrefs.occlusionCustomConfigId
        maskConfigName = if (id > 0) {
            withContext(Dispatchers.IO) {
                runCatching {
                    MaskConfigStore.getInstance(context.filesDir)
                        .getConfigs(article.id)
                        .firstOrNull { it.id == id }?.name
                }.getOrNull().orEmpty()
            }
        } else ""
    }
    // 浮层（列表/编辑器）开启/关闭/切换会话时，从 store 重读本文章阅读设置：
    // 浮层内的写入（使用配置 / 保存新建 / 删除清指向）落在 store + 磁盘，阅读页内存态
    // 不会自动感知 —— 不同步则正文仍按旧 id/粒度解析（自定义遮挡不生效）、chip 显示旧
    // 配置名或不显示。store.get 读缓存，浮层写入立即可见；值相同则不动状态（防抖）。
    LaunchedEffect(maskOverlay, maskEditSession) {
        val fresh = withContext(Dispatchers.IO) { prefsStore.get(articleKey) }
        if (fresh != null && fresh != readerPrefsState.value) readerPrefsState.value = fresh
    }
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
    // 统一走公共 ImmersiveSystemBarsEffect（含定制 ROM/低版本兜底 + Theme.kt 冲突规避）。
    // 遮挡自定义浮层（列表/编辑器）打开期间临时退出沉浸：这些是常规页面版式，
    // 需要状态栏 inset（否则顶栏顶到屏幕上缘/挖孔下），关闭浮层后自动恢复沉浸
    ImmersiveSystemBarsEffect(enabled = maskOverlay == null)

    // 打开设置时返回键优先关闭设置面板
    BackHandler(enabled = maskOverlay != null) {
        if (maskOverlay == "edit") maskOverlay = "list" else maskOverlay = null
    }
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
            .graphicsLayer {
                shape = RoundedCornerShape(16.dp)
                clip = true
            }
    ) {
        // ── 正文层：包装成真实 ViewGroup 供液态玻璃采样 ──
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { frame -> sourceRef.value = frame }.apply {
                    clipChildren = true
                    clipToPadding = true
                    addView(
                        ComposeView(ctx).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                            setContent {
                                // ── 正文组合直接订阅阅读设置流 ──
                                // ComposeView 内部是独立组合，不随外层重组重跑；
                                // 这里直接订阅 AppPrefs 流，任何设置变更（含遮挡开关）都即时驱动正文重组
                                val bodyThemeMode by ThemeManager.themeMode.collectAsState()
                                val bodySystemDark = isSystemInDarkTheme()
                                val bodyIsDark = when (bodyThemeMode) {
                                    ThemeMode.SYSTEM -> bodySystemDark
                                    ThemeMode.DARK -> true
                                    ThemeMode.LIGHT -> false
                                }
                                val bodyAppBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()
                                // 正文组合订阅「本文章的阅读设置」桥接 State：
                                // 旧实现直接订阅 AppPrefs 全局流 —— 改文章后所有文章跟着变（真机反馈）；
                                // 现读顶层 readerPrefsState（实例稳定，跨组合可用）。
                                val bodyReaderPrefs by readerPrefsState
                                val bodyFontPx = bodyReaderPrefs.fontPx
                                val bodyLineHeight = bodyReaderPrefs.lineHeight
                                val bodyBgMode = bodyReaderPrefs.bgMode
                                val bodyAutoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
                                val bodyLayoutMode = bodyReaderPrefs.layoutMode
                                val bodyFontId = bodyReaderPrefs.fontId
                                val bodyFontWeight = bodyReaderPrefs.fontWeight
                                val bodyOcclusionEnabled = bodyReaderPrefs.occlusionEnabled
                                val bodyOcclusionMode = bodyReaderPrefs.occlusionMode
                                // 挡片颜色：正文组合内订阅，切换即时生效（ComposeView 不随外层重组）
                                val bodyOcclusionColorIndex = bodyReaderPrefs.occlusionColor
                                val bodyMaskColor = when (bodyOcclusionColorIndex) {
                                    1 -> Macaron.continueP().fill
                                    2 -> Macaron.info().fill
                                    3 -> Macaron.warn().fill
                                    4 -> Macaron.lavender().fill
                                    5 -> Macaron.neutral().fill
                                    else -> Macaron.review().fill
                                }
                                val bodyFontFamily = remember(context, bodyFontId, bodyFontWeight) {
                                    // 字重直接体现在 fontFamily 里（霞鹜文楷按字重选对应字体文件）
                                    ReaderFonts.resolveFontFamily(context, bodyFontId, bodyFontWeight)
                                        ?: FontFamily.Default
                                }
                                // 自定义遮挡：custom 粒度时按文章读取使用中的配置，
                                // 解析为「段落文本 → 遮块列表」查找器（段落口径 splitParagraphs，
                                // 翻页/滚动两种布局下均按段文本匹配，重复段落取并集）
                                val bodyCustomConfigId = bodyReaderPrefs.occlusionCustomConfigId
                                var bodyCustomResolver by remember {
                                    mutableStateOf<((String) -> List<MaskConfigStore.MaskSpan>)?>(null)
                                }
                                // 以 store 修订号参与 key：编辑器里重存配置后回阅读页，遮挡立即刷新
                                val maskStore = remember { MaskConfigStore.getInstance(context.filesDir) }
                                var maskRevision by remember { mutableStateOf(maskStore.getRevision()) }
                                LaunchedEffect(Unit) {
                                    // 轮询修订号（浮层与正文不在同一组合，无法直接共享状态）
                                    while (true) {
                                        val r = maskStore.getRevision()
                                        if (r != maskRevision) maskRevision = r
                                        kotlinx.coroutines.delay(300)
                                    }
                                }
                                LaunchedEffect(bodyOcclusionMode, bodyCustomConfigId, article.id, maskRevision) {
                                    if (bodyOcclusionMode != "custom") {
                                        bodyCustomResolver = null
                                    } else {
                                        val cfg = withContext(Dispatchers.IO) {
                                            maskStore.getConfigs(article.id)
                                                .firstOrNull { it.id == bodyCustomConfigId }
                                        }
                                        if (cfg == null) {
                                            bodyCustomResolver = null
                                        } else {
                                            val byIdx = cfg.spans.groupBy { it.p }
                                            // 段落文本 → 配置遮块：整段精确匹配（滚动/翻页常规情形）之外，
                                            // 翻页布局会把超长段落按行拆成片段，再做「包含匹配 + 区间平移」兜底
                                            val articleParas = ReaderOcclusion.splitParagraphs(article.content)
                                            val exact = articleParas
                                                .withIndex()
                                                .groupBy({ it.value.text }, { it.index })
                                            val resolver: (String) -> List<MaskConfigStore.MaskSpan> = { paraText ->
                                                val idxs = exact[paraText]
                                                if (idxs != null) {
                                                    idxs.flatMap { byIdx[it].orEmpty() }
                                                        .distinctBy { s -> Triple(s.a, s.e, s.c) }
                                                } else {
                                                    // 片段：找到包含它的原段落，把遮块裁剪/平移到片段坐标系
                                                    articleParas
                                                        .asSequence()
                                                        .mapIndexedNotNull { idx, p ->
                                                            val off = p.text.indexOf(paraText)
                                                            if (off >= 0 && paraText.length >= 4) idx to off else null
                                                        }
                                                        .firstOrNull()
                                                        ?.let { (paraIdx, off) ->
                                                            byIdx[paraIdx].orEmpty()
                                                                .mapNotNull { s ->
                                                                    val a = s.a - off
                                                                    val e = s.e - off
                                                                    if (a >= 0 && e <= paraText.length && e > a)
                                                                        MaskConfigStore.MaskSpan(0, a, e, s.c) else null
                                                                }
                                                        }
                                                        .orEmpty()
                                                }
                                            }
                                            bodyCustomResolver = resolver
                                        }
                                    }
                                }
                                val bodyTextColor = when {
                                    bodyIsDark -> DarkText
                                    bodyBgMode == 1 || (bodyBgMode == 0 && bodyAppBeige) -> PaperBeigeText
                                    else -> PaperWhiteText
                                }
                                val bodyIndent = bodyAutoIndentEnabled && article.autoIndent
                                val bodyOcclusionActive = bodyOcclusionEnabled
                                val bodyOcclusion = OcclusionParams(
                                    enabled = bodyOcclusionEnabled,
                                    mode = bodyOcclusionMode,
                                    onToggleControls = { controlsVisible = !controlsVisible }
                                )
                                if (sections.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = accentColor)
                                    }
                                    return@setContent
                                }
                                if (bodyLayoutMode == 0) {
                                    // 整篇滚动：一屏滚到底，全文完整可达（默认模式）。
                                    // 遮挡模式下正文段落接管点按（揭示遮块/切换控件），容器不再抢手势
                                    Box(Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(scrollState)
                                            // 大屏限宽：平板横屏下单行会到 100+ 字符，眼睛回扫极易串行。
                                            // 限宽后左右留白、正文居中，符合中文排版 25–35 字/行的舒适区。
                                            .widthIn(max = ReadingMaxWidth)
                                            .align(Alignment.TopCenter)
                                            .then(
                                                if (!bodyOcclusionActive) Modifier.tapGesturesPenAware {
                                                        controlsVisible = !controlsVisible
                                                    } else Modifier
                                            )
                                    ) {
                                        // 文章标题、作者统一显示在顶部液态玻璃控件（LiquidGlassPill 内），
                                        // 正文里不再单独放作者，避免作者信息挤在正文最上方。
                                        if (bodyOcclusionActive) {
                                            OccludedReadingContent(
                                                text = article.content,
                                                fontPx = bodyFontPx,
                                                lineHeight = bodyLineHeight,
                                                textColor = bodyTextColor,
                                                fontFamily = bodyFontFamily,
                                                indent = bodyIndent,
                                                maskColor = bodyMaskColor,
                                                occlusion = bodyOcclusion,
                                                customSpansResolver = bodyCustomResolver
                                            )
                                        } else {
                                            ReadingTextContent(
                                                text = article.content,
                                                fontPx = bodyFontPx,
                                                lineHeight = bodyLineHeight,
                                                textColor = bodyTextColor,
                                                fontFamily = bodyFontFamily,
                                                indent = bodyIndent
                                            )
                                        }
                                    }
                                    }
                                } else {
                                    // 章节翻页：按段聚合分节，左右滑动。
                                    // 大屏下同样限宽（翻页模式正文更不该铺满超宽屏）。
                                    Box(Modifier.fillMaxSize()) {
                                    HorizontalPager(
                                        state = pagerState,
                                        beyondViewportPageCount = 1,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            // 大屏限宽：平板横屏下单行会到 100+ 字符，眼睛回扫极易串行。
                                            // 限宽后左右留白、正文居中，符合中文排版 25–35 字/行的舒适区。
                                            .widthIn(max = ReadingMaxWidth)
                                            .align(Alignment.TopCenter)
                                            .then(
                                                if (!bodyOcclusionActive) Modifier.tapGesturesPenAware {
                                                        controlsVisible = !controlsVisible
                                                    } else Modifier
                                            )
                                    ) { page ->
                                        ReadingSectionPage(
                                            text = sections[page],
                                            fontPx = bodyFontPx,
                                            lineHeight = bodyLineHeight,
                                            textColor = bodyTextColor,
                                            fontFamily = bodyFontFamily,
                                            onScrollFraction = { inPageFraction = it },
                                            indent = bodyIndent,
                                            maskColor = bodyMaskColor,
                                            occlusion = bodyOcclusion,
                                            customSpansResolver = bodyCustomResolver,
                                            header = if (page == 0 && article.author.isNotBlank()) {
                                                {
                                                    Text(
                                                        text = article.author.trim(),
                                                        fontSize = (bodyFontPx * 0.9f).sp,
                                                        lineHeight = (bodyLineHeight * 0.95f).sp,
                                                        color = bodyTextColor.copy(alpha = 0.62f),
                                                        fontFamily = bodyFontFamily,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 10.dp)
                                                    )
                                                }
                                            } else null
                                        )
                                    }
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
                    // 字号改动同样只写本文章存档（原写全局：会让所有文章跟着变）
                    updateReaderPrefs { p -> p.copy(fontPx = (p.fontPx * zoom).coerceIn(14f, 36f)) }
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
    // 胶囊触摸拦截：玻璃显示时其区域点击 = 隐藏控件（且下层正文不可点，保持现状）；
    // 玻璃消失后 modifier 退化为空 → 胶囊区域完全穿透，正文可点。
    // 不用 indication（无涟漪），不改玻璃样貌。
    val pillTouchInteraction = remember { MutableInteractionSource() }
    val pillTouchModifier = if (controlsVisible) {
        Modifier.clickable(
            interactionSource = pillTouchInteraction,
            indication = null
        ) { controlsVisible = false }
    } else Modifier
    // 控件层整体 z 序随显隐切换：显示时悬浮在正文之上（拦截其区域点击）；
    // 隐藏时 zIndex=-1 垫到正文之下——正文层先命中，玻璃区域的挡块/翻页/滚动
    // 全部恢复可点（仅靠 INVISIBLE 玻璃不足以穿透，真机复现）。
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .graphicsLayer { alpha = controlsAlpha }
            .zIndex(if (controlsVisible) 0f else -1f)
    ) {
        // 顶部：单条玻璃胶囊栏（返回 + 标题 + 设置）——控件聚合为一条，系统原生风格
        // 外层 Box 负责横屏时水平居中定位；内层胶囊约束最大宽度
        LiquidGlassPill(
            sourceRef = sourceRef,
            isDark = isDark,
            cornerPx = LgCornerPx(23f),
            touchAlpha = controlsAlpha,
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .height(46.dp)
                .align(Alignment.TopCenter)
                .then(pillTouchModifier)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (controlsVisible) {
                    GlassIconButton(onClick = onExit, buttonSize = 38.dp) {
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
                    text = if (article.author.isNotBlank()) {
                        "${article.title} · ${article.author.trim()}"
                    } else {
                        article.title
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                if (controlsVisible) {
                    GlassIconButton(onClick = { settingsVisible = true }, buttonSize = 38.dp) {
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
        // 外层 Box 负责横屏时水平居中定位；内层胶囊约束最大宽度
        LiquidGlassPill(
            sourceRef = sourceRef,
            isDark = isDark,
            cornerPx = LgCornerPx(22f),
            touchAlpha = controlsAlpha,
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 34.dp)
                .height(46.dp)
                .align(Alignment.BottomCenter)
                .then(pillTouchModifier)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (controlsVisible && layoutMode == 1) {
                    GlassIconButton(
                        onClick = {
                            if (pagerState.currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        buttonSize = 36.dp
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
                        text = estimatedRemainingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                    Text(
                        text = elapsedMinText,
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor
                    )
                }
                if (controlsVisible && layoutMode == 1) {
                    GlassIconButton(
                        onClick = {
                            if (pagerState.currentPage < sections.size - 1) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        enabled = pagerState.currentPage < sections.size - 1,
                        buttonSize = 36.dp
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
            }  // close LiquidGlassPill content
    }

    // ── 阅读设置面板（液态玻璃底部面板）──
    // sheetState 外提以便统一管理面板状态；面板内的「自定义」入口采用
    // 「挂浮层 + 立即关闭面板」的原子切换（见 ReadingSettingsSheet 内注释），
    // 不再依赖 hide() 动画回调，避免模态窗口滞留挡住浮层/吞掉点击。
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ReadingSettingsSheet(
        visible = settingsVisible,
        sheetState = settingsSheetState,
        onDismiss = { settingsVisible = false },
        fontPx = fontPx,
        onFontChange = { v -> updateReaderPrefs { p -> p.copy(fontPx = v) } },
        lineHeight = lineHeight,
        onLineHeightChange = { v -> updateReaderPrefs { p -> p.copy(lineHeight = v) } },
        bgMode = bgMode,
        onBgModeChange = { v -> updateReaderPrefs { p -> p.copy(bgMode = v) } },
        layoutMode = layoutMode,
        onLayoutModeChange = { v -> updateReaderPrefs { p -> p.copy(layoutMode = v) } },
        fontId = readingFontId,
        onFontIdChange = { newId ->
            // 换字体后把字重收敛到新字体支持的档位（预设/导入/系统字体三条路径共用此入口）：
            // 例如从霞鹜文楷的「中等 500」切到单字重字体 → 取最近的「加粗 700」
            updateReaderPrefs { p ->
                p.copy(
                    fontId = newId,
                    fontWeight = ReaderFonts.snapWeight(p.fontWeight, ReaderFonts.weightMode(newId))
                )
            }
        },
        fontWeight = readingFontWeight,
        onFontWeightChange = { v -> updateReaderPrefs { p -> p.copy(fontWeight = v) } },
        occlusionEnabled = occlusionEnabled,
        onOcclusionEnabledChange = { v -> updateReaderPrefs { p -> p.copy(occlusionEnabled = v) } },
        occlusionMode = occlusionMode,
        onOcclusionModeChange = { v -> updateReaderPrefs { p -> p.copy(occlusionMode = v) } },
        occlusionColorIndex = occlusionColorIndex,
        onOcclusionColorChange = { v -> updateReaderPrefs { p -> p.copy(occlusionColor = v) } },
        onOpenMaskConfig = {
            // 只负责把浮层挂上（藏在面板后）；面板本身的关闭由调用方在 hide 动画完成后执行
            maskOverlay = "list"
        },
        maskConfigName = maskConfigName,
        isDark = isDark,
        accent = accentColor
    )

    // ── 遮挡自定义浮层：配置列表 / 编辑器（覆盖整个阅读界面，系统返回逐级退出）──
    // 用 Dialog 承载：GlassModalBottomSheet 内部是 Material3 ModalBottomSheet（独立 Dialog window），
    // 其 window 的销毁滞后于组合树的移除。点击「自定义」时 sheet 会被立即移出组合树，但它的窗口在
    // 一小段时间内仍浮于 Activity 内容之上 —— Compose 内浮层用的 zIndex 只在同一 window 内比较层级，
    // 压不过这个残留窗口，于是被遮挡并可能吞掉点击，表现为「点自定义后要再点一下才进得去」。
    // 改为 Dialog 后本浮层也是独立 window，且创建时间晚于 sheet 的 Dialog，层级天然更高、立即显示。
    if (maskOverlay != null) {
        Dialog(
            // 系统返回键逐级退出：编辑器 → 列表 → 关闭。
            // 走 Dialog 的 onDismissRequest（配合下方 dismissOnBackPress），而不是在 Dialog 内容里
            // 注册 BackHandler：Compose Dialog 的返回键由 dialog 窗口自身处理（DialogWrapper 直接回调
            // onDismissRequest），不经过 Activity 的 OnBackPressedDispatcher，BackHandler 并不可靠。
            onDismissRequest = {
                if (maskOverlay == "edit") maskOverlay = "list" else maskOverlay = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,   // 允许铺满屏幕
                dismissOnBackPress = true,          // 返回键逐级退出（见上方 onDismissRequest）
                dismissOnClickOutside = false,      // 全屏不透明浮层：不允许点击外部关闭
                decorFitsSystemWindows = false       // 沉浸式：内容自己处理 insets
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // 拦截全部点击：空区域不再穿透到正文（防止误触发正文控制条/揭块）
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                if (maskOverlay == "edit") {
                    // key(会话)：见 maskEditSession 注释 —— 不同编辑会话的状态完全隔离。
                    // 前缀 "maskEdit" 用于升级换代：换 tag 即整体更换槽位键命名空间，
                    // 旧版本残留的已保存状态不会再被任何新会话命中/消费。
                    androidx.compose.runtime.key("maskEdit", maskEditSession) {
                        MaskConfigEditScreen(
                            article = article,
                            configId = maskEditConfigId,
                            onBack = { maskOverlay = "list" }
                        )
                    }
                } else {
                    MaskConfigListScreen(
                        articleId = article.id,
                        onBack = { maskOverlay = null },
                        onEdit = { id ->
                            maskEditConfigId = id
                            maskEditSession++
                            maskOverlay = "edit"
                        },
                        onNew = {
                            maskEditConfigId = -1L
                            maskEditSession++
                            maskOverlay = "edit"
                        }
                    )
                }
            }
        }
    }
}

}

// ========== 章节分页与进度辅助 ==========

/** 轻量可变引用（供闭包写入共享状态） */
internal class Ref<T>(var value: T)

/** 单节目标字符数（分割太碎翻页频繁，太大失去章节感） */
internal const val ReadingSectionTarget = 420
