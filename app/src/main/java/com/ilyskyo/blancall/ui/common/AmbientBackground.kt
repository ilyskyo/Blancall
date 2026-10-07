// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面底色占位容器：只提供 statusBarsPadding + fillMaxSize 的空 Box，不绘制任何内容。
 *
 * 历史：这里曾画渐变光斑，后来被移除；配套的 glassSurface 真实模糊层也已删除
 * （RenderEffect 模糊不到身后内容，见 GlassBlur.kt 的说明）。
 * 保留它只为让各页保持"背景色之上、内容之下"这一层结构一致，删掉它需要同步改 10 个调用点。
 *
 * 纯色页面背景由调用方的 `Modifier.background(MaterialTheme.colorScheme.background)` 提供。
 *
 * 用法：放在页面最外层 Box 的最底部（背景色之上、内容之下）。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().statusBarsPadding())
}
