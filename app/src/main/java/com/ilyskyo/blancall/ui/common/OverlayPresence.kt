// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * 弹窗 / 底部面板「退场期间继续存在」的宿主。
 *
 * 调用方过去写 `if (flag) BlancallAlertDialog(...)`：按钮把 flag 置 false 时，
 * 整个 Dialog 同帧出组合，退场动画根本没有机会播。组件自己挡不住被父级移除，
 * 所以由这个宿主把「是否显示」和「是否仍占着组合」拆开：
 *
 * - [target] 非空 → 记录内容并保证子树在场；
 * - [target] 变空 → 子树仍在场，通过 [LocalOverlayExitRequested] 通知浮层开始退场；
 * - 浮层播完（或减动效下立即）调用 [LocalOverlayExitFinished] → 宿主才移除子树。
 *
 * 这样调用点只需把原来的 `if` / `?.let` 换成宿主，按钮、成功页、副作用驱动的关闭
 * 走的是同一条退场路径。
 *
 * 注意：子树里的浮层必须接入 [LocalOverlayExitRequested]（[BlancallAlertDialog] 与
 * [GlassModalBottomSheet] 已接入）。普通 Composable 放进来会在 target 变空后永远留存。
 */
@Composable
fun <T : Any> AnimatedOverlayPresence(
    target: T?,
    onExitFinished: () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    // 保留最后一次非空内容：target 置空的同一帧就要开始退场，内容不能先变成 nothing。
    var lastTarget by remember { mutableStateOf(target) }
    var exitFinished by remember { mutableStateOf(false) }
    if (target != null) {
        lastTarget = target
        exitFinished = false
    }
    val shown = target ?: lastTarget ?: return
    val exitRequested = target == null
    if (exitRequested && exitFinished) return

    // 兜底：浮层可能永远不回执（退场动画被取消，或组件根本没接这条协议）。
    // 那时它的窗口会一直留在组合里 —— 看不见，却把整页的点击全吃掉，
    // 用户看到的就是「页面还在，但哪儿都点不动」。宁可少播半段动画，也不能留下这层壳。
    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        delay(EXIT_WATCHDOG_MS)
        if (!exitFinished) {
            exitFinished = true
            onExitFinished()
        }
    }

    // 回执 lambda 每次组合都是新建的，而 staticCompositionLocal 不会因为值变了就重组消费者：
    // 闭包里必须读「当前」的 target，否则consumer 拿着上一帧的 lambda 会以为还没开始退场。
    val latestTarget = rememberUpdatedState(target)
    CompositionLocalProvider(
        LocalOverlayExitRequested provides exitRequested,
        LocalOverlayExitFinished provides {
            if (latestTarget.value == null && !exitFinished) {
                exitFinished = true
                onExitFinished()
            }
        },
    ) {
        content(shown)
    }
}

/** 退场回执的兜底等待：比最长的一段退场动画宽松，又不至于让人等出「卡死」的错觉。 */
private const val EXIT_WATCHDOG_MS = 900L

/** Boolean 条件版的宿主；内容和关闭状态无关时用它，避免写 `if (flag) Unit else null`。 */
@Composable
fun AnimatedOverlayPresence(
    visible: Boolean,
    onExitFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AnimatedOverlayPresence(
        target = if (visible) Unit else null,
        onExitFinished = onExitFinished,
    ) { content() }
}

/**
 * 浮层开始退场的信号。没有宿主时恒为 false，组件保持原有行为。
 */
internal val LocalOverlayExitRequested = staticCompositionLocalOf { false }

/**
 * 浮层退场结束的回执。容器在 [AnimatedVisibility] 内容被移除时调用；
 * 减动效下由容器直接调用，不等一个不会到来的动画回调。
 */
internal val LocalOverlayExitFinished = staticCompositionLocalOf<(() -> Unit)?> { null }
