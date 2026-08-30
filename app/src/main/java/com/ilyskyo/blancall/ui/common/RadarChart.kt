// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 雷达图（蛛网图）：用于展示多维度的能力/弱点分布。
 *
 * @param axes   各维度标签（如 ["错字","漏字","多填","乱序"]），至少 3 项
 * @param values 各维度数值 0f..1f，长度需与 [axes] 一致
 * @param label  图表副标题
 */
@Composable
fun RadarChart(
    axes: List<String>,
    values: List<Float>,
    modifier: Modifier = Modifier,
    label: String = ""
) {
    if (axes.size < 3 || values.size != axes.size) return

    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val animated by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(700, delayMillis = 60),
        label = "radarEnter"
    )

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val strokeColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    val desc = axes.indices.joinToString("，") { i ->
        "${axes[i]} ${(values.getOrNull(i)?.let { it * 100 }?.toInt() ?: 0)}%"
    }
    // Path 对象提到 Composable 层复用，避免动画每帧在 DrawScope 内创建新对象导致 GC 抖动
    val gridPath = remember { Path() }
    val dataPath = remember { Path() }
    Column(
        modifier = modifier.semantics { contentDescription = "弱点画像：$desc" }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.minDimension / 2f * 0.72f
                val n = axes.size
                val angleStep = (2 * PI / n).toFloat()
                val startAngle = (-PI / 2).toFloat() // 顶部为第一个轴

                // 同心网格（4 圈）：复用 gridPath，每环 rewind 清空；最外圈加粗强调
                val rings = 4
                for (ring in 1..rings) {
                    val r = radius * ring / rings
                    gridPath.rewind()
                    for (i in 0 until n) {
                        val a = startAngle + i * angleStep
                        val x = cx + r * cos(a)
                        val y = cy + r * sin(a)
                        if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                    }
                    gridPath.close()
                    drawPath(
                        gridPath,
                        color = gridColor,
                        style = Stroke(width = if (ring == rings) 1.5.dp.toPx() else 1.dp.toPx())
                    )
                }

                // 轴线（从中心到顶点）
                for (i in 0 until n) {
                    val a = startAngle + i * angleStep
                    val x = cx + radius * cos(a)
                    val y = cy + radius * sin(a)
                    drawLine(
                        color = axisColor,
                        start = Offset(cx, cy),
                        end = Offset(x, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 数据多边形：复用 dataPath
                dataPath.rewind()
                for (i in 0 until n) {
                    val a = startAngle + i * angleStep
                    val v = values[i].coerceIn(0f, 1f) * animated
                    val r = radius * v
                    val x = cx + r * cos(a)
                    val y = cy + r * sin(a)
                    if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
                }
                dataPath.close()
                // 多边形填充：纯色填充（已移除 radialGradient）
                drawPath(
                    dataPath,
                    color = strokeColor.copy(alpha = 0.22f)
                )
                drawPath(
                    dataPath,
                    color = strokeColor,
                    style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
                )

                // 顶点圆点：光晕 + 实心点
                for (i in 0 until n) {
                    val a = startAngle + i * angleStep
                    val v = values[i].coerceIn(0f, 1f) * animated
                    val r = radius * v
                    val x = cx + r * cos(a)
                    val y = cy + r * sin(a)
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.18f),
                        radius = 7.dp.toPx(),
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = dotColor,
                        radius = 3.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }

        // 维度标签 + 数值
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            axes.forEachIndexed { i, axis ->
                val pct = (values.getOrNull(i)?.coerceIn(0f, 1f) ?: 0f) * 100
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        axis,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        fontSize = 10.sp
                    )
                    Text(
                        "${pct.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = strokeColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp
                    )
                }
            }
        }

        if (label.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}
