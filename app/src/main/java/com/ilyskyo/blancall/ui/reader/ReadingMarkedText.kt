// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.data.repository.ReadingMark
import kotlin.math.min

/**
 * 一个段落的定位信息：给选区覆盖层用（它要把选区矩形画在段落坐标之外）。
 *
 * [topLeft] 是段落在**正文根容器**（阅读页最外层 Box）中的位置。滚动会改变它，
 * 因此只在"开始选择"的瞬间抓取一次 —— 选择期间正文不滚动（选择层吞掉手势）。
 */
internal class ParagraphAnchor(
    val p: Int,
    val text: String,
    val layout: TextLayoutResult,
    val topLeft: Offset
)

/**
 * 正文段落（可划线 / 可长按选择版）。
 *
 * 外观与 [ReadingTextContent] 里的普通 Text 逐项对齐（字号 / 行高 / 字重 / 首行缩进 / 颜色），
 * 区别只在绘制与手势：
 * - `drawWithContent` 在文字之后叠划线：淡色背景 + 底部实线；批注在行尾多一个圆点。
 *   画在文字**同一绘制阶段**，滚动/改字号时划线永远贴住字，不会出现"线在字上飘"。
 * - 长按 → 选句（英文选词），把段落定位与 layout 一起交给上层开选择态。
 * - 短按若命中已有划线 → 打开该条（看/改批注）；其余短按**原样放行**给外层
 *   （点空白切换悬浮控件的行为不变）。
 */
