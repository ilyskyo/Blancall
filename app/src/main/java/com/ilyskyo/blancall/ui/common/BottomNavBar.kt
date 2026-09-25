// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.ilyskyo.blancall.ui.theme.AppPrefs
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
 * 液态玻璃底部导航栏（Tab 栏风格，真机真液态）：
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

/**
 * 导航项定义（底栏与侧栏共用）：
 * [AppIconKind] 是应用自有图标集，避免引入 material-icons-extended 的额外体积。
 */
private val BaseNavTabs: List<Pair<String, AppIconKind>> = listOf(
    "首页" to AppIconKind.Home,
    "我的文章" to AppIconKind.Articles,
    "数据" to AppIconKind.Insights,
)

private fun navTabsFor(showLibraryTab: Boolean): List<Pair<String, AppIconKind>> =
    if (showLibraryTab) BaseNavTabs + ("素材库" to AppIconKind.Library) else BaseNavTabs

/**
 * 大屏侧边导航栏（Tablet / 折叠屏展开 / 横屏，Material 3 NavigationRail 规范）。
 *
 * **为什么横屏要用侧栏**：底部导航栏在横屏下有三个硬伤 ——
 * ① 高度本就紧张（横屏可用高度常低于 500dp），一条 64dp 的底栏再切掉一块；
 * ② 手指在横屏握持时离底部更远，横向滑动比纵向点击更贴近拇指活动区；
 * ③ 平板横屏宽高比接近 3:2，竖向空间是最稀缺资源，横向空间反而富余。
 * Material 3 规范因此规定：宽度 ≥ 600dp 用 NavigationRail，≥ 840dp 可上持久抽屉。
 *
 * 视觉上沿用同一套液态玻璃：竖向 76dp 宽玻璃条 + 半透明胶囊指示器，
 * 与底栏保持一致的材质语言（不引入第二套设计）。
 *
 * @param host 液态玻璃折射采样源（页面容器），与 [BottomNavBar] 同款。
 */
