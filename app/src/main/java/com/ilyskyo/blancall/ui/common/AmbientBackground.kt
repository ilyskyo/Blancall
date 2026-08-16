// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 毛玻璃氛围背景：为玻璃卡片提供透出层次的光斑。
 *
 * - 右下光斑：Brush.radialGradient 纯渐变绘制，零模糊开销，全版本可用
 * - 中央主光斑：独立小面积 Box + Modifier.blur（API 31+ 生效，低版本降级为清晰光斑）
 * - 状态栏区域保持纯背景色：内部 statusBarsPadding，光斑只在状态栏以下绘制
 * - 性能：仅 2 个绘制节点、一次性静态绘制，不随滚动重绘
 *
 * 用法：放在页面最外层 Box 的最底部（背景色之上、内容之下）。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    // statusBarsPadding：光斑/渐变避开状态栏区域，状态栏保持纯背景色
    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        // 右下光斑（辅助色）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.94f, size.height * 0.88f)
            val radius = size.width * 0.50f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = 0.10f), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
        // 中央主光斑：小面积 blur 增强玻璃感（限制模糊面积，控制合成开销）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 140.dp)
                .size(320.dp)
                .blur(60.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.45f, size.height * 0.45f)
                val radius = size.minDimension * 0.5f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.12f), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
    }
}
