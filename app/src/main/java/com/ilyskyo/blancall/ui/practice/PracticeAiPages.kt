// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.MarkdownText
import com.ilyskyo.blancall.ui.common.pressClick
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.AiClozeGenerator
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/** 提交后的 AI 训练分析卡片（Markdown 渲染；加载失败可重试）。 */
@Composable
internal fun TrainingAnalysisCard(
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
            Text(stringResource(com.ilyskyo.blancall.R.string.ai_training_analysis), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_analysing), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error -> {
                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_analysis_failed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = onRetry) { Text(stringResource(com.ilyskyo.blancall.R.string.retry), style = MaterialTheme.typography.labelMedium) }
                }
                text != null -> MarkdownText(text, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ========== AI 难度信息采集页（Pro）==========

/** 难度信息采集页：难度（难/中等/合适）+ 挖空策略（均衡/薄弱优先/全覆盖）+ 可选自定义需求。 */
@Composable
internal fun DifficultyInfoPage(
    mode: BlancallMode,
    initialStrategy: BlancallGenerator.Strategy,
    onConfirm: (String, String, BlancallGenerator.Strategy) -> Unit,
    onSkipToLocal: () -> Unit,
    onCancel: () -> Unit
) {
    var selected by remember { mutableStateOf(AiClozeGenerator.DIFFICULTY_EASY) }
    var custom by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(initialStrategy) }

    // value 是稳定档位（传给生成器与提示词），label 才是当前语言的显示文案 ——
    // 二者混用会让英文界面选「Hard」后 normalizeDifficulty 认不出、静默退回默认密度
    data class DiffOption(val value: String, val label: String, val desc: String)
    val options = listOf(
        DiffOption(AiClozeGenerator.DIFFICULTY_HARD, stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_hard), stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_hard_desc)),
        DiffOption(AiClozeGenerator.DIFFICULTY_MEDIUM, stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_medium), stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_medium_desc)),
        DiffOption(AiClozeGenerator.DIFFICULTY_EASY, stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_easy), stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_easy_desc))
    )
    data class StrategyOption(val value: BlancallGenerator.Strategy, val label: String, val desc: String)
    val strategyOptions = listOf(
        StrategyOption(BlancallGenerator.Strategy.BALANCED, stringResource(com.ilyskyo.blancall.R.string.strategy_balanced_short), stringResource(com.ilyskyo.blancall.R.string.strategy_balanced_desc)),
        StrategyOption(BlancallGenerator.Strategy.WEAKNESS_FOCUS, stringResource(com.ilyskyo.blancall.R.string.strategy_weakness), stringResource(com.ilyskyo.blancall.R.string.strategy_weakness_desc)),
        StrategyOption(BlancallGenerator.Strategy.FULL_COVERAGE, stringResource(com.ilyskyo.blancall.R.string.strategy_full), stringResource(com.ilyskyo.blancall.R.string.strategy_full_desc))
    )

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
                Text(stringResource(com.ilyskyo.blancall.R.string.ai_local_algorithm), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_intro, modeLabel(mode)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            options.forEach { opt ->
                val isSel = selected == opt.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .pressClick { selected = opt.value },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { selected = opt.value })
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
            Text(stringResource(com.ilyskyo.blancall.R.string.pick_strategy), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            strategyOptions.forEach { opt ->
                val isSel = strategy == opt.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .pressClick { strategy = opt.value },
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
                label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_extra_requirements)) },
                placeholder = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_extra_hint)) },
                supportingText = {
                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_extra_note),
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
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(selected, custom, strategy) },
                    modifier = Modifier.weight(1.6f)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.ai_start_generating)) }
            }
        }
    }
}


/** AI 生成挖空加载页：显示实时进度（已接收字符数递增=AI 在生成），可随时用本地算法兜底。 */
@Composable
internal fun AiGeneratingPage(mode: BlancallMode, difficulty: String, progress: Int, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(18.dp))
            Text(stringResource(com.ilyskyo.blancall.R.string.ai_generating_cloze), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = modeLabel(mode) + " · " + when (difficulty) { AiClozeGenerator.DIFFICULTY_HARD -> stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_hard); AiClozeGenerator.DIFFICULTY_MEDIUM -> stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_medium); else -> stringResource(com.ilyskyo.blancall.R.string.ai_difficulty_easy) },
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
                text = if (progress <= 0) stringResource(com.ilyskyo.blancall.R.string.ai_connecting) else stringResource(com.ilyskyo.blancall.R.string.ai_progress_chars, progress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(com.ilyskyo.blancall.R.string.ai_coords_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            // 兜底：超过 40 秒无响应会自动回退本地；这里允许用户主动结束等待
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(com.ilyskyo.blancall.R.string.ai_use_local), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
