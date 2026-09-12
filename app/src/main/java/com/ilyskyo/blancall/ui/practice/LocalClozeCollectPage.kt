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
            Text("选择挖空设置", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "本地算法生成挖空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ── 挖空策略（与练习页 ⋮ 菜单共用同一语义） ──
            Text("挖空策略", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            strategyOptions.forEach { opt ->
                val isSel = strategy == opt.value
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { strategy = opt.value },
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
            Text("段落选择", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            SectionModeItem(
                emoji = "📖", label = "全文连贯",
                desc = "按原文顺序，覆盖所有段落",
                selected = sectionMode == SectionMode.FULL,
                onClick = {
                    sectionMode = SectionMode.FULL
                    selected = sections.map { it.index }.toSet()
                }
            )
            SectionModeItem(
                emoji = "🎯", label = "薄弱集训",
                desc = "只练错题集中的段落",
                selected = sectionMode == SectionMode.WEAKNESS,
                onClick = {
                    sectionMode = SectionMode.WEAKNESS
                    val weak = rankedSections.filter { it.errorRate > 0f }.map { it.section.index }.toSet()
                    selected = if (weak.isEmpty()) sections.map { it.index }.toSet() else weak
                }
            )
            SectionModeItem(
                emoji = "✂️", label = "自选段落",
                desc = "手动勾选要复习的段落",
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
                        "已选 ${selected.size}/${sections.size}",
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
                        Text(if (selected.size == sections.size) "取消全选" else "全选",
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
                ) { Text("取消") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onConfirm(strategy, sectionMode, selected) },
                    modifier = Modifier.weight(1.6f)
                ) { Text("开始") }
            }
        }
    }
}
