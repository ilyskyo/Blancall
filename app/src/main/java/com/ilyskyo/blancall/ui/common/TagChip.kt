// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.ui.theme.isBlancallDark

/**
 * 标签徽标（tag chip / badge）共享组件。
 *
 * 视觉规范（与列表页「待复习」状态 chip 同语言）：
 * - 高 18dp / 圆角 6dp / 水平内边距 6dp；`labelSmall` + Medium；单行省略，最大宽 76dp；
 * - 背景 = surface 上叠标签色（浅 14% / 深 30%）的**不透明合成**；
 *   前景由 [ColorOps.chipColors] 自动取深/取浅，保证对比度 ≥ 4.5:1（深浅两模式皆然）；
 * - 多标签只出一行：最多 N 枚 + 「+N」溢出徽标（不换行）；
 * - 矮卡（1 行高）零高度增量方案：[TagColorDots] 纯色点 ≤3 枚。
 */

/** chip 展示数据（name + 0xRRGGBB） */
data class TagChipUi(val name: String, val color: Int)

/** [Tag] → [TagChipUi]（列表/首页/搜索/句子卡统一用） */
fun Tag.toChipUi(): TagChipUi = TagChipUi(name = name, color = color)

/** [Tag] 列表 → [TagChipUi] 列表 */
fun List<Tag>.toChipUis(): List<TagChipUi> = map { it.toChipUi() }

/** 0xRRGGBB → 不透明 Compose Color（Compose 的 Color(Int) 会把 24 位当成 0 alpha，须补 FF） */
private fun opaqueColor(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** 标签圆点（管理页 / 选择面板 / 筛选 chip 共用）：默认 10dp，纯标签色填充 */
@Composable
fun TagDot(tag: TagChipUi, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(opaqueColor(tag.color)),
    )
}

/** 单个标签徽标 */
@Composable
fun TagChipView(tag: TagChipUi, modifier: Modifier = Modifier) {
    val isDark = isBlancallDark()
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    val chip = remember(tag.color, isDark, surfaceArgb) {
        ColorOps.chipColors(tag.color, isDark, surfaceArgb and 0xFFFFFF)
    }
    Box(
        modifier = modifier
            .height(18.dp)
            .widthIn(max = 76.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(opaqueColor(chip.bg))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = opaqueColor(chip.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 「+N」溢出徽标（中性色，与标签 chip 同规格） */
@Composable
private fun OverflowChip(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 标签徽标行：单行、最多 [maxChips] 枚 + 「+N」（[showOverflow]，窄卡可关）。
 * 标签为空时整行不占位。
 */
@Composable
fun TagChipRow(
    tags: List<TagChipUi>,
    modifier: Modifier = Modifier,
    maxChips: Int = 2,
    showOverflow: Boolean = true,
) {
    if (tags.isEmpty()) return
    val shown = tags.take(maxChips.coerceAtLeast(1))
    val overflow = tags.size - shown.size
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            TagChipView(t)
        }
        if (showOverflow && overflow > 0) {
            Spacer(Modifier.width(4.dp))
            OverflowChip(overflow)
        }
    }
}

/**
 * 标签色点（矮卡专用零高度增量方案）：≤ [max] 枚 5dp 纯色圆点，间距 3dp。
 * 无文字、不可点击，仅作视觉分类线索；无障碍朗读标签名。
 */
@Composable
fun TagColorDots(
    tags: List<TagChipUi>,
    modifier: Modifier = Modifier,
    max: Int = 3,
) {
    if (tags.isEmpty()) return
    val shown = tags.take(max.coerceAtLeast(1))
    Row(
        modifier = modifier.semantics {
            contentDescription = "标签：" + shown.joinToString("、") { it.name }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.width(3.dp))
            TagDot(tag = t, size = 5.dp)
        }
    }
}
