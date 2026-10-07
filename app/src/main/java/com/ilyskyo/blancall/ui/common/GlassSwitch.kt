// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.theme.isBlancallDark

/**
 * 原生风格开关：全 App 统一开关组件。
 *
 * 参照系统开关样式：实心胶囊轨道（开启=强调色 / 关闭=灰）+ 纯白圆形滑块，
 * 滑块略大于轨道厚度上限并带 1dp 投影，形成"浮起"立体感；切换时滑块位移与轨道颜色
 * 同步弹性动画。
 *
 * 交互：indication 设为 null，彻底取消点击矩形波纹/焦点框，像系统原生开关那样点按只切换状态。
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
        // 底色是**颜色**，按词表走 tween（原来是一条裸 `spring(stiffness = 900f)` ——
        // 弹簧对颜色没有物理意义，只会让它在终点附近再抖一下）
        animationSpec = MotionFade.color(MotionFade.enter),
        label = "switchTrackColor"
    )

    // 尺寸对齐系统开关：约 52x32，胶囊比例 1.625，滑块占满轨道高度只留 2dp 边距
    val trackW = 52.dp
    val trackH = 32.dp
    val gap = 2.dp
    val thumbD = trackH - gap * 2          // 28dp 正圆
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackW - gap - thumbD else gap,
        // 拇指平移是位移 → 弹簧（Motion.toggleTravel：约 20dp 行程，利落带一点回弹）
        animationSpec = Motion.toggleTravel(),
        label = "switchThumbX"
    )

    // 开关是全局最沉默的一类交互：改造前任何开关都「不响」。
    // Toggle 档 = 10ms/90 的轻击，只在用户真的翻了一格时给，程序回写不经过这里。
    val flipHaptic = rememberHaptic(HapticTier.Toggle)

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(trackW, trackH)
            .clip(CircleShape)
            // toggleable 而非 clickable：除点击外还带 ToggleableState 语义，
            // 无屏阅读（TalkBack）能播报「开 / 关」状态；视觉与交互完全不变（无指示器、弹动同前）
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && onCheckedChange != null,
                role = Role.Switch,
                onValueChange = {
                    flipHaptic()
                    onCheckedChange?.invoke(it)
                }
            )
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(Modifier.matchParentSize().background(trackColor, CircleShape))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                // 平移走 translationX，不走 offset{}：offset 改的是「摆放」，每帧重排整棵树；
                // graphicsLayer 只重绘，弹簧跑动期间不占布局预算（法则六 / S4）。
                .graphicsLayer { translationX = thumbX.toPx() }
                .size(thumbD)
                // 1dp 投影：让白滑块在轨道上"浮起来"（图 2 的体感来源）
                .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                .background(Color.White, CircleShape)
        )
    }
}
