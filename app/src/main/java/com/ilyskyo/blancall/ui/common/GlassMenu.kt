// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 毛玻璃下拉菜单容器：把 M3 `DropdownMenu` 当作定位/边界/滚动引擎，
 * 容器色设透明后内部套上 [GlassMenuCard]，复用 App 同款 4 层玻璃语言
 * （API31+ 真实 backdrop blur + 半透明染色 + 顶部高光 + 底部内阴影 + 1dp 细描边）。
 * 圆角 18.dp。进出场动画交给 M3 `DropdownMenu` 内置的"按锚点计算 transformOrigin 的
 * 缩放 + 淡入"——右对齐菜单的原点即 (1,0) = 触发图标正下方，天然实现"从图标向下弹出"。
 * 不再叠加自定义 `AnimatedVisibility`：那层首帧 0 尺寸会让定位器先摆到锚点右侧再翻转
 * （横向闪跳），且与内置动画双重叠加。
 *
 * 与原生 `DropdownMenu` 的差异：纯玻璃质感、无阴影、统一图标/排版语言。
 *
 * @param expanded 是否展开（false 时不挂载菜单，行为同原 DropdownMenu）
 * @param onDismissRequest 点击菜单外区域时回调（由 DropdownMenu 自动处理，无需调用方写遮罩）
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null
    ) {
        // 宽度随屏幕自适应：手机上占约 82% 宽（更舒展、不再窄条），最大 360dp、最小 260dp
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val menuWidth = minOf(360.dp, maxOf(260.dp, screenW * 0.82f))
        GlassMenuCard(width = menuWidth, content = content)
    }
}

/**
 * 玻璃外框：复制 [GlassCard] 的视觉层次，但作为下拉菜单（固定宽度、可滚动、无点击）。
 * 深色模式下使用更亮的容器色（surfaceContainerHigh），保证在纯黑背景上仍有层次。
 */
@Composable
private fun GlassMenuCard(
    width: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val baseColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    // 菜单比卡片更"实"：浅色 0.93（基本实心，背文不显） / 深色 0.95，
    // 与 GlassBlur.GLASS_MENU_ALPHA_LIGHT 对齐。卡片仍保留 GLASS_ALPHA_LIGHT = 0.72 的玻璃感。
    val bgAlpha = if (isDark) 0.95f else GLASS_MENU_ALPHA_LIGHT
    val stainColor = baseColor.copy(alpha = bgAlpha)
    val highlightColor = if (isDark) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.20f)
    }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .width(width)
            .heightIn(max = 420.dp)
            .clip(shape)
            .border(1.dp, outlineColor.copy(alpha = 0.5f), shape)
    ) {
        // 1) 真实 backdrop blur 层（API31+）：克隆氛围背景并模糊，形成真实玻璃感。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .glassSurface(radiusPx = 22f)
            ) { AmbientBackground() }
        }
        // 2) 半透明染色层：让模糊光斑透出，同时保证文字对比。
        Box(Modifier.matchParentSize().background(stainColor))
        // 3) 顶部高光：玻璃上缘反光。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(
                    Brush.verticalGradient(
                        0f to highlightColor,
                        1f to Color.Transparent
                    )
                )
        )
        // 4) 内容：可滚动，保留竖向留白。
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            content = content
        )
    }
}

/**
 * 玻璃菜单项：固定 20.dp 图标列 + 12.dp 间隙，所有标签左边缘对齐（无论是否有图标）。
 * 按压 / 悬停时以极淡的 onSurface 底色反馈（无涟漪，符合 App 整体克制语言）。
 *
 * @param enabled false 时不可点击、不显示按压底色（用于只读行，如「当前模式」）。
 */
@Composable
fun GlassMenuItem(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        !enabled -> Color.Transparent
        pressed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) { leadingIcon() }
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { label() }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * 玻璃菜单分隔线：0.5dp 细线 + 左右内缩，呼应卡片内分区。
 */
@Composable
fun GlassMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}
