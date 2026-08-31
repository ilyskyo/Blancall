// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.DifficultyCalculator

/** 阅读背诵遮挡的一个空（原文 [start, end) 区间，半开区间） */
data class OcclusionSpan(val start: Int, val end: Int)

/** 遮挡渲染参数：由 [ReadingModeScreen] 组装后下发给正文渲染器 */
data class OcclusionParams(
    val enabled: Boolean,
    /**
     * 遮挡强度（三种均为本地算法，仅控制"遮多遮少"）：
     * - "short"=短遮挡：每从句仅遮最难的单字（高阈值），遮得最少
     * - "long" =长遮挡：每从句最多遮 3 个难字（低阈值），遮得最多
     * - "mixed"=混合长短遮挡：按从句序号交替长/短，长短不一的观感
     */
    val mode: String = "long",
    val onToggleControls: () -> Unit = {}
)

/**
 * 本地遮挡算法（双版本可用，无联网）：按逗号/句号切从句，在每个从句里挑
 * 最高难度的 1-2 个汉字作为遮挡空。返回在 [text] 上的区间。
 */
object ReaderOcclusion {

    private val CLAUSE_PUNCT = setOf('，', ',', '；', ';', '、', '。', '.', '！', '！', '?', '？', '\n', ' ')

    /** 段落（trim 后的文本与其在原文中的起止） */
    data class Para(val start: Int, val end: Int, val text: String)

    /**
     * 将文本按"空行段落"切分为段落，记录每个段落 trim 后在原文中的 [start,end)。
     * 与正文渲染的 `split("\n\\s*\n")` 语义对齐，供遮挡做区间映射。
     */
    fun splitParagraphs(text: String): List<Para> {
        val res = mutableListOf<Para>()
        val n = text.length
        var i = 0
        while (i < n) {
            // 跳过段落前空白/换行
            while (i < n && (text[i].isWhitespace() || text[i] == '\n')) i++
            if (i >= n) break
            val s = i
            while (i < n) {
                if (text[i] == '\n') {
                    // 该换行是否构成"空行段落分隔"（其后若干空白后又换行）
                    var j = i + 1
                    while (j < n && text[j] != '\n' && text[j].isWhitespace()) j++
                    if (j < n && text[j] == '\n') break
                }
                i++
            }
            val e = i
            var ls = s
            while (ls < e && text[ls].isWhitespace()) ls++
            var le = e
            while (le > ls && text[le - 1].isWhitespace()) le--
            if (le > ls) res.add(Para(ls, le, text.substring(ls, le)))
        }
        return res
    }

    /**
     * 在一个从句内挑遮挡字，返回【多个独立遮块】（每个最难字 1 个遮块）。
     * [density] 决定遮多遮少：
     * - "short"：高阈值，仅遮最难的单字（[maxChars]=1）→ 遮得最少
     * - "long" ：低阈值，最多遮 [maxChars]=3 个难字 → 遮得最多
     * - 其它（兜底）：中等阈值，单字
     */
    private fun pickInRange(text: String, s: Int, e: Int, density: String): List<OcclusionSpan> {
        if (e - s < 2) return emptyList()
        val (threshold, maxChars) = when (density) {
            "long" -> 0.25f to 3   // 长遮挡：低阈值 + 多字 → 遮得多
            "short" -> 0.55f to 1  // 短遮挡：高阈值 + 单字 → 遮得少
            else -> 0.32f to 1     // 兜底（理论上不会命中）
        }
        val hard = mutableListOf<Int>()
        for (i in s until e) {
            if (isChinese(text[i])) {
                val d = DifficultyCalculator.calculateCharDifficulty(text[i])
                if (d >= threshold) hard.add(i)
            }
        }
        if (hard.isEmpty()) return emptyList()
        // 按难度降序，取最难的若干字各自成块
        hard.sortByDescending { DifficultyCalculator.calculateCharDifficulty(text[it]) }
        return hard.take(maxChars).map { OcclusionSpan(it, it + 1) }
    }

    /** 混合模式：按从句序号交替长/短，形成长短不一的观感 */
    private fun clauseDensity(mode: String, clauseIdx: Int): String =
        if (mode == "mixed") (if (clauseIdx % 2 == 0) "long" else "short") else mode

    /** 段落级本地遮挡（返回段内区间） */
    fun localRangesInPara(para: String, mode: String): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        var start = 0
        var clauseIdx = 0
        for (i in para.indices) {
            if (para[i] in CLAUSE_PUNCT) {
                out += pickInRange(para, start, i + 1, clauseDensity(mode, clauseIdx))
                start = i + 1
                clauseIdx++
            }
        }
        if (start < para.length) out += pickInRange(para, start, para.length, clauseDensity(mode, clauseIdx))
        return out.distinctBy { it.start }
    }

    /** 整篇本地遮挡（返回在 [text] 上的全局区间） */
    fun localRanges(text: String, mode: String): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        for (p in splitParagraphs(text)) {
            for (sp in localRangesInPara(p.text, mode)) out.add(OcclusionSpan(p.start + sp.start, p.start + sp.end))
        }
        return out
    }

    private fun isChinese(ch: Char): Boolean =
        ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf'
}

