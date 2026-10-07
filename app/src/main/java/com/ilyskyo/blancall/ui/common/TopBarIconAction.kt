// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 顶栏图标操作按钮（**固定尺寸槽位**）。
 *
 * 为什么必须固定尺寸：撤销/重做这类按钮如果按「栈是否非空」条件渲染，
 * 重做按钮一出现就会把整组按钮挤动 —— 也就是「点一下撤销，撤销键位置就跳」的根因。
 * 因此这里**始终占位**：不可用时只降低图标透明度并禁用点击，尺寸与位置恒定。
 *
 * @param enabled 不可用时置灰且不可点，但**不改变占位**
 */
@Composable
fun TopBarIconAction(
    kind: AppIconKind,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    // 图标按钮行程短，用最大那一档收缩（Motion.Scale.icon）才读得出「按下去了」；
    // 焦点与悬停的高亮由 pressFeedback 补回，关涟漪不连带失去无障碍可见性。
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(36.dp)
            .pressFeedback(src, PressTier.Icon, enabled = enabled)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(
            kind = kind,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f),
            contentDescription = contentDescription
        )
    }
}
