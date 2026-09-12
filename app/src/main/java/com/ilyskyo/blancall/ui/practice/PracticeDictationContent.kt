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


/**
 * 反向默写内容区：展示打乱顺序的挖空分句作为线索，每个分句旁有复制按钮，
 * 用户复制下来还原顺序后，在下方输入框默写原文。
 * 提交后展示整段判分（覆盖率/准确率/顺序正确率/综合得分 + 逐句对比）。
 * - 暗色模式：全部使用 MaterialTheme.colorScheme，自动适配
 * - 无障碍：输入框带 label，结果区带 contentDescription
 * - 键盘滚动：输入框内部可滚动，整页 LazyColumn 避免键盘遮挡
 */
@Composable
internal fun DictationContent(
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
        // ── 线索区：打乱顺序的挖空分句 ──
        item(key = "clueTitle") {
            Text("默写线索（分句已打乱顺序并挖空，点复制可复制单句）",
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
                    placeholder = "按原文顺序默写整段，可把复制下来的分句拼回去…",
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


/** 反向默写单句线索卡片：展示挖空分句 + 复制按钮 */
@Composable
internal fun DictationClauseCard(
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
            // 复制按钮：复制挖好空的分句文本，方便用户拼回去默写
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("默写分句", clause.displayText)
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


/** 反向默写评分卡片：醒目综合得分行 + 覆盖率/准确率/顺序正确率，右侧查看本篇文章数据 */
@Composable
internal fun DictationScoreCard(
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
