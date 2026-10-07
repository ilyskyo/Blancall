// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

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

// 这里原有 `Modifier.glassSurface(enabled, radiusPx)`：在 graphicsLayer 里挂 RenderEffect.createBlurEffect。
// 它被删掉是因为 Compose 的 RenderEffect 只能模糊**自己图层已绘制的内容**，模糊不到身后的页面，
// 所以两个调用点（GlassMenu / GlassSheet）实际是在给一块什么都不画的空容器做全屏 GPU 模糊。
// 需要真实背景模糊时用 LiquidGlassView（原生采样）；需要模糊自身内容时用 androidx.compose.ui.draw.blur。
