// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.foundation.clickable
import com.ilyskyo.blancall.ui.common.pressClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.ui.viewmodel.SectionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/**
 * 本地挖空信息采集页（「使用AI挖空」关闭时）：只采集挖空策略与段落选择，
 * 确认后由本地算法生成，不涉及 AI 难度与自定义需求。
 */
@Composable
internal fun LocalClozeCollectPage(
    initialStrategy: BlancallGenerator.Strategy,
    initialSectionMode: SectionMode,
    initialSelected: Set<Int>,
    sections: List<SectionSplitter.Section>,
    rankedSections: List<SectionSplitter.RankedSection>,
    onConfirm: (BlancallGenerator.Strategy, SectionMode, Set<Int>) -> Unit,
    onCancel: () -> Unit
) {
    var strategy by remember { mutableStateOf(initialStrategy) }
    var sectionMode by remember { mutableStateOf(initialSectionMode) }
    var selected by remember { mutableStateOf(initialSelected) }

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
            Text(stringResource(com.ilyskyo.blancall.R.string.ai_select_cloze_settings), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(com.ilyskyo.blancall.R.string.ai_local_algorithm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ── 挖空策略（与练习页 ⋮ 菜单共用同一语义） ──
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

            Spacer(Modifier.height(20.dp))
            // ── 段落选择（与练习页 ⋮ 菜单「段落分层」同一语义） ──
            Text(stringResource(com.ilyskyo.blancall.R.string.section_picker_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            SectionModeItem(
                emoji = "📖", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_full),
                desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_full_desc),
                selected = sectionMode == SectionMode.FULL,
                onClick = {
                    sectionMode = SectionMode.FULL
                    selected = sections.map { it.index }.toSet()
                }
            )
            SectionModeItem(
                emoji = "🎯", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_weak),
                desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_weak_desc),
                selected = sectionMode == SectionMode.WEAKNESS,
                onClick = {
                    sectionMode = SectionMode.WEAKNESS
                    val weak = rankedSections.filter { it.errorRate > 0f }.map { it.section.index }.toSet()
                    selected = if (weak.isEmpty()) sections.map { it.index }.toSet() else weak
                }
            )
            SectionModeItem(
                emoji = "✂️", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_pick),
                desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_pick_desc),
                selected = sectionMode == SectionMode.SELECTED,
                onClick = { sectionMode = SectionMode.SELECTED }
            )

            // 自选模式 → 段落勾选列表
            if (sectionMode == SectionMode.SELECTED) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.selected_of_total, selected.size, sections.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        selected = if (selected.size == sections.size) {
                            sections.firstOrNull()?.let { setOf(it.index) } ?: emptySet()
                        } else {
                            sections.map { it.index }.toSet()
                        }
                    }) {
                        Text(if (selected.size == sections.size) stringResource(com.ilyskyo.blancall.R.string.deselect_all) else stringResource(com.ilyskyo.blancall.R.string.select_all),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                sections.forEach { section ->
                    val isSel = section.index in selected
                    val errorRate = rankedSections
                        .find { it.section.index == section.index }?.errorRate ?: 0f
                    val heatColor = when {
                        errorRate >= 0.5f -> Color(0xFFE53935)
                        errorRate >= 0.3f -> Color(0xFFFB8C00)
                        errorRate >= 0.1f -> Color(0xFFFDD835)
                        errorRate > 0f -> Color(0xFF66BB6A)
                        else -> Color.Unspecified
                    }
                    SectionCheckItem(
                        label = section.heading ?: section.contentOnly.take(30),
                        index = section.index,
                        isSelected = isSel,
                        heatColor = heatColor,
                        hasError = errorRate > 0f,
                        onClick = {
                            selected = if (isSel) {
                                if (selected.size > 1) selected - section.index else selected
                            } else {
                                selected + section.index
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(strategy, sectionMode, selected) },
                    modifier = Modifier.weight(1.6f)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.start_action)) }
            }
        }
    }
}
