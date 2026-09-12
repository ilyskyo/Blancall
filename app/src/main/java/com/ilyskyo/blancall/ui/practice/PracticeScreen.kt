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
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_MENU_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.theme.AppPrefs
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
fun PracticeScreen(navController: NavController, articleIds: List<Long>, initialMode: BlancallMode? = null, resume: Boolean = false, initialSectionMode: SectionMode? = null, initialConfigId: Long = -1L) {
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
    val context = LocalContext.current

    // 自定义挖空：从配置列表页「开始练习」进入（?configId=）——应用配置直接开始，无弹层
    var customConfigApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialConfigId, article?.id) {
        if (initialConfigId > 0 && article != null && articleIds.size == 1 && !customConfigApplied) {
            customConfigApplied = true
            // 配置读取放 IO 线程；配置不存在（已被删除等）给出提示，不静默卡在随机挖空
            val cfg = withContext(Dispatchers.IO) {
                CustomClozeStore.getInstance(context.filesDir).getConfigs(articleIds.first())
                    .firstOrNull { it.id == initialConfigId }
            }
            if (cfg != null) {
                vm.startCustomPractice(cfg)
                modeSelected = true
            } else {
                Toast.makeText(context, "自定义配置不存在或已被删除", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var showBackWarning by remember { mutableStateOf(false) }
    var backWarningNoMore by remember { mutableStateOf(false) }
    val customConfigName by vm.customConfigName.collectAsState()

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
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = 600.dp).padding(horizontal = 16.dp, vertical = 16.dp)) {
        // ── 顶部导航：返回 + 文章标题 + 操作 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回：未提交过作答必须先弹窗确认（返回本身不承担保存，保存用右上角提交）
            val handlePracticeBack = {
                val hasUnsaved = modeSelected && !isSubmitted &&
                    (userAnswers.values.any { it.isNotBlank() } || dictationInput.isNotBlank())
                if (hasUnsaved && !AppPrefs.practiceBackWarningDisabled) {
                    showBackWarning = true
                } else {
                    navController.popBackStack()
                }
            }
            // 系统返回手势/返回键也走同一逻辑，否则会绕过弹窗直接退出
            BackHandler(onBack = { handlePracticeBack() })
            BackButton(onClick = { handlePracticeBack() })
            Spacer(Modifier.width(8.dp))
            // 顶部标题：左右滑动可快速切换三种练习模式（AI 开启时按已选难度重新生成）
            val latestMode by rememberUpdatedState(mode)
            val titleSwipeEnabled by rememberUpdatedState(modeSelected && !isSubmitted && customConfigName == null)
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
                            modifier = Modifier.size(22.dp),
                            contentDescription = "更多"
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
                                // 自定义练习时显示用户命名的配置名
                                customConfigName ?: modeLabel(mode),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    // 自定义练习：只保留显示提示，其余菜单项全部隐藏
                    if (customConfigName == null) {
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
                    } // end: 自定义练习隐藏区（切换模式/挖空策略）
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
                    // ── 古文模式（字词模式时显示，保留菜单；自定义练习隐藏）──
                    if (customConfigName == null && mode == BlancallMode.WORD) {
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
                    // ── 沉浸模式（一次性动作，关闭菜单；自定义练习隐藏）──
                    if (customConfigName == null) {
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
                    } // end: 沉浸模式（自定义练习隐藏）
                    // ── 段落分层（始终显示；自定义练习隐藏）──
                    if (customConfigName == null) {
                    GlassMenuDivider()
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
                    } // end: 段落分层（自定义练习隐藏）
                    // ── 导出 / 分享（已提交时；自定义练习隐藏）──
                    if (isSubmitted && customConfigName == null) {
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

        // AI 已开启但未配置有效对话密钥：明确提示，避免"开启 AI 挖空却仍是本地"的困惑
        if (AppPrefs.aiEnabled && !vm.isAiAvailable()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ℹ️", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "AI 挖空已开启，但尚未在「设置 → AI 配置」中添加并启用有效的对话配置，当前使用本地算法。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 3,
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
                            desc = "隐藏完复句子，适合段落背诵",
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
                    desc = "分句/半句/复句 — 理解式记忆",
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

    // ── 返回未保存提示弹窗（返回本身不承担保存；要保存请点右上角提交）──
    if (showBackWarning) {
        BlancallAlertDialog(
            onDismissRequest = { showBackWarning = false },
            title = { Text("还有未提交的作答", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "请在练习页点右上角「提交」保存本次作答情况；点返回键不会保存当前的作答。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = backWarningNoMore,
                            onCheckedChange = {
                                backWarningNoMore = it
                                // 勾选即持久化：之后点返回键直接退出，不再弹窗
                                AppPrefs.practiceBackWarningDisabled = it
                            }
                        )
                        Text(
                            "以后不再提示，直接返回",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackWarning = false }) { Text("返回作答") }
            }
        )
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
                    desc = "每个分句都有机会，均匀分布",
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
