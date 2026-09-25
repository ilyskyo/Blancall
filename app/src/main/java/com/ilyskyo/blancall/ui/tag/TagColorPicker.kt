// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagChipView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 标签颜色选择器：**色环自由取色 + 预设色系套装**双通道（两者互不丢失各自选择）。
 *
 * - 色环：HSV 圆盘（角度=色相、半径=饱和度，盘面固定 V=1）+ 明度滑条；
 *   点击/拖动即时取色，拖动全程消费指针（不与外层面板滚动抢手势）；
 * - 套装：4 套 × 8 色成品配色，点选即赋色；
 * - 底部实时预览（当前名称的 chip 效果）+ HEX 回显；
 * - 所有颜色数学收敛到 [ColorOps]（纯函数、可单测）。
 *
 * 状态保存：色环 HSV 与模式均 rememberSaveable（旋转不丢；
 * 外部颜色变化——切换编辑对象/套装赋色——自动同步回色环）。
 */
@Composable
fun TagColorPicker(
    color: Int,
    previewName: String,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 模式：0 = 色环自由取色；1 = 预设色系套装
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var hsv by rememberSaveable(stateSaver = HsvSaver) {
        mutableStateOf(ColorOps.rgbToHsv(color).copyOf(3))
    }

    // 外部颜色变化（切换标签 / 点选套装）→ 同步色环；自身拖动回写因数值一致不会重置
    LaunchedEffect(color) {
        val current = ColorOps.hsvToRgb(hsv[0], hsv[1], hsv[2])
        if (current != color) hsv = ColorOps.rgbToHsv(color).copyOf(3)
    }

    /** 色环取色回调（rememberUpdatedState 保证闭包内 value 为最新值） */
    val pickRef = rememberUpdatedState<(Float, Float) -> Unit> { hue, sat ->
        onColorChange(ColorOps.hsvToRgb(hue, sat, hsv[2]))
    }

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("色环") },
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("色系套装") },
            )
        }

        Spacer(Modifier.height(10.dp))

        if (mode == 0) {
            // ── 色环（居中摆放）──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(200.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val radius = minOf(size.width, size.height) / 2f
                                fun pick(pos: Offset) {
                                    val dx = pos.x - center.x
                                    val dy = pos.y - center.y
                                    val r = sqrt(dx * dx + dy * dy)
                                    val sat = (r / radius).coerceIn(0f, 1f)
                                    var deg = Math.toDegrees(
                                        atan2(dy.toDouble(), dx.toDouble())
                                    ).toFloat()
                                    if (deg < 0f) deg += 360f
                                    pickRef.value(deg, sat)
                                }
                                down.consume()
                                pick(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    pick(change.position)
                                }
                            }
                        },
                ) {
                    val radius = size.minDimension / 2f
                    val center = this.center
                    // 饱和盘：色相环（V=1）+ 白心（半径 = 饱和度）
                    drawCircle(brush = Brush.sweepGradient(HUE_STOPS), radius = radius, center = center)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                    // 指示器：当前色相/饱和度位置（白描边 + 半透明黑外环，任意底色都可见）
                    val ang = Math.toRadians(hsv[0].toDouble())
                    val px = center.x + hsv[1] * radius * cos(ang).toFloat()
                    val py = center.y + hsv[1] * radius * sin(ang).toFloat()
                    drawCircle(Color.White, radius = 9f, center = Offset(px, py), style = Stroke(2.5f))
                    drawCircle(
                        Color.Black.copy(alpha = 0.35f),
                        radius = 10.5f,
                        center = Offset(px, py),
                        style = Stroke(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 明度滑条（盘面固定 V=1 展示纯色相/饱和度；实际颜色 = 盘面色 × 明度）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "明度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = hsv[2],
                    onValueChange = { v ->
                        val next = hsv.copyOf()
                        next[2] = v.coerceIn(0f, 1f)
                        hsv = next
                        onColorChange(ColorOps.hsvToRgb(next[0], next[1], next[2]))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // ── 预设色系套装（4 套 × 8 色，点选即赋色）──
            ColorOps.PRESET_PALETTES.forEachIndexed { presetIndex, preset ->
                if (presetIndex > 0) Spacer(Modifier.height(10.dp))
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                preset.colors.chunked(4).forEachIndexed { rowIndex, rowColors ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowColors.forEach { c ->
                            PresetDot(
                                color = c,
                                selected = c == color,
                                onClick = { onColorChange(c) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 预览与 HEX 回显 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagChipView(TagChipUi(name = previewName.ifBlank { "标签" }, color = color))
            Spacer(Modifier.width(10.dp))
            Text(
                ColorOps.toHex(color),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 套装色圆点：26dp；选中态以主色描外环标记 */
@Composable
private fun PresetDot(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFF000000.toInt() or (color and 0xFFFFFF)))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ring else outline.copy(alpha = 0.6f),
                shape = CircleShape,
            )
            .pointerInput(color) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            onClick()
                            break
                        }
                    }
                }
            },
    )
}

/** 色环 HSV 状态保存器（旋转恢复用） */
private val HsvSaver = listSaver<FloatArray, Float>(
    save = { it.toList() },
    restore = { it.toFloatArray() },
)

/** 色相停靠点（0°..360°，步长 30°，S=V=1），与 sweepGradient 均分对应 */
private val HUE_STOPS: List<Color> = (0..12).map { i ->
    Color(0xFF000000.toInt() or ColorOps.hsvToRgb(i * 30f, 1f, 1f))
}
