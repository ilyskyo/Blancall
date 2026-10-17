// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.theme.Macaron

/**
 * 错误类型分布条形图（公共组件）。
 *
 * 将六类错误（记成他字 / 字形混淆 / 漏填 / 多填 / 顺序错误 / 没记住）的计数合并展示为横向比例条，
 * 总数内部自动汇总，仅渲染计数大于 0 的项。OverviewScreen 与 StatisticsScreen 共用。
 *
 * 「记成他字」对应 AnswerChecker 的 TYPO：长度一致、同一位置换成了不相干的字；
 * 「字形混淆」对应 GLYPH_CONFUSED：换上的字与正字同属一个形近组（己/已/巳）。
 * 判题只看字形是否相同，不比对读音，所以这里可能是手上写错也可能是没记清 ——
 * 两者分开列，用户才知道该练辨析还是该补记忆。
 *
 * 文案一律走 string 资源：新加的类别若在代码里写死中文，六语界面上就这一行是简体，
 * 而所有多语言守卫都是绿的（它们只比 res 目录）。
 *
 * @param typo       记成他字次数
 * @param glyph      字形混淆次数
 * @param missing    漏填次数
 * @param extra      多填次数
 * @param order      顺序错误次数
 * @param incorrect  没记住次数（编辑距离过大，非前几类）
 * @param modifier   外部约束
 */
@Composable
fun MistakeBar(
    typo: Int,
    missing: Int,
    extra: Int,
    order: Int,
    incorrect: Int = 0,
    glyph: Int = 0,
    modifier: Modifier = Modifier
) {
    val total = typo + glyph + missing + extra + order + incorrect
    if (total <= 0) return

    Column(modifier = modifier) {
        if (typo > 0) {
            MistakeRow(stringResource(R.string.mistake_bar_typo), typo, total, MaterialTheme.colorScheme.error)
        }
        if (glyph > 0) {
            // 薰衣草：与「记成他字」的红同为换字类、但色相独立 —— 两行挨在一起，
            // 颜色必须能分开，否则用户读成「同一类错分成两行」。
            MistakeRow(stringResource(R.string.mistake_bar_glyph), glyph, total, Macaron.lavender().accent)
        }
        if (missing > 0) {
            MistakeRow(stringResource(R.string.mistake_bar_missing), missing, total, MaterialTheme.colorScheme.tertiary)
        }
        if (extra > 0) {
            MistakeRow(stringResource(R.string.mistake_bar_extra), extra, total, MaterialTheme.colorScheme.secondary)
        }
        if (order > 0) {
            MistakeRow(stringResource(R.string.mistake_bar_order), order, total, MaterialTheme.colorScheme.primary)
        }
        if (incorrect > 0) {
            MistakeRow(stringResource(R.string.mistake_bar_incorrect), incorrect, total, MaterialTheme.colorScheme.errorContainer)
        }
    }
}

/**
 * 单条错误比例条。
 */
@Composable
private fun MistakeRow(label: String, count: Int, total: Int, color: Color) {
    val fraction = count.toFloat() / total
    // 入场动画：进度条从 0 生长到目标比例
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val animatedFraction by animateFloatAsState(
        targetValue = if (played) fraction else 0f,
        // 条形图的数值揭示 → 词表的 chartReveal 档（改造前是这里独有一个 600ms）
        animationSpec = MotionFade.number(MotionFade.chartReveal),
        label = "mistakeBar"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    // 进度长度放在**绘制期**算：改造前是 `fillMaxWidth(animatedFraction)`，
                    // 每次数值变化都要重测自己与同一行右侧的计数文字。
                    // 这里也没用 scaleX —— 那会把 6dp 圆角一起拉扁；drawRoundRect 画多大就是多大。
                    .drawBehind {
                        drawRoundRect(
                            color = color.copy(alpha = 0.75f),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            size = Size(size.width * animatedFraction, size.height),
                        )
                    }
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${count}次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
    }
}
