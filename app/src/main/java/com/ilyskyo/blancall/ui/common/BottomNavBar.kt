// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.qmdeve.liquidglass.widget.LiquidGlassView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 液态玻璃导航栏参数 ──
// 滑块 blur 12 / 色散 0：中性白玻璃，色散完全关闭——滑块边缘不产生任何绿/黄/紫色差光晕。
// 液态质感由 refractionHeight/refractionOffset（折射形变）提供，不靠色散；色散是绿黄圈的根源，
// 与背后页面内容无关（不同页面"看似有无色散"只是对比度差异，关掉即全局一致无彩边）──
private const val LgBarBlur = 6f
private const val LgBarDispersion = 0.5f

private const val LgSliderBlur = 12f
private const val LgSliderDispersion = 0f

/**
 * 液态玻璃底部导航栏（iOS 26 Liquid Glass tab bar 风格，真机真液态）：
 *
 * 架构（与阅读模式完全同构）：
 * - AppNavigation 维护一个 **专用 FrameLayout 页面容器**（pageHost），挂 NavHost；
 *   BottomNavBar 拿到 pageHost 引用。
 * - 整条 LiquidGlassView 和滑块 LiquidGlassView 都是 BottomNavBar 的子 View，
 *   它们的 **父 ViewGroup** = Activity 根 layout = pageHost 的父 layout —— 因此
 *   `bind(pageHost)` 拿到的页面像素就跟玻璃同级（无自我反馈循环）。
 * - 玻璃参数：bar blur 6 + dispersion 0.5；slider blur 12 + dispersion 0（slider 关闭色散，仅保留折射形变）。
 *
 * 布局策略：
 * - BoxWithConstraints 算 tabW = 玻璃条宽 / tab 数
 * - 玻璃条 = AndroidView(LiquidGlassView) fillMaxSize（z 最低）
 * - 滑块 = Box(size+absoluteOffset) 包 AndroidView(LiquidGlassView) + 描边/高光
 *   滑块 z 位于玻璃条之上，但与 tab 内容 Row 互不干扰（滑块仅占 48dp 居中）
 * - tab Row = 顶层 Row，z 最高
 *
 * 全部不用 Modifier.layout 自定义 measure，避免与 View 生命周期竞速。
 */
