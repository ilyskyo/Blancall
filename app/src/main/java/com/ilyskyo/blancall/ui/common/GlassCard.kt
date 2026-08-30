// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 卡片容器（无渐变版）：半透明染色 + 细描边 + 大圆角，
 * 可选 backdrop 模糊层（API31+）。已移除顶部高光与底部内阴影两类渐变装饰。
 *
 * 与早期 GlassCard 的差异：
 * - 不再叠加 verticalGradient 高光与底部内阴影，整体视觉回归纯色面
 * - 半透明 surface（浅色 0.66 / 深色 0.75）保持不变，颜色由调用方主题决定
 * - 自带 1dp 细描边，调用方无需再传 border（避免双边框）
 * - onClick 非空时整卡可点击（无涟漪，内容区交互不受影响）；onClick + onLongClick 时支持长按
 *
 * 实现为纯 Modifier 组合（clip + background + border），
 * 不依赖 Outline 内部结构，跨 Compose 版本稳定。
 *
 * 用于首页与统计页的信息卡片容器；内容结构保持与 Card 一致（ColumnScope）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color? = null,
    containerAlpha: Float? = null,
    borderColor: Color? = null,
    // 是否渲染「克隆氛围背景 + 真实模糊」的毛玻璃背板。
    // 默认 true（首页/统计页等少量卡片保留高级磨砂质感）；
    // 列表等长列表场景传 false，改用纯半透明染色层，避免每张卡各跑一次 GPU 模糊导致进入页面卡顿。
    backdrop: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isBlancallDark()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    // 玻璃不透明度下调（深色 0.68 / 浅色 0.66，见 GlassBlur.kt）：保留半透明层次。
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    // 染色层颜色：自定义容器色保持原色相；未提供时用 surface 按玻璃不透明度染色。
    // containerAlpha 允许调用方保留自己的透明度语义（如选择态 primaryContainer 0.3）。
    val stainColor = if (containerColor != null) {
        containerColor.copy(alpha = containerAlpha ?: (if (isDark) 0.88f else 0.80f))
    } else {
        surfaceColor.copy(alpha = bgAlpha)
    }

    // 点击修饰符：仅 onClick → clickable；onClick + onLongClick → combinedClickable（如文章卡片）
    // interactionSource 可外部传入（调用方需要自绘按压反馈时，如 ModeCard 的按压缩放）
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val clickModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            interactionSource = src,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
        onClick != null -> Modifier.clickable(
            interactionSource = src,
            indication = null,
            onClick = onClick
        )
        else -> Modifier
    }

    Box(
        modifier = modifier
            .then(clickModifier)
            // 大圆角裁切 → 细描边（背景由下方分层绘制，避免裁切边缘漏出实心）
            .clip(shape)
            .border(1.dp, (borderColor ?: outlineColor).copy(alpha = 0.5f), shape)
    ) {
        // 1) backdrop 真实模糊层（API31+）：克隆氛围背景并裁剪到卡片形状后施加玻璃模糊；
        //    AmbientBackground 已退化为纯色容器，模糊出的仍是纯色面但保留 GPU blur 的层次。
        //    backdrop=false 时长列表场景跳过本层，仅保留下方半透明染色。
        if (backdrop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .glassSurface(radiusPx = 18f)
            ) { AmbientBackground() }
        }
        // 2) 半透明染色层：保证文字与卡片背景的对比
        Box(Modifier.matchParentSize().background(stainColor))
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
