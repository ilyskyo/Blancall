// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.animation.core.Spring
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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


// ========== 练习模式选项的统一视觉 ==========
//
// 三个入口（锚点浮窗 / 进页居中卡片 / 切换模式底部面板）共用这一套行，
// 保证「选模式」这件事在 App 里长一个样。
//
// ── 为什么最终是「无线框 + 不透明分隔线」而不是一块底色 ──
//
// 这个控件前后推翻了两版：先淡灰 alpha（显透、还白闪），再实心暖灰块（清晰但像压了块灰板）。
// 两次都失败指向同一条约束：**这个控件长在半透明的近白玻璃上**。
// 在那种底色上，「填充色块」只有两种结局 —— 淡了就透、深了就闷，没有中间态。
//
// iOS 的 ActionSheet 不在玻璃上铺色块，不是因为懒：浮层里再套浮层会让材质层级互相打架。
// 所以这里回到 Sheet 的原生语言：**等距排列 + 贯穿的不透明分隔线**。
// 分组感来自 Gestalt 的接近性（四行等距、行高一致）与一条连续边界，而不是一块实色区域；
// 这正好绕开「填充必然两难」的死结。至于「能不能少画东西」——Tuch et al. (2009, IJHCS 67:703-715)
// 用 36 张真实页面 + 48 人测出：视觉复杂度每涨一档，愉悦度（r=-.61）与识别率都在掉。
// 少一层填充，就是少一档复杂度。
//
// 每行左侧那枚图形来自 Wiedenbeck (1999, Behaviour & IT 18:68-82)：60 名新手两轮实验中，
// 「图标+文字」在易用感评价上最高（注意不是识别更快，纯图标反而最差）；
// McDougall et al. (2000) 补充：**具体物象**优于抽象几何，所以画的是纸页、词块、滑块。

/** 分组内单行高度：56dp —— 高于 48dp 触控下限，图标与文字之间留得住呼吸。 */
private val ModeRowMinHeight = 56.dp

/** 行内图形尺寸：22dp 是「能被认出来」与「不抢文字」的平衡点。 */
private val ModeGlyphSize = 22.dp

/** 按压底纹：手指按下去必须看得出按的是哪一行。 */
private val ModeRowPressedLight = Color(0x14000000)
private val ModeRowPressedDark = Color(0x1FFFFFFF)

/**
 * 模式行左侧的图形（自绘，非 emoji）。
 *
 * 四个都取**具体物象**：两行文字、词块、纸页、滑块 —— 对应 McDougall et al. (2000)
 * 「具体图标在首次使用时优于抽象图标」的结论。
 */
internal enum class ModeGlyph { SENTENCE, WORD, REVERSE, CUSTOM }

@Composable
private fun ModeGlyphIcon(glyph: ModeGlyph, tint: Color) {
    Canvas(Modifier.size(ModeGlyphSize)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        val cap = StrokeCap.Round
        when (glyph) {
            // 两行文字：上一行完整，下一行中间断开 —— 「句子里挖掉一段」
            ModeGlyph.SENTENCE -> {
                drawLine(tint, Offset(w * 0.14f, h * 0.32f), Offset(w * 0.86f, h * 0.32f), sw, cap)
                drawLine(tint, Offset(w * 0.14f, h * 0.68f), Offset(w * 0.42f, h * 0.68f), sw, cap)
                drawLine(tint, Offset(w * 0.58f, h * 0.68f), Offset(w * 0.86f, h * 0.68f), sw, cap)
            }
            // 一行词串，中间一个方框 —— 「挖掉的是词」
            ModeGlyph.WORD -> {
                drawLine(tint, Offset(w * 0.10f, h * 0.5f), Offset(w * 0.34f, h * 0.5f), sw, cap)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.43f, h * 0.26f),
                    size = Size(w * 0.24f, h * 0.48f),
                    cornerRadius = CornerRadius(w * 0.06f),
                    style = Stroke(width = sw)
                )
                drawLine(tint, Offset(w * 0.76f, h * 0.5f), Offset(w * 0.90f, h * 0.5f), sw, cap)
            }
            // 纸页 —— 「把段落打散后默写回去」
            ModeGlyph.REVERSE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.18f, h * 0.14f),
                    size = Size(w * 0.64f, h * 0.72f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = sw)
                )
                drawLine(tint, Offset(w * 0.33f, h * 0.40f), Offset(w * 0.67f, h * 0.40f), sw * 0.85f, cap)
                drawLine(tint, Offset(w * 0.33f, h * 0.60f), Offset(w * 0.55f, h * 0.60f), sw * 0.85f, cap)
            }
            // 两条滑块 —— 「按文章自定义配置」
            ModeGlyph.CUSTOM -> {
                drawLine(tint, Offset(w * 0.12f, h * 0.34f), Offset(w * 0.88f, h * 0.34f), sw * 0.8f, cap)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.66f, h * 0.34f))
                drawLine(tint, Offset(w * 0.12f, h * 0.68f), Offset(w * 0.88f, h * 0.68f), sw * 0.8f, cap)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.34f, h * 0.68f))
            }
        }
    }
}

/**
 * 模式选项分组容器：**不填充任何颜色**，只负责把四行竖排。
 *
 * 这里推翻了自己前两版的做法（先淡灰 alpha、再实心灰块），原因是同一个：
 * 在半透明的近白玻璃上，任何"填充色块"都只有两个结果 —— 淡了就显透、就显脏，
 * 深了就变成一块压在玻璃上的灰板。iOS 的 ActionSheet 之所以不铺底色，
 * 是因为它本身就在毛玻璃上：**浮层里不再套浮层**，一层材质只说一件事。
 *
 * 分组感改由 Palmer (1992) 已经验证过的另一条路来给：等距排列（接近性）+ 贯穿的分隔线。
 */
@Composable
internal fun ModeOptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        content = content
    )
}

/**
 * 行间分隔线：1 物理像素的**不透明白**，贯穿整个面板宽度。
 *
 * 不透明是关键 —— 用户前两轮反馈的都是"透"，而 alpha 叠在玻璃上永远读得出底下的光。
 * 这条线取 onSurface 14%（深色 16% 白），在浅色玻璃上读作 #E0DCD6 一类的暖灰实线。
 */
@Composable
internal fun ModeOptionDivider() {
    val density = LocalDensity.current.density
    val isDark = isBlancallDark()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((1f / density).dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.16f else 0.14f)
            )
    )
}

/**
 * 分组内单行：一枚自绘图形 + 一行模式名。
 *
 * @param modifier 外部修饰（入场动画等）；背景与点击由本组件负责
 * @param interactionSource 可由调用方传入，让按压底纹与外部动画取同一个交互源
 */
@Composable
internal fun ModeOptionRow(
    label: String,
    glyph: ModeGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null
) {
    // 与 GlassCard 同一写法：外部要观察按压（比如驱动入场动画）就传进来，否则本地自建
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val isDark = isBlancallDark()
    val rowBackground by animateColorAsState(
        targetValue = if (isPressed)
            if (isDark) ModeRowPressedDark else ModeRowPressedLight
        else Color.Transparent,
        // 色彩没有位移，不用弹簧：弹簧会在终点附近抖
        animationSpec = MotionFade.color(MotionFade.pressAlpha),
        label = "modeRowBackground"
    )
    Row(
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
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标只做识别锚点，不抢文字：用次要前景色，不再额外上色
        ModeGlyphIcon(
            glyph = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
        Spacer(Modifier.width(12.dp))
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
