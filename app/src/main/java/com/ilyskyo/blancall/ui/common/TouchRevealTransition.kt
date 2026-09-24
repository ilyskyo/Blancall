// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * 触点为源的页面展开转场动画（生产级）。
 *
 * 同一套体系的两条实现路径（共用 [TouchAnchor] / [toTouchAnchor] 触点锚点）：
 * 1. **[TouchRevealHost]**（覆盖层变体）：目标内容以叠加层形式从触点展开（无导航栈场景 / 弹层展开）；
 * 2. **[RevealNav]** + [revealEnter] / [revealPopExit] 等（页面路由变体，当前主用）：接入 Compose Navigation
 *    转场，保留既有返回栈、预测性返回与状态恢复，点击位置 = 页面放大浮出的起点。
 *
 * 适用：列表元素 / 卡片 / 圆形按钮 → 对应详情页。
 * 禁止用于：底部 Tab 切换（无单触点）、任务流页面（练习/跨文，保持滑入）、无触发源的自动跳转。
 *
 * 核心原则：用户点击的位置 = 页面展开的起点。
 * - 进入：scale 0.8→1, alpha 0→1, 圆角 圆(50%)→直(0%)，以触点为 scale 中心，easeOut 320ms。
 * - 退出：完全反向，缩回原点击位置（非 fadeOut / slideOut）。
 *
 * 特殊情况处理：
 * 1. 快速连续点击：expand() 自增 token 作为 LaunchedEffect key，重启动画；
 *    Animatable.animateTo 内部会取消上一个动画 job，避免叠加。
 * 2. 列表复用：锚点在点击瞬间通过 onGloballyPositioned 实时捕获屏幕坐标，不缓存 item 位置。
 * 3. 屏幕旋转：状态用 remember() 随重组重建，旋转后自动回到 Idle，避免坐标错乱。
 * 4. 生命周期：Animatable 协程绑定 Composable 生命周期，离开即取消，无内存泄漏。
 * 5. 预测性返回：展开期间支持 predictive back 手势，手指拖动实时缩小页面，
 *    松手按进度决定收起（缩回圆圈）或回弹到展开状态。
 */

/** 动画阶段 */
enum class RevealPhase { Idle, Expanding, Expanded, Collapsing }

/** 触点锚点：元素中心的屏幕 / window 绝对坐标（由 onGloballyPositioned 捕获） */
data class TouchAnchor(val centerX: Float, val centerY: Float)

/** 从 [onGloballyPositioned] 回调中的 [Rect]（boundsInWindow）生成锚点，取中心点 */
fun Rect.toTouchAnchor(): TouchAnchor =
    TouchAnchor((left + right) / 2f, (top + bottom) / 2f)

/** 创建触点展开转场状态 */
@Composable
fun rememberTouchRevealState(): TouchRevealState = remember { TouchRevealState() }

/**
 * 触点展开转场状态机。
 * 调用 [expand] 开始展开（自动取消进行中的动画），[collapse] 开始反向收起。
 */
class TouchRevealState internal constructor() {
    var anchor by mutableStateOf<TouchAnchor?>(null)
        internal set
    var phase by mutableStateOf(RevealPhase.Idle)
        internal set

    // 动画请求令牌：每次 expand/collapse 自增，作为 LaunchedEffect 的 key 重启动画协程
    internal var token by mutableIntStateOf(0)
        private set

    /** 预测性返回手势正在拖动中（用于 graphicsLayer 判断走退出分支缩回圆圈） */
    var isUserDragging by mutableStateOf(false)
        internal set

    fun expand(anchor: TouchAnchor) {
        this.anchor = anchor
        phase = RevealPhase.Expanding
        token++
    }

    fun collapse() {
        // 仅在已展开 / 展开中允许收起，避免重复触发
        if (phase == RevealPhase.Expanded || phase == RevealPhase.Expanding) {
            phase = RevealPhase.Collapsing
            token++
        }
    }

    internal fun onExpandEnd() { phase = RevealPhase.Expanded }
    internal fun onCollapseEnd() { phase = RevealPhase.Idle; anchor = null }

    /**
     * 从导航返回时恢复展开状态（练习/阅读页返回后仍显示展开的目标页）。
     * 组合重建后 Animatable 归零，由 TouchRevealHost 的恢复逻辑同步进度。
     */
    internal fun restore(phase: RevealPhase, anchorX: Float, anchorY: Float) {
        this.phase = phase
        this.anchor = TouchAnchor(anchorX, anchorY)
    }
}

