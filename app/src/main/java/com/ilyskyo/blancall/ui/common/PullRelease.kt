// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 「拉出 / 拉回」手势的共用实现：下拉进入学习任务流、任务流到底上拉返回，共用同一套手感。
 *
 * ## 铁律：手指不松开，就什么都不发生
 * 位移只是「蓄力」，圆环只反映蓄力多少。越过阈值**不给任何跳转**，
 * 只有抬手那一刻才判定 —— 拉满算数，没拉满回弹。这样拉到一半反悔还有退路，
 * 不会出现「刚转满就被弹进下一页」的惊吓。
 *
 * ## 撤回：往回退，圆环倒着转
 * 位移按手势增量双向变化（[pullReleaseTravel]）：往下拉多少转多少，往上退多少退多少。
 * 到了满格硬顶（阈值 + 一点阻尼余量）后再往回退，圈环同样跟着倒转。
 */

/** 手势方向：下拉（进入）／上拉（退出）。只影响指示器的箭头与露出方向。 */
enum class PullReleaseDirection { Down, Up }

/**
 * 推进「拉出位移」（纯函数，供 JVM 单测）。
 *
 * - [delta] 为正 = 往满的方向拉；为负 = 手指往回退。
 * - 阈值内 1:1 跟手；越过阈值后按 [overshootRatio] 强阻尼，让人感觉「到顶了、拉不动」。
 * - 硬顶是 `threshold + overshootMax`，回退时 1:1 释放，所以退回一点点就能看见圈环倒转。
 * - [threshold] 非正属配置错误：直接归零，绝不退化成「一碰就到位」。
 *
 * @return 新的位移，恒在 `[0, threshold + overshootMax]` 内
 */
internal fun pullReleaseTravel(
    current: Float,
    delta: Float,
    threshold: Float,
    overshootMax: Float = 0f,
    overshootRatio: Float = 0f,
): Float {
    if (threshold <= 0f || !current.isFinite() || !delta.isFinite()) return 0f
    val max = threshold + overshootMax.coerceAtLeast(0f)
    val base = current.coerceIn(0f, max)
    // 只有「已经满格后继续往下拉」才吃阻尼；回退保持 1:1，免得撤回看起来迟钝
    val scaled = if (base >= threshold && delta > 0f && overshootRatio > 0f) {
        delta * overshootRatio.coerceIn(0f, 1f)
    } else {
        delta
    }
    return (base + scaled).coerceIn(0f, max)
}

/**
 * 位移是否已够「松手就生效」。
 *
 * 语义是**抬起手指那一刻**的判定条件，不是拖动过程中的触发器 —— 拖动中越过它只给一次触感，
 * 绝不跳转。阈值非正时永不成立（配置错误不该变成「一碰就进」）。
 */
internal fun pullReleaseReached(pullPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && pullPx >= thresholdPx

/**
 * 拉满闩锁：越过一次阈值后保持「已满格」，直到手指回退超过 [cancelSlackPx] 才解除。
 *
 * 为什么需要它：抬手是一个动作，手指离屏前常有几像素的回带（系统也会补一帧反向位移），
 * 于是「圆圈已经画满、松手时却差 1–2dp」随机发生，读起来是「明明拉满了却没进去」。
 * 闩锁把判定从「松手那一帧的瞬时值」改成「本次手势是否满过格」：
 * - 满格后小幅回带 → 仍算满格；
 * - 回退超过 [cancelSlackPx] → 解除（保留「往回拉可以反悔」）；
 * - 阈值非正 → 永不闩锁（配置错误不该变成「一碰就进」）。
 *
 * 纯函数，供 JVM 单测覆盖这条随机失败路径（见 OverviewFlowPullTest）。
 */
internal fun pullReleaseLatched(
    wasLatched: Boolean,
    pullPx: Float,
    thresholdPx: Float,
    cancelSlackPx: Float,
): Boolean = when {
    thresholdPx <= 0f -> false
    pullPx >= thresholdPx -> true
    pullPx <= thresholdPx - cancelSlackPx.coerceAtLeast(0f) -> false
    else -> wasLatched
}

/** 指示器直径。 */
private val INDICATOR_SIZE = 38.dp

/** 指示器在开始拉动后的前 24dp 内淡入，避免刚碰屏幕就闪出一个圆环。 */
private val INDICATOR_FADE_IN = 24.dp

/**
 * 拉动指示器：跟手位移越大圆环越满；满格 = 松手即生效。
 *
 * 位移与进度都在绘制阶段读取，不把每帧变化带进组合/测量。
 * 方向决定指示器从哪条边露出：下拉时从顶部空隙往下沉，上拉时从底部空隙往上浮。
 */
@Composable
fun PullReleaseIndicator(
    pullPx: () -> Float,
    thresholdPx: Float,
    direction: PullReleaseDirection,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .size(INDICATOR_SIZE)
            .graphicsLayer {
                val pull = pullPx()
                val fadeIn = INDICATOR_FADE_IN.toPx()
                alpha = if (fadeIn > 0f) (pull / fadeIn).coerceIn(0f, 1f) else 1f
                val progress = if (thresholdPx > 0f) (pull / thresholdPx).coerceIn(0f, 1f) else 0f
                val scale = 0.85f + 0.15f * progress
                scaleX = scale
                scaleY = scale
                // 居中于「已经被拉出来的空隙」：中心 = 半个位移
                val halfPx = INDICATOR_SIZE.toPx() / 2f
                translationY = when (direction) {
                    PullReleaseDirection.Down -> pull * 0.5f - halfPx
                    PullReleaseDirection.Up -> halfPx - pull * 0.5f
                }
            }
            .drawBehind {
                drawCircle(color = surface, radius = size.minDimension / 2f)
                val progress = if (thresholdPx > 0f) {
                    (pullPx() / thresholdPx).coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (progress <= 0f) return@drawBehind
                val stroke = 2.dp.toPx()
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = Size(size.width - stroke * 2f, size.height - stroke * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AppIcon(
            AppIconKind.ChevronRight,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    rotationZ = when (direction) {
                        PullReleaseDirection.Down -> 90f
                        PullReleaseDirection.Up -> -90f
                    }
                },
            tint = accent
        )
    }
}
