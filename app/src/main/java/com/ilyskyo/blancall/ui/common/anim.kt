// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 列表项入场动画：按 index 错峰淡入 + 轻微上滑。
 *
 * **必须配 [rememberEnteredOnFirstFrame] 使用**：AnimatedVisibility 在首次组合就拿到目标值时
 * 根本不播 enter（直接以终态静态出现），所以 visible 不能恒为 true —— 那样这段入场是死代码，
 * 屏幕上看到的「没有入场动画」其实是「入场动画写了但永远不会跑」。
 *
 * 上滑的谱子在 [Motion.itemSlide] 里保持 NoBouncy + StiffnessMedium，
 * 与本函数改造前未传 spec 时走的库默认 spring() 是同一组参数 —— 观感不变。
 */
fun listItemEnter(index: Int) =
    fadeIn(MotionFade.alpha(MotionFade.itemEnter, delayMillis = staggerDelay(index)))
        .plus(slideInVertically(animationSpec = Motion.itemSlide(), initialOffsetY = { it / 6 }))

/**
 * 首帧入场开关：先以 false 组合一帧，下一帧才翻成 true。
 *
 * 「常驻可见 + 想播入场」只能靠一次真正的状态变化来触发：同一次组合里连着赋值两次不算
 * （Compose 一帧只应用最后一个值，等于从没变过），所以中间要真的跨过一帧 ——
 * [withFrameNanos] 就是那道栅栏。
 *
 * 用 rememberSaveable 而不是 remember：旋转后开关已经是 true，入场不会重放
 * —— 用户不该在转屏时把已经看过的卡片再看一遍飞入。
 */
@Composable
fun rememberEnteredOnFirstFrame(): Boolean {
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        entered = true
    }
    return entered
}

/**
 * 带弹簧入场 / 退场的 AlertDialog（drop-in 替换 M3 `AlertDialog`）。
 *
 * 仅把内层 M3 卡片包进 [AnimatedVisibility]（scale + fade 弹簧），
 * 原 `title` / `text` / `confirmButton` / `dismissButton` 等参数全部透传，行为零变化。
 * 为避免 Dialog 嵌套，内层用 [Surface] 复刻 M3 对话框的卡片外观。
 *
 * 注意：ModalBottomSheet 已有官方动画，请勿用本组件包裹。
 */