/**
 * 触点展开转场宿主。
 *
 * 放在外层 [Box] 的末尾（最高 z 序）。[phase] 为 Idle 时不渲染任何内容，零开销。
 * 非 Idle 时以 [anchor] 为起点对 [target] 做 scale + alpha + 圆角动画。
 *
 * @param state  [rememberTouchRevealState] 返回的状态
 * @param target 目标页面内容（点击元素展开为的完整页面）
 */
@Composable
fun TouchRevealHost(
    state: TouchRevealState,
    target: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.phase == RevealPhase.Idle) return
    val anchor = state.anchor ?: return

    // 进度 0..1：1 = 完全展开。remember 保证整个转场生命周期共用同一实例
    val progress = remember { Animatable(0f) }

    // 恢复场景：从练习/阅读页返回时组合重建，progress 归零但 phase 已是 Expanded，
    // 直接对齐到完全展开，避免展开页以 0.8 缩放比例显示
    LaunchedEffect(Unit) {
        if (state.phase == RevealPhase.Expanded) progress.snapTo(1f)
    }

    // 监听 token 重启动画：expand → 0→1；collapse → 1→0（从当前进度反向，不 snapTo）
    LaunchedEffect(state.token) {
        when (state.phase) {
            RevealPhase.Expanding -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                if (state.phase == RevealPhase.Expanding) state.onExpandEnd()
            }
            RevealPhase.Collapsing -> {
                // 退出更快更利落，避免残影：180ms + LinearOutSlowInEasing
                progress.animateTo(0f, tween(180, easing = LinearOutSlowInEasing))
                if (state.phase == RevealPhase.Collapsing) state.onCollapseEnd()
            }
            else -> {}
        }
    }

    // 预测性返回手势：展开状态下拦截，手指拖动实时控制页面缩回进度，
    // collect 正常结束表示手势完成（松手达系统阈值）→ collapse 缩回圆圈；
    // 抛 CancellationException 表示手势取消（滑回起点）→ spring 回弹到展开。
    PredictiveBackHandler(enabled = state.phase == RevealPhase.Expanded) { progressFlow: Flow<BackEventCompat> ->
        try {
            // 进入手势拖动阶段：实时映射进度 0..1 → 展开进度 1..0
            state.isUserDragging = true
            progressFlow.collect { backEvent ->
                val dragProgress = backEvent.progress
                // 系统返回手势进度 0..1，映射到展开进度 1→0（缩回圆圈方向）
                progress.snapTo((1f - dragProgress).coerceIn(0f, 1f))
            }
            // 手势完成：触发 collapse（走 token → LaunchedEffect 跑 180ms 收尾动画）
            state.collapse()
        } catch (e: CancellationException) {
            // 手势取消（如用户滑回起点松手）：用 spring 回弹到完全展开
            progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } finally {
            state.isUserDragging = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        // 圆角：圆形(50%) → 直角(0%)，随进度线性过渡
        val cornerPercent = (50f * (1f - p)).toInt().coerceIn(0, 50)
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 先按圆角裁剪内容（layout 阶段），再整体 scale（draw 阶段），
                // scale 以 transformOrigin 为中心 → 圆角矩形从触点向外展开
                .clip(RoundedCornerShape(percent = cornerPercent))
                .graphicsLayer {
                    // 进入：scale 0.8→1；退出/拖动：scale 1→0.05（缩进圆圈里消失）
                    val isExiting = state.phase == RevealPhase.Collapsing || state.isUserDragging
                    val s = if (isExiting) {
                        1f - 0.95f * (1f - p)      // 退出/拖动：1 → 0.05
                    } else {
                        0.8f + 0.2f * p            // 进入：0.8 → 1
                    }
                    scaleX = s
                    scaleY = s
                    // alpha：进入时随 p 渐入；退出/拖动时保持不透明，快到圆圈时（p<0.3）快速归零
                    // 避免缩小过程中与圆圈按钮图案重叠产生残影
                    alpha = if (isExiting) {
                        if (p < 0.3f) (p / 0.3f).coerceIn(0f, 1f) else 1f
                    } else {
                        p
                    }
                    // 缩放中心 = 用户点击位置（圆形按钮中心，归一化 0..1）
                    transformOrigin = TransformOrigin(
                        (anchor.centerX / size.width).coerceIn(0f, 1f),
                        (anchor.centerY / size.height).coerceIn(0f, 1f)
                    )
                }
        ) {
            target()
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// 页面路由浮起转场（Nav 驱动，与覆盖层变体共用触点锚点）
// ══════════════════════════════════════════════════════════════════

/**
 * 页面浮起转场的锚点登记表（进程内单例，主线程访问）。
 *
 * 生命周期：配置变更（旋转）后仍在 → 转场状态可恢复；进程重建即空表 → 转场自动回退默认动画（不会错乱）。
 *
 * 协议：
 * 1. 点击方导航前调用 [NavController.navigateReveal] 登记「基础路由 → 触点锚点」；
 * 2. 目标页 enterTransition 用 [consume] 消费锚点并绑定到该返回栈条目（[bound]）；
 * 3. 返回时 popExit 用 [bound] 取回锚点做反向缩回；条目销毁时由 [RevealPageShell] 的 onDispose [forget]。
 */
object RevealNav {
    /** 锚点有效期：防止导航被拦（如同路由 launchSingleTop 命中）后残留锚点污染后续普通导航 */
    private const val PENDING_TTL_MS = 2_000L

    /** 时间源（测试可注入） */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private var pendingRoute: String? = null
    private var pendingAnchor: TouchAnchor? = null
    private var pendingAt: Long = 0L

    /** 已浮起展开的返回栈条目（entryId → 锚点），供 popExit 反向缩回 */
    private val boundByEntry = mutableStateMapOf<String, TouchAnchor>()
    /** 入场圆角动画已播放的条目（防旋转/重组重放） */
    private val playedByEntry = mutableStateMapOf<String, Boolean>()

    /** 导航表面尺寸（window px）与内容横向偏移（大屏侧栏让位 px）：把 window 坐标换算为归一化变换原点 */
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceOffsetX = 0f

    /** 路由基础段（跳过参数与查询串）：'reader/12?x=1' → 'reader' */
    fun baseRoute(route: String): String = route.substringBefore('/').substringBefore('?')

    /** AppNavigation 每次组合同步表面尺寸/大屏让位偏移（旋转、侧栏切换会自动更新） */
    fun updateSurface(width: Int, height: Int, offsetX: Float) {
        surfaceWidth = width
        surfaceHeight = height
        surfaceOffsetX = offsetX
    }

    /** 登记下一次浮起导航的锚点（由 [NavController.navigateReveal] 调用） */
    fun post(route: String, anchor: TouchAnchor) {
        pendingRoute = baseRoute(route)
        pendingAnchor = anchor
        pendingAt = clock()
    }

    /**
     * 非消耗探测：该路由当前是否有待用锚点（供源页 exit 判断，可能在 [consume] 前后被调用）。
     * 过期返回 null（不清理；真正的清理由 [consume] 或下一次 [post] 完成）。
     */
    fun pendingFor(routePattern: String): TouchAnchor? {
        val pr = pendingRoute ?: return null
        val pa = pendingAnchor ?: return null
        if (clock() - pendingAt > PENDING_TTL_MS) return null
        return if (pr == baseRoute(routePattern)) pa else null
    }

    /**
     * 入口转场消费：路由匹配且未过期 → 绑定到条目并返回锚点；
     * 否则清除待用状态并返回 null（后续按普通导航回退默认动画）。
     */
    fun consume(routePattern: String, entryId: String): TouchAnchor? {
        val pr = pendingRoute
        val pa = pendingAnchor
        val at = pendingAt
        pendingRoute = null
        pendingAnchor = null
        if (pr == null || pa == null || pr != baseRoute(routePattern)) return null
        if (clock() - at > PENDING_TTL_MS) return null
        boundByEntry[entryId] = pa
        return pa
    }

    /** 该条目是否以浮起方式进入（及其锚点）；普通进入返回 null */
    fun bound(entryId: String): TouchAnchor? = boundByEntry[entryId]

    /** 入场圆角动画完成标记（防止旋转/重组重放） */
    fun markPlayed(entryId: String) {
        playedByEntry[entryId] = true
    }

    fun hasPlayed(entryId: String): Boolean = playedByEntry[entryId] == true

    /** 条目销毁后清理（[RevealPageShell] onDispose 调用），避免注册表无限增长 */
    fun forget(entryId: String) {
        boundByEntry.remove(entryId)
        playedByEntry.remove(entryId)
    }

    /** 测试用：清空全部状态 */
    internal fun reset() {
        pendingRoute = null
        pendingAnchor = null
        pendingAt = 0L
        boundByEntry.clear()
        playedByEntry.clear()
        surfaceWidth = 0
        surfaceHeight = 0
        surfaceOffsetX = 0f
    }

    /** 触点 → 归一化 TransformOrigin（扣除大屏侧栏让位偏移；尺寸未知时退化为屏幕中心） */
    fun originOf(anchor: TouchAnchor): TransformOrigin {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return TransformOrigin.Center
        val x = ((anchor.centerX - surfaceOffsetX) / surfaceWidth).coerceIn(0f, 1f)
        val y = (anchor.centerY / surfaceHeight).coerceIn(0f, 1f)
        return TransformOrigin(x, y)
    }
}

/** 记录元素中心的实时 window 坐标（点击时取用；滚动/布局变化自动更新）。不改变任何点击行为 */
fun Modifier.trackTouchAnchor(state: MutableState<TouchAnchor?>): Modifier =
    onGloballyPositioned { state.value = it.boundsInWindow().toTouchAnchor() }

/** 创建触点锚点状态（与 [trackTouchAnchor] 配套） */
@Composable
fun rememberTouchAnchor(): MutableState<TouchAnchor?> = remember { mutableStateOf(null) }

/**
 * 浮起导航：登记触点锚点后执行常规 navigate。
 * 锚点为 null 时等价于普通 navigate——目标路由的浮起转场会自动回退默认动画（不会错乱）。
 */
fun NavController.navigateReveal(
    route: String,
    anchor: TouchAnchor?,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (anchor != null) RevealNav.post(route, anchor)
    navigate(route, builder)
}

/** 浮起进入：有待用锚点 → 从触点放大浮出（scale 0.84→1 + 渐显）；否则 [fallback]（保持既有观感） */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealEnter(fallback: EnterTransition): EnterTransition {
    val anchor = RevealNav.consume(targetState.destination.route ?: "", targetState.id) ?: return fallback
    return scaleIn(
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        initialScale = 0.84f,
        transformOrigin = RevealNav.originOf(anchor),
    ) + fadeIn(tween(220))
}

/**
 * 源页退出：本轮为浮起导航（目标页有待用/已绑定锚点）→ 仅轻淡出，
 * 不与浮起页的放大互相打架；否则 [fallback]。
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealExit(fallback: ExitTransition): ExitTransition {
    val revealed = RevealNav.pendingFor(targetState.destination.route ?: "") != null ||
        RevealNav.bound(targetState.id) != null
    return if (revealed) fadeOut(tween(200)) else fallback
}

/** 返回退出（被弹出的浮起页）：缩放回触点 + 渐隐；非浮起进入的页面回退 [fallback] */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealPopExit(fallback: ExitTransition): ExitTransition {
    val anchor = RevealNav.bound(initialState.id) ?: RevealNav.bound(targetState.id) ?: return fallback
    return scaleOut(
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        targetScale = 0.84f,
        transformOrigin = RevealNav.originOf(anchor),
    ) + fadeOut(tween(160))
}

/** 返回进入（下方页面重新露出）：上方是浮起页 → 轻渐显（不回滑）；否则 [fallback] */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealPopEnter(fallback: EnterTransition): EnterTransition {
    val fromReveal = RevealNav.bound(initialState.id) != null
    return if (fromReveal) fadeIn(tween(180)) else fallback
}

/**
 * 浮起页面的外壳：入场时圆角 24dp → 0 收束（与 scaleIn 并行，绘相位动画、无逐帧重组）。
 *
 * - 仅对「浮起进入」的条目包一层（普通条目零包装、零开销）；
 * - 包裹与否只看首次组合时的绑定结果（remember 锁存），避免绑定晚到导致包裹层切换、子树重建丢状态；
 * - 旋转后依赖 [RevealNav.hasPlayed] 不再重放圆角动画（结构保持同一，无缩放比例错乱）；
 * - 条目销毁时清理注册表（防泄漏）。
 */
@Composable
fun RevealPageShell(entryId: String, content: @Composable () -> Unit) {
    val revealed = remember { RevealNav.bound(entryId) != null }
    DisposableEffect(entryId) {
        onDispose { RevealNav.forget(entryId) }
    }
    if (!revealed) {
        content()
        return
    }
    val animateEntry = remember { !RevealNav.hasPlayed(entryId) }
    val radiusDp = remember { Animatable(if (animateEntry) 24f else 0f) }
    LaunchedEffect(Unit) {
        if (animateEntry) {
            radiusDp.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
            RevealNav.markPlayed(entryId)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundedCornerShape(radiusDp.value.dp)
                clip = true
            }
    ) {
        content()
    }
}
