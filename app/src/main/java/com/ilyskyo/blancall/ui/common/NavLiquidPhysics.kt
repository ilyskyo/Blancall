// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 底部导航栏的「双弹簧 + 速度形变」物理状态（纯 Kotlin，不参与绘制）。
 *
 * 参数与手感来自 Kyant0/AndroidLiquidGlass 的 `DampedDragAnimation`：
 * - **位置**：强阻尼（damping=1）只追不弹，手指只改 `target`，实际值滞后跟随；
 * - **速度**：独立欠阻尼弹簧（0.5 / 300），松手后来回振荡，拉伸随之衰减；
 * - **按压**：指示器先放大到 78/56≈1.393，等位置滑到位后再缩回；
 * - **整栏位移**：拖动量映射为最多 4dp 的横向位移，松手回正。
 *
 * 为什么不用一个 `Animatable` 走天下：位置要「跟手但不抖」，速度要「松手后还能
 * 多荡两下」，两者必须用不同阻尼；共用一个 spring 会把「Q 弹」抹平成普通滑动。
 *
 * 暴露值全部是 Compose `State`：UI 只在 `graphicsLayer` / `draw` / `effects` 的
 * lambda 里读取，因此拖动期间只失效绘制层，不触发组合重组。
 */
class NavLiquidPhysics(
    initialTab: Int,
    private val tabCount: Int,
    reducedMotion: Boolean = false,
) {
    /** 系统「减少动态效果」：去掉速度拉伸与超调，位置直接到位。 */
    var reducedMotion: Boolean = reducedMotion
        set(value) {
            if (field == value) return
            field = value
            if (value) snapToRest()
        }

    private val positionSpring = Spring(
        initialValue = initialTab.toFloat(),
        stiffness = 1000f,
        damping = 1f,
        threshold = 0.001f,
    )
    private val velocitySpring = Spring(
        initialValue = 0f,
        stiffness = 300f,
        damping = 0.5f,
        threshold = 0.005f,
    )
    private val pressSpring = Spring(
        initialValue = 0f,
        stiffness = 1000f,
        damping = 1f,
        threshold = 0.001f,
    )
    private val scaleXSpring = Spring(
        initialValue = 1f,
        stiffness = 250f,
        damping = 0.6f,
        threshold = 0.001f,
    )
    private val scaleYSpring = Spring(
        initialValue = 1f,
        stiffness = 250f,
        damping = 0.7f,
        threshold = 0.001f,
    )
    private val panelSpring = Spring(
        initialValue = 0f,
        stiffness = 300f,
        damping = 1f,
        threshold = 0.5f,
    )

    private val velocityTracker = VelocityTracker()
    private var isDragging = false
    private var pendingClickRelease = false

    // 绘制层读取的状态镜像：每次推进后同步，读它们只失效 layer / draw，不重组。
    private val positionState = mutableFloatStateOf(initialTab.toFloat())
    private val velocityState = mutableFloatStateOf(0f)
    private val pressState = mutableFloatStateOf(0f)
    private val scaleXState = mutableFloatStateOf(1f)
    private val scaleYState = mutableFloatStateOf(1f)
    private val stretchState = mutableFloatStateOf(0f)
    private val visualScaleXState = mutableFloatStateOf(1f)
    private val visualScaleYState = mutableFloatStateOf(1f)
    private val panelOffsetState = mutableFloatStateOf(0f)

    // 帧循环唤醒信号：CONFLATED，拖动中连续 send 不会堆积、也不会触发重组。
    // UI 用一个常驻 `LaunchedEffect` 收集它，收到后跑一段 `withFrameNanos` 直到静止。
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    val wakeSignal: ReceiveChannel<Unit> = wakeChannel

    /** 栏宽 / 单 tab 宽度 / 整栏最大位移（px）：由 UI 在尺寸变化时写入。 */
    var barWidthPx: Float = 1f
        private set
    var tabWidthPx: Float = 1f
        private set
    var maxPanelOffsetPx: Float = 1f
        private set

    /** 当前是否还有弹簧在运动（帧循环的退出条件）。 */
    val isActive: Boolean
        get() = !positionSpring.settled ||
            !velocitySpring.settled ||
            !pressSpring.settled ||
            !scaleXSpring.settled ||
            !scaleYSpring.settled ||
            !panelSpring.settled

    val position: Float get() = positionState.floatValue
    val targetPosition: Float get() = positionSpring.target
    val pressProgress: Float get() = pressState.floatValue
    val velocity: Float get() = velocityState.floatValue
    val isDraggingNow: Boolean get() = isDragging

    /** 速度驱动各向异性拉伸：s = clamp(v/10, ±0.2)。 */
    val stretch: Float get() = stretchState.floatValue

    /** 指示器实际缩放（含速度拉伸）；文字/图标不读它。 */
    val visualScaleX: Float get() = visualScaleXState.floatValue
    val visualScaleY: Float get() = visualScaleYState.floatValue

    /** 整栏横向位移（px）：拖动量的非线性映射，最大 4dp。 */
    val panelOffsetPx: Float get() = panelOffsetState.floatValue

    fun updateMetrics(barWidthPx: Float, tabWidthPx: Float, maxPanelOffsetPx: Float) {
        this.barWidthPx = barWidthPx.coerceAtLeast(1f)
        this.tabWidthPx = tabWidthPx.coerceAtLeast(1f)
        this.maxPanelOffsetPx = maxPanelOffsetPx.coerceAtLeast(0f)
        syncStates()
    }

    /** 指示器按下：重置速度跟踪，放大到 1.393。 */
    fun press() {
        velocityTracker.resetTracking()
        isDragging = true
        pendingClickRelease = false
        if (reducedMotion) {
            pressSpring.snapTo(0f)
            scaleXSpring.snapTo(1f)
            scaleYSpring.snapTo(1f)
        } else {
            pressSpring.target = 1f
            scaleXSpring.target = PRESSED_SCALE
            scaleYSpring.target = PRESSED_SCALE
        }
        syncStates()
        wake()
    }

    /**
     * 拖动：手指只改目标值，实际位置由强阻尼弹簧追；整栏位移按原始拖动量累积。
     *
     * @param dxPx 本帧水平位移
     * @param nowMs 本帧时间（毫秒），用于速度追踪
     */
    fun drag(dxPx: Float, nowMs: Long) {
        if (!isDragging) press()
        val nextTarget = (positionSpring.target + dxPx / tabWidthPx)
            .coerceIn(0f, (tabCount - 1).toFloat())
        positionSpring.target = nextTarget
        panelSpring.value += dxPx
        panelSpring.target = panelSpring.value
        panelSpring.velocity = 0f

        if (reducedMotion) {
            positionSpring.snapTo(nextTarget)
            velocitySpring.snapTo(0f)
            syncStates()
            return
        }
        velocityTracker.addPosition(nowMs, Offset(positionSpring.value, 0f))
        velocitySpring.target = normalizedVelocity()
        syncStates()
        wake()
    }

    /**
     * 松手/取消：统一走同一套收尾（不能卡在按下态）。
     *
     * @return 落格后的 tab 下标；调用方负责触发 `onSelect`
     */
    fun release(): Int {
        val target = positionSpring.target.roundToInt()
            .coerceIn(0, tabCount - 1)
        positionSpring.target = target.toFloat()
        velocitySpring.target = 0f
        panelSpring.target = 0f
        isDragging = false

        if (reducedMotion) {
            snapToRest(target)
            return target
        }

        // 还要滑一段：保持按压态，等位置到位后再缩回（松手先滑、后回弹）
        val willSlide = !positionSpring.settled
        pendingClickRelease = willSlide
        if (!willSlide) {
            pressSpring.target = 0f
            scaleXSpring.target = 1f
            scaleYSpring.target = 1f
        }
        syncStates()
        wake()
        return target
    }

    /**
     * 外部切 tab（点击/返回/通知）：先按下，等位置滑到位再缩回。
     * 这是「点击 tab 也像被按了一下」的来源，不是额外装饰。
     */
    fun animateToTab(index: Int) {
        val target = index.coerceIn(0, tabCount - 1).toFloat()
        // 已经在该格且没有未完成的动画：首帧/重复调用不播按压
        val alreadyThere = positionSpring.target == target &&
            abs(positionSpring.value - target) <= 0.001f &&
            !isActive
        if (alreadyThere) return
        // 拖动松手已经设置过同一目标：不要重复按下
        if (positionSpring.target == target && (isDragging || pendingClickRelease)) return
        // 已经被拖动松手接管（目标一致且正在回弹）：不要再按一次
        if (positionSpring.target == target && isActive) return
        positionSpring.target = target
        velocitySpring.target = 0f
        if (reducedMotion) {
            snapToRest(index.coerceIn(0, tabCount - 1))
            return
        }
        pressSpring.target = 1f
        scaleXSpring.target = PRESSED_SCALE
        scaleYSpring.target = PRESSED_SCALE
        pendingClickRelease = true
        isDragging = false
        syncStates()
        wake()
    }

    /**
     * 手势被取消 / 判定为垂直滚动：回到当前 tab，不提交任何切换。
     * 与 [release] 的区别只在于落点，按下态一样要放行。
     */
    fun cancel(tab: Int) {
        positionSpring.target = tab.coerceIn(0, tabCount - 1).toFloat()
        velocitySpring.target = 0f
        pressSpring.target = 0f
        scaleXSpring.target = 1f
        scaleYSpring.target = 1f
        panelSpring.target = 0f
        isDragging = false
        pendingClickRelease = false
        syncStates()
        wake()
    }

    /**
     * 推进一步。由 `withFrameNanos` 调用，内部按 1/240s 子步积分，帧率无关。
     *
     * @param dtSeconds 距上一帧的秒数（已夹到 64ms 上限）
     * @param nowMs 当前时间（毫秒），用于速度追踪
     */
    fun step(dtSeconds: Float, nowMs: Long) {
        val dt = dtSeconds.coerceIn(0f, 0.064f)

        if (dt > 0f) {
            var remaining = dt
            while (remaining > 0f) {
                val h = minOf(remaining, 1f / 240f)
                positionSpring.step(h)
                velocitySpring.step(h)
                pressSpring.step(h)
                scaleXSpring.step(h)
                scaleYSpring.step(h)
                panelSpring.step(h)
                remaining -= h
            }
        }

        if (isDragging && !reducedMotion) {
            velocityTracker.addPosition(nowMs, Offset(positionSpring.value, 0f))
            velocitySpring.target = normalizedVelocity()
        }

        // 点击切页：位置到位后再松开按压，避免「还没滑过去就缩回去」
        if (pendingClickRelease && positionSpring.settled) {
            pressSpring.target = 0f
            scaleXSpring.target = 1f
            scaleYSpring.target = 1f
            pendingClickRelease = false
        }

        if (!isActive) {
            positionSpring.snapTo(positionSpring.target)
            velocitySpring.snapTo(0f)
            pressSpring.snapTo(pressSpring.target)
            scaleXSpring.snapTo(scaleXSpring.target)
            scaleYSpring.snapTo(scaleYSpring.target)
            panelSpring.snapTo(0f)
        }
        syncStates()
    }

    private fun normalizedVelocity(): Float {
        if (tabCount <= 1) return 0f
        return (velocityTracker.calculateVelocity().x / (tabCount - 1))
            .coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
    }

    private fun snapToRest(tab: Int? = null) {
        val target = tab ?: positionSpring.target.roundToInt()
        positionSpring.snapTo(target.coerceIn(0, tabCount - 1).toFloat())
        velocitySpring.snapTo(0f)
        pressSpring.snapTo(0f)
        scaleXSpring.snapTo(1f)
        scaleYSpring.snapTo(1f)
        panelSpring.snapTo(0f)
        isDragging = false
        pendingClickRelease = false
        syncStates()
    }

    private fun wake() {
        wakeChannel.trySend(Unit)
    }

    /** 把弹簧值同步到绘制层状态；只写 State，不读组合。 */
    private fun syncStates() {
        positionState.floatValue = positionSpring.value
        velocityState.floatValue = velocitySpring.value
        pressState.floatValue = pressSpring.value
        scaleXState.floatValue = scaleXSpring.value
        scaleYState.floatValue = scaleYSpring.value

        val s = (velocitySpring.value / 10f).coerceIn(-0.2f, 0.2f)
        stretchState.floatValue = s
        visualScaleXState.floatValue = scaleXSpring.value / (1f - s * 0.75f)
        visualScaleYState.floatValue = scaleYSpring.value * (1f - s * 0.25f)
        panelOffsetState.floatValue = computePanelOffset()
    }

    private fun computePanelOffset(): Float {
        if (barWidthPx <= 0f) return 0f
        val fraction = (panelSpring.value / barWidthPx).coerceIn(-1f, 1f)
        return maxPanelOffsetPx * sign(fraction) * easeOutCubic(abs(fraction))
    }

    /** 标准 cubic ease-out：先快后慢，整栏位移的「被推着走」感来自它。 */
    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    /**
     * 单自由度弹簧（单位质量）：
     * a = -k * (x - target) - 2ζ√k * v
     * 用 1/240s 子步的半隐式欧拉积分，保证高刚度下不炸。
     */
    private class Spring(
        initialValue: Float,
        private val stiffness: Float,
        private val damping: Float,
        private val threshold: Float,
    ) {
        var value: Float = initialValue
        var target: Float = initialValue
        var velocity: Float = 0f

        val settled: Boolean
            get() = abs(value - target) <= threshold &&
                abs(velocity) <= threshold * 20f + 1e-4f

        fun step(dtSeconds: Float) {
            if (settled) {
                value = target
                velocity = 0f
                return
            }
            val omega = sqrt(stiffness)
            val c = 2f * damping * omega
            val acceleration = -stiffness * (value - target) - c * velocity
            velocity += acceleration * dtSeconds
            value += velocity * dtSeconds
        }

        fun snapTo(newValue: Float) {
            value = newValue
            target = newValue
            velocity = 0f
        }
    }

    private companion object {
        /** 78/56：按住时指示器的放大倍数（Kyant 参考实现）。 */
        const val PRESSED_SCALE = 78f / 56f

        /** 速度上限：防止极端甩动把拉伸推到荒谬的值。 */
        const val MAX_VELOCITY = 40f
    }
}