/** 计算 [OcclusionSpan] 在 [layout] 中占用的逐行矩形（用于画遮块） */
private fun rangeRects(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
    val len = layout.layoutInput.text.length
    if (start >= end || start < 0 || end > len) return emptyList()
    var line = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end - 1)
    val res = mutableListOf<Rect>()
    while (true) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, visibleEnd = true)
        val cs = maxOf(start, lineStart)
        val ce = minOf(end - 1, lineEnd - 1)
        if (ce >= cs) {
            val left = layout.getBoundingBox(cs).left
            val right = layout.getBoundingBox(ce).right
            // 遮块高度与字形同高：取该段字符的实际包围盒（min top / max bottom），
            // 而不是整行高（行高含行距，会让遮块比字高出一截）
            var top = layout.getBoundingBox(cs).top
            var bottom = layout.getBoundingBox(cs).bottom
            for (i in cs + 1..ce) {
                val bbox = layout.getBoundingBox(i)
                if (bbox.top < top) top = bbox.top
                if (bbox.bottom > bottom) bottom = bbox.bottom
            }
            // 贴合字形墨迹：上下各内缩一点（字符包围盒含 ascent/descent 余量，比可见字形略高）
            val visualInset = (bottom - top) * 0.10f
            res.add(Rect(left, top + visualInset, right, bottom - visualInset))
        }
        line++
        if (line > lastLine) break
    }
    return res
}

/**
 * 单段落背诵遮挡渲染：
 * - 底层面板【原文完整渲染】保证换行/缩进与正常阅读完全一致
 * - 遮块在 Text 的 drawBehind 中与文字【同一绘制阶段】同步绘制——字号/字体/行距/
 *   缩进/换行任何变化，遮块与文字永远同帧更新，零时延、不分离
 * - 遮块为文字色高不透明覆盖：真正遮住原文（不是半透明）
 * - 点一下遮块 → 立即露出原文；再点同一位置 → 重新遮上
 * - 点段落空白处 → 交给上层切换悬浮控件显隐（不与点块冲突）
 */
@Composable
fun OccludedParagraph(
    text: String,
    hidden: List<OcclusionSpan>,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    indent: Boolean,
    maskColor: Color,
    onToggleControls: () -> Unit
) {
    val revealedStarts = remember { mutableStateOf(setOf<Int>()) }
    // 挡片颜色用 State 包裹：drawWithContent 在绘制阶段读取 .value，
    // 切换颜色时 State 变化触发 invalidate → 实时重绘（普通闭包变量不会触发重绘）
    val maskColorState = rememberUpdatedState(maskColor)
    // 普通可变容器（非 State）：onTextLayout 布局回调中同步填充，
    // 同一帧的绘制阶段（drawBehind）即可读取——避免 State 写入导致的下一帧重组时延
    val blockRects = remember { mutableListOf<Pair<OcclusionSpan, List<Rect>>>() }
    val density = LocalDensity.current
    // 圆角更大（8dp）：遮块呈圆润胶囊感，贴合字形（高度已按字形 top/bottom 对齐）
    val cornerRadiusPx = with(density) { 8.dp.toPx() }

    // 按当前 layout 计算全部遮块矩形（含已揭示的——已揭示位置再点一下可重新遮上）
    fun computeBlocks(l: TextLayoutResult) {
        blockRects.clear()
        for (sp in hidden) {
            if (sp.end <= sp.start) continue
            val rects = rangeRects(l, sp.start, sp.end)
            if (rects.isNotEmpty()) blockRects.add(sp to rects)
        }
    }

    Text(
        text = text,
        fontSize = fontPx.sp,
        lineHeight = (fontPx * lineHeight).sp,
        color = textColor,
        fontFamily = fontFamily,
        style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                // 先画原文，再在其上画遮块——遮块在文字之上，才能真正不透明盖住内容
                drawContent()
                blockRects.forEach { (span, rects) ->
                    if (span.start !in revealedStarts.value) {
                        rects.forEach { r ->
                            drawRoundRect(
                                color = maskColorState.value,
                                topLeft = Offset(r.left, r.top),
                                size = Size(r.width, r.height),
                                cornerRadius = CornerRadius(cornerRadiusPx)
                            )
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val hit = blockRects.firstOrNull { (_, rects) -> rects.any { it.contains(pos) } }
                    when {
                        hit == null -> onToggleControls()
                        hit.first.start in revealedStarts.value ->
                            revealedStarts.value = revealedStarts.value - hit.first.start // 再点一下遮回去
                        else ->
                            revealedStarts.value = revealedStarts.value + hit.first.start // 点开揭示
                    }
                }
            },
        onTextLayout = { l ->
            // 布局回调：同步计算遮块矩形，同一帧绘制即可用（零时延）
            computeBlocks(l)
        }
    )
}