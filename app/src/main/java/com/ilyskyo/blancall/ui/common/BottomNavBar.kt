// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.animation.ValueAnimator
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ── 底栏几何（唯一真源）──
private val BarHeight = 64.dp
private val BarHorizontalMargin = 14.dp
private val BarBottomMargin = 14.dp
private val IndicatorInset = 4.dp
private val BarGlassBlur = 8.dp
private val BarGlassLens = 24.dp
private val IndicatorLensX = 10.dp
private val IndicatorLensY = 14.dp
private val PanelMaxOffset = 4.dp

/**
 * 导航项定义（底栏与侧栏共用）：
 * [AppIconKind] 是应用自有图标集，避免引入 material-icons-extended 的额外体积。
 */
// 标签存 @StringRes 而不是写死的中文：底栏是每屏都在的界面，也是国际化最先要落地的一处。
private val BaseNavTabs: List<Pair<Int, AppIconKind>> = listOf(
    com.ilyskyo.blancall.R.string.nav_home to AppIconKind.Home,
    com.ilyskyo.blancall.R.string.nav_articles to AppIconKind.Articles,
    com.ilyskyo.blancall.R.string.nav_stats to AppIconKind.Insights,
)

private fun navTabsFor(showLibraryTab: Boolean): List<Pair<Int, AppIconKind>> =
    if (showLibraryTab) BaseNavTabs + (com.ilyskyo.blancall.R.string.nav_library to AppIconKind.Library)
    else BaseNavTabs

/**
 * 大屏侧边导航栏（Tablet / 折叠屏展开 / 横屏，Material 3 NavigationRail 规范）。
 *
 * 本次只把它的玻璃源迁到 Compose `drawBackdrop`（与底栏共用 [Backdrop]），
 * 布局、选中动画与手势保持原样；垂直方向的拖动物理不在本次底栏改造范围内。
 */
