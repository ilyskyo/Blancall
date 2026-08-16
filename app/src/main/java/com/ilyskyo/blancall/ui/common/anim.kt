// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 列表项入场动画：按 index 错峰淡入 + 轻微上滑。
 * 仅作用于静态展示（visible 恒为 true），不改点击 / 导航 / 数据流向。
 */
fun listItemEnter(index: Int) = fadeIn(tween(220, delayMillis = minOf(index * 30, 320)))
    .plus(slideInVertically(initialOffsetY = { it / 6 }))

/**
 * 长按触觉反馈句柄。在被改的 `onLongClick` 中调用
 * `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` 即可。
 */
@Composable
fun rememberHaptic() = LocalHapticFeedback.current

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
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties? = null,
    content: @Composable (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties ?: DialogProperties()) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(tween(160)),
            exit = scaleOut(targetScale = 0.96f) + fadeOut(tween(120))
        ) {
            Surface(
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
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
                        Spacer(modifier = Modifier.height(24.dp))
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