@Composable
fun NavRail(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    showLibraryTab: Boolean = false,
    host: FrameLayout? = null,
    modifier: Modifier = Modifier
) {
    val tabs = navTabsFor(showLibraryTab)
    val isDark = isBlancallDark()
    val accent = MaterialTheme.colorScheme.primary
    val subTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val navGlass by AppPrefs.navLiquidGlassFlow.collectAsState()
    val railGlassAlpha by animateFloatAsState(if (navGlass) 1f else 0f, label = "railGlassAlpha")

    val railWidthDp = NavRailWidth
    val railCornerDp = 28.dp
    val railCornerPx = with(density) { railCornerDp.toPx() }
    val railShape = RoundedCornerShape(railCornerDp)

    val railGlassRef = remember { AtomicReference<LiquidGlassView?>(null) }
    // 选中项指示器的玻璃胶囊（与底栏滑块同款）：侧栏「像波态玻璃」的一半质感来自它。
    // ⚠️ 必须**常驻不随选中态移出组合树**（LiquidGlassView 在 detach 态会崩），
    // 因此它不写在 tab 的 Box 里，而是独立一层、用 offset 跟随选中项。
    val railSelGlassRef = remember { AtomicReference<LiquidGlassView?>(null) }

    // 切 tab 时的玻璃过渡：页面重绘首帧的采样可能拿到「空帧」——玻璃显示兜底白、
    // 下一帧才恢复取色（真机反馈：「点滑块取色突然变白、跳一下再取色」；数据页因
    // 内容入场较缓本就没有白闪）。用一次 70ms 淡出 + 200ms 淡回把硬跳变成柔和过渡，
    // 对数据页是无损叠加（不会改坏它现在的表现）。
    val switchFade = remember { Animatable(1f) }
    var lastTabForFade by remember { mutableStateOf(currentTab) }
    LaunchedEffect(currentTab) {
        if (currentTab != lastTabForFade) {
            lastTabForFade = currentTab
            switchFade.animateTo(0.6f, tween(70))
            switchFade.animateTo(1f, tween(200))
        }
    }

    // 选中位置动画（单位 = 导航项索引）：玻璃胶囊 offset 与「滑动切换」手势共用同一实例。
    // 与底栏滑块同款交互：按住上下拖动 ⇒ 胶囊跟手；松手吸附到最近项并切换页面。
    val railSelAnim = remember(tabs.size) { Animatable(currentTab.toFloat()) }
    val railScope = androidx.compose.runtime.rememberCoroutineScope()
    // 回调兜 Fresh：pointerInput 块内直接捕获的实例会冻结在协程启动时（与 HomeCardCanvas 同款
    // 陷阱），经 rememberUpdatedState 保证手势始终调用最新实现。
    val onSelectLatest by rememberUpdatedState(onSelect)
    LaunchedEffect(currentTab) {
        railSelAnim.animateTo(
            currentTab.coerceIn(0, (tabs.size - 1)).toFloat(),
            spring(dampingRatio = 0.6f, stiffness = 380f)
        )
    }

    // 同为「玻璃 View 创建竞速」：等就绪再 bind，与底栏一致
    LaunchedEffect(host) {
        val target = host ?: return@LaunchedEffect
        repeat(400) {
            val rail = railGlassRef.get()
            val sel = railSelGlassRef.get()
            if (rail != null && sel != null) {
                rail.bind(target)
                sel.bind(target)
                return@LaunchedEffect
            }
            delay(16)
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
            // ① 玻璃底
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AndroidView(
                    factory = { ctx ->
                        LiquidGlassView(ctx).apply {
                            setBackgroundColor(
                                // 兜底白降到 150：让下方页面底色透出来（「向下取色」观感）；
                                // 205 时在纯色页上几乎呈实心白，用户反馈「没有向下取色」
                                if (isDark) android.graphics.Color.argb(170, 26, 26, 26)
                                else android.graphics.Color.argb(150, 255, 255, 255)
                            )
                            setCornerRadius(railCornerPx)
                            // 折射形变加强（20/70 → 32/85）：侧栏背景层次弱（氛围底），
                            // 更强的边缘形变才能看出「液态」（真机反馈「不液态」）。
                            setRefractionHeight(with(density) { 32.dp.toPx() })
                            setRefractionOffset(with(density) { 85.dp.toPx() })
                            setBlurRadius(LgBarBlur)
                            // 侧栏关闭色散（真机反馈「不要什么色散」）：去掉彩边，只留折射形变
                            setDispersion(0f)
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
                            railGlassRef.set(this)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .graphicsLayer { alpha = railGlassAlpha * switchFade.value }
                        // 阴影已移除：切换 tab 时阴影渲染节点重建会「闪一下」（真机反馈）
                        .clip(railShape)
                )
                if (!navGlass) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(0.05f)
                            .clip(railShape)
                            .background(if (isDark) Color(0xE61A1A1A) else Color(0xC8FFFFFF))
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(railShape)
                        .background(if (isDark) Color(0xE61A1A1A) else Color(0xC8FFFFFF))
                )
            }

            // ② 1dp 描边饰面（与底栏同款）
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(0.1f)
                    .clip(railShape)
                    .drawBehind {
                        drawRoundRect(
                            color = if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF),
                            size = size,
                            cornerRadius = CornerRadius(railCornerPx, railCornerPx),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
            )

            // ②.5 选中项波态玻璃胶囊（常驻层，offset 跟随选中项；底栏滑块同款参数）。
            // 导航项步进 64dp；胶囊高 58dp / 上缩 3dp 与项内 padding 对齐。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 结构对齐底栏滑块：阴影 + 玻璃 + 白描边。
                // ⚠️ 描边与阴影不可省：纯白背景页（如统计页）上，白玻璃本体与背景同色
                // 会「隐身」（真机反馈「滑块没了」）；底栏滑块正是靠这两层在任何页面可见。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        // 位置由 railSelAnim 驱动（切换吸附与拖动跟手共用同一动画实例）；
                        // 读值放在 offset lambda（布局阶段）：滑动动画不触发侧栏每帧重组
                        .offset {
                            IntOffset(0, (64.dp.toPx() * railSelAnim.value + 3.dp.toPx()).roundToInt())
                        }
                        .zIndex(1f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            LiquidGlassView(ctx).apply {
                                setBackgroundColor(
                                    if (isDark) android.graphics.Color.argb(150, 26, 26, 26)
                                    else android.graphics.Color.argb(150, 255, 255, 255)
                                )
                                setCornerRadius(with(density) { 29.dp.toPx() })
                                setRefractionHeight(with(density) { 20.dp.toPx() })
                                setRefractionOffset(with(density) { 70.dp.toPx() })
                                setBlurRadius(LgSliderBlur)
                                setDispersion(LgSliderDispersion)
                                setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                                setTintAlpha(if (isDark) 0.14f else 0.10f)
                                setDraggableEnabled(false)
                                setElasticEnabled(false)
                                setTouchEffectEnabled(false)
                                railSelGlassRef.set(this)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = railGlassAlpha * switchFade.value }
                            // 阴影已移除：换页时胶囊阴影闪烁（真机反馈）
                            .clip(RoundedCornerShape(50))
                    )
                    // 白色描边（与底栏滑块同款）：白玻璃在白底页上的「可见性底线」
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(50))
                            .drawBehind {
                                drawRoundRect(
                                    color = if (isDark) Color(0xB3FFFFFF) else Color(0xE6FFFFFF),
                                    size = size,
                                    cornerRadius = CornerRadius(29.dp.toPx(), 29.dp.toPx()),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                    )
                }
            }

            // ③ 导航项（竖向排布，选中项带胶囊指示器）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top
            ) {
                tabs.forEachIndexed { index, (label, kind) ->
                    val selected = index == currentTab
                    val tint = if (selected) accent else subTint
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(50))
                            // 选中指示由下方的波态玻璃胶囊（②.5 层）承担（与底栏滑块同构），
                            // 项内不再叠加纯色块——否则色块会盖住玻璃、退化成普通高亮。
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSelect(index) },
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
                                color = tint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ④ 手势层（与底栏滑块同构；真机补遗：侧栏此前只能点击、没有滑动换页）：
            //   Initial pass 消费事件 —— 轻点 ⇒ 切换到该项；按住上下滑动 ⇒ 胶囊跟手，
            //   松手按抬手位置整格吸附并切换（判定与底栏同款）。zIndex 3 置顶，与底栏
            //   一致地阻断事件泄漏。⚠️ 块内禁止直接捕获 currentTab 做判定——pointerInput
            //   key 不变时闭包冻结为首次组合旧值；提交一律无条件走幂等 navigateToTab。
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .pointerInput(tabs.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            down.consume()
                            val unitPx = 64.dp.toPx()
                            val base = railSelAnim.value
                            var tracking = false
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // 与底栏（BottomNavBar）同款抬手判定：目标按「按下位置/抬起位置」
                                    // 整格(floor)决定，无条件提交——navigateToTab 幂等（同页安全），
                                    // 由 AppNavigation.selectTab 保证。**不读 currentTab**：本块 key 为
                                    // tabs.size（不随切页重启），闭包内直接捕获的 currentTab 会冻结为
                                    // 首次组合的旧值，守卫 `!= currentTab` 会把「点回首页」永久拦掉
                                    // （真机反馈：横屏侧栏点首页无效）。也不用 railSelAnim.roundToInt()
                                    // 反推松手目标（轻点微抖会被 round 回原位、静默吞掉点击）。
                                    // 拖回原格 ⇒ startTab==endTab ⇒ 提交原 tab（幂等无感）+ 胶囊弹回，
                                    // 「拖回原位只弹回不切换」语义保留。
                                    val startTab = (down.position.y / unitPx)
                                        .toInt().coerceIn(0, tabs.size - 1)
                                    val endTab = (change.position.y / unitPx)
                                        .toInt().coerceIn(0, tabs.size - 1)
                                    val targetTab = if (startTab == endTab) startTab else endTab
                                    railScope.launch {
                                        railSelAnim.animateTo(
                                            targetTab.toFloat(),
                                            spring(dampingRatio = 0.6f, stiffness = 380f)
                                        )
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
                                    // 受限作用域不能直接调外部挂起函数，经 scope 调度（开销极小）
                                    railScope.launch { railSelAnim.snapTo(t) }
                                }
                            }
                        }
                    }
            )
        }
    }
}

