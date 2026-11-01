// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextAlign
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.GlassButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilyskyo.blancall.algorithm.ReadingSelection
import com.ilyskyo.blancall.algorithm.ReadingSpeed
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.ReaderPrefs
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.data.repository.ReadingMark
import com.ilyskyo.blancall.data.repository.ReadingMarkStore
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.ImmersiveSystemBarsEffect
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.ReadingMaxWidth
import com.ilyskyo.blancall.ui.common.pinchZoomByTouch
import com.ilyskyo.blancall.ui.common.tapGesturesPenAware
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.theme.rememberAppIsDark
import com.ilyskyo.blancall.ui.theme.ThemeMode
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
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
fun ReadingModeScreen(
    article: Article,
    onExit: () -> Unit,
    /** 「把这一段变成挖空」：段落起点（全文坐标）+ 段内遮块区间；转换与导航由外层负责 */
    onPracticeParagraph: (Int, List<IntRange>) -> Unit = { _, _ -> },
    /** 「练这一段」面板里选的挖空方式（句子 / 字词 / 反向）：整篇练习，导航由外层负责 */
    onPracticeMode: (BlancallMode) -> Unit = {},
) {
    // 章节翻页的回执：「跨过去一页」是轻的一下（Toggle），
    // 而不是 Compose 语义类型那一下 —— 与 ui/common/Haptics.kt 同一套直振栈。
    val pageHaptic = rememberHaptic(HapticTier.Toggle)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 长按选区的「复制」：直接写系统剪贴板（Android 13+ 系统自带复制提示，低版本补 Toast）
    val clipboard = remember(context) {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }

    // ── 主题明暗：统一走 rememberAppIsDark（三处复制的路由已收口，新增模式不用改这里）──
    val isDark = rememberAppIsDark()
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
    // 强调色：直接用主题主色（深色下 primaryDark 本身就是提亮版，在纯黑上够亮）。
    // 旧版这里写死了一个偏蓝的 #7C9ED1，导致阅读设置里选了暖橙/翠绿主题，
    // 选中态仍然是蓝的 —— 主题色在阅读页整套断链，根因就在这一行。
    val accentColor = MaterialTheme.colorScheme.primary

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

    // ── 划线与批注 ──
    // 段落口径与遮挡/存储一致：全文段落文本列表，既用于渲染时把页内段号映射回全局段号，
    // 也用于列表里取摘录（存储只存坐标，不存文本 —— 改文章也不会留下两份正文）。
    val allParagraphs = remember(article.content) {
        ReaderOcclusion.splitParagraphs(article.content).map { it.text }
    }
    val markStore = remember { ReadingMarkStore.getInstance(context.filesDir) }
    val markRevision by markStore.revisionFlow.collectAsState()
    var marks by remember(article.id) { mutableStateOf<List<ReadingMark>>(emptyList()) }
    LaunchedEffect(article.id, markRevision) {
        marks = withContext(Dispatchers.IO) { markStore.getMarks(article.id) }
    }
    // 当前选区：null = 没在选。选区与操作条画在正文组合内的覆盖层上 ——
    // 正文是 AndroidView，它永远压在外部 Compose 之上，覆盖层必须和正文同一个组合。
    var selection by remember { mutableStateOf<ReadingSelectionRange?>(null) }
    var selectionAnchor by remember { mutableStateOf<ParagraphAnchor?>(null) }
    // 「有没有选区」单独抽成 derivedState：拖动时 selection 每帧都在变，
    // 外层若直接读 selection，整页（所有段落 + 悬浮控件）都会跟着每帧重组。
    val hasSelection by remember { derivedStateOf { selection != null } }
    // 长按生效时刻：外层「点一下切换控件」在手指抬起时还会触发一次，得让这一下失效
    var lastSelectAt by remember { mutableLongStateOf(0L) }
    // 批注编辑目标（null = 没打开）
    var noteTarget by remember { mutableStateOf<NoteEditTarget?>(null) }
    var marksSheetVisible by remember { mutableStateOf(false) }
    // 段落顶部位置（段索引 → 滚动内容坐标）：从划线列表跳转时精确滚动用。
    // 普通 MutableMap 而不是 State —— 滚动时每帧都在写，进重组会掉帧。
    val paragraphTops = remember(article.id) { mutableMapOf<Int, Float>() }

    // ── 阅读速度与剩余时间 ──
    // 标准字总量：汉字 1 字、英文整词 ≈ 2 字（换算见 ReadingSpeed.standardChars）
    val stdTotalChars = remember(article.content) { ReadingSpeed.standardChars(article.content) }
    // 个人速度（标准字/分钟）；未校准时用默认值
    var speedCpm by remember {
        mutableFloatStateOf(
            AppPrefs.getReadingSpeedCpm().takeIf { it > 0f } ?: ReadingSpeed.DefaultCpm
        )
    }
    // 已累计的有效阅读秒数（设置面板里显示"依据：你的阅读记录（x 分钟）"）
    var speedSampleSec by remember { mutableLongStateOf(AppPrefs.getReadingSpeedSampleSeconds()) }
    // 本次会话统计：每秒都在动，只放普通引用，不进重组
    val sessionChars = remember(article.id) { Ref(0f) }
    val sessionActiveSec = remember(article.id) { Ref(0L) }
    val lastProgressRef = remember(article.id) { Ref(0f) }
    // 上一次进度变化的时刻：有效阅读时长按「两次进度变化之间」结算（见 ReadingSpeed.SilenceGapSeconds）
    val lastProgressAtRef = remember(article.id) { Ref(SystemClock.uptimeMillis()) }
    // 五分钟没翻页就问一句「还在读吗」：确认了就把这五分钟算进阅读时长，
    // 说「先去忙」就放行（这五分钟不计时，也不批评）
    var idleStage by remember { mutableStateOf(IdlePromptStage.Hidden) }
    var idleMinutes by remember { mutableIntStateOf(5) }
    // 本轮「进度周期」里已经问过一次了：翻页/滚动之前不再重复问。
    // 读一屏二十分钟不动手是正常的，每五分钟弹一次会从"关心"变成"骚扰"。
    val idleAskedRef = remember(article.id) { Ref(false) }
    // 确认框已挂起的轮数（5 秒一轮）：30 秒没人应答就静默收起，别一直糊在正文上
    var idlePromptTicks by remember { mutableIntStateOf(0) }

    /** 把「上次进度变化到现在」按有效阅读结算掉（封顶 SilenceGapSeconds），并重置起点 */
    fun settleActiveReading() {
        val now = SystemClock.uptimeMillis()
        sessionActiveSec.value +=
            ReadingSpeed.effectiveSeconds((now - lastProgressAtRef.value) / 1000L)
        lastProgressAtRef.value = now
    }

    fun endSelection() {
        selection = null
        selectionAnchor = null
    }

    /** 把本次会话的抽样并进个人速度（退出时调用一次；样本不可信则原样不动） */
    fun commitSpeedSample(updateUi: Boolean = true) {
        val sec = sessionActiveSec.value
        val chars = sessionChars.value
        val sample = ReadingSpeed.cpmOf(chars, sec) ?: return
        val prevCpm = AppPrefs.getReadingSpeedCpm().takeIf { it > 0f }
        val blended = ReadingSpeed.blend(prevCpm, sample, sec)
        AppPrefs.setReadingSpeed(
            cpm = blended,
            sampleSeconds = AppPrefs.getReadingSpeedSampleSeconds() + sec,
            sampleChars = AppPrefs.getReadingSpeedSampleChars() + chars
        )
        // onDispose 里不要再写组合内的 State（组合已在拆除）
        if (updateUi) {
            speedCpm = blended
            speedSampleSec = AppPrefs.getReadingSpeedSampleSeconds()
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    // 「遮挡段 → 一次性挖空」：只列出真的被遮住的段落，避免用户从头翻找
    var showPracticeSheet by remember { mutableStateOf(false) }
    var practiceChoices by remember { mutableStateOf<List<PracticeParagraph>>(emptyList()) }
    // 遮挡段统计完成标记：面板刚弹出时先显示占位，避免「当前没有可转换的遮挡段」错态闪一下
    var practiceChoicesReady by remember { mutableStateOf(false) }
    LaunchedEffect(
        showPracticeSheet,
        readerPrefs.occlusionMode,
        readerPrefs.occlusionCustomConfigId,
        article.id,
    ) {
        if (!showPracticeSheet) return@LaunchedEffect
        practiceChoicesReady = false
        practiceChoices = withContext(Dispatchers.IO) {
            val paras = ReaderOcclusion.splitParagraphs(article.content)
            val store = MaskConfigStore.getInstance(context.filesDir)
            // 「自定义」粒度用哪套配置：先看本文阅读设置，设置里没写（旧数据 / 刚清过）就退回
            // 配置库的「使用中」标记 —— 不然这里恒算成 0 段可练，面板看着还是空的。
            val customId = readerPrefs.occlusionCustomConfigId.takeIf { it > 0L }
                ?: store.getSelected(article.id)
            val customSpansByPara: Map<Int, List<IntRange>> =
                if (readerPrefs.occlusionMode == "custom" && customId > 0L) {
                    store.getConfigs(article.id)
                        .firstOrNull { it.id == customId }
                        ?.spans
                        ?.groupBy { it.p }
                        ?.mapValues { (_, spans) -> spans.map { it.a until it.e } }
                        .orEmpty()
                } else {
                    emptyMap()
                }
            paras.mapIndexedNotNull { idx, p ->
                val spans = if (readerPrefs.occlusionMode == "custom") {
                    customSpansByPara[idx].orEmpty()
                } else {
                    ReaderOcclusion.localRangesInPara(p.text, readerPrefs.occlusionMode)
                        .map { it.start until it.end }
                }
                if (spans.isEmpty()) null
                else PracticeParagraph(start = p.start, text = p.text, spans = spans)
            }
        }
        practiceChoicesReady = true
    }
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
    // 浮层内的写入（使用配置 / 保存新建 / 删除清指向）落在 store + 磁盘，文章详情页内存态
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
    // 预计剩余阅读时间：剩余**标准字** ÷ 个人速度（见 ReadingSpeed），随滚动/翻页实时联动。
    // 旧实现是「剩余字符数 ÷ 固定 350」：英文段落的字符数远大于它的阅读量，
    // 中英混排的文章会把时间估到实际的两三倍。
    // 累计已阅读时长：只显示分钟（向下取整，不做四舍五入）；数值照旧，文案按语言在组合层组装
    val elapsedMinutes by remember { derivedStateOf { liveSeconds / 60 } }
    val elapsedMinText = if (elapsedMinutes >= 1) {
        stringResource(com.ilyskyo.blancall.R.string.reader_read_minutes, elapsedMinutes)
    } else {
        stringResource(com.ilyskyo.blancall.R.string.reader_read_less_minute)
    }
    // 剩余比例 = 1 - 当前进度：滑回（进度后退）剩余时间随之变多，不依赖累计已读时长。
    // 该计算与文案已下沉到 ReadingRemainingLabel：组合期读取滚动进度会让整个文章详情页每帧重组。
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
    // 五分钟没翻页 → 问一句「还在读吗」。
    // 用 5 秒轮询而不是精确闹钟：弹窗早几秒晚几秒无所谓，却省掉一个 Handler 的生命周期管理。
    LaunchedEffect(article.id, layoutMode) {
        while (true) {
            delay(5_000)
            when (idleStage) {
                IdlePromptStage.Hidden -> {
                    if (!idleAskedRef.value) {
                        val idleSec = (SystemClock.uptimeMillis() - lastProgressAtRef.value) / 1000L
                        if (idleSec >= ReadingSpeed.SilenceGapSeconds) {
                            idleMinutes = (idleSec / 60L).toInt().coerceAtLeast(1)
                            idlePromptTicks = 0
                            idleStage = IdlePromptStage.Confirm
                        }
                    }
                }
                IdlePromptStage.Confirm -> {
                    idlePromptTicks++
                    // 30 秒没人应答：多半是手机搁在桌上了，静默按「先去忙」处理 ——
                    // 弹窗一直挂着比不弹更糟（它会一直挡着正文，还占着"在读"这个判断）
                    if (idlePromptTicks >= 6) {
                        idleAskedRef.value = true
                        lastProgressAtRef.value = SystemClock.uptimeMillis()
                        idleStage = IdlePromptStage.Hidden
                    }
                }
                IdlePromptStage.Cheer -> Unit
            }
        }
    }
    // 页面切换即保存断点
    LaunchedEffect(pagerState.currentPage) { persistPosition() }
    // 滚动模式：滚动即更新待落盘进度（dispose/定时器统一回写，避免频繁写盘）
    // 同时结算「有效阅读时长」与「已读标准字」：
    // - 时长 = 这次进度变化距上次变化的间隔（单次封顶 SilenceGapSeconds，超出的当走开）
    // - 字数只计前进量，滑回不扣
    // 按进度结算而不是按触摸结算：读一屏文字常常一两分钟不碰屏幕，
    // 触摸法会把这段全丢掉，分母偏小 → 个人速度被抬到离谱，剩余时间反而更不准。
    LaunchedEffect(scrollState) {
        snapshotFlow { progressFraction }.collect { p ->
            lastSavedFraction = p
            val delta = p - lastProgressRef.value
            val now = SystemClock.uptimeMillis()
            // 翻页/滚动 = 新的进度周期：下次静默五分钟可以再问一次
            if (delta != 0f) idleAskedRef.value = false
            // 进度一动就证明人在：确认弹窗自动当「在读」处理（不需要再点一次）
            if (delta != 0f && idleStage == IdlePromptStage.Confirm) {
                settleActiveReading()
                idleStage = IdlePromptStage.Hidden
            } else if (delta != 0f) {
                sessionActiveSec.value +=
                    ReadingSpeed.effectiveSeconds((now - lastProgressAtRef.value) / 1000L)
            }
            if (delta > 0f) sessionChars.value += delta * stdTotalChars
            lastProgressRef.value = p
            lastProgressAtRef.value = now
        }
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
            // 退出时把本次抽样并进个人阅读速度（样本不足 90 秒会原样返回，不污染历史值）
            commitSpeedSample(updateUi = false)
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
    // 有选区时返回键先收起选区（不然这一下直接变成"退出阅读"，选中的内容全丢）
    BackHandler(enabled = hasSelection) { endSelection() }
    BackHandler(enabled = settingsVisible) { settingsVisible = false }

    // 排版/布局一变，选区的锚点（段落坐标 + layout 快照）就失效了 —— 直接收起来，
    // 不留下一个"位置对不上字"的假选区。
    LaunchedEffect(layoutMode, fontPx, lineHeight, readerPrefs.fontId, readerPrefs.fontWeight) {
        endSelection()
    }

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
                                // 正文是独立 ComposeView 组合，CompositionLocal 传不进来，
                                // 只能靠 rememberAppIsDark() 重新订阅（同一份实现，不再复制路由）
                                val bodyIsDark = rememberAppIsDark()
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
                                // 以 store 修订号参与 key：编辑器里重存配置后回文章详情页，遮挡立即刷新
                                val maskStore = remember { MaskConfigStore.getInstance(context.filesDir) }
                                // 修订号用 StateFlow 订阅：旧实现每 300ms 轮询一次，文章详情页常亮期间持续唤醒 CPU
                                val maskRevision by maskStore.revisionFlow.collectAsState()
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
                                    // 空内容不是「还在等」而是「这里没有东西」：转圈说的是「等」，
                                    // 文案说的是「走」，同屏两个指令会互相拆台 —— 所以整块撤掉
                                    // CircularProgressIndicator，换成一句正文 + 一扇真的能走的门
                                    // （onExit 是本屏唯一的退出回调，必须留在这条分支里可达）。
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.reader_empty_placeholder),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        GlassButton(onClick = onExit) {
                                            Text(
                                                stringResource(com.ilyskyo.blancall.R.string.back),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    return@setContent
                                }
                                // 正文 + 选区覆盖层同一个根 Box：
                                // 覆盖层必须和正文同处一个组合 —— 正文是 AndroidView（ComposeView），
                                // 外部 Compose 声明的浮层永远会被它盖住（本项目已多次踩到）。
                                // 正文 + 选区覆盖层同一个根 Box（覆盖层必须和正文同处一个组合，
                                // 外部 Compose 声明的浮层会被 AndroidView 盖住）
                                Box(Modifier.fillMaxSize()) {
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
                                                        // 长按刚开完选择：这一下抬起不能再切控件
                                                        if (SystemClock.uptimeMillis() - lastSelectAt > 700L) {
                                                            controlsVisible = !controlsVisible
                                                        }
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
                                                indent = bodyIndent,
                                                marks = marks,
                                                accent = accentColor,
                                                selectable = true,  // 选择态由覆盖层接管触摸，段落侧无需关长按
                                                paragraphOffsetHint = 0,
                                                allParagraphs = allParagraphs,
                                                onLongPress = { p, offset, anchor ->
                                                    val r = ReadingSelection.rangeAt(anchor.text, offset)
                                                    if (ReadingSelection.isSelectable(anchor.text, r)) {
                                                        selection = ReadingSelectionRange(p, r.first, r.last + 1)
                                                        selectionAnchor = anchor
                                                        lastSelectAt = SystemClock.uptimeMillis()
                                                    }
                                                },
                                                onMarkClick = { m ->
                                                    // 延后一帧再开对话框：这次 up 还没分发完，
                                                    // 新窗口在同一轮输入里出现会被当成"点外面"瞬间关掉
                                                    scope.launch {
                                                        delay(80)
                                                        noteTarget = noteTargetOf(m, allParagraphs)
                                                    }
                                                },
                                                onHandledTap = { lastSelectAt = SystemClock.uptimeMillis() },
                                                onParagraphPositioned = { p, top ->
                                                    if (layoutMode == 0) {
                                                        val contentTop = top + scrollState.value
                                                        val prev = paragraphTops[p]
                                                        if (prev == null || kotlin.math.abs(prev - contentTop) > 1f) {
                                                            paragraphTops[p] = contentTop
                                                        }
                                                    }
                                                }
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
                                                        if (SystemClock.uptimeMillis() - lastSelectAt > 700L) {
                                                            controlsVisible = !controlsVisible
                                                        }
                                                    } else Modifier
                                            )
                                    ) { page ->
                                        ReadingSectionPage(
                                            text = sections[page].text,
                                            fontPx = bodyFontPx,
                                            lineHeight = bodyLineHeight,
                                            textColor = bodyTextColor,
                                            fontFamily = bodyFontFamily,
                                            onScrollFraction = { inPageFraction = it },
                                            indent = bodyIndent,
                                            maskColor = bodyMaskColor,
                                            occlusion = bodyOcclusion,
                                            customSpansResolver = bodyCustomResolver,
                                            marks = marks,
                                            accent = accentColor,
                                            selectable = true,  // 选择态由覆盖层接管触摸，段落侧无需关长按
                                            paragraphOffsetHint = sections[page].firstPara,
                                            allParagraphs = allParagraphs,
                                            onLongPress = { p, offset, anchor ->
                                                val r = ReadingSelection.rangeAt(anchor.text, offset)
                                                if (ReadingSelection.isSelectable(anchor.text, r)) {
                                                    selection = ReadingSelectionRange(p, r.first, r.last + 1)
                                                    selectionAnchor = anchor
                                                    lastSelectAt = SystemClock.uptimeMillis()
                                                }
                                            },
                                            onMarkClick = { m ->
                                                // 同上：延后一帧，避免新窗口在同一次 up 里被关掉
                                                scope.launch {
                                                    delay(80)
                                                    noteTarget = noteTargetOf(m, allParagraphs)
                                                }
                                            },
                                            onHandledTap = { lastSelectAt = SystemClock.uptimeMillis() },
                                            onParagraphPositioned = null,
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

                                // ── 选区覆盖层 ──
                                val sel = selection
                                val anchor = selectionAnchor
                                if (sel != null && anchor != null && !sel.isEmpty && anchor.p == sel.p) {
                                    // 「被覆盖」而非「完全重合」：在已划线句子上多选一个字也算命中。
                                    // 工具条据此给「取消划线/编辑批注」，点划线则由存储层并成一条（不叠层）。
                                    val covering = marks
                                        .filter { it.p == sel.p && it.a <= sel.a && it.e >= sel.e }
                                        .let { list -> list.firstOrNull { it.hasNote } ?: list.firstOrNull() }
                                    val quote = anchor.text.substring(
                                        sel.a.coerceIn(0, anchor.text.length),
                                        sel.e.coerceIn(0, anchor.text.length)
                                    )
                                    ReadingSelectionOverlay(
                                        anchor = anchor,
                                        selection = sel,
                                        accent = accentColor,
                                        isDark = bodyIsDark,
                                        coveringMark = covering,
                                        onRangeChange = { a, e ->
                                            selection = ReadingSelectionRange(sel.p, a, e)
                                        },
                                        onDismiss = { endSelection() },
                                        onCopy = {
                                            if (quote.isNotBlank()) {
                                                clipboard?.setPrimaryClip(
                                                    ClipData.newPlainText("Blancall", quote)
                                                )
                                                Toast.makeText(
                                                    context,
                                                    context.getString(com.ilyskyo.blancall.R.string.reader_mark_copied),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            endSelection()
                                        },
                                        onHighlight = {
                                            val s = sel
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    markStore.addMark(article.id, s.p, s.a, s.e)
                                                }
                                                endSelection()
                                            }
                                        },
                                        onUnhighlight = {
                                            val id = covering?.id
                                            if (id != null) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        markStore.removeMark(article.id, id)
                                                    }
                                                    endSelection()
                                                }
                                            } else {
                                                endSelection()
                                            }
                                        },
                                        onNote = {
                                            val cover = covering
                                            val np = cover?.p ?: sel.p
                                            val na = cover?.a ?: sel.a
                                            val ne = cover?.e ?: sel.e
                                            val noteQuote = anchor.text.substring(
                                                na.coerceIn(0, anchor.text.length),
                                                ne.coerceIn(0, anchor.text.length)
                                            )
                                            noteTarget = NoteEditTarget(
                                                p = np,
                                                a = na,
                                                e = ne,
                                                quote = noteQuote,
                                                note = cover?.note.orEmpty(),
                                                markId = cover?.id
                                            )
                                            endSelection()
                                        }
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
                    // 字号改动同样只写本文章存档（原写全局：会让所有文章跟着变）
                    updateReaderPrefs { p -> p.copy(fontPx = (p.fontPx * zoom).coerceIn(14f, 36f)) }
                }
        )

        // ── 底部细进度条（常驻，液态玻璃胶囊）──
        ReadingProgressCapsule(
            fraction = { progressFraction },
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
        animationSpec = MotionFade.number(MotionFade.enter),
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
            cornerPx = lgCornerPx(23f),
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
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.reader_exit_cd),
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
                // 练习入口常驻：面板第二出口（句子/字词/反向 三种挖空）本来就不依赖遮挡，
                // 早前误写成 occlusionEnabled 门控 → 没开遮挡的文章整个入口消失（用户报障）。
                if (controlsVisible) {
                    GlassIconButton(
                        onClick = { showPracticeSheet = true },
                        buttonSize = 38.dp
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.reader_practice_section_cd),
                            tint = accentColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                if (controlsVisible) {
                    GlassIconButton(onClick = { settingsVisible = true }, buttonSize = 38.dp) {
                        Icon(
                            imageVector = Icons.Outlined.FormatSize,
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.reader_settings_cd),
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
            cornerPx = lgCornerPx(22f),
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
                                pageHaptic()
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        buttonSize = 36.dp
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.reader_prev_section),
                            tint = subColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (layoutMode == 1) {
                            "${pagerState.currentPage + 1}/${sections.size}"
                        } else {
                            stringResource(com.ilyskyo.blancall.R.string.reader_full_text)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    ReadingPercentLabel(
                        progress = { progressFraction },
                        color = subColor
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    ReadingRemainingLabel(
                        progress = { progressFraction },
                        totalStdChars = stdTotalChars,
                        cpm = speedCpm,
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
                                pageHaptic()
                            }
                        },
                        enabled = pagerState.currentPage < sections.size - 1,
                        buttonSize = 36.dp
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.reader_next_section),
                            tint = subColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
            }  // close LiquidGlassPill content
    }

    // ── 遮挡段转练：「练这一段」面板 ──
    // 旧实现是只有标题 + 空态的对话框（没遮住时点开等于什么都不给），现在铺成底部面板：
    // 上面是「把遮住的段落变成挖空」，下面是三种挖空方式（点了直接进整篇练习）。
    ReaderPracticeSheet(
        visible = showPracticeSheet,
        choices = practiceChoices,
        choicesReady = practiceChoicesReady,
        onDismiss = { showPracticeSheet = false },
        onPracticeParagraph = { choice ->
            showPracticeSheet = false
            onPracticeParagraph(choice.start, choice.spans)
        },
        onPracticeMode = { mode ->
            showPracticeSheet = false
            onPracticeMode(mode)
        }
    )

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
        marksCount = marks.size,
        onOpenMarks = {
            // 设置面板立即收起、列表同时挂上。这里不做延时：延时窗口内用户再点一下，
            // 列表就永远不会出现（实测），而"两个 sheet 短暂同屏"只是一帧的层级问题 ——
            // 设置面板的退场动画播完就没了，用户看到的仍是列表从底部滑上来。
            settingsVisible = false
            marksSheetVisible = true
        },
        speedCpm = speedCpm,
        speedSampleSeconds = speedSampleSec,
        onResetSpeed = {
            AppPrefs.resetReadingSpeed()
            speedCpm = ReadingSpeed.DefaultCpm
            speedSampleSec = 0L
        },
        isDark = isDark,
        accent = accentColor
    )

    // ── 划线与批注：列表 / 批注编辑 ──
    // 摘录在组合期从全文段落切（存储只存坐标）：文章正文被改过时，越界部分会自然退化成空串。
    val markRows = remember(marks, allParagraphs) {
        marks.map { m ->
            val para = allParagraphs.getOrNull(m.p).orEmpty()
            val a = m.a.coerceIn(0, para.length)
            val e = m.e.coerceIn(a, para.length)
            val raw = para.substring(a, e)
            val clipped = raw.length > 120
            ReadingMarkRow(mark = m, quote = if (clipped) raw.take(120) else raw, clipped = clipped)
        }
    }
    ReadingMarksSheet(
        visible = marksSheetVisible,
        rows = markRows,
        onDismiss = { marksSheetVisible = false },
        onJump = { row ->
            marksSheetVisible = false
            if (layoutMode == 0) {
                val top = paragraphTops[row.mark.p]
                scope.launch {
                    if (top != null) {
                        scrollState.animateScrollTo((top - 96f).toInt().coerceAtLeast(0))
                    } else {
                        // 还没量到位置（刚打开文章就点列表）：按段落比例落一个大致位置
                        val ratio = (row.mark.p + 0.5f) / allParagraphs.size.coerceAtLeast(1)
                        scrollState.animateScrollTo((scrollState.maxValue * ratio).toInt())
                    }
                }
            } else {
                val page = sections.indexOfFirst {
                    row.mark.p >= it.firstPara && row.mark.p < it.firstPara + it.paraCount
                }
                if (page >= 0) scope.launch { pagerState.animateScrollToPage(page) }
            }
        },
        onDelete = { row ->
            scope.launch {
                withContext(Dispatchers.IO) { markStore.removeMark(article.id, row.mark.id) }
            }
        }
    )

    // 五分钟无操作确认 / 庆祝
    ReadingIdlePrompt(
        stage = idleStage,
        idleMinutes = idleMinutes,
        accent = accentColor,
        isDark = isDark,
        onStillReading = {
            settleActiveReading()
            idleAskedRef.value = true
            idleStage = IdlePromptStage.Cheer
        },
        onBusy = {
            // 放行：这五分钟不计入（人确实不在读），起点重设，下次进度变化重新开始算
            lastProgressAtRef.value = SystemClock.uptimeMillis()
            idleAskedRef.value = true
            idleStage = IdlePromptStage.Hidden
            Toast.makeText(
                context,
                context.getString(com.ilyskyo.blancall.R.string.reader_idle_paused),
                Toast.LENGTH_SHORT
            ).show()
        },
        onCheerDone = { idleStage = IdlePromptStage.Hidden }
    )

    // 批注编辑：新建（选区来的）与编辑（点已有划线）共用同一个对话框
    val editingNote = noteTarget
    NoteEditorDialog(
        visible = editingNote != null,
        quote = editingNote?.quote.orEmpty(),
        initialNote = editingNote?.note.orEmpty(),
        canDelete = editingNote?.markId != null,
        onDismiss = { noteTarget = null },
        onSave = { text ->
            val target = noteTarget
            noteTarget = null
            if (target != null) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val id = target.markId
                        if (id != null) {
                            markStore.updateNote(article.id, id, text)
                        } else {
                            markStore.addMark(article.id, target.p, target.a, target.e, text)
                        }
                    }
                }
            }
        },
        onDelete = {
            val target = noteTarget
            noteTarget = null
            val id = target?.markId
            if (id != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { markStore.removeMark(article.id, id) }
                }
            }
        }
    )

    // ── 遮挡自定义浮层：配置列表 / 编辑器（覆盖整个阅读界面，系统返回逐级退出）──
    // 用 Dialog 承载：GlassModalBottomSheet 内部是 Material3 ModalBottomSheet（独立 Dialog window），
    // 其 window 的销毁滞后于组合树的移除。点击「自定义」时 sheet 会被立即移出组合树，但它的窗口在
    // 一小段时间内仍浮于 Activity 内容之上 —— Compose 内浮层用的 zIndex 只在同一 window 内比较层级，
    // 压不过这个残留窗口，于是被遮挡并可能吞掉点击，表现为「点自定义后要再点一下才进得去」。
    // 改为 Dialog 后本浮层也是独立 window，且创建时间晚于 sheet 的 Dialog，层级天然更高、立即显示。
    // ── 遮罩配置浮层：入场与退场都要播 ──
    // 改造前这块是全 app 最大的零动效面积：打开＝整屏瞬间换成配置页，关闭＝瞬间换回正文。
    // 窗口存活条件带上「退场还在播」那一项（与文章详情页的全屏编辑对话框同一处理）；
    // 减动效时 alpha 走 snap 同帧归零，条件同帧失效 —— 是跳过动画，不是延后 dismiss。
    MaskOverlayWindow(
        shown = maskOverlay != null,
        onDismissRequest = {
            if (maskOverlay == "edit") maskOverlay = "list" else maskOverlay = null
        },
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


/**
 * 遮挡配置浮层的独立窗口：淡入 + 被物理带走的缩放，退场播完之后窗口才拆。
 *
 * 与 `FadeDialogWindow` 同款理由 —— 补间器必须住在比宿主页面更低的层：之前这块在
 * ReadingModeScreen 自己的组合作用域里读 `maskAlpha > 0f` 当窗口存活判据，于是浮层淡入的
 * 每一帧都重组整页正文（阅读模式页是全文渲染，逐帧重组最贵）。现在动画值只在下面的
 * graphicsLayer 里读，宿主只在 maskOverlay 真的翻动时重组一次。
 *
 * 与 FadeDialogWindow 分开而不是加参数合并：这里的物理不同（缩放用 Motion.Scale.dismissTo +
 * Motion.lift()、decorFitsSystemWindows=false、整屏拦截点击、返回键是「编辑器→列表→关闭」
 * 的逐级退出），揉成一个组件只会让两边都难读。
 */
@Composable
internal fun MaskOverlayWindow(
    shown: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(shown) }
    LaunchedEffect(shown) { visible = shown }
    val windowAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MotionFade.alpha(MotionFade.enter),
        label = "maskFade"
    )
    // 缩放负责「被物理带走」的重量，起点/终点用 Motion.Scale.dismissTo 那一档。
    val windowScale by animateFloatAsState(
        targetValue = if (visible) 1f else Motion.Scale.dismissTo,
        animationSpec = Motion.lift(),
        label = "maskScale"
    )
    if (shown || windowAlpha > 0f) {
        Dialog(
            // 系统返回键逐级退出：编辑器 → 列表 → 关闭。
            // 走 Dialog 的 onDismissRequest（配合下方 dismissOnBackPress），而不是在 Dialog 内容里
            // 注册 BackHandler：Compose Dialog 的返回键由 dialog 窗口自身处理（DialogWrapper 直接回调
            // onDismissRequest），不经过 Activity 的 OnBackPressedDispatcher，BackHandler 并不可靠。
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,   // 允许铺满屏幕
                dismissOnBackPress = true,          // 返回键逐级退出（见上方 onDismissRequest）
                dismissOnClickOutside = false,      // 全屏不透明浮层：不允许点击外部关闭
                decorFitsSystemWindows = false       // 沉浸式：内容自己处理 insets
            )
        ) {
            // 入场缩放/淡入在这里用 graphicsLayer 做，窗口层不要再叠一份
            com.ilyskyo.blancall.ui.common.SuppressDialogWindowAnimation()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .graphicsLayer {
                        alpha = windowAlpha
                        scaleX = windowScale
                        scaleY = windowScale
                    }
                    // 拦截全部点击：空区域不再穿透到正文（防止误触发正文控制条/揭块）
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                content()
            }
        }
    }
}

