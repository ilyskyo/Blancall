// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.repository.InkBatch

/**
 * 墨迹缩略图 / 大图：把 [InkBatch] 里的笔画按**当时板面尺寸**等比缩放后绘制。
 *
 * ## 为什么要逐批次用自己的 boardW/H
 * 不同空、不同批次的板面尺寸可能不同（旋转、分屏、面板高度差异），
 * 统一按一个尺寸换算会把旧批次的字拉扁或抻长。逐批次计算
 * `scale = min(宽比, 高比)` 并居中，字形永不变形。
 *
 * ## 坐标语义
 * 笔画点是**板面 px**（存盘时定点化、读回已还原）；本组件只负责等比映射到当前
 * Canvas 尺寸。空点集/非法板面尺寸直接跳过 —— 展示性数据不允许崩溃。
 */
@Composable
fun InkThumbnail(
    batches: List<InkBatch>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strokeWidth: Dp = 1.5.dp
) {
    Canvas(modifier = modifier) {
        val boxW = size.width
        val boxH = size.height
        if (boxW <= 0f || boxH <= 0f) return@Canvas
        val sw = strokeWidth.toPx()

        batches.forEach { batch ->
            if (batch.boardW <= 0 || batch.boardH <= 0) return@forEach
            val scale = minOf(boxW / batch.boardW, boxH / batch.boardH)
            val ox = (boxW - batch.boardW * scale) / 2f
            val oy = (boxH - batch.boardH * scale) / 2f

            batch.strokes.forEach { pts ->
                if (pts.isEmpty()) return@forEach
                if (pts.size == 1) {
                    // 单点笔（顿点/「、」）：画圆点
                    drawCircle(
                        color = color,
                        radius = sw / 2f,
                        center = Offset(ox + pts[0].x * scale, oy + pts[0].y * scale)
                    )
                } else {
                    val path = Path()
                    path.moveTo(ox + pts[0].x * scale, oy + pts[0].y * scale)
                    for (i in 1 until pts.size) {
                        path.lineTo(ox + pts[i].x * scale, oy + pts[i].y * scale)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}