@Composable
fun NavRail(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showLibraryTab: Boolean = false,
    backdrop: Backdrop = emptyBackdrop(),
) {
    val tabs = navTabsFor(showLibraryTab)
    val isDark = isBlancallDark()
    val accent = MaterialTheme.colorScheme.primary
    val subTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val navGlass by AppPrefs.navLiquidGlassFlow.collectAsState()
    val glassActive = navGlass && navGlassSupported()
    val railGlassAlpha by animateFloatAsState(
        if (glassActive) 1f else 0f,
        MotionFade.alpha(MotionFade.enter),
        label = "railGlassAlpha",
    )

    val railWidthDp = NavRailWidth
    val railCornerDp = 28.dp
    val railCornerPx = with(density) { railCornerDp.toPx() }
    val railShape = RoundedCornerShape(railCornerDp)
    val indicatorShape = RoundedCornerShape(29.dp)
    val railBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, railBackdrop)

    val railTint = if (isDark) Color.Black.copy(alpha = 0.42f)
    else MaterialTheme.colorScheme.background.copy(alpha = 0.42f)
    val fallbackTint = if (isDark) Color.Black.copy(alpha = 0.92f)
    else Color.White.copy(alpha = 0.86f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.08f)
    val indicatorTint = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)

    // 选中位置动画（单位 = 导航项索引）：玻璃胶囊 offset 与「滑动切换」手势共用同一实例。
    val railSelAnim = remember(tabs.size) {
        Animatable(currentTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)).toFloat())
    }
    val railScope = rememberCoroutineScope()
    // 回调兜 Fresh：pointerInput 块内直接捕获的实例会冻结在协程启动时。
    val onSelectLatest by rememberUpdatedState(onSelect)
    LaunchedEffect(currentTab, tabs.size) {
        if (currentTab >= 0) {
            railSelAnim.animateTo(
                currentTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)).toFloat(),
                Motion.sliderIndex(),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidthDp)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(vertical = 14.dp, horizontal = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // ── ① 兜底底色：玻璃未就绪 / 关闭玻璃时可见，也保证阴影有实体可投 ──
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(
                        14.dp,
                        railShape,
                        ambientColor = Color.Black.copy(alpha = 0.16f),
                        spotColor = Color.Black.copy(alpha = 0.20f),
                    )
                    .clip(railShape)
                    .background(
                        if (glassActive) fallbackTint.copy(alpha = 0.20f) else fallbackTint
                    )
            )

            // ── ② 真液态玻璃（API 31+）：vibrancy + blur + 侧栏专用的轻折射 ──
            if (glassActive) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = railGlassAlpha }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { railShape },
                            effects = {
                                navVibrancy()
                                blur(BarGlassBlur.toPx())
                                lens(16.dp.toPx(), 16.dp.toPx())
                            },
                            highlight = { Highlight.Default },
                            shadow = null,
                            onDrawSurface = { drawRect(railTint) },
                            exportedBackdrop = railBackdrop,
                        )
                )
            }

            // ── ③ 1dp 描边饰面 ──
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(0.1f)
                    .clip(railShape)
                    .drawBehind {
                        drawRoundRect(
                            color = borderColor,
                            size = size,
                            cornerRadius = CornerRadius(railCornerPx, railCornerPx),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
            )

            // ── ④ 选中项玻璃胶囊（常驻层，offset 跟随选中项）──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .offset {
                        IntOffset(0, (64.dp.toPx() * railSelAnim.value + 3.dp.toPx()).roundToInt())
                    }
                    .zIndex(1f)
            ) {
                if (glassActive) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBackdrop(
                                backdrop = indicatorBackdrop,
                                shape = { indicatorShape },
                                effects = {
                                    lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = true)
                                },
                                highlight = { Highlight.Default },
                                shadow = {
                                    Shadow.Default.copy(
                                        radius = 12.dp,
                                        offset = DpOffset(0.dp, 4.dp),
                                        alpha = 0.25f,
                                    )
                                },
                                onDrawSurface = { drawRect(indicatorTint) },
                            )
                    )
                } else {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(indicatorShape)
                            .background(accent.copy(alpha = if (isDark) 0.35f else 0.24f))
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(indicatorShape)
                        .drawBehind {
                            drawRoundRect(
                                color = if (isDark) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.80f),
                                size = size,
                                cornerRadius = CornerRadius(29.dp.toPx(), 29.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                )
            }

            // ── ⑤ 导航项（竖向排布，选中项带胶囊指示器）──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
            ) {
                tabs.forEachIndexed { index, (labelId, kind) ->
                    val label = stringResource(labelId)
                    val selected = index == currentTab
                    val tint = if (selected) accent else subTint
                    val src = remember { MutableInteractionSource() }
                    val tabHaptic = rememberHaptic(HapticTier.Toggle)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(50))
                            .pressFeedback(src, PressTier.Container)
                            .clickable(
                                interactionSource = src,
                                indication = null,
                                role = Role.Tab,
                            ) {
                                tabHaptic()
                                onSelect(index)
                            }
                            .semantics {
                                this[SemanticsProperties.Selected] = selected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            AppIcon(kind = kind, tint = tint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.size(3.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ── ⑥ 手势层（保持原侧栏行为：整栏点击 + 上下拖动换页）──
            // 与底栏一样，pointerInput 里禁止直接捕获 currentTab；提交走幂等 onSelectLatest。
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .pointerInput(tabs.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            down.consume()
                            val unitPx = 64.dp.toPx()
                            val base = railSelAnim.value
                            var tracking = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val startTab = (down.position.y / unitPx)
                                        .toInt().coerceIn(0, tabs.size - 1)
                                    val endTab = (change.position.y / unitPx)
                                        .toInt().coerceIn(0, tabs.size - 1)
                                    val targetTab = if (startTab == endTab) startTab else endTab
                                    railScope.launch {
                                        railSelAnim.animateTo(targetTab.toFloat(), Motion.sliderIndex())
                                    }
                                    onSelectLatest(targetTab)
                                    change.consume()
                                    break
                                }
                                val dy = change.position.y - down.position.y
                                if (!tracking && abs(dy) > viewConfiguration.touchSlop) {
                                    tracking = true
                                }
                                if (tracking) {
                                    change.consume()
                                    val t = (base + dy / unitPx)
                                        .coerceIn(0f, (tabs.size - 1).toFloat())
                                    railScope.launch { railSelAnim.snapTo(t) }
                                }
                            }
                        }
                    }
            )
        }
    }
}

