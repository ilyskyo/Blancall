// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 阅读页液态玻璃组件（图标按钮 / 玻璃胶囊 / 降级玻璃板 / 进度胶囊；从 ReadingModeScreen.kt 拆出）。
 */

/** dp → px（液态玻璃参数用） */
@Composable
internal fun LgCornerPx(dp: Float): Float = with(LocalDensity.current) { dp.dp.toPx() }


/**
 * 玻璃胶囊内的图标按钮：学 Kyant0 LiquidButton 的按压反馈——
 * 按下时微缩 + 变淡，spring 回弹（"液态"手感），保留系统 ripple。
 */
@Composable
internal fun GlassIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    buttonSize: Dp,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glassIconScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.66f else 1f,
        animationSpec = tween(110),
        label = "glassIconAlpha"
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    ) {
        content()
    }
}


/**
 * 单颗液态玻璃胶囊：底层半透明玻璃兜底（低版本），中层真实液态折射玻璃
 * （仅 API 33+ 渲染，采样 [sourceRef] 指向的正文容器），最上层绘制控件内容。
 *
 * 顺序关键：玻璃效果绘制在「控件内容」之下，保证图标/文字清晰不被折射模糊。
 *
 * @param touchAlpha 外层控件层的淡入淡出透明度：接近 0（完全消失）时玻璃退出
 *   绘制与触摸命中（INVISIBLE），让下层正文可点——否则 alpha 归零的玻璃 View
 *   仍在 View 体系参与命中，其区域下方内容点不了（真机复现）。
 */
@Composable
internal fun LiquidGlassPill(
    sourceRef: Ref<FrameLayout?>,
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier,
    touchAlpha: Float = 1f,
    content: @Composable () -> Unit
) {
    // 持有真实 LiquidGlassView 引用：退出组合时 bind(null) 释放采样跟踪器（库内部 recycle），
    // 避免视图移除后 PreDraw 监听残留在 ViewTreeObserver 上。
    val glassViewRef = remember { Ref<LiquidGlassView?>(null) }
    // 柔和投影：玻璃悬浮的"离地感"（新一代系统玻璃元素都有软阴影）
    val density = LocalDensity.current
    val shape = remember(cornerPx) { RoundedCornerShape(with(density) { cornerPx.toDp() }) }
    Box(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.26f)
            )
    ) {
        // 1) 兜底玻璃板：半透明底色 + 顶部高光 + 描边（API<33 时的完整外观）
        FallbackGlassPlate(isDark = isDark, cornerPx = cornerPx, modifier = Modifier.matchParentSize())
        // 2) 真实液态玻璃（折射 + 色散 + 模糊，仅 Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val refractPx = with(LocalDensity.current) { LgRefractionHeightDp.dp.toPx() }
            val offsetPx = with(LocalDensity.current) { LgRefractionOffsetDp.dp.toPx() }
            AndroidView(
                factory = { ctx ->
                    LiquidGlassView(ctx).apply {
                        // bind 前兜底背景：液态层尚未采样到内容时先给玻璃底色，
                        // 否则首帧只剩高光辉光块（与导航栏同一处理）
                        setBackgroundColor(
                            if (isDark) android.graphics.Color.argb(230, 26, 26, 26)
                            else android.graphics.Color.argb(230, 255, 255, 255)
                        )
                        setCornerRadius(cornerPx)
                        setRefractionHeight(refractPx)
                        setRefractionOffset(offsetPx)
                        setBlurRadius(LgBlurRadius)
                        setDispersion(LgDispersion)
                        if (isDark) {
                            // 深色：暖黑玻璃，与导航栏 tint 一致
                            setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                            setTintAlpha(0.25f)
                        } else {
                            // 浅色：白玻璃轻染，与导航栏 tint 一致
                            setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                            setTintAlpha(0.12f)
                        }
                        setDraggableEnabled(false)
                        setElasticEnabled(false)
                        setTouchEffectEnabled(false)
                        glassViewRef.value = this
                        // 延迟到本帧测量/布局完成后再绑定采样源：
                        // 挂载帧内同步 bind 会 invalidate + 重建采样配置，与正在进行的
                        // measure pass 竞争（真机复现 requireOwner 崩溃的诱因之一）。
                        post {
                            if (isAttachedToWindow && getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                                sourceRef.value?.let { bind(it) }
                                setTag(com.ilyskyo.blancall.R.id.lg_bound_tag, true)
                            }
                        }
                    }
                },
                update = { view ->
                    // 淡出完成后 INVISIBLE：退出命中测试与绘制（样貌不变，本就不可见），
                    // 否则消失的玻璃仍拦截其区域点击，下层正文点不了
                    view.visibility = if (touchAlpha > 0.05f) View.VISIBLE else View.INVISIBLE
                    if (view.getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                        view.post {
                            if (view.isAttachedToWindow && view.getTag(com.ilyskyo.blancall.R.id.lg_bound_tag) == null) {
                                sourceRef.value?.let { view.bind(it) }
                                view.setTag(com.ilyskyo.blancall.R.id.lg_bound_tag, true)
                            }
                        }
                    }
                },
                modifier = Modifier.matchParentSize()
            )
        }
        // 3) 控件内容：绘于玻璃之上
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { content() }
    }
    // 退出组合：解绑采样（bind(null) → 库内 LiquidTracker.recycle()），清除残留绘制监听
    DisposableEffect(Unit) {
        onDispose {
            runCatching { glassViewRef.value?.bind(null) }
        }
    }
}

// tag key 改用 res/values/ids.xml 中的 com.ilyskyo.blancall.R.id.lg_bound_tag：
// View.setTag(key, tag) 的 key 必须是应用资源 id，裸常量（如 0x4C47）会直接抛
// IllegalArgumentException("The key must be an application-specific resource id")，真机闪退。


/** 兜底玻璃板（半透明底色 + 高光描边 + 圆角，已移除顶部高光 verticalGradient 装饰） */
@Composable
internal fun FallbackGlassPlate(
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerPx)
    val glassActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    // 真玻璃存在时底色极淡（质感交给折射层）；无玻璃时用半透明底撑住可读性
                    isDark -> if (glassActive) Color(0x1FFFFFFF) else Color(0xE61A1A1A)
                    else -> if (glassActive) Color(0x14FFFFFF) else Color(0xC8FFFFFF)
                }
            )
            // 高光描边：玻璃边缘的亮线（液态玻璃的标志性边缘），深色下提亮、浅色下用白
            .border(BorderStroke(1.dp, if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF)), shape)
    )
}


/** 底部细进度胶囊（阅读进度） */
@Composable
internal fun ReadingProgressCapsule(
    fraction: Float,
    isDark: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(168.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (isDark) Color(0x33FFFFFF) else Color(0x26000000))
    ) {
        val animated = animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(220),
            label = "readProgress"
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated.value)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.85f))
        )
    }
}

// ========== 阅读设置面板 ==========

