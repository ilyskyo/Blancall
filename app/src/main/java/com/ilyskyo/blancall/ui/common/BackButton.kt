// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
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
    Surface(
        modifier = modifier
            .size(40.dp)
            .pressFeedback(src, PressTier.Icon)
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .semantics { contentDescription = "返回" },
        shape = CircleShape,
        color = if (dark) Color.White.copy(alpha = 0.14f) else Color.White,
        shadowElevation = if (dark) 0.dp else 4.dp,
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
