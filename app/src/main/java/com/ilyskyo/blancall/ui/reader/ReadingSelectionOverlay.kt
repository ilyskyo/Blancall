// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.repository.ReadingMark
import com.ilyskyo.blancall.ui.common.pressClick
import kotlin.math.max
import kotlin.math.roundToInt

/** 当前选区：[p] 段（splitParagraphs 口径）内 `[a, e)` */
internal data class ReadingSelectionRange(val p: Int, val a: Int, val e: Int) {
    val isEmpty: Boolean get() = e <= a
}

/**
 * 选区覆盖层：画高亮 + 两端手柄 + 浮在选区上方的操作条。
 *
 * 为什么单独一层而不是直接画在段落里：手柄得伸到行外（上方/下方），段落的绘制会被自身
 * 边界裁掉；操作条也要压在所有段落之上。这一层挂在正文根 Box 上，用
 * [ParagraphAnchor.topLeft] 把段内坐标换算成根坐标。
 *
 * 交互约定（与系统文本选择一致的手感）：
 * - 拖任一端手柄 → 逐字扩/缩选区（不跨段，段落就是划线的存储粒度）
 * - 点空白 → 收起选区（不穿透到正文，避免顺手把悬浮控件也切了）
 * - 点操作条 → 复制 / 划线（或取消划线）/ 批注
 */
@Composable
internal fun ReadingSelectionOverlay(
    anchor: ParagraphAnchor,
    selection: ReadingSelectionRange,
    accent: Color,
    isDark: Boolean,
    /** 与当前选区完全重合的已有记录（用于把「划线」切换成「取消划线」） */
    coveringMark: ReadingMark?,
    onRangeChange: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onUnhighlight: () -> Unit,
    onNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 9.dp.toPx() }
    val hitRadius = with(density) { 30.dp.toPx() }
    val barGap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }

    // 拖动过程中 selection 每帧变化：必须用 rememberUpdatedState 让 pointerInput(Unit)
    // 读到最新值 —— 否则 pointerInput 的 key 变化会重启手势协程，拖到一半就断。
    val selState by rememberUpdatedState(selection)
    val onRangeChangeState by rememberUpdatedState(onRangeChange)
    val onDismissState by rememberUpdatedState(onDismiss)

    val rects = remember(anchor, selection.a, selection.e) {
        paragraphRects(anchor.layout, selection.a, selection.e).map {
            it.translate(anchor.topLeft.x, anchor.topLeft.y)
        }
    }
    if (rects.isEmpty()) return

    val first = rects.first()
    val last = rects.last()
    val startHandle = Offset(first.left, first.bottom + handleRadius * 0.55f)
    val endHandle = Offset(last.right, last.bottom + handleRadius * 0.55f)

    // 操作条实测尺寸（首帧为 0 → 先用估算值，量到后再校正一格）
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val estBarW = with(density) { 210.dp.toPx() }
    val estBarH = with(density) { 42.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val parentW = constraints.maxWidth.toFloat()
        val parentH = constraints.maxHeight.toFloat()
        val barW = if (barSize.width > 0) barSize.width.toFloat() else estBarW
        val barH = if (barSize.height > 0) barSize.height.toFloat() else estBarH

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 手柄优先：手指落在手柄命中圈里就进入拖动
                        val which = when {
                            (down.position - startHandle).getDistance() <= hitRadius -> 0
                            (down.position - endHandle).getDistance() <= hitRadius -> 1
                            else -> -1
                        }
                        if (which >= 0) {
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                if (!ch.pressed) break
                                val off = anchor.layout.getOffsetForPosition(ch.position - anchor.topLeft)
                                val cur = selState
                                val len = anchor.text.length
                                if (which == 0) {
                                    val newA = off.coerceIn(0, cur.e - 1)
                                    if (newA != cur.a) onRangeChangeState(newA, cur.e)
                                } else {
                                    val newE = (off + 1).coerceIn(cur.a + 1, len)
                                    if (newE != cur.e) onRangeChangeState(cur.a, newE)
                                }
                            }
                        } else {
                            // 点空白 = 收起；点选区内部不动（留给操作条）
                            var moved = false
                            val start = down.position
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if ((ch.position - start).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    // 选着的时候不许滚动正文：选区坐标是长按那一刻的快照，
                                    // 内容一滚，手柄和高亮就会停在原来的位置上（画在字外）。
                                    ch.consume()
                                }
                                if (!ch.pressed) {
                                    if (!moved && rects.none { it.contains(start) }) {
                                        // 整段吞掉这一下：收起选区时不该顺带切换悬浮控件
                                        down.consume()
                                        ch.consume()
                                        onDismissState()
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            // ── 选区底色 ──
            rects.forEach { r ->
                drawRoundRect(
                    color = accent.copy(alpha = if (isDark) 0.34f else 0.22f),
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    cornerRadius = CornerRadius(r.height * 0.18f)
                )
            }
        }

        // ── 操作条：默认贴选区上方；上方放不下就翻到下方 ──
        // 放下方时再额外让开一个手柄的高度：操作条压在选区下缘会把末端手柄整个盖住，
        // 而手柄恰恰是"扩选"的唯一入口（实测过：盖住以后只能重新长按）。
        val barX = (first.left + last.right) / 2f - barW / 2f
        val aboveY = first.top - barH - barGap
        val belowY = last.bottom + barGap + handleRadius * 2.2f
        // 上方放不下才翻到下方；下方也放不下（选区贴着屏幕底部）就夹回可见区域，
        // 宁可稍微压住一点正文，也不能把三个操作按钮推到屏幕外
        val barY = (if (aboveY >= margin) aboveY else belowY)
            .coerceIn(margin, max(margin, parentH - barH - margin))
        SelectionActionBar(
            accent = accent,
            isDark = isDark,
            coveringMark = coveringMark,
            onCopy = onCopy,
            onHighlight = onHighlight,
            onUnhighlight = onUnhighlight,
            onNote = onNote,
            modifier = Modifier
                .onSizeChanged { barSize = it }
                .offset {
                    val maxX = max(margin, parentW - barW - margin)
                    IntOffset(barX.coerceIn(margin, maxX).roundToInt(), barY.roundToInt())
                }
        )

        // ── 两端手柄：画在操作条之上（自绘：细杆 + 圆点）──
        // 单独一层且不带 pointerInput：既不会被操作条盖住，也不额外拦截触摸
        // （命中判定仍在下面那层 Canvas 的手势里，用的还是同一组坐标）。
        Canvas(Modifier.fillMaxSize()) {
            listOf(startHandle, endHandle).forEach { h ->
                val hy = h.y.coerceIn(handleRadius + 2f, size.height - handleRadius - 2f)
                val hx = h.x.coerceIn(handleRadius, size.width - handleRadius)
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(hx - 1.2f, hy - handleRadius * 1.9f),
                    size = Size(2.4f, handleRadius * 1.6f),
                    cornerRadius = CornerRadius(1.2f)
                )
                drawCircle(color = accent, radius = handleRadius, center = Offset(hx, hy))
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = handleRadius * 0.42f,
                    center = Offset(hx, hy)
                )
            }
        }
    }
}

