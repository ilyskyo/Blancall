// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.theme.isBlancallDark

/**
 * iOS 风格开关：全 App 统一开关组件。
 *
 * 参照系统开关样式：实心胶囊轨道（开启=强调色 / 关闭=灰）+ 纯白圆形滑块，
 * 滑块略大于轨道厚度上限并带 1dp 投影，形成"浮起"立体感；切换时滑块位移与轨道颜色
 * 同步弹性动画。
 *
 * 交互：indication 设为 null，彻底取消点击矩形波纹/焦点框，像 iOS 那样点按只切换状态。
 *
 * @param accent 开启态轨道色（阅读页等深色场景传强调色，默认主题主色）
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val isDark = isBlancallDark()
    val trackColor by animateColorAsState(
        targetValue = when {
            checked -> accent
            isDark -> Color(0xFF3A3A3C)
            else -> Color(0xFFE3E3E8)
        },
        animationSpec = spring(stiffness = 900f),
        label = "switchTrackColor"
    )

    // 尺寸对齐 iOS UISwitch：约 52x32，胶囊比例 1.625，滑块占满轨道高度只留 2dp 边距
    val trackW = 52.dp
    val trackH = 32.dp
    val gap = 2.dp
    val thumbD = trackH - gap * 2          // 28dp 正圆
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackW - gap - thumbD else gap,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
        label = "switchThumbX"
    )

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(trackW, trackH)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                onClick = { onCheckedChange?.invoke(!checked) }
            )
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(Modifier.matchParentSize().background(trackColor, CircleShape))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(thumbD)
                // 1dp 投影：让白滑块在轨道上"浮起来"（图 2 的体感来源）
                .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                .background(Color.White, CircleShape)
        )
    }
}
