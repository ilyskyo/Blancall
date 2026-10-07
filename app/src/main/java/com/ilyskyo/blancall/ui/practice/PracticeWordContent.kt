// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.ui.handwriting.AnswerInputField
import com.ilyskyo.blancall.ui.common.penTapToHandwriting
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.BlankCountWarning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


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
    onAnswerChange: (Int, String) -> Unit,
    /** 墨迹上报（错题回顾）：手写被消费时回调（blankIdx 已在内部绑定）；透传给内嵌书写板 */
    onInkCommitted: ((Int, List<List<Offset>>, Int, Int) -> Unit)? = null
) {
    blancall?.let {
        // 提示计时目标 = 第一个未填完的空：即使从未输入，满足无操作时长也提示
        LaunchedEffect(userAnswers, isSubmitted) {
            if (isSubmitted) return@LaunchedEffect
            val firstUnfinished = blancall.blanks.indexOfFirst { userAnswers[it.index].isNullOrEmpty() }
            if (firstUnfinished >= 0) onBlankFocus(firstUnfinished)
        }

        // ── 手写态：整页只保留**一块**书写板（就地内嵌在目标空里，不弹层）──
        // 一页可能有十几个空；若每个空都渲染书写板（≥140dp），列表会变成一望无际的
        // 「书写板墙」——一屏装不下两个空，用户也分不清自己在写哪一个。
        // 所以只给**当前作答目标**渲染书写板（就在该空的输入框下面），
        // 其余空的输入框保持**键盘可编辑**：手指点上去就能打字。
        val handwritingMode by AppPrefs.handwritingInputEnabledFlow.collectAsStateWithLifecycle()
        var activeBlank by remember { mutableStateOf<Int?>(null) }
        // 笔点过的空：即使「手写模式」关着，也对它临时启用书写板（笔来了就该能写）
        var penTarget by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(handwritingMode, blancall) {
            if (!handwritingMode) return@LaunchedEffect
            val indexes = blancall.blanks.map { it.index }
            if (activeBlank == null || activeBlank !in indexes) {
                activeBlank = indexes.firstOrNull { userAnswers[it].isNullOrEmpty() }
                    ?: indexes.firstOrNull()
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    // 只有当前目标渲染书写板（就地嵌在本卡里，不弹层）
                                    handwritingTarget = blankIdx == activeBlank,
                                    // 笔点过的空 ← 临时手写（不改默认输入方式开关）
                                    forceHandwriting = penTarget == blankIdx,
                                    onActivate = { activeBlank = blankIdx },
                                    onPenActivate = {
                                        activeBlank = blankIdx
                                        penTarget = blankIdx
                                    },
                                    // 该空的标准答案（生僻字守卫：计算下一个期望字符）
                                    originalAnswer = blank.originalChar,
                                    // 文种按该空的标准答案自动选（英文空走 EMNIST 模型）
                                    script = HandwritingScript.forAnswer(blank.originalChar),
                                    // 墨迹上报（错题回顾）：按该空 index 绑定上报
                                    // （面板切换/卸载不影响已上报数据）
                                    onInkCommitted = { s, w, h ->
                                        onInkCommitted?.invoke(blankIdx, s, w, h)
                                    },
                                    onValueChange = { onAnswerChange(blankIdx, it) }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── 上 / 下一空：手写作答时手不用离开书写区去找下一个空 ──
        val target = activeBlank ?: penTarget
        val blanksList = blancall.blanks
        if (!isSubmitted && handwritingMode && blanksList.size > 1 && target != null) {
            val pos = blanksList.indexOfFirst { it.index == target }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (pos > 0) activeBlank = blanksList[pos - 1].index },
                    enabled = pos > 0
                ) { Text("← 上一空") }
                Text(
                    "第 ${pos + 1} / ${blanksList.size} 空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { if (pos in 0 until blanksList.size - 1) activeBlank = blanksList[pos + 1].index },
                    enabled = pos in 0 until blanksList.size - 1
                ) { Text("下一空 →") }
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
    /**
     * 是否为本页的「当前作答目标」。
     *
     * 一页可能有十几个空；若每个空都渲染书写板（≥140dp），列表会变成一望无际的
     * 「书写板墙」——一屏放不下两个空，用户也分不清自己在写哪一个。
     * 所以只给**当前目标**渲染书写板（就地嵌在该空的输入框下面，不弹层），
     * 其余空的输入框保持**键盘可编辑** —— 手指点上去就能直接打字。
     */
    handwritingTarget: Boolean = true,
    /** 笔点过的空：即使「手写模式」关着，也临时对它启用书写板（笔来了就该能写） */
    forceHandwriting: Boolean = false,
    onActivate: (() -> Unit)? = null,
    /** 笔点本卡：切为当前手写目标 */
    onPenActivate: (() -> Unit)? = null,
    /** 该空的标准答案（生僻字守卫用：计算已输入内容之后的下一个期望字符） */
    originalAnswer: String = "",
    /** 该空的识别文种（按标准答案自动选） */
    script: HandwritingScript = HandwritingScript.Chinese,
    onValueChange: (String) -> Unit,
    /** 墨迹上报（错题回顾）：透传给内嵌书写板（blankIdx 由调用方绑定） */
    onInkCommitted: ((List<List<Offset>>, Int, Int) -> Unit)? = null
) {
    // 只有「非目标」卡才需要整卡点击切目标（点输入框仍能正常打字，互不冲突）
    val activatable = !handwritingTarget && !isSubmitted && onActivate != null
    Card(
        modifier = Modifier
            // 笔点即书写：笔尖落在卡上 → 把它切为手写目标。
            // ⚠️ 不弹层、也不改写 `handwritingInputEnabled`（那是「默认输入方式」）。
            //
            // ⚠️⚠️ `!handwritingTarget` 这一条是必需的：本卡**已经是**作答目标时，
            // 卡内部就渲染着书写板（AndroidView）。卡级拦截会在 Initial pass 把整段笔事件
            // 消费掉 ⇒ 书写板一笔都收不到 ⇒ 真机现象「字词挖空完全写不出字」。
            // 拦截的意义只是「用笔点**别的**空把它切为目标」，目标卡上就不该再拦。
            .penTapToHandwriting(
                key = label,
                enabled = onPenActivate != null && !isSubmitted && !handwritingTarget
            ) {
                onPenActivate?.invoke()
            }
            .then(if (activatable) Modifier.pressClick { onActivate?.invoke() } else Modifier),
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
            AnswerInputField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitted,
                placeholder = "请输入答案",
                hintChar = if (!isSubmitted) hintChar else null,
                isError = isSubmitted && checkDetail?.result != AnswerChecker.Result.CORRECT,
                singleLine = !multiline,
                maxLines = if (multiline) 3 else 1,
                imeAction = ImeAction.Next,
                // 只有当前目标允许手写；其余空保持键盘态 —— 手指点上去就是打字
                allowHandwritingSwitch = !isSubmitted && handwritingTarget,
                forceHandwriting = forceHandwriting,
                // 生僻字守卫：答案的下一个期望字不在识别字表时禁止自动上屏、引导键盘
                expectedNextChar = originalAnswer.getOrNull(value.length),
                // 英文默写的答案先验：剩余答案（容错匹配 o/0、1/l 等混淆后整词提交）
                expectedWord = originalAnswer.drop(value.length).takeIf { it.isNotEmpty() },
                script = script,
                onInkCommitted = onInkCommitted
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
