// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.MarkdownText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_MENU_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.ilyskyo.blancall.ui.viewmodel.BlankCountWarning
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.PracticeViewModel
import com.ilyskyo.blancall.ui.viewmodel.SectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// ========== 顶部标题滑动切换模式 ==========

internal const val TitleSwipeThreshold = 80f

// 标题拖拽跟手上限（px）：限制可拉范围，避免越拉越远
internal const val MaxTitleDrag = 96f


/** 三种模式的有序列表（用于标题左右滑动循环切换） */
internal val MODE_ORDER = listOf(BlancallMode.SENTENCE, BlancallMode.WORD, BlancallMode.REVERSE)


internal fun nextMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) + 1) % MODE_ORDER.size]


internal fun prevMode(m: BlancallMode): BlancallMode =
    MODE_ORDER[(MODE_ORDER.indexOf(m) - 1 + MODE_ORDER.size) % MODE_ORDER.size]


// ========== 双指缩放字号 ==========

/**
 * 双指捏合缩放：仅当屏幕上有 ≥2 个触点时按缩放增量回调 [onZoomChange]，
 * 单指滑动完全交给下层滚动容器处理，不产生任何手势冲突。
 */
internal fun Modifier.pinchZoom(onZoomChange: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var prevDist = -1f
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (prevDist > 0f && dist > 0f) {
                    val factor = dist / prevDist
                    if (factor.isFinite()) onZoomChange(factor)
                }
                prevDist = dist
            }
        } while (event.changes.any { it.pressed })
    }
}


/** 把整套 Typography 的每个字号按 [scale] 放大（lineHeight 同步），用于练习页字号缩放。 */
internal fun scaledTypography(base: Typography, scale: Float): Typography {
    if (scale <= 0f || scale == 1f) return base
    fun s(ts: TextStyle): TextStyle = ts.copy(fontSize = ts.fontSize * scale, lineHeight = ts.lineHeight * scale)
    return Typography(
        displayLarge = s(base.displayLarge),
        displayMedium = s(base.displayMedium),
        displaySmall = s(base.displaySmall),
        headlineLarge = s(base.headlineLarge),
        headlineMedium = s(base.headlineMedium),
        headlineSmall = s(base.headlineSmall),
        titleLarge = s(base.titleLarge),
        titleMedium = s(base.titleMedium),
        titleSmall = s(base.titleSmall),
        bodyLarge = s(base.bodyLarge),
        bodyMedium = s(base.bodyMedium),
        bodySmall = s(base.bodySmall),
        labelLarge = s(base.labelLarge),
        labelMedium = s(base.labelMedium),
        labelSmall = s(base.labelSmall)
    )
}
