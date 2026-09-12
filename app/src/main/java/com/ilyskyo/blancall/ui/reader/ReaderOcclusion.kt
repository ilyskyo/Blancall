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

/**
 * 遮块矩形缓存（绘制阶段专用）。
 *
 * 用普通可变持有对象而非 Compose State：绘制中读写不触发重组，避免"绘制时修改状态"引发的
 * 重组风暴。key 覆盖全部影响矩形的输入（layout、遮挡集合、已揭示集合），变化即整体重算。
 */
private class OcclusionRectsCache {
    var key: Triple<TextLayoutResult, List<OcclusionSpan>, Set<Int>>? = null
    var rects: Map<Int, List<Rect>> = emptyMap()
}

/** 遮挡渲染参数：由 [ReadingModeScreen] 组装后下发给正文渲染器 */
data class OcclusionParams(
    val enabled: Boolean,
    /**
     * 遮挡粒度（三种均为本地算法，控制"遮成什么大小"而非"遮多遮少"）：
     * - "short"=短遮挡：字词级，每分句只遮最难的一两个汉字（独立小遮块）
     * - "long" =长遮挡：复句级，把每个分句/复句作为一整块遮住（句子与其中很多字都被盖住）
     * - "mixed"=混合遮挡：逐句用稳定的伪随机在「复句长遮」与「字词短遮」之间二选一（一会长一会短）
     */
    val mode: String = "long",
    val onToggleControls: () -> Unit = {}
)

/**
 * 本地遮挡算法（双版本可用，无联网）：
 * - 短遮挡：按逗号/句号切分句，在每个分句里挑最难的一两个汉字作为小遮块。
 * - 长遮挡：把每个分句整段作为一整块遮住（盖住句子及其中很多字）。
 * - 混合遮挡：逐句用稳定的伪随机在「复句长遮」与「字词短遮」之间二选一。
 * 返回在 [text] 上的半开区间 [start, end)。
 */
object ReaderOcclusion {

    private val CLAUSE_PUNCT = setOf('，', ',', '；', ';', '、', '。', '.', '！', '！', '?', '？', '\n')

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

    /** 短遮挡（字词级）每分句选取的最难字数量上限与难度阈值 */
    private const val SHORT_MAX_CHARS = 3
    private const val SHORT_THRESHOLD = 0.35f

    /** [s, e) 内是否包含至少一个汉字 */
    private fun hasChineseInRange(text: String, s: Int, e: Int): Boolean {
        for (i in s until e) if (isChinese(text[i])) return true
        return false
    }

    /**
     * 短遮挡（字词级）：在 [s, e) 内挑最难的一至 [SHORT_MAX_CHARS] 个汉字，
     * 每个字各成一个独立小遮块（字词遮挡）。
     */
    private fun pickHardChars(text: String, s: Int, e: Int): List<OcclusionSpan> {
        if (e - s < 2) return emptyList()
        val hard = mutableListOf<Int>()
        for (i in s until e) {
            if (isChinese(text[i])) {
                val d = DifficultyCalculator.calculateCharDifficulty(text[i])
                if (d >= SHORT_THRESHOLD) hard.add(i)
            }
        }
        if (hard.isEmpty()) return emptyList()
        hard.sortByDescending { DifficultyCalculator.calculateCharDifficulty(text[it]) }
        return hard.take(SHORT_MAX_CHARS).map { OcclusionSpan(it, it + 1) }
    }

    /**
     * 混合遮挡的稳定伪随机：基于分句起点与全文长度得出，重组不变，
     * 但长短交错自然（不严格按序号交替），约 50/50。
     */
    private fun mixedUseLong(clauseStart: Int, textLen: Int): Boolean {
        var h = (clauseStart.toLong() * 374761393L + textLen.toLong() * 668265263L) and 0x7FFFFFFFFFFFFFFFL
        h = (h * 2654435761L) and 0x7FFFFFFFFFFFFFFFL
        return (h and 1L) == 0L
    }

    /** 将段落切成若干「分句 [s, e)」（e 含句末标点），三种模式复用 */
    private fun clausesOf(para: String): List<Pair<Int, Int>> {
        val res = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (i in para.indices) {
            if (para[i] in CLAUSE_PUNCT) {
                res.add(start to i + 1) // 含句末标点：长遮时盖成干净整条，不露标点
                start = i + 1
            }
        }
        if (start < para.length) res.add(start to para.length)
        return res
    }