@Composable
fun BlancallAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color? = null,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties? = null,
    content: @Composable (() -> Unit)? = null,
    // text 块之后的底部间距；当按钮直接放在 text 内部时传 0.dp，避免按钮下方出现 24dp 空白
    textBottomSpacing: Dp = 24.dp
) {
    // 由本组件发起的关闭（点遮罩、按返回）不再立刻通知调用方，而是等退场播完 —— 见下方注释。
    var dismissRequested by remember { mutableStateOf(false) }
    var exitPlayed by remember { mutableStateOf(false) }
    // 关闭回执：点遮罩/按返回是用户自己发起的「我要关掉」，给轻的一下。
    // 按钮路径不走这里（调用方直接翻 flag），所以不会与按钮自己的回执叠成两下。
    val dismissHaptic = rememberHaptic(HapticTier.Toggle)
    Dialog(onDismissRequest = { dismissRequested = true }, properties = properties ?: DialogProperties()) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        // ── 延后撤销 ──
        // 调用方一律写作 `if (flag) BlancallAlertDialog(...)`：flag 一翻，本组件同帧出组合，
        // Dialog 窗口连带被拆 —— 于是下面那行 exit **永远不会播**（改造前它是死代码，
        // 约 30 个界面的关闭都是「凭空消失」，用户读作卡了一下）。
        // 组件挡不住自己被移出组合，但**由 Dialog 自己发起**的关闭可以：
        // 先把退场播完，等 AnimatedVisibility 把内容层 dispose 的那一刻再通知调用方撤销窗口，
        // 期间 flag 仍是 true，所以退场有地方可播。30 个调用点一行都不用改。
        // 减动效下走「跳过动画」而不是「延后 dismiss」：不等一个不会到来的完成信号，
        // 否则界面会永久停在「正在退出」里 —— 比没有动画更糟。
        LaunchedEffect(dismissRequested) {
            if (dismissRequested) {
                dismissHaptic()
                visible = false
            }
        }
        LaunchedEffect(dismissRequested, exitPlayed) {
            if (dismissRequested && (exitPlayed || !Motion.exitsAreAnimated)) onDismissRequest()
        }
        AnimatedVisibility(
            visible = visible,
            // 位移=弹簧、alpha=tween（词表见 Motion / MotionFade）。
            // 入场原来是 MediumBouncy + StiffnessLow（约 900ms 才稳），换成 Motion.press()
            // 后到达更快、尾段只留一次极小回弹 —— 对话框是被「按」出来的，不是飘出来的。
            enter = scaleIn(
                initialScale = Motion.Scale.enterFrom,
                animationSpec = Motion.press()
            ) + fadeIn(MotionFade.alpha(MotionFade.enter)),
            exit = scaleOut(
                targetScale = Motion.Scale.dismissTo,
                animationSpec = Motion.press()
            ) + fadeOut(MotionFade.alpha(MotionFade.exit))
        ) {
            // 退场播完的唯一可靠信号：内容层被 AnimatedVisibility 移出组合。
            // 用固定时长猜完成时刻会随弹簧参数漂移，这条不会。
            DisposableEffect(Unit) {
                onDispose { exitPlayed = true }
            }
            // 玻璃面板：默认半透明染色（深色暖黑 0.94 / 浅色白 0.95）+ 1dp 高光描边，
            // 在 Dialog 的暗色 scrim 上呈现毛玻璃质感；显式传 containerColor 时保留调用方语义（如错误色）
            val glassColor = if (isBlancallDark()) Color(0xF01C1C1E) else Color(0xF2FFFFFF)
            Surface(
                shape = shape,
                color = containerColor ?: glassColor,
                tonalElevation = tonalElevation,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    if (title != null) {
                        Box(modifier = Modifier.fillMaxWidth()) { title() }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (text != null) {
                        Box(modifier = Modifier.fillMaxWidth()) { text() }
                        Spacer(modifier = Modifier.height(textBottomSpacing))
                    }
                    content?.let {
                        it()
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.let {
                            it()
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}


/**
 * 全屏 Dialog：淡入 + 14dp 上移，退场原路返回，窗口由本组件自己决定何时拆。
 *
 * 为什么补间器要住在这一层：调用方过去在自己的页面组合里读 `alpha > 0f` 当「窗口还在不在」
 * 的判据，于是淡入的每一帧都重组整页 —— 阅读页与导入页都是上千行的骨架，逐帧重组就是掉帧。
 * 现在动画值只在下面的 graphicsLayer 里被读，只有绘制失效，页面本体不再逐帧重组。
 *
 * 撤销判据仍然用 `alpha > 0f` 而不是等一个完成回调：完成信号可能因为导航离开 / 配置变更
 * 而永远不来（sheet 悬死那次的教训），而 alpha 归零是必然事件。减动效时
 * [MotionFade.alpha] 给的是 snap，alpha 同帧到 0、窗口同帧拆 —— 是「跳过动画」，
 * 不是「等一个不会到来的信号」。
 */
@Composable
fun FadeDialogWindow(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 名字不能就叫 alpha：graphicsLayer 的 lambda 里 `alpha` 会被解析成
    // GraphicsLayerScope 自己的属性，`alpha = alpha` 就成了往 val 上赋值。
    val windowAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MotionFade.alpha(MotionFade.enter),
        label = "dialogFade"
    )
    val windowTravel by animateDpAsState(
        targetValue = if (visible) 0.dp else 14.dp,
        animationSpec = Motion.dialogTravel(),
        label = "dialogTravel"
    )
    if (visible || windowAlpha > 0f) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = windowAlpha; translationY = windowTravel.toPx() },
                color = MaterialTheme.colorScheme.background
            ) {
                content()
            }
        }
    }
}
