// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import com.kyant.backdrop.BackdropEffectScope

/**
 * 导航栏专用的轻量 vibrancy（饱和 + 轻对比）。
 *
 * 上游 1.0.0 没有暴露 `vibrancy()`；这里用 Android 的 ColorMatrix RenderEffect
 * 直接接到 [BackdropEffectScope.renderEffect] 上。调用顺序必须是
 * vibrancy → blur → lens：后两者会把当前 effect 包在内层，顺序反了会被覆盖。
 *
 * API < 31 没有 RenderEffect，本函数直接跳过；玻璃层会走模糊/纯色降级。
 */
internal fun BackdropEffectScope.navVibrancy() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val matrix = ColorMatrix().apply {
        setSaturation(1.18f)
        // 以 0.5 为中心做 1.06 对比：127.5 * (1 - 1.06) = -7.65
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    1.06f, 0f, 0f, 0f, -7.65f,
                    0f, 1.06f, 0f, 0f, -7.65f,
                    0f, 0f, 1.06f, 0f, -7.65f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    val filter = ColorMatrixColorFilter(matrix)
    val current = renderEffect
    renderEffect = if (current != null) {
        RenderEffect.createColorFilterEffect(filter, current)
    } else {
        RenderEffect.createColorFilterEffect(filter)
    }
}

/** 导航栏玻璃是否有意义（API 31+ 才支持 RenderEffect 模糊）。 */
internal fun navGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
