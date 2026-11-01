// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 浮条圆角：与卡片同一族的「大圆角」，但因为面积小，视觉上是一条胶囊。 */
private val ToastShape = RoundedCornerShape(16.dp)

/**
 * 底部浮条：报告刚才发生了什么，并在可撤销时把「撤销」摆在伸手就够得着的地方。
 *
 * 为什么不用系统 Toast / Snackbar：
 * - 系统 Toast 在 Android 11+ 只读不可点，撤销按钮放不进去；
 * - M3 Snackbar 自带 material 的方形观感与阴影层，和本应用的纯色卡片语言不是一套。
 *
 * 交互：**整条可点**（[PressTier.Plain]，只压暗不缩放 —— 整行宽的条缩一下像布局错位），
 * 右侧那颗「撤销」是给可发现性的按钮，点哪儿都一样。
 *
 * 配色用 inverseSurface / inverseOnSurface：浅色主题下是深底浅字、深色主题下反过来，
 * 与页面上的卡片天然分层，不需要再叠一层模糊或渐变（本项目禁止 gradient）。
 *
 * [visible] 由调用方控制，自动消失的计时也在调用方 —— 停留多久取决于这条提示能不能撤销，
 * 那是语义决定的，不是这个纯展示组件该猜的。
 */
@Composable
fun UndoToast(
    message: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    undoLabel: String? = null,
    onUndo: (() -> Unit)? = null,
) {
    val canUndo = undoLabel != null && onUndo != null
    // 智能转换在这里不可用（可空值来自参数、被 lambda 捕获），取局部别名后判定一次
    val undo = onUndo
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // 两头都写明谱子：不写给库默认值时，减动效下不会坍缩（见 TransitionGuardTest）
        enter = slideInVertically(Motion.toastSlide()) { height -> height } +
            fadeIn(MotionFade.alpha(MotionFade.enter)),
        exit = slideOutVertically(Motion.toastSlide()) { height -> height } +
            fadeOut(MotionFade.alpha(MotionFade.exit))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 阴影先画、裁切后画：clip = false 让阴影留在形状之外，再由 clip 收住内容
                .shadow(
                    elevation = 14.dp,
                    shape = ToastShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.18f),
                    spotColor = Color.Black.copy(alpha = 0.14f)
                )
                .clip(ToastShape)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .then(
                    if (canUndo && undo != null) {
                        Modifier.pressClick(tier = PressTier.Plain, onClick = undo)
                    } else {
                        Modifier
                    }
                )
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f)
            )
            if (canUndo && undo != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.16f))
                        .pressClick(tier = PressTier.Container, onClick = undo)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = undoLabel.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }
    }
}
