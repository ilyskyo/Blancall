// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一行放得下多少就显示多少的「胶囊行」，**绝不换行**。
 *
 * 为什么不做 FlowRow：换行会让一组快捷选项变成两行，卡片高度跳动、视觉重心散掉
 * （真机反馈「多出一行，不好」）。这里的策略是按优先级舍弃中间项：
 * - [alwaysLeading] 个：永远显示的头部（如「目标」文字、筛选的「全部」）；
 * - [alwaysTrailing] 个：永远显示的尾部（如「自定义」，它必须一直在）；
 * - 中间项按顺序尽量塞，塞不下的直接不摆（不占位、不换行）。
 *
 * 量法：先量头部与尾部并预留尾部宽度，再用剩余宽度依次量中间项。
 * 子项都会被组合，但没摆的项不参与布局与语义。
 * [keepIndices] 指定的中间项无条件摆放（当前选中项不能是那个被舍弃的）。
 *
 * @param spacing 相邻胶囊的水平间距
 */
@Composable
fun AdaptiveChipRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    alwaysLeading: Int = 0,
    alwaysTrailing: Int = 0,
    /**
     * 必须显示的中间项（绝对下标）。典型用法：当前选中的预设 ——
     * 被舍弃的恰好是当前值，会让整行看起来“没有选中任何项”。
     */
    keepIndices: Set<Int> = emptySet(),
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxW = constraints.maxWidth
        val count = measurables.size
        val leadCount = alwaysLeading.coerceIn(0, count)
        val trailCount = alwaysTrailing.coerceIn(0, count - leadCount)

        val lead = measurables.subList(0, leadCount).map { it.measure(Constraints()) }
        val trail = measurables.subList(count - trailCount, count).map { it.measure(Constraints()) }
        val middle = measurables.subList(leadCount, count - trailCount)

        val placed = ArrayList<Placeable>(count)
        var used = 0

        fun place(placeable: Placeable, force: Boolean): Boolean {
            val extra = if (placed.isEmpty()) placeable.width else placeable.width + gap
            if (!force && used + extra > maxW) return false
            used += extra
            placed.add(placeable)
            return true
        }

        // 头部与尾部永远显示；尾部宽度先预留，避免中间项把它挤出去
        lead.forEach { place(it, force = true) }
        val trailReserve = trail.sumOf { it.width } + gap * trail.size

        // 当前选中项必须占位：先测量中间项，keep 的宽度从可用空间里预留出来
        val middleStart = leadCount
        val measuredMiddle = middle.mapIndexed { i, m -> (middleStart + i) to m.measure(Constraints()) }
        var keepReserve = measuredMiddle
            .filter { (idx, _) -> idx in keepIndices }
            .sumOf { (_, m) -> m.width + gap }
        measuredMiddle.forEach { (idx, measured) ->
            if (idx in keepIndices) {
                place(measured, force = true)
                keepReserve -= measured.width + gap
            } else if (used + gap + measured.width + trailReserve + keepReserve <= maxW) {
                place(measured, force = false)
            }
        }
        trail.forEach { place(it, force = true) }

        val rowHeight = placed.maxOfOrNull { it.height } ?: 0
        val rowWidth = if (maxW == Constraints.Infinity) used else used.coerceAtMost(maxW)
        layout(rowWidth, rowHeight) {
            var x = 0
            placed.forEach { p ->
                p.placeRelative(x, (rowHeight - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}
