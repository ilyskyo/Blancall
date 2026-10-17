// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * 触点为源的页面展开转场。
 *
 * 用户点在哪，详情页就从哪放大铺开；返回时缩回同一点。核心原则只有一句：
 * **点击的位置 = 页面展开的起点**。
 *
 * 接线三步：
 * 1. 点击方在导航前用 [navigateReveal] 把触点（[TouchAnchor]）登记给 [RevealNav]
 *    —— 坐标由 [trackTouchAnchor] 逐帧实测，点击瞬间取用，不缓存行位置；
 * 2. 目标路由的四条转场套上 [revealEnter] / [revealExit] / [revealPopExit] / [revealPopEnter]：
 *    解析到锚点就走浮起，解析不到就原样回退普通滑入（同一导航重复求值结果一致）；
 * 3. 目的地内容包一层 [RevealPageShell]，负责入场那圈圆角的收束与条目销毁后的登记清理。
 *
 * 适用：首页文章卡 / 自定义遮挡卡 → 阅读页，句子卡 → 大卡片界面。
 * 禁止用于：底部 tab 切换（没有单一触点）、练习与跨文这类任务流页面（保持滑入）、
 * 以及没有触发源的自动跳转。
 *
 * 这套在 v7.2.4 是产品形态，重做首页画布时被整条摘掉；用户 2026-10-09 要求按旧范围恢复，
 * 速率沿用旧值。时长一个都不写在这里 —— 全部收在 MotionSpec 的「触点浮起转场」一节，
 * 由动效词表守卫（MotionVocabularyTest）看着。
 *
 * 与 [HeroTransition] 那套共享元素互不干涉：这里管**整页**怎么进场，
 * 那里管**某个节点**跨页飞行，同一条路由挂两者也只是各做各的。
 */

/** 触点锚点：元素中心的 window 绝对坐标（由 [onGloballyPositioned] 捕获） */
data class TouchAnchor(val centerX: Float, val centerY: Float)

/** 从 [onGloballyPositioned] 回调中的 [Rect]（boundsInWindow）生成锚点，取中心点 */
fun Rect.toTouchAnchor(): TouchAnchor =
    TouchAnchor((left + right) / 2f, (top + bottom) / 2f)

/**
 * 页面浮起转场的锚点登记表（进程内单例，主线程访问）。
 *
 * 生命周期：配置变更（旋转）后仍在 → 转场状态可恢复；进程重建即空表 → 转场自动回退默认动画（不会错乱）。
 *
 * 协议（转场 lambda 会被框架在同一导航内多次求值，所有接口必须幂等、无破坏性副作用）：
 * 1. 点击方导航前调用 [NavController.navigateReveal] 登记「基础路由 → 触点锚点」；
 * 2. 目标页 enterTransition 用 [anchorFor] 解析锚点：首次命中即幂等绑定到该返回栈条目，重复求值结果一致；
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

    /** 待用锚点已被哪个返回栈条目认领（防止有效期内被其它导航误用；[post] 重置） */
    private var pendingEntryId: String? = null

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
        pendingEntryId = null
    }

    /** 待用锚点对该条目是否可用：路由匹配、未过期、未被其它条目认领（只读） */
    private fun claimable(routePattern: String, entryId: String): TouchAnchor? {
        val pr = pendingRoute ?: return null
        val pa = pendingAnchor ?: return null
        if (clock() - pendingAt > PENDING_TTL_MS) return null
        if (pr != baseRoute(routePattern)) return null
        val owner = pendingEntryId
        if (owner != null && owner != entryId) return null
        return pa
    }

    /**
     * 非破坏探测：该条目此刻能否解析到浮起锚点（已绑定命中或待用锚点可认领）。
     * 供源页 exit 判断使用——幂等，重复调用不改任何状态。
     */
    fun peek(routePattern: String, entryId: String): TouchAnchor? =
        boundByEntry[entryId] ?: claimable(routePattern, entryId)

    /**
     * 入口转场解析：命中则幂等绑定到该条目并返回锚点。
     *
     * 一次导航中框架会对转场 lambda 多次求值（源页退出、目标页进入各一次），
     * 旧版「消费即清空」会让先求值的那次拿走锚点，真正生效的目标页求值只剩 fallback
     * （旧右侧滑入）——故所有解析必须可重复、结果一致。
     */
    fun anchorFor(routePattern: String, entryId: String): TouchAnchor? {
        boundByEntry[entryId]?.let { return it }
        val pa = claimable(routePattern, entryId) ?: return null
        pendingEntryId = entryId
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
        pendingEntryId = null
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

/** 浮起进入：可解析锚点 → 从触点放大浮出（scale 0.84→1 + 渐显；同一导航重复求值结果一致）；否则 [fallback]（保持既有观感） */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealEnter(fallback: EnterTransition): EnterTransition {
    val anchor = RevealNav.anchorFor(targetState.destination.route ?: "", targetState.id) ?: return fallback
    return scaleIn(
        animationSpec = Motion.revealScaleIn(),
        initialScale = Motion.revealScaleFrom,
        transformOrigin = RevealNav.originOf(anchor),
    ) + fadeIn(MotionFade.alpha(MotionFade.revealEnter))
}

/**
 * 源页退出：本轮为浮起导航（目标页有待用/已绑定锚点）→ 仅轻淡出，
 * 不与浮起页的放大互相打架；否则 [fallback]。
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealExit(fallback: ExitTransition): ExitTransition {
    val revealed = RevealNav.peek(targetState.destination.route ?: "", targetState.id) != null
    return if (revealed) fadeOut(MotionFade.alpha(MotionFade.revealSourceExit)) else fallback
}

/** 返回退出（被弹出的浮起页）：缩放回触点 + 渐隐；非浮起进入的页面回退 [fallback] */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealPopExit(fallback: ExitTransition): ExitTransition {
    val anchor = RevealNav.bound(initialState.id) ?: RevealNav.bound(targetState.id) ?: return fallback
    return scaleOut(
        animationSpec = Motion.revealScaleOut(),
        targetScale = Motion.revealScaleFrom,
        transformOrigin = RevealNav.originOf(anchor),
    ) + fadeOut(MotionFade.alpha(MotionFade.revealExit))
}

/**
 * 返回进入（下方页面重新露出）：上方是浮起页 → 轻渐显（不回滑）；否则 [fallback]。
 *
 * 时长复用 [MotionFade.enter]（180）：这和「覆盖层出现」是同一件事——底下的页面重新可见。
 * 为它单开一个常量只会让词表里多出两个数值相同的档位。
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.revealPopEnter(fallback: EnterTransition): EnterTransition {
    val fromReveal = RevealNav.bound(initialState.id) != null
    return if (fromReveal) fadeIn(MotionFade.alpha(MotionFade.enter)) else fallback
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
    val radiusDp = remember { Animatable(if (animateEntry) Motion.revealShellRadiusDp else 0f) }
    LaunchedEffect(Unit) {
        if (animateEntry) {
            radiusDp.animateTo(0f, Motion.revealShell())
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
