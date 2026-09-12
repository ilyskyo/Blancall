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


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SentenceClozeContent(
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
internal fun SentenceBlankInline(
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
internal fun HintGhost(hintChar: Char?, show: Boolean = true, modifier: Modifier = Modifier) {
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
internal fun HintOutlinedField(
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
