// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.ui.common.BackButton
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

    // 挖空策略与古文模式
    val strategy by vm.strategy.collectAsState()
    val classicalMode by vm.classicalMode.collectAsState()

    // 三点菜单点位（必须在 LaunchedEffect 之前声明）
    var modeSelected by remember { mutableStateOf(false) }
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
            Text(
                text = article?.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 已提交：快捷操作
            if (isSubmitted && modeSelected) {
                TextButton(onClick = { vm.reset() }) { Text("重做") }
            }
            // 未提交：提交按钮
            if (!isSubmitted && modeSelected && totalBlanks > 0) {
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
                            Switch(
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
                                Switch(
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

        // ── 进度条（沉浸模式下隐藏）──
        if (!immersiveMode && !isSubmitted && totalBlanks > 0) {
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

        // 内容区域
        Box(modifier = Modifier.weight(1f)) {
            if (article == null || (modeSelected && (
                (mode == BlancallMode.SENTENCE && sentenceCloze == null)
                || (mode == BlancallMode.WORD && wordCloze == null)
                || (mode == BlancallMode.REVERSE && dictationResult == null)
            ))) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在生成挖空...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (!modeSelected) {
                // 等待用户选择模式（浮层会覆盖此处）
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在准备...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "modeContent"
                ) { currentMode ->
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
                            showHint = showHint,
                            onViewArticleData = viewArticleData,
                            onAnswerChange = { i, a -> vm.updateAnswer(i, a) }
                        )
                        BlancallMode.WORD -> WordClozeContent(
                            blancall = wordCloze,
                            userAnswers = userAnswers,
                            checkResults = checkResults,
                            isSubmitted = isSubmitted,
                            showHint = showHint,
                            onViewArticleData = viewArticleData,
                            onAnswerChange = { i, a -> vm.updateAnswer(i, a) }
                        )
                        BlancallMode.REVERSE -> DictationContent(
                            dictationResult = dictationResult,
                            userInput = dictationInput,
                            checkResult = dictationCheckResult,
                            isSubmitted = isSubmitted,
                            onViewArticleData = viewArticleData,
                            onInputChange = { vm.updateDictationInput(it) }
                        )
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
                .clickable(enabled = false) { },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
                            vm.setMode(BlancallMode.SENTENCE)
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
                            vm.setMode(BlancallMode.WORD)
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
                            vm.setMode(BlancallMode.REVERSE)
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
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("切换练习模式", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                // 句子挖空
                RadioListItem(
                    emoji = "📝", label = "句子挖空",
                    desc = "从句/半句/整句 — 理解式记忆",
                    selected = mode == BlancallMode.SENTENCE,
                    onClick = { vm.setMode(BlancallMode.SENTENCE); showModeSheet = false }
                )
                // 字词挖空
                RadioListItem(
                    emoji = "🔤", label = "字词挖空",
                    desc = "1-3字词精准填空 — 细节记忆",
                    selected = mode == BlancallMode.WORD,
                    onClick = { vm.setMode(BlancallMode.WORD); showModeSheet = false }
                )
                // 反向默写
                RadioListItem(
                    emoji = "✍️", label = "反向默写",
                    desc = "把段落打散后默写原文 — 深度回忆",
                    selected = mode == BlancallMode.REVERSE,
                    onClick = { vm.setMode(BlancallMode.REVERSE); showModeSheet = false }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── 二级：挖空策略 BottomSheet ──
    if (showStrategySheet) {
        ModalBottomSheet(
            onDismissRequest = { showStrategySheet = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("选择挖空策略", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
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
        ModalBottomSheet(
            onDismissRequest = { showSectionSheet = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                        HintRow("✓ 只批改已完成的部分")
                        Spacer(Modifier.height(4.dp))
                        HintRow("✓ 未填写内容不会计入错误统计")
                        Spacer(Modifier.height(4.dp))
                        HintRow("✓ 当前练习进度会被保存，下次可以继续")
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
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceClozeContent(
    blancall: BlancallGenerator.SentenceClozeResult?,
    userAnswers: Map<Int, String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    isSubmitted: Boolean,
    showHint: Boolean,
    onViewArticleData: (() -> Unit)? = null,
    onAnswerChange: (Int, String) -> Unit
) {
    blancall?.let { result ->
        var currentBlankIndex by remember { mutableIntStateOf(0) }
        val blanks = result.blanks
        val totalBlanks = blanks.size
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
                                        isSubmitted = isSubmitted, showHint = showHint,
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
                    OutlinedTextField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入被挖掉的内容") },
                        minLines = 2, maxLines = 3
                    )
                    // 提示文字
                    if (showHint) {
                        val hintBlank = blanks.getOrNull(currentBlankIndex)
                        if (hintBlank != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildHintText(hintBlank.originalText),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                        item(key = "score") { BlancallScoreCard(checkResults, onViewArticleData) }
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
                                        isSubmitted = isSubmitted, showHint = showHint,
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
                    OutlinedTextField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入被挖掉的内容") },
                        minLines = 2, maxLines = 3
                    )
                    // 提示文字
                    if (showHint) {
                        val hintBlank = blanks.getOrNull(currentBlankIndex)
                        if (hintBlank != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildHintText(hintBlank.originalText),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
    showHint: Boolean,
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
            Text(answer, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
        } else {
            Text("＿＿＿＿",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 提示文字（未提交 + 开启提示 + 未填写时显示）
        if (showHint && !isSubmitted && answer.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                buildHintText(blank.originalText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 生成提示文字：首字 + ... + 字数 */
private fun buildHintText(original: String): String {
    if (original.isEmpty()) return ""
    if (original.length == 1) return "（共1字）"
    return "首字：${original.first()}...（共${original.length}字）"
}

// ========== 字词挖空 UI ==========

@Composable
private fun WordClozeContent(
    blancall: BlancallGenerator.WordClozeResult?,
    userAnswers: Map<Int, String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    isSubmitted: Boolean,
    showHint: Boolean = false,
    onViewArticleData: (() -> Unit)? = null,
    onAnswerChange: (Int, String) -> Unit
) {
    blancall?.let {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 提交后顶部展示评分卡
            if (isSubmitted) {
                item(key = "score") { BlancallScoreCard(checkResults, onViewArticleData) }
            }
            itemsIndexed(blancall.sentences, key = { idx, _ -> "s_$idx" }) { _, sentence ->
                if (sentence.blanks.isEmpty()) {
                    Text(sentence.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
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
                                    showHint = showHint,
                                    originalText = blank.originalChar,
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
    showHint: Boolean = false,
    originalText: String = "",
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
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitted,
                placeholder = { Text("请输入答案") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = !multiline,
                maxLines = if (multiline) 3 else 1,
                isError = isSubmitted && checkDetail?.result != AnswerChecker.Result.CORRECT,
                shape = RoundedCornerShape(8.dp)
            )
            // 提示文字
            if (showHint && !isSubmitted && value.isBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildHintText(originalText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSubmitted && checkDetail != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        )
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
    onViewArticleData: (() -> Unit)? = null,
    onInputChange: (String) -> Unit
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
                OutlinedTextField(
                    value = userInput,
                    onValueChange = onInputChange,
                    // 注意：TextField 自带内部滚动，禁止再套 verticalScroll，
                    // 双重滚动容器会在 IME 提交文本（英文/标点即时 commit）时
                    // 触发测量冲突崩溃（中文走候选框流程不触发）
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                    placeholder = { Text("按原文顺序默写整段，可把复制下来的从句拼回去…") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        } else if (checkResult != null) {
            // 评分卡：综合得分 + 覆盖率/准确率/顺序正确率 + 查看本篇文章数据
            item(key = "scoreCard") { DictationScoreCard(checkResult, onViewArticleData) }
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
    onViewArticleData: (() -> Unit)? = null
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
                Text("全部正确 ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
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
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text("段落复习模式", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("${sections.size} 个段落", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

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
