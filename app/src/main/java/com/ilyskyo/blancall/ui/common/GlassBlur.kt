// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/** 玻璃卡片在深色模式下的不透明度（更低 → 氛围光斑透出更明显） */
const val GLASS_ALPHA_DARK = 0.68f

/** 玻璃卡片在浅色模式下的不透明度（与素材库网页卡片 rgba(255,255,255,.72) 对齐） */
const val GLASS_ALPHA_LIGHT = 0.72f

/**
 * 玻璃下拉菜单在浅色模式下的不透明度（比 [GLASS_ALPHA_LIGHT] 更实，避免背后文字透出
 * 影响菜单项可读；菜单通常叠加在内容页上，而不是像卡片那样在静态底色上）。
 * 深色模式下沿用 [GLASS_ALPHA_DARK]。
 */
const val GLASS_MENU_ALPHA_LIGHT = 0.93f

/**
 * 在 API31+ 上对内容施加真实 backdrop blur（[RenderEffect] + `graphicsLayer`）。
 *
 * 低版本（< API 31）直接原样返回，由调用方降级为「仅半透明染色层」，
 * 保证观感不退化（minSdk = 26，必须守卫）。
 *
 * @param enabled 是否启用模糊（默认 true；可在调用方根据版本/开关控制）
 * @param radiusPx 模糊半径（像素）
 */
fun Modifier.glassSurface(enabled: Boolean = true, radiusPx: Float = 24f): Modifier =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        this
    }