// ========== 章节分页与进度辅助 ==========

/** 进度百分比：只让这一格随滚动进度重组，不再带动整页 */
@Composable
private fun ReadingPercentLabel(progress: () -> Float, color: Color) {
    Text(
        text = "${(progress() * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/**
 * 预计剩余阅读时间：由进度实时折算，读取与重组都限制在本组合内。
 *
 * 折算走 [ReadingSpeed]：剩余**标准字** ÷ 个人速度。旧实现是「剩余字符 ÷ 350」，
 * 中英混排时英文段落的字符量是它真实阅读量的两倍以上，时间会被高估到离谱。
 */
@Composable
private fun ReadingRemainingLabel(
    progress: () -> Float,
    totalStdChars: Float,
    cpm: Float,
    color: Color
) {
    val remainRatio = (1f - progress()).coerceIn(0f, 1f)
    val remainSec = ReadingSpeed.remainingSeconds(totalStdChars * remainRatio, cpm)
    val minutes = ReadingSpeed.displayMinutes(remainSec)
    val text = when {
        remainRatio <= 0.005f -> stringResource(com.ilyskyo.blancall.R.string.reader_finished)
        minutes <= 0L -> stringResource(com.ilyskyo.blancall.R.string.reader_remaining_less_minute)
        minutes >= 60L -> if (minutes % 60L == 0L) {
            stringResource(com.ilyskyo.blancall.R.string.reader_remaining_hours_only, minutes / 60L)
        } else {
            stringResource(
                com.ilyskyo.blancall.R.string.reader_remaining_hours,
                minutes / 60L,
                minutes % 60L
            )
        }
        else -> stringResource(
            com.ilyskyo.blancall.R.string.reader_remaining_minutes,
            minutes.coerceAtLeast(1L)
        )
    }
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

/** 轻量可变引用（供闭包写入共享状态） */
internal class Ref<T>(var value: T)

/** 批注编辑目标：位置（写回存储）+ 引用文本（给人看）+ 已有记录 id（null = 新建） */
internal data class NoteEditTarget(
    val p: Int,
    val a: Int,
    val e: Int,
    val quote: String,
    val note: String,
    val markId: Long?
)

/** 由一条记录生成编辑目标（段文本被改过/越界时摘录退化成空串，不崩） */
internal fun noteTargetOf(mark: ReadingMark, allParagraphs: List<String>): NoteEditTarget {
    val para = allParagraphs.getOrNull(mark.p).orEmpty()
    val a = mark.a.coerceIn(0, para.length)
    val e = mark.e.coerceIn(a, para.length)
    return NoteEditTarget(
        p = mark.p,
        a = mark.a,
        e = mark.e,
        quote = para.substring(a, e),
        note = mark.note,
        markId = mark.id
    )
}

/** 单节目标字符数（分割太碎翻页频繁，太大失去章节感） */
internal const val ReadingSectionTarget = 420