@Composable
fun BottomNavBar(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    showLibraryTab: Boolean = false,
    host: FrameLayout? = null
) {
    val tabs = navTabsFor(showLibraryTab)
    val tabCount = tabs.size
    // 手势层经 rememberUpdatedState 始终调用最新 onSelect（与 NavRail 同款陷阱防护）：
    // pointerInput(tabCount) 的闭包只创建一次，直接捕获 onSelect 会冻结为首次实现 ——
    // AppNavigation.selectTab 读取的 currentTab 也会随之冻结，使「点击当前 tab 直接忽略」
    // 的判定误拦（历史真机坑：点回首页永久失效）。
    val onSelectLatest by rememberUpdatedState(onSelect)
    val isDark = isBlancallDark()
    val accent = MaterialTheme.colorScheme.primary
    val subTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    // 液态玻璃开关（设置-外观）：关闭后玻璃层 alpha 渐隐、回退纯色底。
    // LiquidGlassView 是 AndroidView，不能按条件移出组合树（detach 态 PreDraw 会崩），
    // 因此节点常驻、只动 alpha。
    val navGlass by AppPrefs.navLiquidGlassFlow.collectAsState()
    val barGlassAlpha by animateFloatAsState(if (navGlass) 1f else 0f, label = "barGlassAlpha")
    val sliderGlassAlpha by animateFloatAsState(if (navGlass) 1f else 0f, label = "sliderGlassAlpha")

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
                                    onSelectLatest(targetTab)
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
                                    // 受限作用域不能直接调外部挂起函数，经 scope 调度（开销极小）
                                    scope.launch { sliderAnim.snapTo(t) }
                                }
                            }
                        }
                    }
            )

            val sliderWPx = (tabW - barHpadPx * 2f).coerceAtLeast(1f)
            val sliderWDp = with(density) { sliderWPx.toDp() }
            // 滑块 x 的计算移入 offset lambda（布局阶段）：读 Animatable.value 不再
            // 触发整栏每帧重组——真机反馈「竖屏导航栏卡卡的」的主因。
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
                        .graphicsLayer { alpha = barGlassAlpha }
                        .shadow(
                            elevation = 14.dp,
                            shape = barShape,
                            ambientColor = Color.Black.copy(alpha = 0.18f),
                            spotColor = Color.Black.copy(alpha = 0.24f),
                            clip = false
                        )
                        .clip(barShape)
                )

                // 液态玻璃关闭时的纯色底回退（与 API<33 分支同款样式）
                if (!navGlass) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(0.05f)
                            .shadow(
                                14.dp, barShape,
                                ambientColor = Color.Black.copy(alpha = 0.20f),
                                spotColor = Color.Black.copy(alpha = 0.26f)
                            )
                            .clip(barShape)
                            .background(if (isDark) Color(0xE61A1A1A) else Color(0xC8FFFFFF))
                    )
                }
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
                    // 选中项用主题色（浮在浅色玻璃滑块上对比清晰，参考新一代系统风格）
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
                        .offset {
                            // 读值放在 lambda（布局阶段执行）：拖动/吸附动画不再触发每帧重组
                            val x = (sliderAnim.value * tabW + (tabW - sliderWPx) / 2f)
                                .coerceIn(0f, barW - sliderWPx)
                            IntOffset(x.roundToInt(), sliderOffsetY.roundToInt())
                        }
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
                            .graphicsLayer { alpha = sliderGlassAlpha }
                            .shadow(
                            elevation = 8.dp,
                            shape = sliderShape,
                            ambientColor = Color.Black.copy(alpha = 0.12f),
                            spotColor = Color.Black.copy(alpha = 0.18f),
                            clip = false
                        )
                            .clip(sliderShape)
                    )
                    // 液态玻璃关闭时：主题色纯色底（与 API<33 分支同款）
                    if (!navGlass) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(sliderShape)
                                .background(accent.copy(alpha = if (isDark) 0.35f else 0.28f))
                        )
                    }
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
                        .offset {
                            // 读值放在 lambda（布局阶段执行）：拖动/吸附动画不再触发每帧重组
                            val x = (sliderAnim.value * tabW + (tabW - sliderWPx) / 2f)
                                .coerceIn(0f, barW - sliderWPx)
                            IntOffset(x.roundToInt(), sliderOffsetY.roundToInt())
                        }
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
