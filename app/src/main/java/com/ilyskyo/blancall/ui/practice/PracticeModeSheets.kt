// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.animation.core.Spring
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.SectionMode


// ========== 练习模式选项的统一分组视觉 ==========
//
// 三个入口（锚点浮窗 / 进页居中卡片 / 切换模式底部面板）共用这一套行与分组，
// 保证「选模式」这件事在 App 里长一个样：
//   · 只有模式名一行，不带 emoji、不带解释性副标题
//   · 四行合进一块极淡中性底色的圆角分组，行间一条发丝线
//   · 按压只换底纹，不缩放（行是紧贴的，一缩就露缝，整组被拆开）

/** 分组内单行高度：52dp —— 高于 48dp 触控下限，四行叠起来仍有呼吸。 */
private val ModeRowMinHeight = 52.dp

/** 分组圆角：比玻璃的 20dp 小一档，读作「玻璃里的一块」，不与外框抢轮廓。 */
private val ModeGroupShape = RoundedCornerShape(14.dp)

/**
 * 模式选项分组容器：一块圆角底色，组内自行插入 [ModeOptionDivider] 与 [ModeOptionRow]。
 */
@Composable
internal fun ModeOptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isBlancallDark()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ModeGroupShape)
            // 比玻璃再深一档的极淡中性底（无色相）：只做「这一组自成一整块」的暗示
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.06f else 0.035f)
            ),
        content = content
    )
}

/** 组内行间发丝线：1 物理像素、从文字起点内缩，不横穿整块。 */
@Composable
internal fun ModeOptionDivider() {
    // 1.dp 在 2.6x~3x 屏会画成 3px 粗线，这里按密度换算回 1px
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .height((1f / density).dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    )
}

/**
 * 分组内单行：只有一行模式名。
 *
 * @param modifier 外部修饰（入场动画等）；背景与点击由本组件负责
 * @param interactionSource 可由调用方传入，让按压底纹与外部动画取同一个交互源
 */
@Composable
internal fun ModeOptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null
) {
    // 与 GlassCard 同一写法：外部要观察按压（比如驱动入场动画）就传进来，否则本地自建
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val rowBackground by animateColorAsState(
        targetValue = if (isPressed)
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        else Color.Transparent,
        // 色彩没有位移，不用弹簧：弹簧会在终点附近抖
        animationSpec = MotionFade.color(MotionFade.pressAlpha),
        label = "modeRowBackground"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ModeRowMinHeight)
            // 背景在 clickable 之前：底纹要铺满整行（含左右内边距），而不只是文字范围
            .background(rowBackground)
            .clickable(
                interactionSource = src,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


// ========== 菜单工具函数 ==========

@Composable
internal fun modeLabel(mode: BlancallMode): String = when (mode) {
    BlancallMode.SENTENCE -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence)
    BlancallMode.WORD -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word)
    BlancallMode.REVERSE -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse)
}


@Composable
internal fun strategyLabel(strategy: BlancallGenerator.Strategy): String = when (strategy) {
    BlancallGenerator.Strategy.BALANCED -> stringResource(com.ilyskyo.blancall.R.string.strategy_balanced_short)
    BlancallGenerator.Strategy.WEAKNESS_FOCUS -> stringResource(com.ilyskyo.blancall.R.string.strategy_weakness)
    BlancallGenerator.Strategy.FULL_COVERAGE -> stringResource(com.ilyskyo.blancall.R.string.strategy_full)
}


// ========== Radio List 选项（用于 BottomSheet）==========

@Composable
internal fun RadioListItem(
    emoji: String,
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        // ⚠️ 必须用 Surface(onClick = …) **重载**，不能把 clickable 挂到 modifier 上：
        // Surface 的 shape 只裁剪它自己的背景，挂在 modifier 上的 clickable 位于裁剪**外层**，
        // 其按压/悬停指示器是矩形 ⇒ 圆角卡片上会浮出一个直角灰块（真机复现）。
        // onClick 重载内部是 surface(clip) → clickable，指示器自然被裁成圆角。
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}


// ========== 段落模式选择 BottomSheet 内容 ==========

@Composable
internal fun SectionPickerContent(
    sectionMode: SectionMode,
    sections: List<SectionSplitter.Section>,
    selectedSections: Set<Int>,
    rankedSections: List<SectionSplitter.RankedSection>,
    onModeChange: (SectionMode) -> Unit,
    onToggleSection: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // 使用 Column + heightIn + verticalScroll，避免 LazyColumn 与 BottomSheet 拖拽手势冲突导致抖动
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
    ) {
        Text(stringResource(com.ilyskyo.blancall.R.string.section_picker_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(pluralStringResource(com.ilyskyo.blancall.R.plurals.sections_count, sections.size, sections.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        // 模式选项（Radio 风格）
        SectionModeItem(
            emoji = "📖", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_full),
            desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_full_desc),
            selected = sectionMode == SectionMode.FULL,
            onClick = { onModeChange(SectionMode.FULL) }
        )
        SectionModeItem(
            emoji = "🎯", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_weak),
            desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_weak_desc),
            selected = sectionMode == SectionMode.WEAKNESS,
            onClick = { onModeChange(SectionMode.WEAKNESS) }
        )
        SectionModeItem(
            emoji = "✂️", label = stringResource(com.ilyskyo.blancall.R.string.section_mode_pick),
            desc = stringResource(com.ilyskyo.blancall.R.string.section_mode_pick_desc),
            selected = sectionMode == SectionMode.SELECTED,
            onClick = { onModeChange(SectionMode.SELECTED) }
        )

        // 自选模式 → 显示段落勾选列表
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
                    stringResource(com.ilyskyo.blancall.R.string.selected_of_total, selectedSections.size, sections.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSelectAll) {
                        Text(if (selectedSections.size == sections.size) stringResource(com.ilyskyo.blancall.R.string.deselect_all) else stringResource(com.ilyskyo.blancall.R.string.select_all),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    // 完成按钮：关闭 sheet，避免返回两次才能退出
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.done), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            sections.forEach { section ->
                val isSelected = section.index in selectedSections
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
                    isSelected = isSelected,
                    heatColor = heatColor,
                    hasError = errorRate > 0f,
                    onClick = { onToggleSection(section.index) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}


@Composable
internal fun SectionModeItem(
    emoji: String,
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        // ⚠️ 必须用 Surface(onClick = …) **重载**，不能把 clickable 挂到 modifier 上：
        // Surface 的 shape 只裁剪它自己的背景，挂在 modifier 上的 clickable 位于裁剪**外层**，
        // 其按压/悬停指示器是矩形 ⇒ 圆角卡片上会浮出一个直角灰块（真机复现）。
        // onClick 重载内部是 surface(clip) → clickable，指示器自然被裁成圆角。
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}


@Composable
internal fun SectionCheckItem(
    label: String,
    index: Int,
    isSelected: Boolean,
    heatColor: Color,
    hasError: Boolean,
    onClick: () -> Unit
) {
    Surface(
        // ⚠️ 同 [RadioListItem]：必须走 Surface(onClick = …) 重载，否则圆角卡片上会
        // 浮出直角矩形按压指示器（shape 剪不到 modifier 层的 clickable）。
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasError) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(heatColor, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(16.dp))
            }

            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
