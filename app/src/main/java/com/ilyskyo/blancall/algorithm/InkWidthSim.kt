// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import kotlin.math.hypot

/**
 * 触屏手写的「仿造压感」：**无笔设备时用手指书写**，没有真实压感可用，
 * 按触点轨迹的**速度**模拟笔迹粗细 —— 慢写粗、快写细，与手写笔模式的压感渲染
 * 共用同一套宽度映射区间（`InkBoardView` 的 `PRESS_MIN_SCALE..PRESS_MAX_SCALE`），
 * 因此两种模式的观感基本一致。
 *
 * ## 为什么不用真实 pressure
 * 手指书写时系统给出的 pressure 要么恒定（电容屏不测压）、要么是模型估算的噪声；
 * 按它调笔宽只会得到「全笔画同一档」或抖动。速度是唯一可靠且稳定的信号。
 *
 * ## 语义（配合 InkBoardView）
 * - [onSample] 每次收到一个采样点调用一次，返回 0..1 的**伪压感因子**
 *   （1 = 最粗档、0 = 最细档），与真实压感的归一化值同语义；
 * - 因子经过一阶低通（[smoothing]）平滑，避免速度毛刺把一笔切成锯齿；
 * - [taperEnd] 在**收笔时**把末尾若干采样按线性收细（模拟抬笔提锋），
 *   只在笔画完成后调用 —— 书写中的实时预览不做末端收细（抬笔前不知道哪是末端）。
 *
 * 与识别无关：识别位图始终按等宽渲染，本类只服务屏上显示的笔宽。
 *
 * @param slowSpeed 判定为「慢写」（最粗档）的速度下限，单位 dp/ms
 * @param fastSpeed 判定为「快写」（最细档）的速度上限，单位 dp/ms
 * @param smoothing 一阶低通系数（0..1，越大越跟手、越小越平滑）
 * @param initial 起笔初始因子（首采样没有速度可比，先给中间偏细的一档）
 */
class InkWidthSim(
    private val slowSpeed: Float = SLOW_SPEED_DP_PER_MS,
    private val fastSpeed: Float = FAST_SPEED_DP_PER_MS,
    private val smoothing: Float = SMOOTHING,
    private val initial: Float = INITIAL
) {

    private var value = initial

    /** 起笔前重置（每笔独立，不相互影响）。 */
    fun reset() {
        value = initial
    }

    /**
     * 落一个采样点。
     *
     * @param dx 与上一采样点的 x 位移（px）
     * @param dy 与上一采样点的 y 位移（px）
     * @param dtMs 与上一采样的时间差（ms；≤0 时保持上一因子）
     * @param density 屏幕密度（px/dp；速度按 dp 口径归一，跨设备观感一致）
     * @return 0..1 的伪压感因子
     */
    fun onSample(dx: Float, dy: Float, dtMs: Long, density: Float): Float {
        val distPx = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // 无位移 / 时间倒挂：给不出速度，保持上一因子（也不推进平滑）
        if (distPx < MIN_DIST_PX || dtMs <= 0L) return value
        val densitySafe = density.coerceAtLeast(MIN_DENSITY)
        val speed = (distPx / densitySafe) / dtMs.toFloat() // dp/ms
        val span = (fastSpeed - slowSpeed).coerceAtLeast(MIN_SPAN)
        // 慢 → 1（粗）；快 → 0（细）
        val target = 1f - ((speed - slowSpeed) / span).coerceIn(0f, 1f)
        value += (target - value) * smoothing.coerceIn(0f, 1f)
        return value.coerceIn(0f, 1f)
    }

    companion object {
        /** 低于该速度（dp/ms）视为最慢（最粗档） */
        const val SLOW_SPEED_DP_PER_MS = 0.10f

        /** 高于该速度（dp/ms）视为最快（最细档） */
        const val FAST_SPEED_DP_PER_MS = 0.80f

        /** 一阶低通系数：约 10 个采样（≈150ms）收敛到目标值 */
        const val SMOOTHING = 0.25f

        /** 起笔初始因子（中间偏细） */
        const val INITIAL = 0.55f

        /** 位移小于该值（px）视为「没有动」：不更新速度 */
        private const val MIN_DIST_PX = 0.05f

        private const val MIN_DENSITY = 0.1f

        private const val MIN_SPAN = 0.01f

        /** 收笔收细的采样数（末尾 N 个）与最细比例 */
        const val TAPER_SAMPLES = 6
        const val TAPER_FLOOR = 0.55f

        /**
         * 收笔收细：把 `factors` 末尾最多 [count] 个因子按线性比例压向 [floor]
         * （最后一个采样最细），模拟抬笔提锋。原地修改；样本不足 2 个时不动。
         */
        fun taperEnd(
            factors: MutableList<Float>,
            count: Int = TAPER_SAMPLES,
            floor: Float = TAPER_FLOOR
        ) {
            val n = factors.size
            if (n < 2 || count < 1) return
            val k = count.coerceAtMost(n)
            val f = floor.coerceIn(0f, 1f)
            for (j in 0 until k) {
                val idx = n - k + j
                val p = (j + 1).toFloat() / k // 越靠后越接近 1（越细）
                factors[idx] = factors[idx] * (1f - (1f - f) * p)
            }
        }
    }
}
