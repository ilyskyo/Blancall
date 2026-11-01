// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 正文选区的纯逻辑（句子 / 单词边界、区间规范化）。
 *
 * 全部是纯函数，便于单测：渲染层（ReadingSelectionOverlay）只负责把手指坐标换成字符偏移，
 * 边界怎么切、切到哪里算"一句"，都在这里定，测试直接对着字符串断言。
 *
 * ## 为什么中文长按选"句"而不是"词"
 * 中文没有空格分隔，靠 `getOffsetForPosition` 猜词边界几乎必错（同一个位置可能是"研究生命"
 * 也可能是"研究/生命"）。阅读场景里划线/批注的粒度本来就是句子；需要更细时拖手柄即可
 * 逐字扩展。英文则相反：单词边界明确，长按选词符合系统习惯。
 */
object ReadingSelection {

    /**
     * 句末标点（含全角/半角、省略号、分号）。
     *
     * 半角句点必须在列：英文段落全靠它断句。缩写/小数里的点由 [isAbbreviationDot] 排除
     * （`Mr. Smith` 不会被切成两句），这条漏掉时中文正常、英文会整段选成一句。
     */
    private const val TERMINATORS = "。！？!?；;…."

    /** 句末之后紧跟的收尾引号/括号，应当并入前一句 */
    private const val CLOSERS = "”’」』）】》〉\"'"

    /** 超过这个长度的"句子"再按逗号切一次（长段无句号的 PDF 文本很常见） */
    private const val LONG_SENTENCE = 120

    /** 逗号级切分的次级分隔符 */
    private const val SUB_SEPARATORS = "，,、：:"

    /**
     * [offset] 所在句子的区间 `[start, end)`；文本为空返回 `0..0`。
     *
     * 落点在句末标点上时归属**前一句**（用户长按句号，想选的显然是刚读完那句）。
     */
    fun sentenceRange(text: String, offset: Int): IntRange {
        val n = text.length
        if (n == 0) return 0 until 0
        var anchor = offset.coerceIn(0, n - 1)
        // 落点若在句末符/收尾引号上，先退到它前面的实字上，避免选出一段纯标点
        while (anchor > 0 && (isTerminator(text[anchor]) || text[anchor] in CLOSERS)) anchor--

        var start = anchor
        while (start > 0) {
            val c = text[start - 1]
            if (isTerminator(c) && !isAbbreviationDot(text, start - 1)) break
            start--
        }
        // 起点若停在收尾引号之后（引号属于上一句），把它让回上一句的自然起点
        while (start < anchor && (text[start] in CLOSERS || text[start].isWhitespace())) start++

        var end = anchor + 1
        while (end < n) {
            val c = text[end]
            if (isTerminator(c) && !isAbbreviationDot(text, end)) {
                end++
                while (end < n && text[end] in CLOSERS) end++
                break
            }
            end++
        }
        // 长句再切一次：取离落点最近的一段子句（逗号/顿号/冒号分隔）
        return refineLongSentence(text, start, end, anchor)
    }

    /**
     * 英文/数字长按选词：连续的字母数字（含词内连字符、撇号）区间。
     * 落点不在词上（空白、标点、汉字）时，退化到 [sentenceRange]。
     */
    fun wordRange(text: String, offset: Int): IntRange {
        val n = text.length
        if (n == 0) return 0 until 0
        val anchor = offset.coerceIn(0, n - 1)
        if (!isWordChar(text[anchor])) return sentenceRange(text, anchor)
        var start = anchor
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = anchor + 1
        while (end < n && isWordChar(text[end])) end++
        return start until end
    }

    /**
     * 长按落点的默认粒度：英文/数字 → 词；其余（汉字、中文标点）→ 句。
     */
    fun rangeAt(text: String, offset: Int): IntRange {
        val n = text.length
        if (n == 0) return 0 until 0
        val anchor = offset.coerceIn(0, n - 1)
        val r = if (isWordChar(text[anchor])) wordRange(text, anchor) else sentenceRange(text, anchor)
        return normalize(text, r.first, r.last + 1)
    }

    /** 把 `[start, end)` 夹进文本范围，并去掉首尾空白；结果为空时返回空区间 */
    fun normalize(text: String, start: Int, end: Int): IntRange {
        val n = text.length
        var s = start.coerceIn(0, n)
        var e = end.coerceIn(0, n)
        if (e < s) { val t = s; s = e; e = t }
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s until e
    }

    /** 区间里是否至少有一个"可见字符"（纯空白/纯标点不值得划线） */
    fun isSelectable(text: String, range: IntRange): Boolean {
        if (range.isEmpty()) return false
        val s = range.first.coerceIn(0, text.length)
        val e = (range.last + 1).coerceIn(0, text.length)
        if (e <= s) return false
        for (i in s until e) {
            val c = text[i]
            if (!c.isWhitespace()) return true
        }
        return false
    }

    /** 选区文本（供复制与批注引用） */
    fun textOf(text: String, range: IntRange): String {
        val s = range.first.coerceIn(0, text.length)
        val e = (range.last + 1).coerceIn(0, text.length)
        return if (e > s) text.substring(s, e) else ""
    }

    // ── 内部 ──

    private fun isTerminator(c: Char): Boolean =
        c in TERMINATORS || c == '\n'

    private fun isWordChar(c: Char): Boolean =
        (c.isLetterOrDigit() || c == '-' || c == '\'' || c == '_') && !ReadingSpeed.isCjk(c)

    /**
     * 英文缩写/小数里的句点不是句末：`e.g.` `Mr.` `3.14`。
     * 判据很轻：点后面紧跟非空白且非大写开头 → 认为是词内点。
     */
    private fun isAbbreviationDot(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length || text[index] != '.') return false
        val prev = text[index - 1]
        val next = if (index + 1 < text.length) text[index + 1] else ' '
        if (prev.isDigit() && next.isDigit()) return true      // 3.14
        if (next.isLetter() && next.isLowerCase()) return true // e.g / i.e
        if (next.isLetter()) return true                       // U.S.A
        return false
    }

    /** 超长句按次级分隔符再切一段，返回包住 [anchor] 的那一段 */
    private fun refineLongSentence(text: String, start: Int, end: Int, anchor: Int): IntRange {
        if (end - start <= LONG_SENTENCE) return start until end
        var s = start
        var bestS = start
        var bestE = end
        var i = start
        while (i < end) {
            if (text[i] in SUB_SEPARATORS && i + 1 < end) {
                val segS = s
                val segE = i + 1
                if (anchor in segS until segE) { bestS = segS; bestE = segE }
                s = i + 1
            }
            i++
        }
        if (anchor in s until end) { bestS = s; bestE = end }
        // 仍超长（整段没有逗号）：退回整句，别把一句话切成两半
        return if (bestE - bestS <= LONG_SENTENCE * 2) bestS until bestE else start until end
    }
}
