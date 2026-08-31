// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilyskyo.blancall.R
import com.qmdeve.liquidglass.widget.LiquidGlassView
import java.util.concurrent.atomic.AtomicReference

/**
 * 弹窗兜底玻璃板：半透明底色 + 高光描边（Apple 液态玻璃的标志性边缘）。
 *
 * @param glassActive 真实液态玻璃是否存在。存在时底色压到极淡（质感交给折射层，
 *                    底色过重会把折射效果盖死）；不存在时（API<33）用较实底色撑住可读性。
 */
@Composable
fun PopupGlassPlate(
    isDark: Boolean,
    glassActive: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    isDark -> if (glassActive) Color(0x331C1C1E) else Color(0xE61A1A1A)
                    else -> if (glassActive) Color(0x66FFFFFF) else Color(0xF2FFFFFF)
                }
            )
            .border(
                BorderStroke(1.dp, if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF)),
                shape
            )
    )
}

/**
 * 弹窗液态玻璃底（仅 API 33+）：自绘「氛围光斑」采样源 + 真实 LiquidGlassView 折射。
 *
 * 层次（自下而上）：光斑画布（采样源）→ 兜底玻璃板 → 折射玻璃 → 内容。
 *
 * **为什么不用页面做采样源**：本弹窗是页面内 overlay，拿不到像阅读模式那样独立的
 * 正文容器；若直接采样页面根布局，玻璃会采样到玻璃自身，形成逐帧反馈循环。
 * 因此沿用项目既有思路（[LiquidBackdropCanvas]）：自绘几颗主题色光斑作为 bind 采样源，
 * 折射 / 色散 / 模糊作用于这些光斑，液态玻璃质感依旧完整。
 *
 * **三个必须做的设置**（缺一就会出现"整块模糊盖住文字"）：
 * 1. `bind(采样源)` —— 不 bind 的 LiquidGlassView 无内容可折射，会渲染成一块糊斑
 * 2. `setTintAlpha` —— 控制玻璃自身不透明度，过重则完全遮住下层与文字
 * 3. `setDraggableEnabled/setElasticEnabled/setTouchEffectEnabled(false)` ——
 *    弹窗内的玻璃不该响应拖拽/弹性/触摸特效，否则会抢走列表项的点击
 */
@Composable
fun LiquidGlassPopupBackdrop(
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val glassRef = remember { AtomicReference<LiquidGlassView?>(null) }
    val sourceRef = remember { AtomicReference<LiquidBackdropCanvas?>(null) }

    val refractPx = with(density) { 26.dp.toPx() }
    val offsetPx = with(density) { 80.dp.toPx() }

    // 主题色光斑：五点均匀分布（左上/右上/左下/右下/底部中），
    // 确保液态玻璃在整个卡片范围内都有可折射的采样源。
    // 浅色：靛蓝 + 淡紫 + 冷白；深色：冷白 + 靛蓝 + 淡紫
    val spots = remember(isDark) {
        if (isDark) {
            listOf(
                LiquidBackdropCanvas.Spot(0x253B5DE7.toInt(), 0.15f, 0.12f, 0.48f),   // 左上 — 靛蓝
                LiquidBackdropCanvas.Spot(0x207A6BF0.toInt(), 0.85f, 0.10f, 0.46f),   // 右上 — 淡紫
                LiquidBackdropCanvas.Spot(0x18A78BFA.toInt(), 0.10f, 0.75f, 0.42f),   // 左下 — 淡紫
                LiquidBackdropCanvas.Spot(0x156B8EF0.toInt(), 0.90f, 0.70f, 0.44f),   // 右下 — 蓝
                LiquidBackdropCanvas.Spot(0x14FFFFFF,         0.50f, 0.94f, 0.38f)    // 底部中 — 冷白高光
            )
        } else {
            listOf(
                LiquidBackdropCanvas.Spot(0x283B5DE7, 0.14f, 0.10f, 0.46f),           // 左上 — 品牌靛蓝
                LiquidBackdropCanvas.Spot(0x22A78BFA, 0.86f, 0.08f, 0.44f),          // 右上 — 淡紫
                LiquidBackdropCanvas.Spot(0x1A7A6BF0, 0.08f, 0.73f, 0.40f),          // 左下 — 蓝紫
                LiquidBackdropCanvas.Spot(0x186B8EF0, 0.92f, 0.68f, 0.42f),          // 右下 — 蓝
                LiquidBackdropCanvas.Spot(0x2EFFFFFF, 0.50f, 0.95f, 0.38f)           // 底部中 — 白色高光
            )
        }
    }

    Box(modifier) {
        // ── a) 采样源：自绘氛围光斑（最底层，供玻璃折射）──
        AndroidView(
            factory = { ctx ->
                LiquidBackdropCanvas(ctx).apply { sourceRef.set(this) }
            },
            modifier = Modifier.matchParentSize(),
            update = { canvas -> canvas.setSpots(spots) }
        )

        // ── b) 兜底玻璃板：半透明底色 + 高光描边 ──
        PopupGlassPlate(isDark = isDark, glassActive = true, modifier = Modifier.matchParentSize())

        // ── c) 真实液态玻璃：折射 + 色散 + 模糊 ──
        AndroidView(
            factory = { ctx ->
                LiquidGlassView(ctx).apply {
                    setCornerRadius(cornerPx)
                    setRefractionHeight(refractPx)
                    setRefractionOffset(offsetPx)
                    setBlurRadius(24f)
                    setDispersion(0.45f)
                    if (isDark) {
                        // 深色：暖黑玻璃，略微提透明度撑起质感
                        setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                        setTintAlpha(0.30f)
                    } else {
                        // 浅色：白玻璃轻染，让"玻璃"本身可见而不喧宾夺主
                        setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                        setTintAlpha(0.14f)
                    }
                    setDraggableEnabled(false)
                    setElasticEnabled(false)
                    setTouchEffectEnabled(false)
                    glassRef.set(this)
                    // 延迟到布局完成后再 bind：挂载帧内同步 bind 会与 measure pass 竞争
                    post {
                        if (isAttachedToWindow && getTag(R.id.lg_bound_tag) == null) {
                            sourceRef.get()?.let { bind(it) }
                            setTag(R.id.lg_bound_tag, true)
                        }
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                if (view.getTag(R.id.lg_bound_tag) == null) {
                    view.post {
                        if (view.isAttachedToWindow && view.getTag(R.id.lg_bound_tag) == null) {
                            sourceRef.get()?.let { view.bind(it) }
                            view.setTag(R.id.lg_bound_tag, true)
                        }
                    }
                }
            }
        )
    }

    // 退出组合：解绑采样（bind(null) → 库内 recycle），清除残留绘制监听
    DisposableEffect(Unit) {
        onDispose { runCatching { glassRef.get()?.bind(null) } }
    }
}
