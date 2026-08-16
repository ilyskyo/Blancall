// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 统一间距与圆角尺寸 Token（4/8 栅格）。
 *
 * 用于替代散落在各屏幕的字面量 `Spacer` / `Modifier.padding(N.dp)`，
 * 保证全应用视觉节奏一致、易于微调。纯视觉，零行为影响。
 */
object Dimens {
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingM = 12.dp
    val spacingL = 16.dp
    val spacingXl = 24.dp
    val spacingXxl = 32.dp

    val radiusSm = 12.dp   // 对齐 BlancallShapes.extraSmall
    val radiusMd = 16.dp
    val radiusLg = 24.dp
}