@Composable
internal fun MarkedParagraph(
    text: String,
    paragraphIndex: Int,
    marks: List<ReadingMark>,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    indent: Boolean,
    accent: Color,
    /** 选择态期间关闭长按（避免二次长按把选区换掉） */
    selectable: Boolean = true,
    onLongPress: (Int, Int, ParagraphAnchor) -> Unit,
    onMarkClick: (ReadingMark) -> Unit,
    /** 本次短按已被本段消费（上层不应再当作「切换控件」） */
    onHandledTap: () -> Unit,
    /** 段落顶部位置上报（根容器坐标）：划线列表跳转时用来精确定位 */
    onPositioned: ((Int, Float) -> Unit)? = null
) {
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val topLeftState = remember { mutableStateOf(Offset.Zero) }
    val marksState by rememberUpdatedState(marks)
    val accentState by rememberUpdatedState(accent)
    val onLongPressState by rememberUpdatedState(onLongPress)
    val onMarkClickState by rememberUpdatedState(onMarkClick)
    val onHandledTapState by rememberUpdatedState(onHandledTap)
    val onPositionedState by rememberUpdatedState(onPositioned)

    Text(
        text = text,
        fontSize = fontPx.sp,
        lineHeight = (fontPx * lineHeight).sp,
        color = textColor,
        fontFamily = fontFamily,
        style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val pos = it.positionInRoot()
                topLeftState.value = pos
                onPositionedState?.invoke(paragraphIndex, pos.y)
            }
            .drawWithContent {
                drawContent()
                val l = layoutState.value ?: return@drawWithContent
                val color = accentState
                // 历史数据里可能存着部分重叠的划线：按并集只画一次。
                // 逐条叠加会让交集处颜色变深，这里是最后一道防叠层的闸。
                mergedRanges(marksState).forEach { seg ->
                    val rects = rangeRects(l, seg.a, seg.e)
                    if (rects.isEmpty()) return@forEach
                    val bgAlpha = if (seg.hasNote) 0.16f else 0.10f
                    val lineAlpha = if (seg.hasNote) 0.9f else 0.72f
                    rects.forEach { r ->
                        // 底色：极淡的一层，主要靠下划线起标识作用（不压字）
                        drawRoundRect(
                            color = color.copy(alpha = bgAlpha),
                            topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height),
                            cornerRadius = CornerRadius(r.height * 0.18f)
                        )
                        // 下划线：贴字形底部，粗细随字号（1.4~2.4px 的观感区间）
                        val thick = (r.height * 0.055f).coerceIn(1.1f, 3.2f)
                        drawRoundRect(
                            color = color.copy(alpha = lineAlpha),
                            topLeft = Offset(r.left, r.bottom - thick * 0.5f),
                            size = Size(r.width, thick),
                            cornerRadius = CornerRadius(thick)
                        )
                    }
                }
                // 批注标记仍按每条记录自己的末尾画（一条带备注的划线一个点）
                marksState.filter { it.hasNote }.forEach { m ->
                    val rects = rangeRects(l, m.a, m.e)
                    if (rects.isEmpty()) return@forEach
                    // 贴在划线末尾右下角的一个小圆点；纯划线不画。
                    val last = rects.last()
                    val r = (last.height * 0.13f).coerceAtLeast(1.8f)
                    drawCircle(
                        color = color.copy(alpha = 0.95f),
                        radius = r,
                        center = Offset(
                            x = min(last.right + r * 1.6f, size.width - r),
                            // 落在下划线下方、行距的空白里：压在字形底边上会糊住笔画
                            y = last.bottom + r * 1.1f
                        )
                    )
                }
            }
            .pointerInput(paragraphIndex, selectable) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val start = down.position
                    // 在长按超时之前：抬起 = 短按（判断是否点在划线上）；超时 = 长按开选择；滑出 slop = 滚动
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: PressOutcome = PressOutcome.Moved
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((ch.position - start).getDistance() > slop) { result = PressOutcome.Moved; break }
                            if (!ch.pressed) { result = PressOutcome.Up(ch.position); break }
                        }
                        result
                    }
                    when {
                        early == null -> {
                            // ── 长按：开选择态 ──
                            val l = layoutState.value
                            if (l != null && selectable) {
                                val offset = l.getOffsetForPosition(start)
                                down.consume()
                                onLongPressState(paragraphIndex, offset, ParagraphAnchor(paragraphIndex, text, l, topLeftState.value))
                                // 长按后把这整套手势吃掉，避免外层把它当点击（切换控件）
                                consumeUntilUp(down.id)
                            }
                        }
                        early is PressOutcome.Up -> {
                            // ── 短按：先看是否落在已有划线上 ──
                            // 命中区按"字形矩形 + 上下各半个字高"算：划线只占字形高度，
                            // 而行距有近一倍字高，只认字形的话点在两行之间就什么都不会发生
                            // （实测就这么点空过，用户只会觉得"点划线没反应"）。
                            val l = layoutState.value
                            if (l != null) {
                                val hit = marksState.firstOrNull { m ->
                                    rangeRects(l, m.a, m.e).any { r ->
                                        val padY = r.height * 0.5f
                                        early.position.x >= r.left - 2f &&
                                            early.position.x <= r.right + 2f &&
                                            early.position.y >= r.top - padY &&
                                            early.position.y <= r.bottom + padY
                                    }
                                }
                                if (hit != null) {
                                    onMarkClickState(hit)
                                    onHandledTapState()
                                }
                            }
                        }
                        else -> Unit // 滑动 = 滚动/翻页，交还给容器
                    }
                }
            },
        onTextLayout = { layoutState.value = it }
    )
}

private sealed interface PressOutcome {
    /** 手指在长按超时前抬起（位置 = 抬起点） */
    data class Up(val position: Offset) : PressOutcome
    /** 超出 touchSlop：这是滑动 */
    data object Moved : PressOutcome
}

/** 长按判定后继续吞掉剩余事件，直到手指全部抬起 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilUp(id: PointerId) {
    while (true) {
        val ev = awaitPointerEvent()
        var anyDown = false
        ev.changes.forEach { ch ->
            if (ch.id == id) ch.consume()
            if (ch.pressed) anyDown = true
        }
        if (!anyDown) return
    }
}

/** 供上层复用：把段落文字按矩形切分（选区命中、跳转定位都要） */
internal fun paragraphRects(layout: TextLayoutResult, a: Int, e: Int) = rangeRects(layout, a, e)