    /** 段落级本地遮挡（返回段内区间） */
    fun localRangesInPara(para: String, mode: String): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        for ((s, e) in clausesOf(para)) {
            if (e - s <= 0) continue
            when (mode) {
                "long" -> if (hasChineseInRange(para, s, e)) out += OcclusionSpan(s, e)
                "short" -> out += pickHardChars(para, s, e)
                "mixed" -> if (mixedUseLong(s, para.length)) {
                    if (hasChineseInRange(para, s, e)) out += OcclusionSpan(s, e)
                } else {
                    out += pickHardChars(para, s, e)
                }
                else -> out += pickHardChars(para, s, e)
            }
        }
        return out.distinctBy { it.start }
    }

    /**
     * 段内「分句」区间（供遮挡自定义编辑器点句用）：
     * 与长遮挡/混合遮挡同一切分口径（按逗号/句号等分句标点，区间含句末标点）。
     * 返回段内半开区间 [start, end)。
     */
    fun clauseRanges(para: String): List<OcclusionSpan> =
        clausesOf(para).map { (s, e) -> OcclusionSpan(s, e) }.filter { it.end > it.start }

    /**
     * 段内「编辑单元」区间（遮挡自定义编辑器拆词/拆字用）：
     * - level 1：字词——连续汉字每 2 字一块（末尾余 1 字自成一块），英文单词整体
     * - level >=2：单字——每个汉字一块，英文单词整体
     * 标点（非中文非字母字符）始终并入前一个单元，避免单独的标点块。
     * 返回段内半开区间 [start, end)。
     */
    fun editUnits(para: String, level: Int): List<OcclusionSpan> {
        if (para.isEmpty()) return emptyList()
        if (level <= 0) return listOf(OcclusionSpan(0, para.length))

        val out = mutableListOf<OcclusionSpan>()
        var i = 0
        val n = para.length

        fun isPunct(c: Char) = !isChinese(c) && !c.isLetter()

        val chunk = if (level == 1) 2 else 1
        while (i < n) {
            val c = para[i]
            when {
                isChinese(c) -> {
                    var runEnd = i
                    while (runEnd < n && isChinese(para[runEnd])) runEnd++
                    var s = i
                    while (s < runEnd) {
                        val e = minOf(s + chunk, runEnd)
                        out.add(OcclusionSpan(s, e))
                        s = e
                    }
                    i = runEnd
                }
                c.isLetter() -> {
                    var runEnd = i
                    while (runEnd < n && para[runEnd].isLetter() && !isChinese(para[runEnd])) runEnd++
                    out.add(OcclusionSpan(i, runEnd))
                    i = runEnd
                }
                else -> {
                    i++
                    while (i < n && isPunct(para[i])) i++
                    // 标点并入前一个单元（前移其终点）
                    if (out.isNotEmpty()) {
                        val last = out.removeAt(out.size - 1)
                        out.add(OcclusionSpan(last.start, i))
                    } else if (i > 0) {
                        out.add(OcclusionSpan(0, i))
                    }
                }
            }
        }
        return out.filter { it.end > it.start }
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

/** 计算 [OcclusionSpan] 在 [layout] 中占用的逐行矩形（用于画遮块）；遮挡编辑器复用 */
internal fun rangeRects(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
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
    onToggleControls: () -> Unit,
    /** 逐块颜色覆盖（key = span.start）：自定义遮挡每块可用不同挡片颜色；缺省统一用 [maskColor] */
    spanColors: Map<Int, Color> = emptyMap()
) {
    val revealedStarts = remember { mutableStateOf(setOf<Int>()) }
    // 挡片颜色用 State 包裹：drawWithContent 在绘制阶段读取 .value，
    // 切换颜色时 State 变化触发 invalidate → 实时重绘（普通闭包变量不会触发重绘）
    val maskColorState = rememberUpdatedState(maskColor)
    val spanColorsState = rememberUpdatedState(spanColors)
    // 遮挡集合用 State 包裹：绘制阶段与点按处理实时读取。遮挡集合变化（编辑配置回存、
    // 遮挡模式切换）无需等待重新布局即可立即生效——避免"改了配置还画旧遮块"
    val hiddenState = rememberUpdatedState(hidden)
    val onToggleControlsState = rememberUpdatedState(onToggleControls)
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    // 圆角更大（8dp）：遮块呈圆润胶囊感，贴合字形（高度已按字形 top/bottom 对齐）
    val cornerRadiusPx = with(density) { 8.dp.toPx() }
    // 遮块矩形缓存：长文上百个遮块时，逐块 rangeRects 会在每次重绘（滚动/点按/颜色切换）重算一遍。
    // 用普通可变持有对象（非 State）：绘制阶段读写不会触发重组，避免"绘制中改 State"的隐患。
    // 缓存键包含 layout + 遮挡集合 + 已揭示集合三个影响矩形的输入，任一变化即重算 —— 不会画旧遮块。
    val rectsCache = remember { OcclusionRectsCache() }

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
                // 先画原文，再在其上画遮块——遮块在文字之上，才能真正不透明盖住内容。
                drawContent()
                val l = layoutState.value ?: return@drawWithContent
                val hidden = hiddenState.value
                val revealed = revealedStarts.value
                val key = Triple(l, hidden, revealed)
                val rects = if (rectsCache.key == key) {
                    rectsCache.rects
                } else {
                    val fresh = hidden
                        .filter { it.end > it.start && it.start !in revealed }
                        .associate { it.start to rangeRects(l, it.start, it.end) }
                    rectsCache.key = key
                    rectsCache.rects = fresh
                    fresh
                }
                rects.forEach { (start, blockRects) ->
                    val blockColor = spanColorsState.value[start] ?: maskColorState.value
                    blockRects.forEach { r ->
                        drawRoundRect(
                            color = blockColor,
                            topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height),
                            cornerRadius = CornerRadius(cornerRadiusPx)
                        )
                    }
                }
            }
            .pointerInput(text) {
                detectTapGestures { pos ->
                    val l = layoutState.value ?: return@detectTapGestures
                    val hit = hiddenState.value.firstOrNull { sp ->
                        sp.end > sp.start && rangeRects(l, sp.start, sp.end).any { it.contains(pos) }
                    }
                    when {
                        hit == null -> onToggleControlsState.value()
                        hit.start in revealedStarts.value ->
                            revealedStarts.value = revealedStarts.value - hit.start // 再点一下遮回去
                        else ->
                            revealedStarts.value = revealedStarts.value + hit.start // 点开揭示
                    }
                }
            },
        onTextLayout = { l ->
            // 布局回调：仅记录 TextLayoutResult，绘制阶段据此实时计算遮块矩形
            layoutState.value = l
        }
    )
}