/**
 * 液态玻璃底部导航栏（酷安 16.6.5 / Kyant0 Backdrop 物理版）。
 *
 * 结构（从上到下 / 从后到前）：
 * - 页面内容由 AppNavigation 的 `Modifier.layerBackdrop(pageBackdrop)` 录进 GraphicsLayer；
 * - 底栏玻璃 `drawBackdrop(pageBackdrop)` 只重录栏区，折射真实页面；
 * - 选中指示器 `drawBackdrop(combined(pageBackdrop, barBackdrop))` 折射「页面 + 底栏玻璃」；
 * - tab 文字/图标永远在最上层，**不参与**玻璃缩放，避免被拉糊；
 * - 整栏最多 4dp 横向位移、指示器双弹簧、速度拉伸全部由 [NavLiquidPhysics] 提供，
 *   值只在 graphicsLayer / draw / effects lambda 中读取，拖动期间不重组。
 */
@Composable
fun BottomNavBar(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    showLibraryTab: Boolean = false,
    backdrop: Backdrop = emptyBackdrop(),
) {
    val tabs = navTabsFor(showLibraryTab)
    val tabCount = tabs.size.coerceAtLeast(1)
    val safeTab = currentTab.coerceIn(0, tabCount - 1)
    val isDark = isBlancallDark()
    val accent = MaterialTheme.colorScheme.primary
    val subTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val navGlass by AppPrefs.navLiquidGlassFlow.collectAsState()
    val glassActive = navGlass && navGlassSupported()
    val reducedMotion = rememberReducedMotion()
    val powerSave = rememberPowerSave()
    val physics = remember(tabCount, reducedMotion) {
        NavLiquidPhysics(safeTab, tabCount, reducedMotion)
    }
    val onSelectLatest by rememberUpdatedState(onSelect)
    val currentTabLatest by rememberUpdatedState(safeTab)
    val tabHaptic = rememberHaptic(HapticTier.Toggle)

    val barShape = RoundedCornerShape(50)
    val indicatorShape = RoundedCornerShape(50)
    val barBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, barBackdrop)

    val barTint = if (isDark) Color.Black.copy(alpha = 0.42f)
    else MaterialTheme.colorScheme.background.copy(alpha = 0.42f)
    val fallbackTint = if (isDark) Color.Black.copy(alpha = 0.92f)
    else Color.White.copy(alpha = 0.86f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.08f)
    val indicatorBorder = if (isDark) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.80f)
    val indicatorBase = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)

    // 手指高光位置（栏内容区坐标）；只在 draw 里读取。
    var highlightCenter by remember { mutableStateOf<Offset?>(null) }

    NavLiquidFrameLoop(physics)
    LaunchedEffect(currentTab, tabCount) {
        if (currentTab >= 0) physics.animateToTab(currentTab)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BarHorizontalMargin)
                .padding(bottom = BarBottomMargin)
                .height(BarHeight)
        ) {
            val barW = constraints.maxWidth.toFloat()
            val tabW = barW / tabCount
            val blurPx = with(density) { (if (powerSave) 4.dp else BarGlassBlur).toPx() }
            val lensPx = with(density) { (if (powerSave) 16.dp else BarGlassLens).toPx() }
            val indicatorLensXPx = with(density) { IndicatorLensX.toPx() }
            val indicatorLensYPx = with(density) { IndicatorLensY.toPx() }

            SideEffect {
                physics.updateMetrics(
                    barWidthPx = barW,
                    tabWidthPx = tabW,
                    maxPanelOffsetPx = with(density) { PanelMaxOffset.toPx() },
                )
            }

            // 整栏最多 4dp 的横向位移：读值在 graphicsLayer 的 lambda 里，不触发重组
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = physics.panelOffsetPx }
            ) {
                // ── 栏体（阴影 + 裁切）：指示器在它外面，允许按压缩放越出栏边 ──
                Box(
                    Modifier
                        .matchParentSize()
                        .shadow(
                            14.dp,
                            barShape,
                            ambientColor = Color.Black.copy(alpha = 0.20f),
                            spotColor = Color.Black.copy(alpha = 0.26f),
                        )
                        .clip(barShape)
                ) {
                    // 兜底底色：玻璃未就绪/关闭时可见，也让阴影有实体
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                if (glassActive) fallbackTint.copy(alpha = 0.18f) else fallbackTint
                            )
                    )

                    if (glassActive) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { barShape },
                                    effects = {
                                        navVibrancy()
                                        blur(blurPx)
                                        lens(lensPx, lensPx)
                                    },
                                    // 栏体不叠随手指变化的高光；指示器才有按压高光
                                    highlight = null,
                                    shadow = null,
                                    onDrawSurface = { drawRect(barTint) },
                                    exportedBackdrop = barBackdrop,
                                )
                        )
                    }

                    // 1dp 描边
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(barShape)
                            .drawBehind {
                                drawRoundRect(
                                    color = borderColor,
                                    size = size,
                                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                    )
                }

                // ── 选中指示器玻璃（可越出栏边）──
                Box(
                    Modifier
                        .width(with(density) { tabW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer { translationX = physics.position * tabW }
                        .zIndex(1f)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = IndicatorInset, vertical = 4.dp)
                    ) {
                        if (glassActive) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .drawBackdrop(
                                        backdrop = indicatorBackdrop,
                                        shape = { indicatorShape },
                                        effects = {
                                            val p = physics.pressProgress
                                            lens(
                                                indicatorLensXPx * p,
                                                indicatorLensYPx * p,
                                                chromaticAberration = true,
                                            )
                                        },
                                        highlight = {
                                            Highlight.Default.copy(alpha = physics.pressProgress)
                                        },
                                        shadow = {
                                            // 静止时也留一点投影，白底上仍能看见胶囊；
                                            // 半径压到 12dp，避免按压缩放时出现一团灰雾
                                            Shadow.Default.copy(
                                                radius = 12.dp,
                                                offset = DpOffset(0.dp, 4.dp),
                                                alpha = 0.18f + 0.50f * physics.pressProgress,
                                            )
                                        },
                                        innerShadow = {
                                            InnerShadow(
                                                radius = 8.dp * physics.pressProgress,
                                                alpha = physics.pressProgress,
                                            )
                                        },
                                        layerBlock = {
                                            scaleX = physics.visualScaleX
                                            scaleY = physics.visualScaleY
                                        },
                                        onDrawSurface = {
                                            val p = physics.pressProgress
                                            drawRect(indicatorBase, alpha = 1f - p)
                                            drawRect(Color.Black.copy(alpha = 0.03f * p))
                                        },
                                    )
                            )
                        } else {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(indicatorShape)
                                    .background(accent.copy(alpha = if (isDark) 0.35f else 0.24f))
                            )
                        }
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(indicatorShape)
                                .drawBehind {
                                    drawRoundRect(
                                        color = indicatorBorder,
                                        size = size,
                                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                        style = Stroke(width = 1.5.dp.toPx()),
                                    )
                                }
                        )
                    }
                }

                // ── 跟随手指的径向高光（叠加在最上层玻璃之上、图标之下）──
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(barShape)
                        .zIndex(1.5f)
                        .drawWithContent {
                            val p = physics.pressProgress
                            val center = highlightCenter
                            if (p > 0.01f && center != null) {
                                drawRect(
                                    Color.White.copy(alpha = 0.06f * p),
                                    blendMode = BlendMode.Plus,
                                )
                                val radius = size.height * 1.6f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.16f * p),
                                            Color.Transparent,
                                        ),
                                        center = center,
                                        radius = radius,
                                    ),
                                    radius = radius,
                                    center = center,
                                    blendMode = BlendMode.Plus,
                                )
                            }
                        }
                )

                // ── tab 内容：图标 / 文字永远在玻璃之上，不参与缩放 ──
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, (labelId, kind) ->
                        val label = stringResource(labelId)
                        val selected = index == currentTab
                        val tint = if (selected) accent else subTint
                        val src = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .width(with(density) { tabW.toDp() })
                                .fillMaxHeight()
                                // 触摸由整栏手势层统一处理；clickable 只负责无障碍动作与名称
                                .clickable(
                                    interactionSource = src,
                                    indication = null,
                                    role = Role.Tab,
                                ) {
                                    tabHaptic()
                                    onSelect(index)
                                }
                                .semantics(mergeDescendants = true) {
                                    this[SemanticsProperties.Selected] = selected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            ) {
                                AppIcon(kind = kind, tint = tint, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.size(3.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // ── 统一手势层：点击切 tab + 水平拖动切格；垂直方向只取消、不提交 ──
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .pointerInput(tabCount, tabW) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                val startX = down.position.x
                                val startY = down.position.y
                                val startTab = (startX / tabW).toInt().coerceIn(0, tabCount - 1)
                                physics.press()
                                highlightCenter = Offset(startX, startY)

                                var dragging = false
                                var vertical = false
                                var committed = false
                                try {
                                    var lastX = startX
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        val dxTotal = change.position.x - startX
                                        val dyTotal = change.position.y - startY

                                        if (!dragging && !vertical) {
                                            if (abs(dyTotal) > viewConfiguration.touchSlop &&
                                                abs(dyTotal) > abs(dxTotal)
                                            ) {
                                                // 垂直滚动：立刻放行按键态，等待抬手，不提交切页
                                                vertical = true
                                                committed = true
                                                physics.cancel(currentTabLatest)
                                                highlightCenter = null
                                            } else if (abs(dxTotal) > viewConfiguration.touchSlop) {
                                                dragging = true
                                                lastX = change.position.x
                                            }
                                        }

                                        if (vertical) {
                                            if (!change.pressed) break
                                            continue
                                        }

                                        if (dragging) {
                                            change.consume()
                                            val dx = change.position.x - lastX
                                            lastX = change.position.x
                                            physics.drag(dx, change.uptimeMillis)
                                            highlightCenter = Offset(change.position.x, change.position.y)
                                        }

                                        if (!change.pressed) {
                                            val target = physics.release()
                                            committed = true
                                            if (dragging) {
                                                if (target != currentTabLatest) {
                                                    tabHaptic()
                                                    onSelectLatest(target)
                                                }
                                            } else {
                                                // 轻点：按松手前所在的 tab 提交（手势层盖住了 tab 的点击）
                                                tabHaptic()
                                                if (startTab != currentTabLatest) {
                                                    onSelectLatest(startTab)
                                                }
                                            }
                                            change.consume()
                                            break
                                        }
                                    }
                                } finally {
                                    if (!committed) {
                                        physics.cancel(currentTabLatest)
                                    }
                                    highlightCenter = null
                                }
                            }
                        }
                )
            }
        }
    }
}

/**
 * `NavLiquidPhysics` 的帧驱动：只在有弹簧运动时跑 `withFrameNanos`，
 * 静止后自动退出，不常驻空转。
 */
@Composable
private fun NavLiquidFrameLoop(physics: NavLiquidPhysics) {
    LaunchedEffect(physics) {
        while (true) {
            physics.wakeSignal.receive()
            var lastNs = 0L
            while (physics.isActive) {
                withFrameNanos { now ->
                    if (lastNs != 0L) {
                        physics.step(
                            dtSeconds = (now - lastNs) / 1_000_000_000f,
                            nowMs = now / 1_000_000L,
                        )
                    }
                    lastNs = now
                }
            }
        }
    }
}

@Composable
private fun rememberReducedMotion(): Boolean = remember {
    !ValueAnimator.areAnimatorsEnabled()
}

@Composable
private fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }
}
