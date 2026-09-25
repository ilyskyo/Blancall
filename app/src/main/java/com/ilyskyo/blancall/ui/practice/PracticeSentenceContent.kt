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
import com.ilyskyo.blancall.ui.common.NavRailWidth
import com.ilyskyo.blancall.ui.common.LocalIsLargeScreen
import com.ilyskyo.blancall.ui.common.TwoPaneMinHeightDp
import com.ilyskyo.blancall.ui.common.TwoPaneMinWidthDp
import com.ilyskyo.blancall.ui.common.suppressAsPalmMisTouch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
import com.ilyskyo.blancall.algorithm.CrossTextReview
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.ui.common.penTapToHandwriting
import com.ilyskyo.blancall.ui.handwriting.AnswerInputField
import com.ilyskyo.blancall.ui.handwriting.HandwritingAnswerSheet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    /** 跨文复习：混合句序 → 每句来源（尺寸与 sentences 一致时逐句标注；非跨文为空表） */
    crossSourceInfo: List<CrossTextReview.SourceInfo> = emptyList(),
    weakHints: Int = 0,
    strongHints: Int = 0,
    analysis: String? = null,
    analysisLoading: Boolean = false,
    analysisError: Boolean = false,
    onRetryAnalysis: () -> Unit = {},
    onViewArticleData: (() -> Unit)? = null,
    onBlankFocus: (Int) -> Unit = {},
    onAnswerChange: (Int, String) -> Unit,
    /** 墨迹上报（错题回顾）：手写被消费时回调（blankIdx 已在内部绑定）；透传给书写板与底部弹层 */
    onInkCommitted: ((Int, List<List<Offset>>, Int, Int) -> Unit)? = null
) {
    blancall?.let { result ->
        var currentBlankIndex by remember { mutableIntStateOf(0) }
        val blanks = result.blanks
        val totalBlanks = blanks.size
        // 手写态下点挖空 → 弹出底部书写板（就地书写）。null = 未打开
        var sheetBlankIndex by remember { mutableStateOf<Int?>(null) }
        val handwritingMode by AppPrefs.handwritingInputEnabledFlow.collectAsStateWithLifecycle()
        // 进入/切换焦点即启动提示计时：满足无操作时长即提示，不受"是否输入过"影响
        LaunchedEffect(currentBlankIndex, isSubmitted) {
            if (!isSubmitted) onBlankFocus(currentBlankIndex)
        }
        // 预计算按句分组的空位映射，避免在 LazyColumn 每个 item 里重复 filter（O(sentences*blanks)）
        val blanksBySentence = remember(blanks) {
            blanks.groupBy { it.sentenceIndex }
                .mapValues { (_, list) -> list.sortedBy { it.startInSentence } }
        }
        // 跨文句源标注表：尺寸与句子数一致才启用（防止错标到错误的句子）
        val crossTags: List<CrossTextReview.SourceInfo>? = remember(crossSourceInfo, result) {
            crossSourceInfo.takeIf { it.size == result.sentences.size }
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

        // 双栏判据：宽度要**扣掉大屏侧边导航栏**，高度也要够。
        //
        // ① 不能直接用 screenWidthDp：大屏下左侧有 NavRail，屏幕宽度会高估一个导航栏的宽度，
        //    在 700dp 级别的折叠屏竖屏上会把两栏切得都过窄。
        // ② 必须同时看高度：手机横屏宽度常达标（900dp），但高度只有 400dp 左右，
        //    右栏「输入区 + 手写板(≥140dp) + 切换 + 上一空/下一空」约 280dp 会被挤出可视区，
        //    表现为「书写板看不见/点不到」。只看宽度是错的。
        val config = LocalConfiguration.current
        val contentWidthDp =
            config.screenWidthDp - (if (LocalIsLargeScreen) NavRailWidth.value.toInt() else 0)
        val isWide = contentWidthDp >= TwoPaneMinWidthDp && config.screenHeightDp >= TwoPaneMinHeightDp

        // 当前作答目标的文种：按**该空的标准答案**自动选模型
        // （含 CJK → 汉字模型；纯拉丁 → EMNIST 英语模型）。
        // 练习页是唯一知道答案的地方，所以判断必须在这里做，而不是在书写板内部猜。
        val currentScript = HandwritingScript.forAnswer(
            blanks.getOrNull(currentBlankIndex)?.originalText.orEmpty()
        )
        // 生僻字守卫：当前空「已输入内容之后的下一个期望字符」；
        // 该字不在识别字表（生僻字）时，手写面板会禁止自动上屏并引导键盘输入。
        val currentExpectedNext: Char? = blanks.getOrNull(currentBlankIndex)?.originalText
            ?.getOrNull((userAnswers[currentBlankIndex] ?: "").length)

        // 已切到双栏时收起「就地书写」弹层：右栏作答区是常驻的，
        // 弹层留着会盖住左侧原文（旋转到横屏 / 展开折叠屏时会遇到）。
        LaunchedEffect(isWide) {
            if (isWide) sheetBlankIndex = null
        }

        if (isWide && !isSubmitted) {
            // 平板：左侧内容 + 右侧输入
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(result.sentences, key = { idx, _ -> "s_$idx" }) { sIdx, sentence ->
                        val sentenceBlanks = blanksBySentence[sIdx].orEmpty()
                        Column {
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
                                        onClick = {
                                            // 掌托守卫：手写时扶屏的手掌蹭到挖空，不应把书写板弹出来
                                            if (!suppressAsPalmMisTouch()) {
                                                currentBlankIndex = blank.index
                                                // 宽屏双栏下**不**弹底部书写板：右栏作答区本来就常驻，
                                                // 再叠一层弹层会盖住左侧原文 —— 而默写恰恰要一边看句子一边写。
                                                // 弹层只服务窄屏的「就地书写」。
                                            }
                                        }
                                    )
                                    lastPos = blank.endInSentence
                                }
                                if (lastPos < sentence.length) {
                                    Text(sentence.substring(lastPos), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                        // 跨文句源标注（仅尺寸一致时启用）
                        crossTags?.getOrNull(sIdx)?.let { src ->
                            if (src.articleTitle.isNotBlank()) {
                                Text(
                                    "— ${src.articleTitle}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                                )
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
                    AnswerInputField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "请输入被挖掉的内容",
                        hintChar = if (!isSubmitted) hintChars[currentBlankIndex] else null,
                        maxLines = 3,
                        minHeight = 74.dp,
                        expectedNextChar = currentExpectedNext,
                        // 英文默写的答案先验：剩余答案（容错匹配后整词提交）
                        expectedWord = blanks.getOrNull(currentBlankIndex)?.originalText
                            ?.drop((userAnswers[currentBlankIndex] ?: "").length)
                            ?.takeIf { it.isNotEmpty() },
                        script = currentScript
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
                        Column {
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
                                        // 笔点即书写：笔尖落在挖空上直接弹书写板，不用先手动切模式
                                        onPenTap = {
                                            currentBlankIndex = blank.index
                                            sheetBlankIndex = blank.index
                                        },
                                        onClick = {
                                            // 掌托守卫：手写时扶屏的手掌蹭到挖空，不应把书写板弹出来
                                            if (!suppressAsPalmMisTouch()) {
                                                currentBlankIndex = blank.index
                                                if (handwritingMode) sheetBlankIndex = blank.index
                                            }
                                        }
                                    )
                                    lastPos = blank.endInSentence
                                }
                                if (lastPos < sentence.length) {
                                    Text(sentence.substring(lastPos), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                        // 跨文句源标注（仅尺寸一致时启用）
                        crossTags?.getOrNull(sIdx)?.let { src ->
                            if (src.articleTitle.isNotBlank()) {
                                Text(
                                    "— ${src.articleTitle}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                                )
                            }
                        }
                        }
                    }
                }

                if (!isSubmitted) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    AnswerInputField(
                        value = userAnswers[currentBlankIndex] ?: "",
                        onValueChange = { onAnswerChange(currentBlankIndex, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "请输入被挖掉的内容",
                        hintChar = if (!isSubmitted) hintChars[currentBlankIndex] else null,
                        maxLines = 3,
                        minHeight = 74.dp,
                        expectedNextChar = currentExpectedNext,
                        // 英文默写的答案先验：剩余答案（同上）
                        expectedWord = blanks.getOrNull(currentBlankIndex)?.originalText
                            ?.drop((userAnswers[currentBlankIndex] ?: "").length)
                            ?.takeIf { it.isNotEmpty() },
                        script = currentScript,
                        // 墨迹上报（错题回顾）：该空的手写被消费时按当前空 index 上报
                        onInkCommitted = { s, w, h ->
                            onInkCommitted?.invoke(currentBlankIndex, s, w, h)
                        },
                        // 笔点作答区同样直接进书写（临时行为，不改「默认输入方式」）
                        onPenTap = { sheetBlankIndex = currentBlankIndex }
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

        // ── 就地书写面板：手写态下点击某个挖空后弹出 ──
        val sheetIdx = sheetBlankIndex
        if (sheetIdx != null && !isSubmitted) {
            HandwritingAnswerSheet(
                answer = userAnswers[sheetIdx] ?: "",
                hintChar = hintChars[sheetIdx],
                blankLabel = "第 ${sheetIdx + 1} 空",
                onAnswerChange = { onAnswerChange(sheetIdx, it) },
                onDismissRequest = { sheetBlankIndex = null },
                // 弹层是为**这个空**服务的，文种按这个空的答案选（可能当前焦点已不是它）
                script = HandwritingScript.forAnswer(
                    blanks.getOrNull(sheetIdx)?.originalText.orEmpty()
                ),
                // 生僻字守卫：同样按「这个空」的进度给下一个期望字符
                expectedNextChar = blanks.getOrNull(sheetIdx)?.originalText
                    ?.getOrNull((userAnswers[sheetIdx] ?: "").length),
                // 英文默写的答案先验：同样按「这个空」的进度给剩余答案
                expectedWord = blanks.getOrNull(sheetIdx)?.originalText
                    ?.drop((userAnswers[sheetIdx] ?: "").length)
                    ?.takeIf { it.isNotEmpty() },
                // 墨迹上报（错题回顾）：弹层是「这个空」的书写区，按 sheetIdx 绑定上报
                onInkCommitted = { s, w, h -> onInkCommitted?.invoke(sheetIdx, s, w, h) }
            )
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
    /**
     * 笔点该挖空时的动作（手机布局下弹底部书写板）。
     * 平板双栏不传：右栏本就是常驻作答区，弹层会盖住左侧原文，而默写要一边看句子一边写。
     */
    onPenTap: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            // ⚠️ clip 必须放在 background 与 clickable **之前**，一次解决两件事：
            // ① 「当前挖空」的选中底色变成圆角（原来是与圆角按键不一致的直角灰块）；
            // ② clickable 的按压/悬停指示器被裁成圆角 —— 指示器绘制在该节点边界内，
            //    链上若不先 clip，圆角控件上就会浮出一个直角矩形（真机复现过）。
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isCurrent && !isSubmitted)
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                else Modifier
            )
            // 笔点即书写：笔尖落在挖空上 → 弹书写板；手指点仍走下面的 clickable
            .penTapToHandwriting(enabled = onPenTap != null && !isSubmitted) { onPenTap?.invoke() }
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
 *
 * [bottomTrailing]：附件控件（如「手写/键盘」切换按钮），渲染在输入框**内部的
 * 右下角**（独立一行、靠右对齐）；null 时保持与旧版完全一致的布局与高度。
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
    imeAction: ImeAction = ImeAction.Default,
    bottomTrailing: (@Composable () -> Unit)? = null
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
                Column(modifier = Modifier.heightIn(min = minHeight)) {
                    Row(
                        modifier = Modifier
                            .padding(start = 16.dp, top = 14.dp, end = 16.dp)
                            .padding(bottom = if (bottomTrailing != null) 4.dp else 14.dp),
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
                    if (bottomTrailing != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 10.dp)
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            bottomTrailing()
                        }
                    }
                }
            }
        )
    }
}
