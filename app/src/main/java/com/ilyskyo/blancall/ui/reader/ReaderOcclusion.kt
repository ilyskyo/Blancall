// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.DifficultyCalculator
import kotlin.math.roundToInt

/** 阅读背诵遮挡的一个空（原文 [start, end) 区间，半开区间） */
data class OcclusionSpan(val start: Int, val end: Int)

/** 遮挡渲染参数：由 [ReadingModeScreen] 组装后下发给正文渲染器 */
data class OcclusionParams(
    val enabled: Boolean,
    /** "local"=本地算法；"ai"=AI 遮挡（仅 Pro 提供） */
    val mode: String = "local",
    /** AI 遮挡在 [articleContent] 上的全局空（仅 ai 模式使用，local 忽略） */
    val aiRanges: List<OcclusionSpan> = emptyList(),
    /** 用于 AI 空→当前展示文本映射时的源文本 */
    val articleContent: String = "",
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

    /** 在一个从句内挑最高难度 1-2 汉字作为遮挡，返回段内区间 */
    private fun pickInRange(text: String, s: Int, e: Int): OcclusionSpan? {
        if (e - s < 2) return null
        var best = -1
        var bd = -1f
        for (i in s until e) {
            if (isChinese(text[i])) {
                val d = DifficultyCalculator.calculateCharDifficulty(text[i])
                if (d > bd) { bd = d; best = i }
            }
        }
        if (best < 0 || bd < 0.32f) return null
        var ee = best + 1
        if (ee < e && isChinese(text[ee]) && DifficultyCalculator.calculateCharDifficulty(text[ee]) >= bd * 0.8f) ee++
        return OcclusionSpan(best, ee)
    }

    /** 段落级本地遮挡（返回段内区间） */
    fun localRangesInPara(para: String): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        var start = 0
        for (i in para.indices) {
            if (para[i] in CLAUSE_PUNCT) {
                pickInRange(para, start, i + 1)?.let { out.add(it) }
                start = i + 1
            }
        }
        if (start < para.length) pickInRange(para, start, para.length)?.let { out.add(it) }
        return out.distinctBy { it.start }
    }

    /** 整篇本地遮挡（返回在 [text] 上的全局区间） */
    fun localRanges(text: String): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        for (p in splitParagraphs(text)) {
            for (sp in localRangesInPara(p.text)) out.add(OcclusionSpan(p.start + sp.start, p.start + sp.end))
        }
        return out
    }

    /**
     * 将 [sourceText] 上的遮挡区间映射到 [targetText]（按子串查找）。
     * 用于把 AI 在整篇上算出的空，映射到章节页/预览等重建文本上。
     */
    fun mapRangesToText(targetText: String, sourceText: String, ranges: List<OcclusionSpan>): List<OcclusionSpan> {
        val out = mutableListOf<OcclusionSpan>()
        for (r in ranges) {
            if (r.start !in 0..sourceText.length || r.end !in r.start..sourceText.length) continue
            val sub = sourceText.substring(r.start, r.end)
            if (sub.isEmpty()) continue
            val k = targetText.indexOf(sub)
            if (k >= 0) out.add(OcclusionSpan(k, k + sub.length))
        }
        return out.distinctBy { it.start }
    }

    /**
     * 段落级遮挡区间：
     * - AI 模式：把整篇 [aiRanges] 映射到本段；映射失败（AI 未返回/坐标失效）则回退本地算法
     * - 本地模式：直接用 [ReaderOcclusion.localRangesInPara]
     * @return 段内 [start,end) 区间列表
     */
    fun rangesForPara(
        para: String,
        fullContent: String,
        isAi: Boolean,
        aiRanges: List<OcclusionSpan>
    ): List<OcclusionSpan> {
        if (isAi && aiRanges.isNotEmpty() && fullContent.isNotEmpty()) {
            val mapped = mapRangesToText(para, fullContent, aiRanges)
            if (mapped.isNotEmpty()) return mapped
        }
        return localRangesInPara(para)
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
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            res.add(Rect(left, top, right, bottom))
        }
        line++
        if (line > lastLine) break
    }
    return res
}

/**
 * 单段落背诵遮挡渲染：
 * - 底层面板【原文完整渲染】保证换行/缩进与正常阅读完全一致
 * - 上方按遮挡区间画"圆角遮块"（黑/灰块，自带柔和投影）
 * - 点一下遮块 → 像揭开挡卡一样缩放淡出，露出被盖住的原文
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
    isDark: Boolean,
    onToggleControls: () -> Unit
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val revealedStarts = remember { mutableStateOf(setOf<Int>()) }

    // 需要绘制遮块的区间（未揭示的）+ 各自矩形
    val blocks = remember(layout, hidden) {
        val l = layout ?: return@remember emptyList()
        hidden
            .filter { it.start !in revealedStarts.value && it.end > it.start }
            .map { it to rangeRects(l, it.start, it.end) }
            .filter { it.second.isNotEmpty() }
    }
    val blocksState = rememberUpdatedState(blocks)

    val fillColor = if (isDark) Color(0x3AFFFFFF) else Color(0x24000000)
    val borderColor = if (isDark) Color(0x59FFFFFF) else Color(0x2E000000)
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val hitBlock = blocksState.value.any { (_, rects) -> rects.any { it.contains(pos) } }
                    if (!hitBlock) onToggleControls()
                }
            }
    ) {
        Text(
            text = text,
            fontSize = fontPx.sp,
            lineHeight = (fontPx * lineHeight).sp,
            color = textColor,
            fontFamily = fontFamily,
            style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layout = it }
        )
        blocks.forEach { (span, rects) ->
            rects.forEach { r ->
                AnimatedVisibility(
                    visible = span.start !in revealedStarts.value,
                    enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.9f, animationSpec = tween(160)),
                    exit = fadeOut(tween(200)) + scaleOut(targetScale = 1.12f, animationSpec = tween(200)),
                    modifier = Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.dp }, with(density) { r.height.dp })
                ) {
                    val shape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(elevation = 3.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                            .clip(shape)
                            .background(fillColor)
                            .border(BorderStroke(1.dp, borderColor), shape)
                            .pointerInput(span) {
                                detectTapGestures { revealedStarts.value = revealedStarts.value + span.start }
                            }
                    )
                }
            }
        }
    }
}