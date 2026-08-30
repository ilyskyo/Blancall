// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 氛围背景占位容器（已移除渐变光斑）。
 *
 * 保留原因：[GlassCard] / [GlassMenuCard] 等组件开启 backdrop 真实模糊时，
 * 需要在子层嵌入一个"可采样 ViewGroup"作为模糊源（见 glassSurface 实现）。
 * 本组件仅提供 statusBarsPadding + fillMaxSize 的空容器，不再绘制任何渐变光斑。
 *
 * 纯色页面背景由调用方的 `Modifier.background(MaterialTheme.colorScheme.background)` 提供。
 *
 * 用法：放在页面最外层 Box 的最底部（背景色之上、内容之下）。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().statusBarsPadding())
}