/**
 * 选区操作条：复制 / 划线 / 批注。
 *
 * 纯文字按钮（不配图标）：三个动作用两个字就能说清，加图标只会在这个尺寸下变糊。
 * 底色用不透明的面板色 + 轻投影 —— 它浮在正文之上，半透明会让底下的字穿上来。
 */
@Composable
private fun SelectionActionBar(
    accent: Color,
    isDark: Boolean,
    coveringMark: ReadingMark?,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onUnhighlight: () -> Unit,
    onNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val fg = if (isDark) Color(0xFFE8E6E1) else Color(0xFF1F1F1F)
    Row(
        modifier = modifier
            // 条内的空白也不能穿透到下面的"点空白收起选区"：
            // 手指落在两个按钮之间时，用户显然不是想取消选择
            .pointerInput(Unit) { detectTapGestures { } }
            .shadow(14.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .height(42.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarAction(
            label = stringResource(com.ilyskyo.blancall.R.string.reader_select_copy),
            color = fg,
            onClick = onCopy
        )
        BarDivider(isDark)
        if (coveringMark != null) {
            BarAction(
                label = stringResource(com.ilyskyo.blancall.R.string.reader_select_unhighlight),
                color = accent,
                onClick = onUnhighlight
            )
        } else {
            BarAction(
                label = stringResource(com.ilyskyo.blancall.R.string.reader_select_highlight),
                color = accent,
                onClick = onHighlight
            )
        }
        BarDivider(isDark)
        BarAction(
            label = if (coveringMark?.hasNote == true) {
                stringResource(com.ilyskyo.blancall.R.string.reader_select_edit_note)
            } else {
                stringResource(com.ilyskyo.blancall.R.string.reader_select_note)
            },
            color = fg,
            onClick = onNote
        )
    }
}

@Composable
private fun BarAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .pressClick { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun BarDivider(isDark: Boolean) {
    Box(
        Modifier
            .width(0.6.dp)
            .height(18.dp)
            .background(if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f))
    )
}
