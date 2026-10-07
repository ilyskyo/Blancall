// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.pinchZoomByTouch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode


// ========== 顶部标题滑动切换模式 ==========

internal const val TitleSwipeThreshold = 80f

// 标题拖拽跟手上限（px）：限制可拉范围，避免越拉越远
internal const val MaxTitleDrag = 96f


/** 三种模式的有序列表（用于标题左右滑动循环切换） */
internal val MODE_ORDER = listOf(BlancallMode.SENTENCE, BlancallMode.WORD, BlancallMode.REVERSE)


internal fun nextMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) + 1) % MODE_ORDER.size]


internal fun prevMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) - 1 + MODE_ORDER.size) % MODE_ORDER.size]


// ========== 双指缩放字号 ==========

/**
 * 双指捏合缩放（仅手指触点）。实现已统一到 [com.ilyskyo.blancall.ui.common.pinchZoomByTouch]，
 * 此处保留同名的内部别名，避免各处调用点大面积改动。
 *
 * 关键修复：旧实现用 `pressed.size >= 2` 判定双指，**未过滤 PointerType**，
 * 导致平板上「一手扶屏（手指）+ 一手写字（主动笔）」被误判为双指捏合 → 字号乱跳。
 * 现在只有手指参与捏合，手写笔与鼠标不触发缩放。
 */
internal fun Modifier.pinchZoom(onZoomChange: (Float) -> Unit): Modifier =
    this.pinchZoomByTouch(onZoomChange = onZoomChange)


/** 把整套 Typography 的每个字号按 [scale] 放大（lineHeight 同步），用于练习页字号缩放。 */
internal fun scaledTypography(base: Typography, scale: Float): Typography {
    if (scale <= 0f || scale == 1f) return base
    fun s(ts: TextStyle): TextStyle = ts.copy(fontSize = ts.fontSize * scale, lineHeight = ts.lineHeight * scale)
    return Typography(
        displayLarge = s(base.displayLarge),
        displayMedium = s(base.displayMedium),
        displaySmall = s(base.displaySmall),
        headlineLarge = s(base.headlineLarge),
        headlineMedium = s(base.headlineMedium),
        headlineSmall = s(base.headlineSmall),
        titleLarge = s(base.titleLarge),
        titleMedium = s(base.titleMedium),
        titleSmall = s(base.titleSmall),
        bodyLarge = s(base.bodyLarge),
        bodyMedium = s(base.bodyMedium),
        bodySmall = s(base.bodySmall),
        labelLarge = s(base.labelLarge),
        labelMedium = s(base.labelMedium),
        labelSmall = s(base.labelSmall)
    )
}
