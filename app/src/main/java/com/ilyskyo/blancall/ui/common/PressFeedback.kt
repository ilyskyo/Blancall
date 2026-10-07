// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按压缩放的档位：元素越大，按下去收得越少。
 *
 * 倍数只在 [Motion.Scale] 里定义，这里只选档 ——
 * 改造前四套缩放各写各的（0.84 / 0.96 / 0.97 / 1.15），同一个 app 里
 * 按下去的手感彼此对不上。
 */
enum class PressTier {
    /** 中小容器：面板内条目、按钮。 */
    Container,

    /** 信息卡片。 */
    Card,

    /** 圆形图标按钮：行程短，收得最多才看得见。 */
    Icon,

    /**
     * 悬浮底栏的滑块。
     *
     * 刻意是**缩小**：改造前这里是 1.15× 放大，而「选中」态本身也用放大表达，
     * 按下与选中两种语义撞在同一个视觉上。
     */
    Slider,

    /**
     * 整行宽的条目与行内文字 —— **只变暗，不缩放**。
     *
     * 缩放对这些东西是错的：一整行缩 3% 会同时离开左右两侧边距，读起来像布局错位；
     * 一行文字缩起来则像基线跳了。它们要的是 Apple 那套「按下即压暗」，
     * 亮度反馈仍在、几何不动。
     */
    Plain,
}

private fun PressTier.pressedScale(): Float = when (this) {
    PressTier.Container -> Motion.Scale.container
    PressTier.Card -> Motion.Scale.card
    PressTier.Icon -> Motion.Scale.icon
    PressTier.Slider -> Motion.Scale.slider
    PressTier.Plain -> 1f
}

/**
 * 统一的「按下」反馈：**缩放 + 亮度**，没有涟漪。
 *
 * 为什么不用涟漪：涟漪回答的是「系统收到了一次点击」，
 * 而不是「这个东西被按下去了」。一块卡片或一颗玻璃按钮被按动时，
 * 用户预期它整个跟着手指沉下去（法则五「无涟漪」＋法则四「有重量」）。
 *
 * 为什么必须自己补悬停与焦点高亮：M3 的 `ripple()` 同时兼职画 focus/hover 高亮，
 * 把 `indication` 关掉会连无障碍可见性一起抹掉。所以这里保留三态 ——
 * 按下（缩放＋变暗）、悬停、键盘焦点（后两者只变亮度、不位移）。
 * 也因此**不要**改用全局 `LocalIndication provides null`：
 * 那会把没接入本反馈的组件的焦点框一并删掉。
 *
 * 用法：把 `clickable(..., interactionSource = src, indication = null)` 里那个
 * 同一个 `src` 传进来，并把这个修饰符接在点击修饰符**之前**，
 * 这样 graphicsLayer 包住整块的绘制（卡片内容与描边一起沉下去）。
 *
 * ⚠️ M3 的 `IconButton`／`Card`／`Surface(onClick = …)` 这几个可点击重载**在实现里
 * 把 `indication = ripple()` 写死了**（material3 1.4：IconButton.kt:187、Surface.kt:229），
 * 既不吃 `LocalIndication`、也不给 `indication` 形参 —— 覆盖 local 对它们完全无效。
 * 要换掉涟漪只能自己接管点击：非点击版容器 + `clickable(interactionSource, indication = null)`。
 * 而普通 `Modifier.clickable(...)`（不写 indication 的那 ~20 处散点）确实吃
 * `MaterialTheme` 提供的 `LocalIndication = ripple()`，是一处一处加
 * `indication = null` + 本修饰符清掉的。
 *
 * 减动效下缩放与亮度都退化为瞬时到位（见 [Motion] 与 [MotionFade.alpha]）：
 * 反馈仍在，只是不再动 —— 不会留下「按下去没反应」的空档。
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: MutableInteractionSource,
    tier: PressTier = PressTier.Card,
    enabled: Boolean = true,
): Modifier {
    val pressed = interactionSource.collectIsPressedAsState()
    val hovered = interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed.value) tier.pressedScale() else 1f,
        animationSpec = Motion.press(),
        label = "pressScale"
    )
    // 亮度衰减比弹簧到达终点还快（MotionFade.pressAlpha），
    // 否则会读成「先暗一下、再沉下去」两件事，而不是一次按下。
    val dim by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed.value -> Motion.pressedAlpha
            hovered.value || focused -> Motion.hoverAlpha
            else -> 1f
        },
        animationSpec = MotionFade.alpha(MotionFade.pressAlpha),
        label = "pressDim"
    )
    return this
        .onFocusEvent { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = dim
        }
}

/**
 * 自带 interactionSource 的「点 + 长按」组合修饰符：散点版的 [pressFeedback] + 无涟漪点击。
 *
 * 为什么要有它：关涟漪必须把**同一个** interactionSource 同时交给 clickable 和
 * pressFeedback，而挖空/遮挡配置那些散点手里没有一个现成的可传；逐个在 `when` 分支里
 * 补 `val src` 会把表达式改成语句，改动面比这条约束本身还大。把「记一个 source」封进
 * 组件内部之后，调用点只换一个修饰符名。
 *
 * 谱子仍走 [pressFeedback]：按下=缩放+变暗，悬停与键盘焦点只变暗（涟漪兼职的那两态
 * 不能跟着涟漪一起被关掉）。
 */
@Composable
fun Modifier.combinedPress(
    tier: PressTier = PressTier.Card,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    return this
        .pressFeedback(src, tier, enabled)
        .combinedClickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

/**
 * [combinedPress] 的「只有单击」版：`Modifier.clickable { … }` 的无涟漪替身。
 *
 * 存在理由同 [combinedPress]：`Modifier.clickable { }` 这种尾随 lambda 写法没有
 * `indication` 形参可写，全站三十多处散点因此一直在水波 —— 逐个补 `val src`
 * 要动的是表达式结构，封进组件内部只换一个修饰符名。
 */
@Composable
fun Modifier.pressClick(
    tier: PressTier = PressTier.Card,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    return this
        .pressFeedback(src, tier, enabled)
        .clickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}