@Composable
fun BottomNavBar(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    showLibraryTab: Boolean = false,
    host: FrameLayout? = null
) {
    val baseTabs = listOf(
        "首页" to AppIconKind.Home,
        "我的文章" to AppIconKind.Articles,
        "数据" to AppIconKind.Insights,
    )
    val tabs = if (showLibraryTab) baseTabs + ("素材库" to AppIconKind.Library) else baseTabs
    val tabCount = tabs.size
    val isDark = isBlancallDark()
    val accent = MaterialTheme.colorScheme.primary
    val subTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val barHeightDp = 64.dp
    val barCornerDp = 28.dp
    val barCornerPx = with(density) { barCornerDp.toPx() }
    val sliderHPx = with(density) { 48.dp.toPx() }
    val sliderHPxHalf = sliderHPx / 2f
    val barHpadPx = with(density) { 10.dp.toPx() }
    // 折射采样参数按库 Demo 默认（H 20dp / 偏移 70dp）
    val barRefractHpx = with(density) { 20.dp.toPx() }
    val barRefractOffPx = with(density) { 70.dp.toPx() }
    val sliderRefractHpx = with(density) { 20.dp.toPx() }
    val sliderRefractOffPx = with(density) { 70.dp.toPx() }
    val barShape = RoundedCornerShape(barCornerDp)
    val sliderShape = RoundedCornerShape(50)

    val barGlassRef = remember { AtomicReference<LiquidGlassView?>(null) }
    val sliderGlassRef = remember { AtomicReference<LiquidGlassView?>(null) }

    // 滑块动画实例：不随 currentTab 重建（否则每次切页都重置到新位置、无滑动动画）。
    // 仅 tabCount 变化（如素材库开关）时重建。
    val sliderAnim = remember(tabCount) {
        Animatable(
            initialValue = currentTab.toFloat().coerceIn(0f, (tabCount - 1).toFloat())
        )
    }
    var sliderPressed by remember { mutableStateOf(false) }
    val sliderScale by animateFloatAsState(
        targetValue = if (sliderPressed) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
        label = "sliderScale"
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(currentTab, tabCount) {
        sliderAnim.animateTo(
            currentTab.toFloat().coerceIn(0f, (tabCount - 1).toFloat()),
            spring(dampingRatio = 0.6f, stiffness = 380f)
        )
    }
    // host/玻璃 View 创建竞速：等两颗玻璃都创建完再 bind
    LaunchedEffect(host) {
        val target = host ?: return@LaunchedEffect
        repeat(400) {
            val bar = barGlassRef.get()
            val sli = sliderGlassRef.get()
            if (bar != null && sli != null) {
                bar.bind(target)
                sli.bind(target)
                return@LaunchedEffect
            }
            delay(16)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 14.dp)
                .height(barHeightDp)
        ) {
            // BoxWithConstraints 的 constraints 已扣除外层 padding：maxWidth 即内容区宽度，
            // 无需再减水平 padding（再减会导致 tabW 偏小 → 滑块偏左、点击区算偏右）。
            // tab 均分、滑块定位与点击区计算统一基于此宽度，三者天然一致。
            val barW = constraints.maxWidth.toFloat()
            val barH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val tabW = barW / tabCount

            // ── 手势层（统一处理 tap 与拖动切换）──
            // 悬浮玻璃导航栏下层是 NavHost 页面容器：若点击下放给 tab clickable，
            // 页面内元素（列表/卡片）会先消费 up 导致 tap 失效。
            // 因此全部交互在此完成，并在 Initial pass 消费事件、阻断泄漏到下层页面。
            val haptic = LocalHapticFeedback.current
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .pointerInput(tabCount) {
                        awaitEachGesture {
                            // Initial pass 最先收到事件：立即消费 down，下层页面收不到未消费事件
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            down.consume()
                            sliderPressed = true
                            var tracking = false
                            val startX = down.position.x
                            var lastX = startX
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // 抬起：tab 归属按「按下位置」向下取整判定——roundToInt 在 tab 中心处
                                    // 产生半格偏移（点第 2 个 tab 会算成第 3 个）；手指微动不跨 tab 时
                                    // 也按按下位置，避免误入拖动吸附导致点击无响应。
                                    val startTab = (startX / tabW).toInt().coerceIn(0, tabCount - 1)
                                    val endTab = (change.position.x / tabW).toInt().coerceIn(0, tabCount - 1)
                                    val targetTab = if (startTab == endTab) startTab else endTab
                                    scope.launch {
                                        sliderAnim.animateTo(
                                            targetTab.toFloat(),
                                            spring(dampingRatio = 0.6f, stiffness = 380f)
                                        )
                                    }
                                    onSelect(targetTab)
                                    sliderPressed = false
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    change.consume()
                                    break
                                }
                                if (!tracking && abs(change.position.x - startX) > viewConfiguration.touchSlop) {
                                    tracking = true
                                    lastX = change.position.x
                                }
                                if (tracking) {
                                    change.consume()
                                    val dx = change.position.x - lastX
                                    lastX = change.position.x
                                    val t = (sliderAnim.value + dx / tabW).coerceIn(0f, (tabCount - 1).toFloat())
                                    scope.launch { sliderAnim.snapTo(t) }
                                }
                            }
                        }
                    }
            )

            val sliderWPx = (tabW - barHpadPx * 2f).coerceAtLeast(1f)
            val sliderWDp = with(density) { sliderWPx.toDp() }
            val sliderOffsetX = (sliderAnim.value * tabW + (tabW - sliderWPx) / 2f)
                .coerceIn(0f, barW - sliderWPx)
            val sliderOffsetY = (barH - sliderHPx) / 2f
            val sliderShapePx = sliderHPxHalf

            // ── ① 玻璃条（fillMaxSize；bind host 折射真实页面）──
            // 注意：必须用 fillMaxSize（内容区）而非 matchParentSize（父总尺寸含 padding），
            // 否则玻璃条/手势层从屏幕左缘起算，与 tab/滑块的内容区坐标错位 14dp。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AndroidView(
                    factory = { ctx ->
                        LiquidGlassView(ctx).apply {
                            // bind 前兜底背景：液态层未采样前给玻璃条底色，
                            // 避免首帧只剩白色高光辉光块（两个分离白块）
                            setBackgroundColor(
                                if (isDark) android.graphics.Color.argb(230, 26, 26, 26)
                                else android.graphics.Color.argb(230, 255, 255, 255)
                            )
                            setCornerRadius(barCornerPx)
                            setRefractionHeight(barRefractHpx)
                            setRefractionOffset(barRefractOffPx)
                            setBlurRadius(LgBarBlur)
                            setDispersion(LgBarDispersion)
                            if (isDark) {
                                setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                                setTintAlpha(0.25f)
                            } else {
                                setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                                setTintAlpha(0.12f)
                            }
                            setDraggableEnabled(false)
                            setElasticEnabled(false)
                            setTouchEffectEnabled(false)
                            barGlassRef.set(this)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .shadow(
                            elevation = 14.dp,
                            shape = barShape,
                            ambientColor = Color.Black.copy(alpha = 0.18f),
                            spotColor = Color.Black.copy(alpha = 0.24f),
                            clip = false
                        )
                        .clip(barShape)
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .shadow(
                            14.dp, barShape,
                            ambientColor = Color.Black.copy(alpha = 0.20f),
                            spotColor = Color.Black.copy(alpha = 0.26f)
                        )
                        .clip(barShape)
                        .background(if (isDark) Color(0xE61A1A1A) else Color(0xC8FFFFFF))
                )
            }

            // ── ② 玻璃条饰面：1dp 描边（顶部高光渐变已移除，纯玻璃质感）──
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(0.1f)
                    .clip(barShape)
                    .drawBehind {
                        drawRoundRect(
                            color = if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF),
                            size = size,
                            cornerRadius = CornerRadius(barCornerPx, barCornerPx),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            )

            // ── ③ tab 内容（z 最高层：图标+文字显示在滑块之上；点击由手势层统一处理）──
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize().zIndex(2f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, (label, kind) ->
                    val selected = index == currentTab
                    // 选中项用主题色（浮在浅色玻璃滑块上对比清晰，参考 iOS 26）
                    val tint = if (selected) accent else subTint
                    Box(
                        modifier = Modifier
                            .width(with(density) { tabW.toDp() })
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            AppIcon(kind = kind, tint = tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.size(3.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint
                            )
                        }
                    }
                }
            }

            // ── ④ 滑块（中性浅色液态玻璃胶囊，作为“选中 tab”的背景高亮；
            //       zIndex 1f 位于 tab Row(2f) 之下，因此主题色图标显示在滑块之上）──
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Box(
                    modifier = Modifier
                        .size(width = sliderWDp, height = 48.dp)
                        .offset { IntOffset(sliderOffsetX.roundToInt(), sliderOffsetY.roundToInt()) }
                        .zIndex(1f)
                        .graphicsLayer {
                            scaleX = sliderScale
                            scaleY = sliderScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            LiquidGlassView(ctx).apply {
                                // bind 前兜底背景：液态层未就绪时显示玻璃底色，避免首帧白色辉光块
                                setBackgroundColor(
                                    if (isDark) android.graphics.Color.argb(190, 26, 26, 26)
                                    else android.graphics.Color.argb(190, 255, 255, 255)
                                )
                                setCornerRadius(sliderHPxHalf)
                                setRefractionHeight(sliderRefractHpx)
                                setRefractionOffset(sliderRefractOffPx)
                                setBlurRadius(LgSliderBlur)
                                setDispersion(LgSliderDispersion)
                                // 中性白透明玻璃：低染色、色散=0（消除绿黄彩边）；液态边缘由 refractionHeight/Offset 折射形变提供
                                setTintColorRed(1f)
                                setTintColorGreen(1f)
                                setTintColorBlue(1f)
                                setTintAlpha(if (isDark) 0.14f else 0.10f)
                                setDraggableEnabled(false)
                                setElasticEnabled(false)
                                setTouchEffectEnabled(false)
                                sliderGlassRef.set(this)
                            }
                        },
                        modifier = Modifier
                            .size(width = sliderWDp, height = 48.dp)
                            .shadow(
                            elevation = 8.dp,
                            shape = sliderShape,
                            ambientColor = Color.Black.copy(alpha = 0.12f),
                            spotColor = Color.Black.copy(alpha = 0.18f),
                            clip = false
                        )
                            .clip(sliderShape)
                    )
                    // 滑块饰面：白色描边（顶部高光渐变已移除，立体液晶感靠液态玻璃本身）
                    Box(
                        Modifier
                            .size(width = sliderWDp, height = 48.dp)
                            .clip(sliderShape)
                            .drawBehind {
                                drawRoundRect(
                                    color = if (isDark) Color(0xB3FFFFFF) else Color(0xE6FFFFFF),
                                    size = size,
                                    cornerRadius = CornerRadius(sliderShapePx, sliderShapePx),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(width = sliderWDp, height = 48.dp)
                        .offset { IntOffset(sliderOffsetX.roundToInt(), sliderOffsetY.roundToInt()) }
                        .zIndex(1f)
                        .graphicsLayer {
                            scaleX = sliderScale
                            scaleY = sliderScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        }
                ) {
                    Box(
                            Modifier
                                .size(width = sliderWDp, height = 48.dp)
                                .shadow(8.dp, sliderShape,
                                    ambientColor = Color.Black.copy(alpha = 0.12f),
                                    spotColor = Color.Black.copy(alpha = 0.18f))
                                .clip(sliderShape)
                                .background(accent.copy(alpha = if (isDark) 0.35f else 0.28f))
                                .drawBehind {
                                    drawRoundRect(
                                        color = if (isDark) Color(0x99FFFFFF) else Color(0xCCFFFFFF),
                                        size = size,
                                        cornerRadius = CornerRadius(sliderShapePx, sliderShapePx),
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                }
                        )
                }
            }
        }
    }
}
