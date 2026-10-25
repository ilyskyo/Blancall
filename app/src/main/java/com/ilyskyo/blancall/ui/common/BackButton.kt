// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.theme.isBlancallDark

/**
 * 页面顶部返回按钮：圆底 + 返回箭头，**颜色随明暗模式翻转**。
 *
 * 深色模式刻意不给白底：人开深色就是为了在保住内容可读的前提下尽量少发光，
 * 一块不透明白圆本身就是屏幕上的光源，所以底改成半透明中性（叠在深色栏上读作
 * 深灰）、箭头取白；浅色模式仍是白底黑图 + 阴影撑边界。
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // M3 的 `Surface(onClick = …)` 把 `indication = ripple()` 写死在实现里
    // （material3 1.4 Surface.kt:229），既不吃 LocalIndication、也没有 indication 形参 ——
    // 覆盖 LocalIndication 对它无效，想真的换掉涟漪只能自己接管点击：
    // 非点击版的 Surface 只负责白底 + 阴影，点击走 clickable(indication = null)，
    // 反馈（缩放／亮度／焦点／悬停）统一由 pressFeedback 给。
    val src = remember { MutableInteractionSource() }
    val dark = isBlancallDark()
    // semantics {} 的 lambda 不是 @Composable，stringResource 只能在组合期先取好
    val backLabel = stringResource(com.ilyskyo.blancall.R.string.back)
    // 按下时**投影跟着收**，不用变暗：
    // pressFeedback 的亮度是靠 graphicsLayer 的 alpha 实现的，而 alpha≠1 会把本节点
    // 推进离屏合成缓冲，圆形 elevation 投影在缓冲里按**方形的层边界**重新光栅化 ——
    // 静止时看着是圆的，手指一按下去立刻浮出一圈方的阴影（用户 2026-10-09 截图指出的就是这个，
    // 实测：按下时圆盘上方的柔和渐变消失、四边出现 226 灰的直边）。
    // 所以这里关掉变暗，改压 elevation：4dp → 1.5dp，一块被按下去的圆盘本来就该离页面更近，
    // 缩放 + 阴影收拢读起来仍是「沉下去」这一件事，而且不再产生方框。
    val pressed by src.collectIsPressedAsState()
    val lift by animateFloatAsState(
        targetValue = if (pressed) 1.5f else 4f,
        animationSpec = Motion.press(),
        label = "backButtonLift",
    )
    Surface(
        modifier = modifier
            .size(40.dp)
            .pressFeedback(
                src,
                PressTier.Icon,
                layerShape = CircleShape,
                dimOnPress = false,
                hapticTier = HapticTier.Toggle,
            )
            // 这里**不能**再加 .clip(CircleShape)：clip 挂在 Surface 外面会把 Surface
            // 自己画的 elevation 投影裁掉（投影画在圆的外侧），结果是白页面上白圆盘
            // 完全没有边界 —— 看着就像一颗裸箭头。v7.2.4 没有这层 clip 所以是好的；
            // 内容裁圆交给下面 shape = CircleShape 自己做，够用。
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .semantics { contentDescription = backLabel },
        shape = CircleShape,
        color = if (dark) Color.White.copy(alpha = 0.14f) else Color.White,
        shadowElevation = if (dark) 0.dp else lift.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = if (dark) Color.White else Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
