// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 长文滚动进度徽章（编辑器用）：滚动时右下角悬浮「X / Y 单位」，停止约 800ms 后淡出。
 * 仅长文（total > 8）启用；纯色玻璃质感，不拦截点击（触摸穿透到正文）。
 */
@Composable
fun ScrollProgressBadge(
    listState: LazyListState,
    total: Int,
    unit: String,
    modifier: Modifier = Modifier
) {
    if (total <= 8) return
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "scrollBadgeAlpha"
    )
    // 滚动中显示；停止后延时淡出（滚动中每跨一段都会重置计时）
    LaunchedEffect(listState.isScrollInProgress, listState.firstVisibleItemIndex) {
        if (listState.isScrollInProgress) {
            visible = true
        } else {
            delay(800)
            visible = false
        }
    }
    if (alpha > 0.01f) {
        Box(
            modifier = modifier
                .alpha(alpha)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "${listState.firstVisibleItemIndex + 1} / $total $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
