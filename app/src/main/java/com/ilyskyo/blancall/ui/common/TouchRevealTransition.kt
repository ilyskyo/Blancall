// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * 触点为源的反向展开页面转场动画（生产级）。
 *
 * 适用：列表元素 / 圆形按钮 → 对应详情页（如首页圆形入口 → 文章列表 / 学习统计）。
 * 禁止用于：底部 Tab 切换、无关联页面跳转、搜索弹窗、Toast/Dialog 等系统覆盖层。
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
    internal var token by mutableStateOf(0)
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
