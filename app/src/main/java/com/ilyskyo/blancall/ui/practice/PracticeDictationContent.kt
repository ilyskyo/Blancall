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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.ui.common.StylusPresence
import com.ilyskyo.blancall.ui.common.penTapToHandwriting
import com.ilyskyo.blancall.ui.handwriting.AnswerInputField
import com.ilyskyo.blancall.ui.handwriting.HandwritingPanel
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
    val handwritingMode by AppPrefs.handwritingInputEnabledFlow.collectAsStateWithLifecycle()
    // 笔设备存在性：手写区入口与兜底文案按有无笔设备区分（见下方 writingVisible 分支）
    val stylusPresent by StylusPresence.present.collectAsStateWithLifecycle()
    // ⚠️ 手写文种必须按**原文**选：整段默写的目标就是原文，而 AnswerInputField 默认走中文模型 ——
    // 英文文章下会一个字都认不出（真机反馈「反向默写的手写没法用」的直接原因之一）。
    val dictationText = remember(dictation.clauses) { dictation.clauses.joinToString("") }
    val dictationScript = remember(dictationText) { HandwritingScript.forAnswer(dictationText) }
    // 生僻字守卫：已输入内容之后的下一个期望字符（古诗文生僻字手写认不出时引导键盘）
    val dictationExpectedNext: Char? = dictationText.getOrNull(userInput.length)
    // 英文默写的「答案先验」：剩余原文里的**当前词**（跳过前导空格，只取连续拉丁字母）。
    // 传给手写面板做容错匹配（纠正 o/0、1/l 等混淆后整词提交）；用户未严格对齐原文时
    // 匹配自然失败，无副作用。
    val dictationExpectedWord: String? = remember(dictationText, userInput) {
        val rest = dictationText.drop(userInput.length).trimStart()
        rest.takeWhile { it.isLetter() && it.code < 0x2E80 }.takeIf { it.isNotEmpty() }
    }
    // 手写作答区是否展开：手写模式开着就常驻；笔点了作答区也展开（笔来了就能写）。
    // ⚠️ 与「手写模式开关」解耦：开关只决定**默认**输入方式，笔点是临时行为。
    var penWriting by remember { mutableStateOf(false) }
    val writingVisible = handwritingMode || penWriting
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
                AnswerInputField(
                    value = userInput,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 笔点即书写：笔尖落在作答区 → 展开下面的手写作答区；手指点 → 照常弹键盘。
                        // 必须**消费整段笔事件**，否则笔的按下会漏给外层 LazyColumn ——
                        // 真机现象就是「页面跟着笔一起滚、字却写不进去」。
                        .penTapToHandwriting(key = "dictationInput") { penWriting = true }
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = "按原文顺序默写整段，可把复制下来的分句拼回去…",
                    hintChar = if (!isSubmitted) dictationHintChar else null,
                    maxLines = Int.MAX_VALUE,
                    minHeight = 90.dp,
                    // 键盘作答区永远是键盘态：手写另有下面那块常驻区，两者并存
                    allowHandwritingSwitch = false,
                    expectedNextChar = dictationExpectedNext,
                    expectedWord = dictationExpectedWord,
                    script = dictationScript
                )

                Spacer(Modifier.height(8.dp))
                // ── 手写作答区（常驻在页面上，不弹层）──
                // 用户明确要求：给一个输入区 + 一个手写区，手写区里写的字直接落到输入区。
                // 所以这里不用弹层、也不用「点开才出现」——手写模式开着就一直在。
                if (writingVisible) {
                    HandwritingPanel(
                        modifier = Modifier.fillMaxWidth(),
                        autoCommit = true,
                        // ⚠️ 按批追加：调用方的 userInput 是组合期快照，逐字回调会互相覆盖
                        onCharsPicked = { chars -> onInputChange(userInput + chars.joinToString("")) },
                        onUndoLast = { onInputChange(userInput.dropLast(1)) },
                        script = dictationScript,
                        expectedNextChar = dictationExpectedNext,
                        // 汉字待填字 ⇒ 禁用拉丁回退（答案只含汉字时不做英文识别）
                        allowLatinFallback = dictationExpectedNext?.let {
                            HandwritingScript.isLatinInputChar(it)
                        } != false,
                        expectedWord = dictationExpectedWord
                    )
                } else {
                    // 无笔设备：「拿笔点作答区」的入口不可达（笔点手势只对笔生效），
                    // 改为引导从「手写」模式开关进入（开关在字词/句子页的输入框内）。
                    Text(
                        if (stylusPresent) {
                            "拿笔点上面的作答区，这里就会出现手写区；写出的字直接落到上面。"
                        } else {
                            "开启「手写」后，这里常驻手写区；写出的字直接落到上面。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
