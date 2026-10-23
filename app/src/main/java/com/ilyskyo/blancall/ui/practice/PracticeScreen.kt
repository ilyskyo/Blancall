// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.stateSwap
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.CoachMark
import com.ilyskyo.blancall.ui.common.CoachShape
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.coachAnchor
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.rememberCoachAnchor
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_MENU_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.common.LocalIsLargeScreen
import com.ilyskyo.blancall.ui.common.PracticeMaxWidth
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.PracticeViewModel
import com.ilyskyo.blancall.ui.viewmodel.SectionMode
import com.ilyskyo.blancall.ui.viewmodel.ensureHintTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(navController: NavController, articleIds: List<Long>, initialMode: BlancallMode? = null, resume: Boolean = false, initialSectionMode: SectionMode? = null, initialConfigId: Long = -1L, initialFocusSentence: Int = -1) {
    val vm: PracticeViewModel = viewModel()
    val article by vm.article.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val sentenceCloze by vm.sentenceCloze.collectAsStateWithLifecycle()
    val wordCloze by vm.wordCloze.collectAsStateWithLifecycle()
    // 反向默写（段落打散默写）：打乱顺序的句子线索 + 用户默写输入 + 判分结果
    val dictationResult by vm.dictationResult.collectAsStateWithLifecycle()
    val dictationInput by vm.dictationInput.collectAsStateWithLifecycle()
    val dictationCheckResult by vm.dictationCheckResult.collectAsStateWithLifecycle()
    val userAnswers by vm.userAnswers.collectAsStateWithLifecycle()
    val checkResults by vm.checkResults.collectAsStateWithLifecycle()
    val isSubmitted by vm.isSubmitted.collectAsStateWithLifecycle()
    val isSubmitting by vm.isSubmitting.collectAsStateWithLifecycle()
    val resumed by vm.resumed.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val totalBlanks by vm.totalBlanks.collectAsStateWithLifecycle()

    val wordBlankCount by vm.wordBlankCount.collectAsStateWithLifecycle()

    // 段落分层（F3）
    val sections by vm.sections.collectAsStateWithLifecycle()
    val sectionMode by vm.sectionMode.collectAsStateWithLifecycle()
    val selectedSections by vm.selectedSections.collectAsStateWithLifecycle()
    val focusWindowActive by vm.focusWindowActive.collectAsStateWithLifecycle()
    val rankedSections by vm.rankedSections.collectAsStateWithLifecycle()

    // 沉浸模式（F4）
    val immersiveMode by vm.immersiveMode.collectAsStateWithLifecycle()
    val progressiveLevel by vm.progressiveLevel.collectAsStateWithLifecycle()

    // 跨文本联动（F7）
    val isCrossMode by vm.isCrossMode.collectAsStateWithLifecycle()
    val crossArticleTitles by vm.crossArticleTitles.collectAsStateWithLifecycle()
    val crossSourceInfo by vm.crossSourceInfo.collectAsStateWithLifecycle()

    // AI 挖空（Pro）：难度采集页 + 生成加载态 + 字号缩放（双指）
    // AI 总开关与「使用AI挖空」子开关用 Flow 响应式读取：设置页切换后返回练习页能立即反映
    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsStateWithLifecycle()
    val useAiCloze by AppPrefs.useAiClozeFlow.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val showDifficultyInfo by vm.showDifficultyInfo.collectAsStateWithLifecycle()
    val isAiGenerating by vm.isAiGenerating.collectAsStateWithLifecycle()
    val aiProgress by vm.aiProgress.collectAsStateWithLifecycle()
    val aiError by vm.aiError.collectAsStateWithLifecycle()
    val difficulty by vm.difficulty.collectAsStateWithLifecycle()
    // 填空辅助提示（弱提示淡显 / 强提示统计）
    val hintChars by vm.hintChars.collectAsStateWithLifecycle()
    val weakHintCount by vm.weakHintCount.collectAsStateWithLifecycle()
    val strongHintCount by vm.strongHintCount.collectAsStateWithLifecycle()
    // 反向默写整段输入提示
    val dictationHint by vm.dictationHint.collectAsStateWithLifecycle()
    // 训练分析（Pro，Markdown）
    val trainingAnalysis by vm.trainingAnalysis.collectAsStateWithLifecycle()
    val analysisLoading by vm.analysisLoading.collectAsStateWithLifecycle()
    val analysisError by vm.analysisError.collectAsStateWithLifecycle()

    // 挖空策略与古文模式
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val classicalMode by vm.classicalMode.collectAsStateWithLifecycle()

    // 三点菜单点位（必须在 LaunchedEffect 之前声明）
    // rememberSaveable：旋转横屏重建后保持已选模式，避免答题界面退回"选择模式"
    var modeSelected by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 长按菜单里「显示提示」那一行打开的「提示设置」浮层（点击该行仍是原来的总开关）
    var showHintSheet by remember { mutableStateOf(false) }
    val hintLength by vm.hintLength.collectAsStateWithLifecycle()
    // 使用指引的锚点与"看过没"：同一屏最多弹一条，见两处 CoachMark 的 enabled 条件
    val titleAnchor = rememberCoachAnchor()
    // 「显示提示」那一行的锚点：长按它就打开提示设置，指引圈要落在这里
    val hintRowAnchor = rememberCoachAnchor()
    val coachSeen by AppPrefs.coachSeenFlow.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    // semantics 的 lambda 不是 @Composable 上下文，字符串必须先取出来
    val moreDesc = stringResource(com.ilyskyo.blancall.R.string.more)
    var showModeSheet by remember { mutableStateOf(false) }
    // 跨文复习「自定义挖空」：按文章分别选择配置浮层
    var showCrossCustomSheet by remember { mutableStateOf(false) }
    var showStrategySheet by remember { mutableStateOf(false) }
    var showSectionSheet by remember { mutableStateOf(false) }
    var showIncompleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current

    // 自定义挖空：从配置列表页「开始练习」进入（?configId=）——应用配置直接开始，无弹层
    // 用普通 remember + VM 侧 customConfigName 双重判定：进程死亡重建时 VM 已清空，
    // 必须重新应用配置（rememberSaveable 会把"已应用"误恢复为 true，导致随机挖空且不锁定）
    var customConfigApplied by remember { mutableStateOf(false) }
    LaunchedEffect(initialConfigId, article?.id) {
        if (initialConfigId > 0 && article != null && articleIds.size == 1 &&
            !customConfigApplied && vm.customConfigName.value == null
        ) {
            customConfigApplied = true
            // 配置读取放 IO 线程；按 (articleId, configId) 双键精确读取（不跨文章串篇）
            val cfg = withContext(Dispatchers.IO) {
                CustomClozeStore.getInstance(context.filesDir).getConfig(articleIds.first(), initialConfigId)
            }
            if (cfg != null) {
                if (vm.startCustomPractice(cfg)) {
                    modeSelected = true
                } else {
                    // 配置与当前文章内容不匹配（文章被修改变短等）：已自动回退随机生成，仅提示
                    Toast.makeText(context, resources.getString(com.ilyskyo.blancall.R.string.cloze_config_mismatch), Toast.LENGTH_LONG).show()
                    modeSelected = true
                }
            } else {
                Toast.makeText(context, resources.getString(com.ilyskyo.blancall.R.string.custom_config_missing), Toast.LENGTH_SHORT).show()
            }
        }
    }
    var showBackWarning by remember { mutableStateOf(false) }
    var backWarningNoMore by remember { mutableStateOf(false) }
    val customConfigName by vm.customConfigName.collectAsStateWithLifecycle()

    LaunchedEffect(articleIds) {
        if (articleIds.size > 1) {
            vm.loadArticles(articleIds)
        } else if (articleIds.size == 1) {
            // 初始段落模式（薄弱集训等）由 loadArticle 在段落数据就绪后统一应用；
            // focusSentence（句级薄弱榜直达）同样在此传入，由 loadArticle 归段后生效
            vm.loadArticle(
                articleIds.first(),
                resume = resume,
                initialSectionMode = initialSectionMode,
                focusSentence = initialFocusSentence
            )
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
    val showHint by vm.showHint.collectAsStateWithLifecycle()
    // 预览文案跟着两个开关实时变：把"组合"翻译成"你会看到什么"
    val hintPreview = stringResource(
        when {
            hintLength && showHint -> com.ilyskyo.blancall.R.string.hint_preview_both
            hintLength -> com.ilyskyo.blancall.R.string.hint_preview_length
            showHint -> com.ilyskyo.blancall.R.string.hint_preview_content
            else -> com.ilyskyo.blancall.R.string.hint_preview_none
        }
    )

    val blankCountWarning by vm.blankCountWarning.collectAsStateWithLifecycle()

    // PDF 导出（F8）
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    // 宽屏放宽内容上限：**只有「句子挖空」在未提交时需要横向空间** ——
    // 它内部会切成「左文章 / 右作答」双栏（见 SentenceClozeContent）。
    // 若外层仍卡 600dp，两栏各只剩约 290dp（中文 16sp 约 15 字/行）——
    // 那比手机单栏还难读，等于把平板做得比手机更差。
    // 其余模式（词卡、整段默写）是单列内容，保持 600dp 的可读上限：
    // 放宽只会把输入框拉成长条，并不会更好用。
    val practiceMaxWidth =
        if (LocalIsLargeScreen && mode == BlancallMode.SENTENCE && !isSubmitted) PracticeMaxWidth
        else 600.dp

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxSize().widthIn(max = practiceMaxWidth).padding(horizontal = 16.dp, vertical = 16.dp)) {
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
            // 横向速度采样：标题滑切换原来只看距离（80dp），
            // 于是「快甩一下但没甩够」什么也不发生，慢慢拖反而切了模式 ——
            // 和翻卡是同一类错：距离是位移，速度才是意图。
            val titleVelocity = remember { VelocityTracker() }
            val titleFlingCommitPx = with(LocalDensity.current) { Motion.flingCommitDpPerSec.dp.toPx() }
            Text(
                text = article?.title ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .coachAnchor(titleAnchor)
                    .offset { IntOffset(titleDrag.value.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        var accumulated = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f; scope.launch { titleDrag.snapTo(0f) } },
                            onDragCancel = {
                                accumulated = 0f
                                titleVelocity.resetTracking()
                                scope.launch { titleDrag.animateTo(0f, Motion.cardSettle()) }
                            },
                            onDragEnd = {
                                val v = titleVelocity.calculateVelocity().x
                                titleVelocity.resetTracking()
                                if (titleSwipeEnabled) {
                                    val flickHard = abs(v) >= titleFlingCommitPx
                                    when {
                                        accumulated <= -TitleSwipeThreshold || (flickHard && v < 0f) ->
                                            vm.switchMode(nextMode(latestMode))
                                        accumulated >= TitleSwipeThreshold || (flickHard && v > 0f) ->
                                            vm.switchMode(prevMode(latestMode))
                                    }
                                }
                                accumulated = 0f
                                // 回位是位移 → 弹簧（改造前是 tween(240)），并把手指的动量还进去
                                scope.launch {
                                    titleDrag.animateTo(0f, Motion.cardSettle(), initialVelocity = v)
                                }
                            }
                        ) { change, dragAmount ->
                            accumulated += dragAmount
                            @Suppress("DEPRECATION")
                            titleVelocity.addPosition(change.uptimeMillis, change.position)
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
                TextButton(onClick = { vm.reset() }) { Text(stringResource(com.ilyskyo.blancall.R.string.redo_desc)) }
            }
            // 未提交：提交按钮（选好模式、难度采集页关闭时才显示）
            if (!isSubmitted && modeSelected && !showDifficultyInfo && totalBlanks > 0) {
                // 反向默写是整段输入，判分逻辑与按空作答不同，单独处理
                if (mode == BlancallMode.REVERSE) {
                    TextButton(
                        onClick = { vm.submitAnswers() },
                        enabled = dictationInput.isNotBlank() && !isSubmitting
                    ) { Text(if (isSubmitting) stringResource(com.ilyskyo.blancall.R.string.practice_grading) else stringResource(com.ilyskyo.blancall.R.string.practice_submit)) }
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
                        Text(if (isSubmitting) stringResource(com.ilyskyo.blancall.R.string.practice_grading) else if (filled > 0) stringResource(com.ilyskyo.blancall.R.string.submit_progress, filled, totalBlanks) else stringResource(com.ilyskyo.blancall.R.string.practice_submit))
                    }
                }
            }
            // 三点菜单（玻璃质感）：提交后禁用，避免误切模式；重做后恢复
            if (modeSelected) {
                Box {
                    // 点击 = 菜单（模式/策略/提示总开关/沉浸/段落/导出）。
                    // 「提示设置」的长按不挂在这里，而是挂在下拉菜单的「显示提示」那一行上 ——
                    // 长按是隐藏手势，教的时候要能直接圈住"被长按的那一行"，挂在 ⋮ 上圈不到。
                    // 尺寸保持 40dp 以对齐顶栏其它按钮。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .pressClick(tier = PressTier.Icon, enabled = !isSubmitted) { showMoreMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            kind = AppIconKind.MoreVert,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(22.dp)
                                .semantics {
                                    contentDescription = moreDesc
                                },
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
                                stringResource(com.ilyskyo.blancall.R.string.current_mode),
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
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.switch_mode_ellipsis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
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
                                Text(stringResource(com.ilyskyo.blancall.R.string.cloze_strategy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
                    // ── 提示开关（保留菜单，符合系统开关直觉）──
                    GlassMenuItem(
                        onClick = { vm.toggleHint() },
                        modifier = Modifier.coachAnchor(hintRowAnchor),
                        // 长按这一行 = 打开「提示设置」（字数提示 / 内容提示 两个维度分开配）。
                        // 点击仍是原来的开关：单击切换总开关，长按才进细分。
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showHintSheet = true
                        },
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.show_hints), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
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
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.classical_mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
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
                                if (immersiveMode) stringResource(com.ilyskyo.blancall.R.string.exit_immersive) else stringResource(com.ilyskyo.blancall.R.string.enter_immersive),
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
                    val sectionLabel = when {
                        // 句级聚焦：只练目标句及其附近几句，跟段落选择无关，标签如实说明
                        focusWindowActive -> stringResource(com.ilyskyo.blancall.R.string.section_focus)
                        sectionMode == SectionMode.FULL -> stringResource(com.ilyskyo.blancall.R.string.section_full)
                        sectionMode == SectionMode.WEAKNESS -> stringResource(com.ilyskyo.blancall.R.string.section_weak)
                        else -> stringResource(com.ilyskyo.blancall.R.string.section_selected, selectedSections.size, sections.size)
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
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.export_pdf), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
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
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.share_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                        )
                    }
                }
                // ── 长按 ⋮ 打开的「提示设置」──
                // 为什么单独一个浮层而不是塞进上面的菜单：那个菜单已经在 12 项左右，
                // 提示设置是"调整怎么提示"，与"切换模式/段落"不同类，混在一起会越加越长。
                GlassDropdownMenu(
                    expanded = showHintSheet,
                    onDismissRequest = { showHintSheet = false },
                ) {
                    GlassMenuItem(
                        enabled = false,
                        onClick = {},
                        label = {
                            Text(
                                stringResource(com.ilyskyo.blancall.R.string.hint_sheet_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    GlassMenuItem(
                        onClick = { vm.toggleHintLength() },
                        label = {
                            Column {
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.hint_length_toggle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.hint_length_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailing = {
                            GlassSwitch(checked = hintLength, onCheckedChange = { vm.toggleHintLength() })
                        },
                    )
                    GlassMenuItem(
                        onClick = { vm.toggleHint() },
                        label = {
                            Column {
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.hint_content_toggle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.hint_content_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailing = {
                            GlassSwitch(checked = showHint, onCheckedChange = { vm.toggleHint() })
                        },
                    )
                    GlassMenuDivider()
                    // 预览：把"两个开关的组合"翻译成"用户实际会看到什么"——
                    // 光看两个开关名，很难想象"只开字数"和"同时开"的差别
                    GlassMenuItem(
                        enabled = false,
                        onClick = {},
                        label = {
                            Text(
                                hintPreview,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

        // ── 使用指引（一次性）──
        // 先教"长按菜单里的「显示提示」能配提示"，等这条看过了，下次进来才教"左右拖标题切模式" ——
        // 两条同时弹会互相压住，而提示这条更重要（提示是本页最常被误解的功能）。
        CoachMark(
            coachKey = "practice_more_long_press",
            anchor = hintRowAnchor,
            title = stringResource(com.ilyskyo.blancall.R.string.coach_practice_more_title),
            body = stringResource(com.ilyskyo.blancall.R.string.coach_practice_more_body),
            shape = CoachShape.Card,
            // 只在「显示提示」那一行真的在屏幕上时才弹（即菜单打开时）。菜单没开时锚点是
            // Rect.Zero、组件本来也不会显示，这里显式写上更清楚。
            enabled = showMoreMenu,
            delayMs = 300L,
        )
        CoachMark(
            coachKey = "practice_title_swipe",
            anchor = titleAnchor,
            title = stringResource(com.ilyskyo.blancall.R.string.coach_practice_title_title),
            body = stringResource(com.ilyskyo.blancall.R.string.coach_practice_title_body),
            shape = CoachShape.Card,
            enabled = "practice_more_long_press" in coachSeen,
            delayMs = 900L,
        )

        Spacer(Modifier.height(10.dp))

        // 跨文本联动指示器（F7）
        if (isCrossMode && crossArticleTitles.size > 1) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔗", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.reader_cross_cloze),
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 跨文不使用单篇自定义配置：预期落差在此显式说明
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.mixed_smart_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
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
                        text = stringResource(com.ilyskyo.blancall.R.string.ai_cloze_failed_local, msg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 「使用AI挖空」已开启、AI 总开关也打开，但没有可用的对话配置：
        // 此时挖空会静默回退本地算法，必须明确提示，避免"开了 AI 挖空却仍是本地"的困惑。
        // 注意：未开启「使用AI挖空」时本地算法是用户的主动选择，不应提示。
        if (aiEnabled && useAiCloze && !vm.isAiAvailable()) {
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
                        text = stringResource(com.ilyskyo.blancall.R.string.ai_cloze_no_profile),
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
                // 文章不存在/被删除（含跨文所选文章全部缺失）：错误态替代无限"正在生成挖空"
                loadFailed -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(com.ilyskyo.blancall.R.string.article_missing_deleted),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(com.ilyskyo.blancall.R.string.back)) }
                        }
                    }
                }
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
                            Text(stringResource(com.ilyskyo.blancall.R.string.generating_blanks), style = MaterialTheme.typography.bodySmall,
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
                            Text(stringResource(com.ilyskyo.blancall.R.string.preparing), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { stateSwap() },
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
                                    showBlankLength = hintLength,
                                    userAnswers = userAnswers,
                                    checkResults = checkResults,
                                    isSubmitted = isSubmitted,
                                    hintChars = hintChars,
                                    crossSourceInfo = crossSourceInfo,
                                    weakHints = weakHintCount,
                                    strongHints = strongHintCount,
                                    analysis = trainingAnalysis,
                                    analysisLoading = analysisLoading,
                                    analysisError = analysisError,
                                    onRetryAnalysis = { vm.generateTrainingAnalysis() },
                                    onViewArticleData = viewArticleData,
                                    onBlankFocus = { vm.ensureHintTimer(it) },
                                    onAnswerChange = { i, a -> vm.updateAnswer(i, a) },
                                    onInkCommitted = { i, s, w, h -> vm.appendInk(i, s, w, h) }
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
                                    onAnswerChange = { i, a -> vm.updateAnswer(i, a) },
                                    onInkCommitted = { i, s, w, h -> vm.appendInk(i, s, w, h) }
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
    //
    // scrim 搬出缩放子树：改造前它和卡片同属一个 AnimatedVisibility 的内容，
    // `scaleIn(0.9f)` 把**遮罩也一起缩小** —— 动画开头屏幕四周约 10% 是没变暗的页面，
    // MediumBouncy 过冲时那块暗层还会被甩出屏外。遮罩的职责只是压暗，不需要被搬动，
    // 所以它单独走 alpha 补间，卡片继续走缩放弹簧（法则三：位移=弹簧，alpha=tween）。
    val pickerVisible = !modeSelected && article != null
    PickerScrim(visible = pickerVisible)

    AnimatedVisibility(
        visible = pickerVisible,
        enter = fadeIn(MotionFade.alpha(MotionFade.enter)) +
                scaleIn(
                    animationSpec = Motion.press(),
                    initialScale = Motion.Scale.enterFrom
                ),
        exit = fadeOut(MotionFade.alpha(MotionFade.exit)) +
               scaleOut(
                   animationSpec = Motion.press(),
                   targetScale = Motion.Scale.dismissTo
               )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
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
                            stringResource(com.ilyskyo.blancall.R.string.pick_practice_mode),
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
                            title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
                            desc = stringResource(com.ilyskyo.blancall.R.string.mode_sentence_card_desc),
                            onClick = {
                                vm.onModeChosen(BlancallMode.SENTENCE)
                                modeSelected = true
                            }
                        )
                        Spacer(Modifier.height(10.dp))

                        // 字词挖空
                        ModeCard(
                            emoji = "🔤",
                            title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
                            desc = stringResource(com.ilyskyo.blancall.R.string.mode_word_card_desc),
                            onClick = {
                                vm.onModeChosen(BlancallMode.WORD)
                                modeSelected = true
                            }
                        )
                        Spacer(Modifier.height(10.dp))

                        // 反向默写
                        ModeCard(
                            emoji = "✍️",
                            title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse),
                            desc = stringResource(com.ilyskyo.blancall.R.string.mode_reverse_card_desc),
                            onClick = {
                                vm.onModeChosen(BlancallMode.REVERSE)
                                modeSelected = true
                            }
                        )
                        // 跨文复习专属：「自定义挖空」—— 按文章分别指定要应用的挖空配置
                        if (isCrossMode) {
                            Spacer(Modifier.height(10.dp))
                            ModeCard(
                                emoji = "🎯",
                                title = stringResource(com.ilyskyo.blancall.R.string.custom_cloze),
                                desc = stringResource(com.ilyskyo.blancall.R.string.mode_custom_card_desc),
                                onClick = { showCrossCustomSheet = true }
                            )
                        }
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
                Text(stringResource(com.ilyskyo.blancall.R.string.switch_practice_mode), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                // 句子挖空
                RadioListItem(
                    emoji = "📝", label = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
                    desc = stringResource(com.ilyskyo.blancall.R.string.mode_sentence_line),
                    selected = mode == BlancallMode.SENTENCE,
                    onClick = { vm.switchMode(BlancallMode.SENTENCE); showModeSheet = false }
                )
                // 字词挖空
                RadioListItem(
                    emoji = "🔤", label = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
                    desc = stringResource(com.ilyskyo.blancall.R.string.mode_word_line),
                    selected = mode == BlancallMode.WORD,
                    onClick = { vm.switchMode(BlancallMode.WORD); showModeSheet = false }
                )
                // 反向默写
                RadioListItem(
                    emoji = "✍️", label = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse),
                    desc = stringResource(com.ilyskyo.blancall.R.string.mode_reverse_line),
                    selected = mode == BlancallMode.REVERSE,
                    onClick = { vm.switchMode(BlancallMode.REVERSE); showModeSheet = false }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ── 跨文复习「自定义挖空」：按文章分别选择配置（未选择/配置被删的文章自然不参与挖空）──
    if (showCrossCustomSheet) {
        // 参与文章按混合来源顺序（distinctBy 保留首次出现顺序，即输入文章顺序）
        val crossArticles = crossSourceInfo
            .distinctBy { it.articleId }
            .map { it.articleId to it.articleTitle }
        CrossCustomClozeSheet(
            articles = crossArticles,
            onDismiss = { showCrossCustomSheet = false },
            onStart = { configs ->
                if (vm.startCrossCustomPractice(configs)) {
                    showCrossCustomSheet = false
                    modeSelected = true
                } else {
                    // 所有选中配置都没有可应用的标注（如文章被改短导致全部失效）：不进入空白练习
                    Toast.makeText(
                        context,
                        resources.getString(com.ilyskyo.blancall.R.string.no_applicable_blanks),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    // ── 返回未保存提示弹窗（返回本身不承担保存；要保存请点右上角提交）──
    if (showBackWarning) {
        BlancallAlertDialog(
            onDismissRequest = { showBackWarning = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.unsubmitted_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.unsubmitted_body),
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
                            stringResource(com.ilyskyo.blancall.R.string.back_no_more),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackWarning = false }) { Text(stringResource(com.ilyskyo.blancall.R.string.keep_answering)) }
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
                Text(stringResource(com.ilyskyo.blancall.R.string.pick_strategy), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                RadioListItem(
                    emoji = "⚖️", label = stringResource(com.ilyskyo.blancall.R.string.strategy_balanced),
                    desc = stringResource(com.ilyskyo.blancall.R.string.strategy_balanced_desc),
                    selected = strategy == BlancallGenerator.Strategy.BALANCED,
                    onClick = { vm.setStrategy(BlancallGenerator.Strategy.BALANCED); showStrategySheet = false }
                )
                RadioListItem(
                    emoji = "🎯", label = stringResource(com.ilyskyo.blancall.R.string.strategy_weakness),
                    desc = stringResource(com.ilyskyo.blancall.R.string.strategy_weakness_desc),
                    selected = strategy == BlancallGenerator.Strategy.WEAKNESS_FOCUS,
                    onClick = { vm.setStrategy(BlancallGenerator.Strategy.WEAKNESS_FOCUS); showStrategySheet = false }
                )
                RadioListItem(
                    emoji = "🔄", label = stringResource(com.ilyskyo.blancall.R.string.strategy_full),
                    desc = stringResource(com.ilyskyo.blancall.R.string.strategy_full_desc),
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

/**
 * 模式选择浮层的模态遮罩：压暗底层 + 消费点击。
 *
 * 补间器住在这一层而不是练习页里：之前宿主直接读 `pickerScrimAlpha > 0f` 当存在判据，
 * 于是淡入的每一帧都重组整个练习页（页面里有正文渲染与评分条）。现在只有绘制失效。
 * 名字不能就叫 alpha —— graphicsLayer 的 lambda 里那会解析到 scope 自己的属性。
 */
@Composable
private fun PickerScrim(visible: Boolean) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MotionFade.alpha(MotionFade.enter),
        label = "pickerScrim"
    )
    if (visible || scrimAlpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                // 模态遮罩：压暗底层页面，毛玻璃叠在内容页上不会显得"透穿破图"
                .background(Color.Black.copy(alpha = 0.32f))
                // 消费点击：浮层显示时阻断底层页面交互
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { },
        )
    }
}