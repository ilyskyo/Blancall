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

// ── 侧栏（大屏）悬浮坞几何 ──
// 侧栏不再是通栏长条，而是一块按内容高度收缩、贴内容区顶部的坞：
// 通栏形态下 4 项只占 256dp，下方会空出一大截玻璃，横屏下读起来像被拉伸的空壳。
private val RailSideMargin = 6.dp       // 坞左右留白：面板实宽 = NavRailWidth - 2×此值 = 64dp
private val RailDockTopMargin = 12.dp   // 坞与内容区顶边的距离
private val RailDockInsetV = 8.dp       // 坞内上下留白
private val RailItemHeight = 64.dp      // 单项格高（手势换算与指示器同源）
private val RailIndicatorWidth = 48.dp  // 选中胶囊只包住图标，不包标签（M3 侧栏构图）
private val RailIconSlot = 32.dp        // 图标槽高 = 胶囊高
private val RailLabelGap = 4.dp
private val RailLabelLine = 16.dp       // 与 labelSmall 的 lineHeight 对齐
private val RailIndicatorInsetH = (NavRailWidth - RailSideMargin * 2 - RailIndicatorWidth) / 2
private val RailIconTopInset =
    (RailItemHeight - RailIconSlot - RailLabelGap - RailLabelLine) / 2
