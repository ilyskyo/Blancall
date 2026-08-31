// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.MarkdownText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_MENU_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.ilyskyo.blancall.ui.viewmodel.BlankCountWarning
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.PracticeViewModel
import com.ilyskyo.blancall.ui.viewmodel.SectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(navController: NavController, articleIds: List<Long>, initialMode: BlancallMode? = null, resume: Boolean = false, initialSectionMode: SectionMode? = null) {
    val vm: PracticeViewModel = viewModel()
    val article by vm.article.collectAsState()
    val mode by vm.mode.collectAsState()
    val sentenceCloze by vm.sentenceCloze.collectAsState()
    val wordCloze by vm.wordCloze.collectAsState()
    // 反向默写（段落打散默写）：打乱顺序的句子线索 + 用户默写输入 + 判分结果
    val dictationResult by vm.dictationResult.collectAsState()
    val dictationInput by vm.dictationInput.collectAsState()
    val dictationCheckResult by vm.dictationCheckResult.collectAsState()
    val userAnswers by vm.userAnswers.collectAsState()
    val checkResults by vm.checkResults.collectAsState()
    val isSubmitted by vm.isSubmitted.collectAsState()
    val isSubmitting by vm.isSubmitting.collectAsState()
    val resumed by vm.resumed.collectAsState()
    val totalBlanks by vm.totalBlanks.collectAsState()

    val wordBlankCount by vm.wordBlankCount.collectAsState()

    // 段落分层（F3）
    val sections by vm.sections.collectAsState()
    val sectionMode by vm.sectionMode.collectAsState()
    val selectedSections by vm.selectedSections.collectAsState()
    val rankedSections by vm.rankedSections.collectAsState()

    // 沉浸模式（F4）
    val immersiveMode by vm.immersiveMode.collectAsState()
    val progressiveLevel by vm.progressiveLevel.collectAsState()

    // 跨文本联动（F7）
    val isCrossMode by vm.isCrossMode.collectAsState()
    val crossArticleTitles by vm.crossArticleTitles.collectAsState()

    // AI 挖空（Pro）：难度采集页 + 生成加载态 + 字号缩放（双指）
    val fontScale by vm.fontScale.collectAsState()
    val showDifficultyInfo by vm.showDifficultyInfo.collectAsState()
    val isAiGenerating by vm.isAiGenerating.collectAsState()
    val aiProgress by vm.aiProgress.collectAsState()
    val aiError by vm.aiError.collectAsState()
    val difficulty by vm.difficulty.collectAsState()
    // 填空辅助提示（弱提示淡显 / 强提示统计）
    val hintChars by vm.hintChars.collectAsState()
    val weakHintCount by vm.weakHintCount.collectAsState()
    val strongHintCount by vm.strongHintCount.collectAsState()
    // 反向默写整段输入提示
    val dictationHint by vm.dictationHint.collectAsState()
    // 训练分析（Pro，Markdown）
    val trainingAnalysis by vm.trainingAnalysis.collectAsState()
    val analysisLoading by vm.analysisLoading.collectAsState()
    val analysisError by vm.analysisError.collectAsState()

    // 挖空策略与古文模式
    val strategy by vm.strategy.collectAsState()
    val classicalMode by vm.classicalMode.collectAsState()

    // 三点菜单点位（必须在 LaunchedEffect 之前声明）
    // rememberSaveable：旋转横屏重建后保持已选模式，避免答题界面退回"选择模式"
    var modeSelected by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showStrategySheet by remember { mutableStateOf(false) }
    var showSectionSheet by remember { mutableStateOf(false) }
    var showIncompleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(articleIds) {
        if (articleIds.size > 1) {
            vm.loadArticles(articleIds)
        } else if (articleIds.size == 1) {
            // 初始段落模式（薄弱集训等）由 loadArticle 在段落数据就绪后统一应用
            vm.loadArticle(articleIds.first(), resume = resume, initialSectionMode = initialSectionMode)
        }
    }

    // 外部传入初始模式时，跳过模式选择界面
    LaunchedEffect(initialMode) {
        if (initialMode != null) {
            vm.setMode(initialMode)
            modeSelected = true
        }
    }

    // 从练习进度恢复时跳过模式选择界面，直接进入上次练习模式
    LaunchedEffect(resumed) {
        if (resumed) {
            modeSelected = true
        }
    }

    // 沉浸模式：提交后记录渐进结果
    LaunchedEffect(isSubmitted) {
        if (isSubmitted) {
            val allCorrect = checkResults.values.all { it.result == AnswerChecker.Result.CORRECT }
            vm.recordProgressiveResult(allCorrect, isSubmitted)
        }
    }

    // 用 derivedStateOf 包裹派生统计，避免每次重组都重算（Compose 重组性能优化）
    val correctCount by remember(checkResults) {
        derivedStateOf { checkResults.values.count { it.result == AnswerChecker.Result.CORRECT } }
    }
    val filledCount by remember(userAnswers) {
        derivedStateOf { userAnswers.values.count { it.isNotBlank() } }
    }
    val showHint by vm.showHint.collectAsState()
    val blankCountWarning by vm.blankCountWarning.collectAsState()

    // PDF 导出（F8）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = 600.dp).padding(horizontal = 16.dp, vertical = 16.dp)) {
        // ── 顶部导航：返回 + 文章标题 + 操作 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = { navController.popBackStack() })
            Spacer(Modifier.width(8.dp))
            // 顶部标题：左右滑动可快速切换三种练习模式（AI 开启时按已选难度重新生成）
            val latestMode by rememberUpdatedState(mode)
            val titleSwipeEnabled by rememberUpdatedState(modeSelected && !isSubmitted)
            // 标题拖拽跟手：滑动时标题随手指平移（有拖拽动画，而非静止检测后跳切）
            // 标题拖拽跟手：Animatable 支持拖拽时即时 snapTo、松手 animateTo 平滑回弹
            val titleDrag = remember { Animatable(0f) }
            Text(
                text = article?.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .offset { IntOffset(titleDrag.value.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        var accumulated = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f; scope.launch { titleDrag.snapTo(0f) } },
                            onDragCancel = { accumulated = 0f; scope.launch { titleDrag.animateTo(0f, tween(240)) } },
                            onDragEnd = {
                                if (titleSwipeEnabled) {
                                    when {
                                        accumulated <= -TitleSwipeThreshold -> vm.switchMode(nextMode(latestMode))
                                        accumulated >= TitleSwipeThreshold -> vm.switchMode(prevMode(latestMode))
                                    }
                                }
                                accumulated = 0f
                                // 松手平滑回弹，不做快速跳回
                                scope.launch { titleDrag.animateTo(0f, tween(240)) }
                            }
                        ) { _, dragAmount ->
                            accumulated += dragAmount
                            if (titleSwipeEnabled) {
                                // 限制可拉范围（跟手上限），避免越拉越远
                                val clamped = accumulated.coerceIn(-MaxTitleDrag, MaxTitleDrag)
                                scope.launch { titleDrag.snapTo(clamped) }
                            }
                        }
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 已提交：快捷操作
            if (isSubmitted && modeSelected) {
                TextButton(onClick = { vm.reset() }) { Text("重做") }
            }
            // 未提交：提交按钮（选好模式、难度采集页关闭时才显示）
            if (!isSubmitted && modeSelected && !showDifficultyInfo && totalBlanks > 0) {
                // 反向默写是整段输入，判分逻辑与按空作答不同，单独处理
                if (mode == BlancallMode.REVERSE) {
                    TextButton(
                        onClick = { vm.submitAnswers() },
                        enabled = dictationInput.isNotBlank() && !isSubmitting
                    ) { Text(if (isSubmitting) "判分中…" else "提交") }
                } else {
                    val hasAnyAnswer = userAnswers.values.any { it.isNotBlank() }
                    val filled = userAnswers.values.count { it.isNotBlank() }
                    val unfilled = totalBlanks - filled
                    TextButton(
                        onClick = {
                            if (unfilled > 0) {
                                showIncompleteDialog = true
                            } else {
                                vm.submitAnswers()
                            }
                        },
                        enabled = hasAnyAnswer && !isSubmitting
                    ) {
                        Text(if (isSubmitting) "判分中…" else if (filled > 0) "提交($filled/$totalBlanks)" else "提交")
                    }
                }
            }
            // 三点菜单（玻璃质感）：提交后禁用，避免误切模式；重做后恢复
            if (modeSelected) {
                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        enabled = !isSubmitted
                    ) {
                        AppIcon(
                            kind = AppIconKind.MoreVert,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    GlassDropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                    // ── 当前模式（只读标签）──
                    GlassMenuItem(
                        enabled = false,
                        onClick = {},
                        label = {
                            Text(
                                "当前模式",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailing = {
                            Text(
                                modeLabel(mode),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    // ── 切换模式 → 打开 BottomSheet ──
                    GlassMenuItem(
                        onClick = { showMoreMenu = false; showModeSheet = true },
                        leadingIcon = {
                            AppIcon(
                                kind = AppIconKind.SwapHoriz,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text("切换模式…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                    )
                    // ── 挖空策略 → 打开 BottomSheet ──
                    GlassMenuItem(
                        onClick = { showMoreMenu = false; showStrategySheet = true },
                        leadingIcon = {
                            AppIcon(
                                kind = AppIconKind.TrackChanges,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("挖空策略", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    strategyLabel(strategy),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    GlassMenuDivider()
                    // ── 提示开关（保留菜单，符合 Apple 开关直觉）──
                    GlassMenuItem(
                        onClick = { vm.toggleHint() },
                        label = { Text("显示提示", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                        trailing = {
                            GlassSwitch(
                                checked = showHint,
                                onCheckedChange = { vm.toggleHint() }
                            )
                        }
                    )
                    // ── 古文模式（字词模式时显示，保留菜单）──
                    if (mode == BlancallMode.WORD) {
                        GlassMenuItem(
                            onClick = { vm.setClassicalMode(!classicalMode) },
                            label = { Text("古文模式", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
                            trailing = {
                                GlassSwitch(
                                    checked = classicalMode,
                                    onCheckedChange = { vm.setClassicalMode(it) }
                                )
                            }
                        )
                    }
                    // ── 沉浸模式（一次性动作，关闭菜单）──
                    GlassMenuItem(
                        onClick = { vm.toggleImmersiveMode(); showMoreMenu = false },
                        label = {
                            Text(
                                if (immersiveMode) "退出沉浸" else "沉浸模式",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        trailing = {
                            if (immersiveMode) {
                                AppIcon(
                                    kind = AppIconKind.Check,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    GlassMenuDivider()
                    // ── 段落分层（始终显示）──
                    val sectionLabel = when (sectionMode) {
                        SectionMode.FULL -> "段落：全文连贯"
                        SectionMode.WEAKNESS -> "段落：薄弱集训"
                        SectionMode.SELECTED -> "段落：自选 (${selectedSections.size}/${sections.size})"
                    }
                    GlassMenuItem(
                        onClick = {
                            showMoreMenu = false
                            showSectionSheet = true
                        },
                        leadingIcon = {
                            AppIcon(
                                kind = AppIconKind.ViewAgenda,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text(sectionLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                    )
                    // ── 导出 / 分享（已提交时）──
                    if (isSubmitted) {
                        GlassMenuDivider()
                        GlassMenuItem(
                            onClick = { showExportDialog = true; showMoreMenu = false },
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.Pdf,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("导出 PDF", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                        )
                        GlassMenuItem(
                            onClick = {
                                showMoreMenu = false
                                scope.launch {
                                    shareNoteImage(context, article, sentenceCloze, wordCloze, dictationResult, mode, isCrossMode, crossArticleTitles, checkResults, totalBlanks)
                                }
                            },
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.Share,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("分享笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                        )
                    }
                }
            }
        }
    }

        Spacer(Modifier.height(10.dp))

        // 跨文本联动指示器（F7）
        if (isCrossMode && crossArticleTitles.size > 1) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔗", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "跨文复习",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        crossArticleTitles.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── 进度条（沉浸模式下隐藏；未选好模式 / 难度采集页不显示，避免提前出现填空进度）──
        if (!immersiveMode && !isSubmitted && modeSelected && !showDifficultyInfo && totalBlanks > 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { filledCount.toFloat() / totalBlanks },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${filledCount}/${totalBlanks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── 模式已选择后才显示内容 ──
        if (modeSelected) {

        // AI 挖空失败提示（已自动回退本地算法，练习仍可用）
        aiError?.let { msg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "AI 挖空失败，已改用本地算法：$msg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 空数警告提示
        blankCountWarning?.let { warning ->
            BlankCountWarningBanner(
                warning = warning,
                onDismiss = { vm.dismissBlankCountWarning() },
                onUseSuggested = { vm.useSuggestedBlankCount() }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        } // modeSelected 条件块结束

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(12.dp))

        // 内容区域：双指缩放手势 + 难度采集 / AI 生成加载 / 正文内容
        // note: pinchZoom 挂在最外层 Box，双指在正文任意处都能调整字号
        Box(
            modifier = Modifier
                .weight(1f)
                .pinchZoom { vm.adjustFontScale(it) }
        ) {
            when {
                // 信息采集页：AI 挖空开启 → 难度 + 策略 + 自定义；关闭 → 只采集挖空策略 + 段落选择
                showDifficultyInfo -> if (vm.isAiClozeEnabled()) {
                    DifficultyInfoPage(
                        mode = mode,
                        initialStrategy = strategy,
                        onConfirm = { d, c, s -> vm.confirmDifficultyAndGenerate(d, c, s) },
                        onSkipToLocal = { vm.skipAiAndGenerateLocal() },
                        onCancel = {
                            // 取消采集 → 回到模式选择浮层（不直接本地生成）
                            vm.cancelDifficultyCollection()
                            modeSelected = false
                        }
                    )
                } else {
                    LocalClozeCollectPage(
                        initialStrategy = strategy,
                        initialSectionMode = sectionMode,
                        initialSelected = selectedSections,
                        sections = sections,
                        rankedSections = rankedSections,
                        onConfirm = { s, sm, sel -> vm.confirmLocalClozeSettings(s, sm, sel) },
                        onCancel = {
                            vm.cancelDifficultyCollection()
                            modeSelected = false
                        }
                    )
                }
                // AI 挖空生成中（切换模式也需要重新生成 → 显示加载页，可随时回退本地）
                isAiGenerating -> AiGeneratingPage(
                    mode = mode,
                    difficulty = difficulty,
                    progress = aiProgress,
                    onCancel = { vm.cancelAiGeneration() }
                )
                article == null || (modeSelected && (
                    (mode == BlancallMode.SENTENCE && sentenceCloze == null)
                    || (mode == BlancallMode.WORD && wordCloze == null)
                    || (mode == BlancallMode.REVERSE && dictationResult == null)
                )) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在生成挖空...", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                !modeSelected -> {
                    // 等待用户选择模式（浮层会覆盖此处）
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在准备...", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                        label = "modeContent"
                    ) { currentMode ->
                        // 双指缩放：把当前色系/形状保留，仅按 fontScale 放大各级字号（自动重排）
                        val scheme = MaterialTheme.colorScheme
                        val shapes = MaterialTheme.shapes
                        val baseType = MaterialTheme.typography
                        MaterialTheme(
                            colorScheme = scheme,
                            shapes = shapes,
                            typography = scaledTypography(baseType, fontScale)
                        ) {
                            // 判分卡"查看本篇文章数据"：仅单篇文章练习时可用（跨文复习无单篇统计页）
                            val viewArticleData: (() -> Unit)? = if (articleIds.size == 1) {
                                { navController.navigate("statistics/${articleIds.first()}") }
                            } else null
                            when (currentMode) {
                                BlancallMode.SENTENCE -> SentenceClozeContent(
                                    blancall = sentenceCloze,
                                    userAnswers = userAnswers,
                                    checkResults = checkResults,
                                    isSubmitted = isSubmitted,
                                    hintChars = hintChars,
                                    weakHints = weakHintCount,
                                    strongHints = strongHintCount,
                                    analysis = trainingAnalysis,
                                    analysisLoading = analysisLoading,
                                    analysisError = analysisError,
                                    onRetryAnalysis = { vm.generateTrainingAnalysis() },
                                    onViewArticleData = viewArticleData,
                                    onBlankFocus = { vm.ensureHintTimer(it) },
                                    onAnswerChange = { i, a -> vm.updateAnswer(i, a) }
                                )
                                BlancallMode.WORD -> WordClozeContent(
                                    blancall = wordCloze,
                                    userAnswers = userAnswers,
                                    checkResults = checkResults,
                                    isSubmitted = isSubmitted,
                                    hintChars = hintChars,
                                    weakHints = weakHintCount,
                                    strongHints = strongHintCount,
                                    analysis = trainingAnalysis,
                                    analysisLoading = analysisLoading,
                                    analysisError = analysisError,
                                    onRetryAnalysis = { vm.generateTrainingAnalysis() },
                                    onViewArticleData = viewArticleData,
                                    onBlankFocus = { vm.ensureHintTimer(it) },
                                    onAnswerChange = { i, a -> vm.updateAnswer(i, a) }
                                )
                                BlancallMode.REVERSE -> DictationContent(
                                    dictationResult = dictationResult,
                                    userInput = dictationInput,
                                    checkResult = dictationCheckResult,
                                    isSubmitted = isSubmitted,
                                    dictationHintChar = dictationHint,
                                    analysis = trainingAnalysis,
                                    analysisLoading = analysisLoading,
                                    analysisError = analysisError,
                                    onRetryAnalysis = { vm.generateTrainingAnalysis() },
                                    onViewArticleData = viewArticleData,
                                    onInputChange = { vm.updateDictationInput(it) },
                                    onEnterInput = { vm.ensureHintTimer() }
                                )
                            }
                        }
                    }
                }
            }
        }

        // PDF 导出对话框（F8）
        if (showExportDialog) {
            ExportPdfDialog(
                onDismiss = { showExportDialog = false },
                onExport = { includeAnswer ->
                    showExportDialog = false
                    scope.launch {
                        exportPdf(context, article, sentenceCloze, wordCloze, dictationResult, mode, isCrossMode, crossArticleTitles, includeAnswer)
                    }
                }
            )
        }

    }

    // ── 模式选择浮层（初次进入，未选模式时显示）──
    // 模式选择浮层：fade + scale 弹性动画，避免直接消失的视觉跳变
    AnimatedVisibility(
        visible = !modeSelected && article != null,
        enter = fadeIn(animationSpec = tween(160)) +
                scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    initialScale = 0.9f
                ),
        exit = fadeOut(animationSpec = tween(120)) +
               scaleOut(targetScale = 0.96f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 模态遮罩：压暗底层页面，毛玻璃叠在内容页上不会显得"透穿破图"
                .background(Color.Black.copy(alpha = 0.32f))
                // 消费点击：浮层显示时阻断底层页面交互
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { },
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                // 浮层叠在内容页上，用菜单级不透明度，避免底层文字透出影响可读性
                containerColor = MaterialTheme.colorScheme.surface,
                containerAlpha = if (isBlancallDark()) GLASS_ALPHA_DARK else GLASS_MENU_ALPHA_LIGHT
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "选择练习模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        article?.title ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(20.dp))

                    // 句子挖空
                    ModeCard(
                        emoji = "📝",
                        title = "句子挖空",
                        desc = "隐藏完整句子，适合段落背诵",
                        onClick = {
                            vm.onModeChosen(BlancallMode.SENTENCE)
                            modeSelected = true
                        }
                    )
                    Spacer(Modifier.height(10.dp))

                    // 字词挖空
                    ModeCard(
                        emoji = "🔤",
                        title = "字词挖空",
                        desc = "挖掉关键词/字，精准检测掌握度",
                        onClick = {
                            vm.onModeChosen(BlancallMode.WORD)
                            modeSelected = true
                        }
                    )
                    Spacer(Modifier.height(10.dp))

                    // 反向默写
                    ModeCard(
                        emoji = "✍️",
                        title = "反向默写",
                        desc = "把段落打散后默写原文，深度记忆",
                        onClick = {
                            vm.onModeChosen(BlancallMode.REVERSE)
                            modeSelected = true
                        }
                    )
                }
            }
        }
    }

    // ── 未完成提交确认弹窗 ──
    if (showIncompleteDialog) {
        val filled = userAnswers.values.count { it.isNotBlank() }
        val unfilled = totalBlanks - filled
        IncompleteSubmitDialog(
            unfilledCount = unfilled,
            onContinue = { showIncompleteDialog = false },
            onSubmitPartial = {
                showIncompleteDialog = false
                vm.submitPartial()
            }
        )
    }

    // ── 二级：切换模式 BottomSheet ──
    if (showModeSheet) {
        GlassModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            dragHandle = { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (isBlancallDark()) Color(0x66FFFFFF) else Color(0x33000000)))
            } }
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 36.dp)) {
                Text("切换练习模式", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                // 句子挖空
                RadioListItem(
                    emoji = "📝", label = "句子挖空",
                    desc = "从句/半句/整句 — 理解式记忆",
                    selected = mode == BlancallMode.SENTENCE,
                    onClick = { vm.switchMode(BlancallMode.SENTENCE); showModeSheet = false }
                )
                // 字词挖空
                RadioListItem(
                    emoji = "🔤", label = "字词挖空",
                    desc = "1-3字词精准填空 — 细节记忆",
                    selected = mode == BlancallMode.WORD,
                    onClick = { vm.switchMode(BlancallMode.WORD); showModeSheet = false }
                )
                // 反向默写
                RadioListItem(
                    emoji = "✍️", label = "反向默写",
                    desc = "把段落打散后默写原文 — 深度回忆",
                    selected = mode == BlancallMode.REVERSE,
                    onClick = { vm.switchMode(BlancallMode.REVERSE); showModeSheet = false }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── 二级：挖空策略 BottomSheet ──
    if (showStrategySheet) {
        GlassModalBottomSheet(
            onDismissRequest = { showStrategySheet = false },
            dragHandle = { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (isBlancallDark()) Color(0x66FFFFFF) else Color(0x33000000)))
            } }
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 36.dp)) {
                Text("选择挖空策略", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                RadioListItem(
                    emoji = "⚖️", label = "均衡挖空",
                    desc = "全覆盖 + 轻度薄弱倾斜",
                    selected = strategy == BlancallGenerator.Strategy.BALANCED,
                    onClick = { vm.setStrategy(BlancallGenerator.Strategy.BALANCED); showStrategySheet = false }
                )
                RadioListItem(
                    emoji = "🎯", label = "薄弱优先",
                    desc = "高频出错区域优先挖空",
                    selected = strategy == BlancallGenerator.Strategy.WEAKNESS_FOCUS,
                    onClick = { vm.setStrategy(BlancallGenerator.Strategy.WEAKNESS_FOCUS); showStrategySheet = false }
                )
                RadioListItem(
                    emoji = "🔄", label = "全覆盖",
                    desc = "每个从句都有机会，均匀分布",
                    selected = strategy == BlancallGenerator.Strategy.FULL_COVERAGE,
                    onClick = { vm.setStrategy(BlancallGenerator.Strategy.FULL_COVERAGE); showStrategySheet = false }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── 二级：段落模式 BottomSheet ──
    if (showSectionSheet) {
        GlassModalBottomSheet(
            onDismissRequest = { showSectionSheet = false },
            dragHandle = { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (isBlancallDark()) Color(0x66FFFFFF) else Color(0x33000000)))
            } }
        ) {
            SectionPickerContent(
                sectionMode = sectionMode,
                sections = sections,
                selectedSections = selectedSections,
                rankedSections = rankedSections,
                // 切到"全文连贯/薄弱集训"立即应用并关闭 sheet；
                // 切到"自选段落"保留 sheet 让用户勾选，由"完成"按钮关闭
                onModeChange = { mode ->
                    vm.setSectionMode(mode)
                    if (mode != SectionMode.SELECTED) showSectionSheet = false
                },
                onToggleSection = { vm.toggleSection(it) },
                onSelectAll = { vm.selectAllSections() },
                onDismiss = { showSectionSheet = false }
            )
        }
    }

    }
}

// ========== 模式选择卡片 ==========

@Composable
private fun ModeCard(
    emoji: String,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    // 按压缩放弹性反馈，让点击有"活力"
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "modeCardScale"
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // emoji 放圆形强调色背景里，更醒目有活力
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "→",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ========== 未完成提交确认弹窗 ==========

@Composable
private fun IncompleteSubmitDialog(
    unfilledCount: Int,
    onContinue: () -> Unit,
    onSubmitPartial: () -> Unit
) {
    BlancallAlertDialog(
        onDismissRequest = onContinue,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        title = {
            Text(
                "还有 ${unfilledCount} 个空未完成",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    "你还有部分内容没有完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "是否保存当前进度并提交？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        HintRow("只批改已完成的部分")
                        Spacer(Modifier.height(4.dp))
                        HintRow("未填写内容不会计入错误统计")
                        Spacer(Modifier.height(4.dp))
                        HintRow("当前练习进度会被保存，下次可以继续")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmitPartial,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存并提交")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinue,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("继续填写")
            }
        }
    )
}

@Composable
private fun HintRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            kind = AppIconKind.Check,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceClozeContent(
    blancall: BlancallGenerator.SentenceClozeResult?,
    userAnswers: Map<Int, String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    isSubmitted: Boolean,
    hintChars: Map<Int, Char> = emptyMap(),
    weakHints: Int = 0,
    strongHints: Int = 0,
    analysis: String? = null,
    analysisLoading: Boolean = false,
    analysisError: Boolean = false,
    onRetryAnalysis: () -> Unit = {},
    onViewArticleData: (() -> Unit)? = null,
    onBlankFocus: (Int) -> Unit = {},
    onAnswerChange: (Int, String) -> Unit
) {
    blancall?.let { result ->
        var currentBlankIndex by remember { mutableIntStateOf(0) }
        val blanks = result.blanks
        val totalBlanks = blanks.size
        // 进入/切换焦点即启动提示计时：满足无操作时长即提示，不受"是否输入过"影响
        LaunchedEffect(currentBlankIndex, isSubmitted) {
            if (!isSubmitted) onBlankFocus(currentBlankIndex)
        }
        // 预计算按句分组的空位映射，避免在 LazyColumn 每个 item 里重复 filter（O(sentences*blanks)）
        val blanksBySentence = remember(blanks) {
            blanks.groupBy { it.sentenceIndex }
                .mapValues { (_, list) -> list.sortedBy { it.startInSentence } }
        }

        if (totalBlanks == 0) {
            LazyColumn {
                itemsIndexed(result.sentences, key = { idx, _ -> "s_$idx" }) { _, sentence ->
                    Text(sentence, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            return
        }

        val isWide = LocalConfiguration.current.screenWidthDp >= 600

        if (isWide && !isSubmitted) {
            // 平板：左侧内容 + 右侧输入
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(result.sentences, key = { idx, _ -> "s_$idx" }) { sIdx, sentence ->
                        val sentenceBlanks = blanksBySentence[sIdx].orEmpty()
                        if (sentenceBlanks.isEmpty()) {
                            Text(sentence, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp))
                        } else {
                            FlowRow(modifier = Modifier.padding(vertical = 2.dp)) {
                                var lastPos = 0
                                for (blank in sentenceBlanks) {
                                    if (blank.startInSentence > lastPos) {
                                        Text(sentence.substring(lastPos, blank.startInSentence),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground)
                                    }
                                    SentenceBlankInline(
                                        blank = blank, isCurrent = blank.index == currentBlankIndex,
                                        answer = userAnswers[blank.index],
                                        checkResult = checkResults[blank.index],
                                        isSubmitted = isSubmitted,
                                        hintChar = hintChars[blank.index],
                                        onClick = { currentBlankIndex = blank.index }
                                    )
                                    lastPos = blank.endInSentence
                                }
                                if (lastPos < sentence.length) {
                                    Text(sentence.substring(lastPos), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                }

                VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp))

                Column(
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    HintOutlinedField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "请输入被挖掉的内容",
                        hintChar = if (!isSubmitted) hintChars[currentBlankIndex] else null,
                        maxLines = 3,
                        minHeight = 74.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { if (currentBlankIndex > 0) currentBlankIndex-- },
                            enabled = currentBlankIndex > 0
                        ) { Text("← 上一个") }
                        TextButton(
                            onClick = { if (currentBlankIndex < totalBlanks - 1) currentBlankIndex++ },
                            enabled = currentBlankIndex < totalBlanks - 1
                        ) { Text("下一个 →") }
                    }
                }
            }
        } else {
            // 手机或已提交：上下布局
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 提交后顶部展示评分卡
                    if (isSubmitted) {
                        item(key = "score") { BlancallScoreCard(checkResults, onViewArticleData, weakHints, strongHints) }
                        item(key = "analysis") {
                            TrainingAnalysisCard(analysisLoading, analysis, analysisError, onRetryAnalysis)
                        }
                    }
                    itemsIndexed(result.sentences, key = { idx, _ -> "s_$idx" }) { sIdx, sentence ->
                        val sentenceBlanks = blanksBySentence[sIdx].orEmpty()
                        if (sentenceBlanks.isEmpty()) {
                            Text(sentence, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp))
                        } else {
                            FlowRow(modifier = Modifier.padding(vertical = 2.dp)) {
                                var lastPos = 0
                                for (blank in sentenceBlanks) {
                                    if (blank.startInSentence > lastPos) {
                                        Text(sentence.substring(lastPos, blank.startInSentence),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground)
                                    }
                                    SentenceBlankInline(
                                        blank = blank, isCurrent = blank.index == currentBlankIndex,
                                        answer = userAnswers[blank.index],
                                        checkResult = checkResults[blank.index],
                                        isSubmitted = isSubmitted,
                                        hintChar = hintChars[blank.index],
                                        onClick = { currentBlankIndex = blank.index }
                                    )
                                    lastPos = blank.endInSentence
                                }
                                if (lastPos < sentence.length) {
                                    Text(sentence.substring(lastPos), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                }

                if (!isSubmitted) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    HintOutlinedField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "请输入被挖掉的内容",
                        hintChar = if (!isSubmitted) hintChars[currentBlankIndex] else null,
                        maxLines = 3,
                        minHeight = 74.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { if (currentBlankIndex > 0) currentBlankIndex-- },
                            enabled = currentBlankIndex > 0
                        ) { Text("← 上一个") }
                        TextButton(
                            onClick = { if (currentBlankIndex < totalBlanks - 1) currentBlankIndex++ },
                            enabled = currentBlankIndex < totalBlanks - 1
                        ) { Text("下一个 →") }
                    }
                }
            }
        }
    }
}

/** 句内挖空标记（内联版）：[N] + 横线/答案，不占满行宽，可自然跟随文字流 */
@Composable
private fun SentenceBlankInline(
    blank: BlancallGenerator.SentenceBlankInfo,
    isCurrent: Boolean,
    answer: String?,
    checkResult: AnswerChecker.CheckDetail?,
    isSubmitted: Boolean,
    hintChar: Char? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .then(
                if (isCurrent && !isSubmitted)
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                else Modifier
            )
            .clickable(enabled = !isSubmitted) { onClick() }
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "[${blank.index + 1}]",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(4.dp))

        if (isSubmitted && checkResult != null) {
            // 提交后：紧凑显示答案与批改 + 相似度
            val userText = answer?.takeIf { it.isNotBlank() } ?: "（未填）"
            val isCorrect = checkResult.result == AnswerChecker.Result.CORRECT
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    userText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCorrect) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!isCorrect) {
                    Text(
                        " → ${checkResult.correctAnswer}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 相似度标签（与反向默写统一口径，正确时为100%不重复显示）
                if (!isCorrect && checkResult.similarity > 0f) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "${(checkResult.similarity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        } else if (!answer.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(answer, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                // 弱提示下一字：显示在空内已填文字之后（淡显）
                HintGhost(if (isSubmitted) null else hintChar)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("＿＿＿＿",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HintGhost(if (isSubmitted) null else hintChar)
            }
        }
    }
}

/** 淡显提示字（弱提示）：5s 淡入到浅灰。用于输入框内部 overlay 与句内空位。 */
@Composable
private fun HintGhost(hintChar: Char?, show: Boolean = true, modifier: Modifier = Modifier) {
    val alpha = remember(hintChar, show) { Animatable(0f) }
    LaunchedEffect(hintChar, show) {
        if (hintChar != null && show) alpha.animateTo(0.38f, animationSpec = tween(5000))
        else alpha.snapTo(0f)
    }
    if (hintChar != null && alpha.value > 0.01f) {
        Text(
            hintChar.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha.value),
            modifier = modifier
        )
    }
}

/**
 * 带弱提示字的输入框（与 OutlinedTextField 同外观）。
 * 用 BasicTextField 的 decorationBox 把提示字放在已输入文字之后（紧跟光标位），
 * 而非输入框右端 overlay，满足"提示字紧贴已输入文字"的体验。
 */
@Composable
private fun HintOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hintChar: Char? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minHeight: Dp = 56.dp,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    imeAction: ImeAction = ImeAction.Default
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        )
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        innerTextField()
                    }
                    // 提示字紧跟已输入文字（与 inner 同一行）；动画常驻，仅由 show 控制淡入
                    HintGhost(
                        hintChar,
                        show = hintChar != null,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        )
    }
}

// ========== 字词挖空 UI ==========

@Composable
private fun WordClozeContent(
    blancall: BlancallGenerator.WordClozeResult?,
    userAnswers: Map<Int, String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    isSubmitted: Boolean,
    hintChars: Map<Int, Char> = emptyMap(),
    weakHints: Int = 0,
    strongHints: Int = 0,
    analysis: String? = null,
    analysisLoading: Boolean = false,
    analysisError: Boolean = false,
    onRetryAnalysis: () -> Unit = {},
    onViewArticleData: (() -> Unit)? = null,
    onBlankFocus: (Int) -> Unit = {},
    onAnswerChange: (Int, String) -> Unit
) {
    blancall?.let {
        // 提示计时目标 = 第一个未填完的空：即使从未输入，满足无操作时长也提示
        LaunchedEffect(userAnswers, isSubmitted) {
            if (isSubmitted) return@LaunchedEffect
            val firstUnfinished = blancall.blanks.indexOfFirst { userAnswers[it.index].isNullOrEmpty() }
            if (firstUnfinished >= 0) onBlankFocus(firstUnfinished)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 提交后顶部展示评分卡
            if (isSubmitted) {
                item(key = "score") { BlancallScoreCard(checkResults, onViewArticleData, weakHints, strongHints) }
                item(key = "analysis") {
                    TrainingAnalysisCard(analysisLoading, analysis, analysisError, onRetryAnalysis)
                }
            }
            itemsIndexed(blancall.sentences, key = { idx, _ -> "s_$idx" }) { _, sentence ->
                if (sentence.blanks.isEmpty()) {
                    Text(sentence.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                } else {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(sentence.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(8.dp))
                            sentence.blanks.forEach { blankIdx ->
                                val blank = blancall.blanks.getOrNull(blankIdx) ?: return@forEach
                                BlankCard(
                                    label = "填空 [${blankIdx + 1}]",
                                    value = userAnswers[blankIdx] ?: "",
                                    checkDetail = checkResults[blankIdx],
                                    isSubmitted = isSubmitted,
                                    multiline = false,
                                    hintChar = hintChars[blankIdx],
                                    onValueChange = { onAnswerChange(blankIdx, it) }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========== 通用空白卡片 ==========

@Composable
private fun BlankCard(
    label: String,
    value: String,
    checkDetail: AnswerChecker.CheckDetail?,
    isSubmitted: Boolean,
    multiline: Boolean,
    hintChar: Char? = null,
    onValueChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSubmitted && checkDetail?.result == AnswerChecker.Result.CORRECT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                isSubmitted && checkDetail != null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            HintOutlinedField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitted,
                placeholder = "请输入答案",
                hintChar = if (!isSubmitted) hintChar else null,
                isError = isSubmitted && checkDetail?.result != AnswerChecker.Result.CORRECT,
                singleLine = !multiline,
                maxLines = if (multiline) 3 else 1,
                imeAction = ImeAction.Next
            )
            if (isSubmitted && checkDetail != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (checkDetail.result == AnswerChecker.Result.CORRECT) {
                        AppIcon(
                            kind = AppIconKind.Check,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        checkDetail.message,
                        color = if (checkDetail.result == AnswerChecker.Result.CORRECT)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    // 相似度标签（与反向默写统一口径，正确时为100%不显示）
                    if (checkDetail.result != AnswerChecker.Result.CORRECT && checkDetail.similarity > 0f) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                "相似度 ${(checkDetail.similarity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (checkDetail.result != AnswerChecker.Result.CORRECT && checkDetail.userAnswer.isNotEmpty()) {
                    Text(
                        "正确答案: ${checkDetail.correctAnswer}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

// ========== 空数警告卡片 ==========

@Composable
private fun BlankCountWarningBanner(
    warning: BlankCountWarning,
    onDismiss: () -> Unit,
    onUseSuggested: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        containerAlpha = 0.7f
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "空数调整提示",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(28.dp)
                ) {
                    AppIcon(
                        kind = AppIconKind.Close,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "本文字数较短，最多可挖 ${warning.maxBlanks} 个空。" +
                if (warning.actualCount < warning.requestedCount)
                    "您输入的 ${warning.requestedCount} 个空无法满足（实际生成 ${warning.actualCount} 个）。"
                else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "建议空数：${warning.suggestedBlanks}（不等于最大值，为更适合练习的推荐值）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("关闭", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onUseSuggested,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("使用建议空数（${warning.suggestedBlanks}）", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ========== PDF 导出（F8）==========

@Composable
private fun ExportPdfDialog(
    onDismiss: () -> Unit,
    onExport: (includeAnswer: Boolean) -> Unit
) {
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出PDF试卷") },
        text = {
            Column {
                Text("选择导出格式：", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onExport(false) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📝 无答案版（纯试卷）")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onExport(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📋 带答案版（末尾附答案）")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══════════════════════════════════════════
//  反向默写（段落打散默写）
// ═══════════════════════════════════════════

/** 构建反向默写的展示文本：把打乱顺序的挖空从句编号列出，作为默写线索 */
private fun buildDictationDisplayText(dictation: BlancallGenerator.DictationResult): String {
    return buildString {
        appendLine("【默写线索 · 从句已打乱顺序并挖空】")
        dictation.shuffledClauses.forEach { sh ->
            appendLine("${sh.displayOrder + 1}. ${sh.displayText}")
        }
    }.trimEnd()
}

/**
 * 反向默写内容区：展示打乱顺序的挖空从句作为线索，每个从句旁有复制按钮，
 * 用户复制下来还原顺序后，在下方输入框默写原文。
 * 提交后展示整段判分（覆盖率/准确率/顺序正确率/综合得分 + 逐句对比）。
 * - 暗色模式：全部使用 MaterialTheme.colorScheme，自动适配
 * - 无障碍：输入框带 label，结果区带 contentDescription
 * - 键盘滚动：输入框内部可滚动，整页 LazyColumn 避免键盘遮挡
 */
@Composable
private fun DictationContent(
    dictationResult: BlancallGenerator.DictationResult?,
    userInput: String,
    checkResult: AnswerChecker.DictationCheckResult?,
    isSubmitted: Boolean,
    dictationHintChar: Char? = null,
    analysis: String? = null,
    analysisLoading: Boolean = false,
    analysisError: Boolean = false,
    onRetryAnalysis: () -> Unit = {},
    onViewArticleData: (() -> Unit)? = null,
    onInputChange: (String) -> Unit,
    onEnterInput: () -> Unit = {}
) {
    val dictation = dictationResult
    if (dictation == null) {
        // 空态/错误态
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无默写内容，请返回重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // 进入作答界面即启动提示计时：即使从未输入，满足无操作时长也提示（VM 内已有运行中的计时则不重置）
    LaunchedEffect(isSubmitted) {
        if (!isSubmitted) onEnterInput()
    }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── 线索区：打乱顺序的挖空从句 ──
        item(key = "clueTitle") {
            Text("默写线索（从句已打乱顺序并挖空，点复制可复制单句）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        itemsIndexed(dictation.shuffledClauses, key = { idx, _ -> "clause_$idx" }) { _, sh ->
            DictationClauseCard(sh, context)
        }
        // ── 输入区 / 结果区 ──
        if (!isSubmitted) {
            item(key = "inputArea") {
                Text("默写原文",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                // 反向默写弱提示：提示字紧跟已输入文字的下一字位（5s 淡入浅灰）
                HintOutlinedField(
                    value = userInput,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                    placeholder = "按原文顺序默写整段，可把复制下来的从句拼回去…",
                    hintChar = if (!isSubmitted) dictationHintChar else null,
                    maxLines = Int.MAX_VALUE,
                    minHeight = 100.dp
                )
            }
        } else if (checkResult != null) {
            // 评分卡：综合得分 + 覆盖率/准确率/顺序正确率 + 查看本篇文章数据
            item(key = "scoreCard") { DictationScoreCard(checkResult, onViewArticleData) }
            item(key = "analysis") {
                TrainingAnalysisCard(analysisLoading, analysis, analysisError, onRetryAnalysis)
            }
        }
    }
}

/** 反向默写单句线索卡片：展示挖空从句 + 复制按钮 */
@Composable
private fun DictationClauseCard(
    clause: BlancallGenerator.ShuffledClause,
    context: Context
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "${clause.displayOrder + 1}. ${clause.displayText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            // 复制按钮：复制挖好空的从句文本，方便用户拼回去默写
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("默写从句", clause.displayText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "已复制：${clause.displayText}", Toast.LENGTH_SHORT).show()
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.semantics {
                    contentDescription = "复制第${clause.displayOrder + 1}句"
                }
            ) {
                Text("复制", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 句子/字词挖空评分卡片：醒目得分行 + 错误分布 + 相似度，右侧查看本篇文章数据 */
@Composable
private fun BlancallScoreCard(
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    onViewArticleData: (() -> Unit)? = null,
    weakHints: Int = 0,
    strongHints: Int = 0
) {
    if (checkResults.isEmpty()) return
    // 入场动画：淡入 + 从下方滑入，让评分卡出现更生动
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(400, delayMillis = 60),
        label = "scoreCardEnter"
    )
    val total = checkResults.size
    val correct = checkResults.values.count { it.result == AnswerChecker.Result.CORRECT }
    val typo = checkResults.values.count { it.result == AnswerChecker.Result.TYPO }
    val missing = checkResults.values.count { it.result == AnswerChecker.Result.MISSING }
    val extra = checkResults.values.count { it.result == AnswerChecker.Result.EXTRA }
    val wrongOrder = checkResults.values.count { it.result == AnswerChecker.Result.WRONG_ORDER }
    val incorrect = checkResults.values.count { it.result == AnswerChecker.Result.INCORRECT }
    val score = (correct.toFloat() / total * 100).toInt()
    // 正确率彩色笔：≥80 主色 / ≥60 辅色 / 低分红色（还原红色笔效果）
    val scoreColor = when {
        score >= 80 -> MaterialTheme.colorScheme.primary
        score >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：正确率（彩色笔）+ 右侧按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("正确率：$score%（$correct / $total）",
                    style = MaterialTheme.typography.titleMedium,
                    color = scoreColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "正确率 $score 分，正确 $correct 个，共 $total 个"
                    })
                if (onViewArticleData != null) {
                    TextButton(onClick = onViewArticleData) {
                        Text("查看本篇文章数据", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 错误分布
            val parts = mutableListOf<String>()
            if (typo > 0) parts.add("错别字 $typo")
            if (missing > 0) parts.add("少字 $missing")
            if (extra > 0) parts.add("多字 $extra")
            if (wrongOrder > 0) parts.add("顺序错 $wrongOrder")
            if (incorrect > 0) parts.add("不正确 $incorrect")
            if (parts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全部正确",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    AppIcon(
                        kind = AppIconKind.Check,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Text(parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 平均相似度（与反向默写统一口径，展示整体作答接近程度）
            val avgSim = checkResults.values.map { it.similarity }.average().toFloat()
            if (avgSim > 0f && avgSim < 1f) {
                Spacer(Modifier.height(4.dp))
                Text("平均相似度 ${(avgSim * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            // 提示统计（弱提示淡显 / 强提示自动填入）
            if (weakHints > 0 || strongHints > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "记忆提示：弱提示 $weakHints 次 · 强提示 $strongHints 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 提交后的 AI 训练分析卡片（Markdown 渲染；加载失败可重试）。 */
@Composable
private fun TrainingAnalysisCard(
    loading: Boolean,
    text: String?,
    error: Boolean,
    onRetry: () -> Unit
) {
    if (!loading && text == null && !error) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("本次训练分析", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 正在分析本次训练…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error -> {
                    Text("训练分析生成失败", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = onRetry) { Text("重试", style = MaterialTheme.typography.labelMedium) }
                }
                text != null -> MarkdownText(text, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 反向默写评分卡片：醒目综合得分行 + 覆盖率/准确率/顺序正确率，右侧查看本篇文章数据 */
@Composable
private fun DictationScoreCard(
    result: AnswerChecker.DictationCheckResult,
    onViewArticleData: (() -> Unit)? = null
) {
    // 入场动画：淡入 + 从下方滑入
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(400, delayMillis = 60),
        label = "dictationScoreCardEnter"
    )
    val score = (result.overallScore * 100).toInt()
    val coverage = (result.coverageRate * 100).toInt()
    val accuracy = (result.accuracyRate * 100).toInt()
    val order = (result.orderCorrectRate * 100).toInt()
    // 综合得分彩色笔：≥80 主色 / ≥60 辅色 / 低分红色
    val scoreColor = when {
        score >= 80 -> MaterialTheme.colorScheme.primary
        score >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：综合得分（彩色笔）+ 右侧按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("综合得分：$score%",
                    style = MaterialTheme.typography.titleMedium,
                    color = scoreColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "反向默写综合得分 $score 分"
                    })
                if (onViewArticleData != null) {
                    TextButton(onClick = onViewArticleData) {
                        Text("查看本篇文章数据", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("覆盖率 $coverage% · 准确率 $accuracy% · 顺序正确率 $order%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private suspend fun exportPdf(
    context: android.content.Context,
    article: com.ilyskyo.blancall.data.model.Article?,
    sentenceCloze: BlancallGenerator.SentenceClozeResult?,
    wordCloze: BlancallGenerator.WordClozeResult?,
    dictationResult: BlancallGenerator.DictationResult?,
    mode: BlancallMode,
    isCrossMode: Boolean,
    crossArticleTitles: List<String>,
    includeAnswer: Boolean
) {
    val art = article ?: return
    val displayText = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.displayText ?: art.content
        BlancallMode.WORD -> wordCloze?.displayText ?: art.content
        // 反向默写导出：展示打乱顺序的句子线索（默写练习题）
        BlancallMode.REVERSE -> dictationResult?.let { buildDictationDisplayText(it) } ?: art.content
    }
    val blanks = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.blanks?.mapIndexed { i, b ->
            PdfExporter.BlankExportInfo(i, b.originalText)
        } ?: emptyList()
        BlancallMode.WORD -> wordCloze?.blanks?.mapIndexed { i, b ->
            PdfExporter.BlankExportInfo(i, b.originalChar)
        } ?: emptyList()
        // 反向默写无按空作答，blanks 为空
        BlancallMode.REVERSE -> emptyList()
    }

    val config = PdfExporter.ExportConfig(
        title = art.title,
        displayText = displayText,
        blanks = blanks,
        includeAnswer = includeAnswer,
        subtitle = if (isCrossMode) "跨文复习 · ${crossArticleTitles.joinToString(" · ")}" else ""
    )

    try {
        val file = withContext(Dispatchers.IO) {
            PdfExporter.export(context, config)
        }
        withContext(Dispatchers.Main) {
            PdfExporter.sharePdf(context, file)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private suspend fun shareNoteImage(
    context: android.content.Context,
    article: com.ilyskyo.blancall.data.model.Article?,
    sentenceCloze: BlancallGenerator.SentenceClozeResult?,
    wordCloze: BlancallGenerator.WordClozeResult?,
    dictationResult: BlancallGenerator.DictationResult?,
    mode: BlancallMode,
    isCrossMode: Boolean,
    crossArticleTitles: List<String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    totalBlanks: Int
) {
    val art = article ?: return
    val displayText = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.displayText ?: art.content
        BlancallMode.WORD -> wordCloze?.displayText ?: art.content
        // 反向默写分享：展示打乱顺序的句子线索
        BlancallMode.REVERSE -> dictationResult?.let { buildDictationDisplayText(it) } ?: art.content
    }
    val stats = if (totalBlanks > 0 && checkResults.isNotEmpty()) {
        val correct = checkResults.values.count { it.result == AnswerChecker.Result.CORRECT }
        "正确率 ${correct * 100 / totalBlanks}%  ·  共 ${totalBlanks} 个空"
    } else ""

    val config = ShareImageGenerator.ShareConfig(
        title = art.title,
        content = displayText,
        subtitle = if (isCrossMode) "跨文复习 · ${crossArticleTitles.joinToString(" · ")}" else "",
        stats = stats
    )

    try {
        val file = withContext(Dispatchers.IO) {
            ShareImageGenerator.generate(context, config)
        }
        withContext(Dispatchers.Main) {
            ShareImageGenerator.shareImage(context, file)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// ========== 菜单工具函数 ==========

private fun modeLabel(mode: BlancallMode): String = when (mode) {
    BlancallMode.SENTENCE -> "📝 句子挖空"
    BlancallMode.WORD -> "🔤 字词挖空"
    BlancallMode.REVERSE -> "✍️ 反向默写"
}

private fun strategyLabel(strategy: BlancallGenerator.Strategy): String = when (strategy) {
    BlancallGenerator.Strategy.BALANCED -> "均衡"
    BlancallGenerator.Strategy.WEAKNESS_FOCUS -> "薄弱优先"
    BlancallGenerator.Strategy.FULL_COVERAGE -> "全覆盖"
}

// ========== Radio List 选项（用于 BottomSheet）==========

@Composable
private fun RadioListItem(
    emoji: String,
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

// ========== 段落模式选择 BottomSheet 内容 ==========

@Composable
private fun SectionPickerContent(
    sectionMode: SectionMode,
    sections: List<SectionSplitter.Section>,
    selectedSections: Set<Int>,
    rankedSections: List<SectionSplitter.RankedSection>,
    onModeChange: (SectionMode) -> Unit,
    onToggleSection: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // 使用 Column + heightIn + verticalScroll，避免 LazyColumn 与 BottomSheet 拖拽手势冲突导致抖动
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
    ) {
        Text("段落复习模式", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("${sections.size} 个段落", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        // 模式选项（Radio 风格）
        SectionModeItem(
            emoji = "📖", label = "全文连贯",
            desc = "按原文顺序，覆盖所有段落",
            selected = sectionMode == SectionMode.FULL,
            onClick = { onModeChange(SectionMode.FULL) }
        )
        SectionModeItem(
            emoji = "🎯", label = "薄弱集训",
            desc = "只练错题集中的段落",
            selected = sectionMode == SectionMode.WEAKNESS,
            onClick = { onModeChange(SectionMode.WEAKNESS) }
        )
        SectionModeItem(
            emoji = "✂️", label = "自选段落",
            desc = "手动勾选要复习的段落",
            selected = sectionMode == SectionMode.SELECTED,
            onClick = { onModeChange(SectionMode.SELECTED) }
        )

        // 自选模式 → 显示段落勾选列表
        if (sectionMode == SectionMode.SELECTED) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选 ${selectedSections.size}/${sections.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSelectAll) {
                        Text(if (selectedSections.size == sections.size) "取消全选" else "全选",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    // 完成按钮：关闭 sheet，避免返回两次才能退出
                    TextButton(onClick = onDismiss) {
                        Text("完成", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            sections.forEach { section ->
                val isSelected = section.index in selectedSections
                val errorRate = rankedSections
                    .find { it.section.index == section.index }?.errorRate ?: 0f
                val heatColor = when {
                    errorRate >= 0.5f -> Color(0xFFE53935)
                    errorRate >= 0.3f -> Color(0xFFFB8C00)
                    errorRate >= 0.1f -> Color(0xFFFDD835)
                    errorRate > 0f -> Color(0xFF66BB6A)
                    else -> Color.Unspecified
                }
                SectionCheckItem(
                    label = section.heading ?: section.contentOnly.take(30),
                    index = section.index,
                    isSelected = isSelected,
                    heatColor = heatColor,
                    hasError = errorRate > 0f,
                    onClick = { onToggleSection(section.index) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionModeItem(
    emoji: String,
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionCheckItem(
    label: String,
    index: Int,
    isSelected: Boolean,
    heatColor: Color,
    hasError: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasError) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(heatColor, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(16.dp))
            }

            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

// ========== 顶部标题滑动切换模式 ==========

private const val TitleSwipeThreshold = 80f
// 标题拖拽跟手上限（px）：限制可拉范围，避免越拉越远
private const val MaxTitleDrag = 96f

/** 三种模式的有序列表（用于标题左右滑动循环切换） */
private val MODE_ORDER = listOf(BlancallMode.SENTENCE, BlancallMode.WORD, BlancallMode.REVERSE)

private fun nextMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) + 1) % MODE_ORDER.size]

private fun prevMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) - 1 + MODE_ORDER.size) % MODE_ORDER.size]

// ========== 双指缩放字号 ==========

/**
 * 双指捏合缩放：仅当屏幕上有 ≥2 个触点时按缩放增量回调 [onZoomChange]，
 * 单指滑动完全交给下层滚动容器处理，不产生任何手势冲突。
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
                if (prevDist > 0f && dist > 0f && prevDist > 0f) {
                    val factor = dist / prevDist
                    if (factor.isFinite()) onZoomChange(factor)
                }
                prevDist = dist
            }
        } while (event.changes.any { it.pressed })
    }
}

/** 把整套 Typography 的每个字号按 [scale] 放大（lineHeight 同步），用于练习页字号缩放。 */
private fun scaledTypography(base: Typography, scale: Float): Typography {
    if (scale <= 0f || scale == 1f) return base
    fun s(ts: TextStyle): TextStyle = ts.copy(fontSize = ts.fontSize * scale, lineHeight = ts.lineHeight * scale)
    return Typography(
        displayLarge = s(base.displayLarge),
        displayMedium = s(base.displayMedium),
        displaySmall = s(base.displaySmall),
        headlineLarge = s(base.headlineLarge),
        headlineMedium = s(base.headlineMedium),
        headlineSmall = s(base.headlineSmall),
        titleLarge = s(base.titleLarge),
        titleMedium = s(base.titleMedium),
        titleSmall = s(base.titleSmall),
        bodyLarge = s(base.bodyLarge),
        bodyMedium = s(base.bodyMedium),
        bodySmall = s(base.bodySmall),
        labelLarge = s(base.labelLarge),
        labelMedium = s(base.labelMedium),
        labelSmall = s(base.labelSmall)
    )
}

// ========== AI 难度信息采集页（Pro）==========

/** 难度信息采集页：难度（难/中等/合适）+ 挖空策略（均衡/薄弱优先/全覆盖）+ 可选自定义需求。 */
@Composable
private fun DifficultyInfoPage(
    mode: BlancallMode,
    initialStrategy: BlancallGenerator.Strategy,
    onConfirm: (String, String, BlancallGenerator.Strategy) -> Unit,
    onSkipToLocal: () -> Unit,
    onCancel: () -> Unit
) {
    var selected by remember { mutableStateOf("合适") }
    var custom by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(initialStrategy) }

    data class DiffOption(val label: String, val desc: String)
    val options = remember {
        listOf(
            DiffOption("难", "挖空密度高，空多，挑战更大"),
            DiffOption("中等", "挖空密度适中，均衡练习"),
            DiffOption("合适", "按内容难度自动挑选重点")
        )
    }
    data class StrategyOption(val value: BlancallGenerator.Strategy, val label: String, val desc: String)
    val strategyOptions = remember {
        listOf(
            StrategyOption(BlancallGenerator.Strategy.BALANCED, "均衡", "兼顾重点与覆盖面"),
            StrategyOption(BlancallGenerator.Strategy.WEAKNESS_FOCUS, "薄弱优先", "优先挖易错内容"),
            StrategyOption(BlancallGenerator.Strategy.FULL_COVERAGE, "全覆盖", "均匀覆盖全文")
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 跳过 AI：直接用本地算法生成（不联网，立即开始练习）——置于「选择挖空难度」上方
            OutlinedButton(
                onClick = onSkipToLocal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("使用本地算法", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text("选择挖空难度", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "AI 将按你选定的难度与策略为「${modeLabel(mode)}」生成挖空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            options.forEach { opt ->
                val isSel = selected == opt.label
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selected = opt.label },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { selected = opt.label })
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.label, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(opt.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            // ── 挖空策略（与练习页 ⋮ 菜单共用同一状态，所选会同步过去） ──
            Text("挖空策略", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            strategyOptions.forEach { opt ->
                val isSel = strategy == opt.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { strategy = opt.value },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { strategy = opt.value })
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.label, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(opt.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = custom,
                onValueChange = { if (it.length <= 120) custom = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补充要求（可选）") },
                placeholder = { Text("如：只挖第一段、空数少一些…") },
                supportingText = {
                    Text("仅作为挖空的「范围/空数」参考，与背诵无关的内容将被忽略",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                minLines = 1,
                maxLines = 3
            )

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(selected, custom, strategy) },
                    modifier = Modifier.weight(1.6f)
                ) { Text("开始生成") }
            }
        }
    }
}

/**
 * 本地挖空信息采集页（「使用AI挖空」关闭时）：只采集挖空策略与段落选择，
 * 确认后由本地算法生成，不涉及 AI 难度与自定义需求。
 */
@Composable
private fun LocalClozeCollectPage(
    initialStrategy: BlancallGenerator.Strategy,
    initialSectionMode: SectionMode,
    initialSelected: Set<Int>,
    sections: List<SectionSplitter.Section>,
    rankedSections: List<SectionSplitter.RankedSection>,
    onConfirm: (BlancallGenerator.Strategy, SectionMode, Set<Int>) -> Unit,
    onCancel: () -> Unit
) {
    var strategy by remember { mutableStateOf(initialStrategy) }
    var sectionMode by remember { mutableStateOf(initialSectionMode) }
    var selected by remember { mutableStateOf(initialSelected) }

    data class StrategyOption(val value: BlancallGenerator.Strategy, val label: String, val desc: String)
    val strategyOptions = remember {
        listOf(
            StrategyOption(BlancallGenerator.Strategy.BALANCED, "均衡", "兼顾重点与覆盖面"),
            StrategyOption(BlancallGenerator.Strategy.WEAKNESS_FOCUS, "薄弱优先", "优先挖易错内容"),
            StrategyOption(BlancallGenerator.Strategy.FULL_COVERAGE, "全覆盖", "均匀覆盖全文")
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("选择挖空设置", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "本地算法生成挖空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ── 挖空策略（与练习页 ⋮ 菜单共用同一语义） ──
            Text("挖空策略", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            strategyOptions.forEach { opt ->
                val isSel = strategy == opt.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { strategy = opt.value },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { strategy = opt.value })
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.label, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(opt.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            // ── 段落选择（与练习页 ⋮ 菜单「段落分层」同一语义） ──
            Text("段落选择", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            SectionModeItem(
                emoji = "📖", label = "全文连贯",
                desc = "按原文顺序，覆盖所有段落",
                selected = sectionMode == SectionMode.FULL,
                onClick = {
                    sectionMode = SectionMode.FULL
                    selected = sections.map { it.index }.toSet()
                }
            )
            SectionModeItem(
                emoji = "🎯", label = "薄弱集训",
                desc = "只练错题集中的段落",
                selected = sectionMode == SectionMode.WEAKNESS,
                onClick = {
                    sectionMode = SectionMode.WEAKNESS
                    val weak = rankedSections.filter { it.errorRate > 0f }.map { it.section.index }.toSet()
                    selected = if (weak.isEmpty()) sections.map { it.index }.toSet() else weak
                }
            )
            SectionModeItem(
                emoji = "✂️", label = "自选段落",
                desc = "手动勾选要复习的段落",
                selected = sectionMode == SectionMode.SELECTED,
                onClick = { sectionMode = SectionMode.SELECTED }
            )

            // 自选模式 → 段落勾选列表
            if (sectionMode == SectionMode.SELECTED) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selected.size}/${sections.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        selected = if (selected.size == sections.size) {
                            sections.firstOrNull()?.let { setOf(it.index) } ?: emptySet()
                        } else {
                            sections.map { it.index }.toSet()
                        }
                    }) {
                        Text(if (selected.size == sections.size) "取消全选" else "全选",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                sections.forEach { section ->
                    val isSel = section.index in selected
                    val errorRate = rankedSections
                        .find { it.section.index == section.index }?.errorRate ?: 0f
                    val heatColor = when {
                        errorRate >= 0.5f -> Color(0xFFE53935)
                        errorRate >= 0.3f -> Color(0xFFFB8C00)
                        errorRate >= 0.1f -> Color(0xFFFDD835)
                        errorRate > 0f -> Color(0xFF66BB6A)
                        else -> Color.Unspecified
                    }
                    SectionCheckItem(
                        label = section.heading ?: section.contentOnly.take(30),
                        index = section.index,
                        isSelected = isSel,
                        heatColor = heatColor,
                        hasError = errorRate > 0f,
                        onClick = {
                            selected = if (isSel) {
                                if (selected.size > 1) selected - section.index else selected
                            } else {
                                selected + section.index
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(strategy, sectionMode, selected) },
                    modifier = Modifier.weight(1.6f)
                ) { Text("开始") }
            }
        }
    }
}

/** AI 生成挖空加载页：显示实时进度（已接收字符数递增=AI 在生成），可随时用本地算法兜底。 */
@Composable
private fun AiGeneratingPage(mode: BlancallMode, difficulty: String, progress: Int, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(18.dp))
            Text("AI 正在生成挖空…", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${modeLabel(mode)} · $difficulty",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            // 进度条：已接收字符数递增表明 AI 确实在生成；停滞不增则说明卡住
            LinearProgressIndicator(
                progress = { (progress.toFloat() / 8000f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .height(6.dp),
                strokeCap = StrokeCap.Round
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (progress <= 0) "正在连接 AI…" else "已返回 $progress 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AI 只返回挖空坐标，原文不会被改动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            // 兜底：超过 40 秒无响应会自动回退本地；这里允许用户主动结束等待
            OutlinedButton(onClick = onCancel) {
                Text("等太久？用本地算法生成", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
