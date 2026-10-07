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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            Text("本次训练分析", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 正在分析本次训练…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error -> {
                    Text("训练分析生成失败", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = onRetry) { Text("重试", style = MaterialTheme.typography.labelMedium) }
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
    var selected by remember { mutableStateOf("合适") }
    var custom by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(initialStrategy) }

    data class DiffOption(val label: String, val desc: String)
    val options = remember {
        listOf(
            DiffOption("难", "挖空密度高，空多，挑战更大"),
            DiffOption("中等", "挖空密度适中，均衡练习"),
            DiffOption("合适", "按内容难度自动挑选重点")
        )
    }
    data class StrategyOption(val value: BlancallGenerator.Strategy, val label: String, val desc: String)
    val strategyOptions = remember {
        listOf(
            StrategyOption(BlancallGenerator.Strategy.BALANCED, "均衡", "兼顾重点与覆盖面"),
            StrategyOption(BlancallGenerator.Strategy.WEAKNESS_FOCUS, "薄弱优先", "优先挖易错内容"),
            StrategyOption(BlancallGenerator.Strategy.FULL_COVERAGE, "全覆盖", "均匀覆盖全文")
        )
    }

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
                Text("使用本地算法", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text("选择挖空难度", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "AI 将按你选定的难度与策略为「${modeLabel(mode)}」生成挖空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            options.forEach { opt ->
                val isSel = selected == opt.label
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .pressClick { selected = opt.label },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSel, onClick = { selected = opt.label })
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
            Text("挖空策略", style = MaterialTheme.typography.titleMedium,
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
                label = { Text("补充要求（可选）") },
                placeholder = { Text("如：只挖第一段、空数少一些…") },
                supportingText = {
                    Text("仅作为挖空的「范围/空数」参考，与背诵无关的内容将被忽略",
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
                ) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(selected, custom, strategy) },
                    modifier = Modifier.weight(1.6f)
                ) { Text("开始生成") }
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
            Text("AI 正在生成挖空…", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${modeLabel(mode)} · $difficulty",
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
                text = if (progress <= 0) "正在连接 AI…" else "已返回 $progress 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AI 只返回挖空坐标，原文不会被改动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            // 兜底：超过 40 秒无响应会自动回退本地；这里允许用户主动结束等待
            OutlinedButton(onClick = onCancel) {
                Text("等太久？用本地算法生成", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
