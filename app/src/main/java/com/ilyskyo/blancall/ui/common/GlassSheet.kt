// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 底部面板容器（无渐变版）：半透明染色 + 28dp 顶部圆角 + 黑色 32% scrim。
 * 已移除顶部高光 verticalGradient 装饰，回归纯色面板。
 *
 * 玻璃层：API31+ 内嵌 AmbientBackground + glassSurface 真实模糊（低版本自动退化为仅半透明染色）。
 *
 * 替换点：PracticeScreen×3 / ModePickerPopup / ReadingModeScreen 设置面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    dragHandle: @Composable (() -> Unit)? = null,
    scrimColor: Color = Color.Black.copy(alpha = 0.32f),
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isBlancallDark()
    // 容器染色：浅色用菜单级 0.93（面板常叠在内容页上，太透会干扰阅读）；深色沿用卡片级 0.68
    val stain = if (isDark) {
        Color(0xFF1C1C1E).copy(alpha = GLASS_ALPHA_DARK)
    } else {
        Color.White.copy(alpha = GLASS_MENU_ALPHA_LIGHT)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = stain,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        // 直接展开到内容高度；避免部分展开（半屏）时出现大片留白
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        content = {
            Box(modifier = Modifier.fillMaxWidth()) {
                // backdrop 真实模糊层（API31+）：内嵌氛围空容器并施加玻璃模糊，低版本跳过。
                // AmbientBackground 已无渐变光斑，模糊出的仍为纯色面但保留 GPU blur 层次。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(shape)
                            .glassSurface(radiusPx = 18f)
                    ) { AmbientBackground() }
                }
                // 内容（绘制在最上层）：wrap 高度 + 可滚动，让面板高度恰好包住内容（不出现大块留白）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    content = content
                )
            }
        }
    )
}
