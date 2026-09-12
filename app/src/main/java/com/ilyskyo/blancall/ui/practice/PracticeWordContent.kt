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


// ========== 字词挖空 UI ==========

@Composable
internal fun WordClozeContent(
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
internal fun BlankCard(
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
internal fun BlankCountWarningBanner(
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
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentDescription = "关闭"
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