private val RailCorner = 26.dp
private val RailIndicatorCorner = 16.dp

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
 * 大屏侧边导航栏（平板竖屏 / 折叠屏展开 / 手机横屏）。
 *
 * 形态：**按内容高度收缩的悬浮坞**，贴内容区顶部（与搜索栏同一条起始线）。
 * 之前是通栏长条：4 项只占 256dp，下方空出一百多 dp 的玻璃，横屏下像被拉伸的空壳。
 *
 * 层级（从后到前）：兜底底色 → 液态玻璃 → 1dp 描边 → 选中胶囊 → 图标/文字 → 手势层。
 * 选中胶囊是**圆角矩形**且四周内缩 4dp：之前是 56×58dp 的正圆贴着面板描边，
 * 又与面板自身的圆角互相咬合，读起来像一颗从框里鼓出来的气泡。
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
    val tabCount = tabs.size.coerceAtLeast(1)
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

    val railCornerPx = with(density) { RailCorner.toPx() }
    val railShape = RoundedCornerShape(RailCorner)
    val indicatorShape = RoundedCornerShape(RailIndicatorCorner)
    val railBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, railBackdrop)

    val railTint = if (isDark) Color.Black.copy(alpha = 0.42f)
    else MaterialTheme.colorScheme.background.copy(alpha = 0.42f)
    val fallbackTint = if (isDark) Color.Black.copy(alpha = 0.92f)
    else Color.White.copy(alpha = 0.86f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.08f)
    // 选中胶囊只有 48×32，中性 10% 灰在浅色面板上几乎看不见；
    // 这里用主色淡填充，让「选中」由底色和图标色一起承担。
    val indicatorTint = accent.copy(alpha = if (isDark) 0.22f else 0.14f)

    // 选中位置动画（单位 = 导航项索引）：玻璃胶囊 offset 与「上下拖动换页」手势共用同一实例。
    val railSelAnim = remember(tabCount) {
        Animatable(currentTab.coerceIn(0, tabCount - 1).toFloat())
    }
    val railScope = rememberCoroutineScope()
    // 回调兜 Fresh：pointerInput 块内直接捕获的实例会冻结在协程启动时。
    val onSelectLatest by rememberUpdatedState(onSelect)
    LaunchedEffect(currentTab, tabCount) {
        if (currentTab >= 0) {
            railSelAnim.animateTo(
                currentTab.coerceIn(0, tabCount - 1).toFloat(),
                Motion.sliderIndex(),
            )
        }
    }

    val dockHeight = RailItemHeight * tabCount + RailDockInsetV * 2

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(NavRailWidth)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = RailSideMargin, vertical = RailDockTopMargin),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dockHeight)
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

            // ── ④ 选中项胶囊（常驻层，offset 跟随选中项；只包图标）──
            Box(
                modifier = Modifier
                    .offset {
                        val top = RailDockInsetV.toPx() +
                            RailItemHeight.toPx() * railSelAnim.value +
                            RailIconTopInset.toPx()
                        IntOffset(0, top.roundToInt())
                    }
                    .fillMaxWidth()
                    .padding(horizontal = RailIndicatorInsetH)
                    .height(RailIconSlot)
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
                                    // 侧栏胶囊同样不开色散：同一套观感，不在这里留一条彩边
                                    lens(10.dp.toPx(), 14.dp.toPx())
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
                            .background(indicatorTint)
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(indicatorShape)
                        .drawBehind {
                            drawRoundRect(
                                color = if (isDark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.65f),
                                size = size,
                                cornerRadius = CornerRadius(
                                    RailIndicatorCorner.toPx(),
                                    RailIndicatorCorner.toPx(),
                                ),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                )
            }

            // ── ⑤ 导航项（竖向排布，选中项带胶囊指示器）──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = RailDockInsetV)
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
                            .height(RailItemHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(RailIndicatorCorner + 4.dp))
                                // Plain 档：只压暗、不缩放 —— 缩放会与不动的胶囊错位
                                .pressFeedback(src, PressTier.Plain)
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
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Column(
                                modifier = Modifier.padding(top = RailIconTopInset),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(RailIndicatorWidth)
                                        .height(RailIconSlot),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AppIcon(kind = kind, tint = tint, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(RailLabelGap))
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
            }

            // ── ⑥ 手势层（保持原侧栏行为：整栏点击 + 上下拖动换页）──
            // 与底栏一样，pointerInput 里禁止直接捕获 currentTab；提交走幂等 onSelectLatest。
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .padding(vertical = RailDockInsetV)
                    .pointerInput(tabCount) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            down.consume()
                            val unitPx = RailItemHeight.toPx()
                            val base = railSelAnim.value
                            var tracking = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val startTab = (down.position.y / unitPx)
                                        .toInt().coerceIn(0, tabCount - 1)
                                    val endTab = (change.position.y / unitPx)
                                        .toInt().coerceIn(0, tabCount - 1)
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
                                        .coerceIn(0f, (tabCount - 1).toFloat())
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

                // ── 选中指示器玻璃（按压时允许越出栏边）──
                //
                // 铁律只有一条：**缩放必须放在这一层 graphicsLayer 上，不能只写进 `layerBlock`**。
                // `layerBlock` 只作用于 drawBackdrop 画出来的玻璃，外面那圈 1.5dp 描边不跟着胀
                // ⇒ 按压时玻璃 78dp、描边还停在 56dp，屏幕上同时出现大、小两个框 ——
                // 用户 2026-10-26 报的「多出来的那条框线」就是它。整层一起缩放，永远只有一个轮廓。
                //
                // 不要给这一层加 `clip(barShape)`：2026-10-26 试过，按住时胶囊上下被栏高框死，
                // 用户当场否掉（「上一个版本不是这样的」）。按压越出栏边本来就是要有。
                Box(
                    Modifier
                        .width(with(density) { tabW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = physics.position * tabW
                            scaleX = physics.visualScaleX
                            scaleY = physics.visualScaleY
                        }
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
                                            // 折射保留，**色散关掉**（2026-10-27 用户报「我的文章页玻璃块有奇怪的色散」）：
                                            // chromaticAberration 会把胶囊边缘那条亮度交界拆成 RGB 三色，
                                            // 在白色底上表现为一条黄绿蓝的彩虹带；底色越干净越刺眼。
                                            // 折射本身已经够表达玻璃厚度，不需要这条彩边。
                                            val p = physics.pressProgress
                                            lens(
                                                indicatorLensXPx * p,
                                                indicatorLensYPx * p,
                                            )
                                        },
                                        highlight = {
                                            // 高光只留一层薄sheen：`Highlight.Default` 的 alpha 拉到 1 时，
                                            // 整个胶囊面会被涂成白色（实测按压时内部 228 → 254，等于把底色吃掉了），
                                            // 于是「玻璃块」变成一个白洞 —— 用户 2026-10-27 报的正是它。
                                            Highlight.Default.copy(alpha = 0.30f * physics.pressProgress)
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
                                        onDrawSurface = {
                                            val p = physics.pressProgress
                                            // 底色**常驻**，不随按压退干净（参考实现那行是 alpha = 1f - p）。
                                            // 退干净以后胶囊内部等于裸页面，而栏体本身带 42% 底色，
                                            // 于是在白底上胶囊反而比栏更白 —— 读作一个「白洞」，只剩投影描出一圈软边，
                                            // 再加上折射把边的亮度交界拆成彩边（用户 2026-10-27 报的「玻璃块色散/不对」）。
                                            drawRect(indicatorBase)
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
                        // 描边与玻璃同层缩放：它不是「框」，是玻璃的边沿。
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
                                // **按下这一帧就必须吃掉**（2026-10-27 修）：
                                // 之前只在拖动/抬手时 consume，于是长按（不移动、不抬手）整段时间里
                                // 事件一路穿到底下的内容上——首页卡片有长按进编辑态，实测长按底栏会
                                // 直接把页面推进编辑态。`detectTapGestures` 的长按在 500ms 时就触发，
                                // 等到抬手再 consume 已经晚了。
                                // 代价：从底栏起手的竖向滑动不再滚动页面（底栏是浮层，本来也不该拖列表）。
                                down.consume()
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
