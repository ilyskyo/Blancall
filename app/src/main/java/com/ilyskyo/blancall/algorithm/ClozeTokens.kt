// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 自定义挖空编辑器的 token 切分逻辑（原 CustomClozeEditScreen 私有实现上移，行为不变）。
 *
 * 按级Level切分 token：
 * - 0 复句
 * - 1 分句：按句内标点切，标点跟随前一个 token
 * - 2 字词：连续汉字每 2 字一块（末尾余 1 字自成一 tok），英文单词整体
 * - 3 单字：每个汉字一个 token，英文单词整体
 * 标点（非中文非字母字符）始终并入前一个 token，避免单独的标点空。
 */
object ClozeTokens {

    /** token：文本 + 句内字符区间 */
    data class EditToken(val text: String, val range: IntRange)

    fun levelName(level: Int): String = when (level) {
        0 -> "复句"
        1 -> "分句"
        2 -> "字词"
        else -> "单字"
    }

    fun tokensFor(sentence: String, level: Int): List<EditToken> {
        if (sentence.isEmpty()) return emptyList()
        if (level <= 0) return listOf(EditToken(sentence, 0 until sentence.length))

        val out = mutableListOf<EditToken>()
        var i = 0
        val n = sentence.length

        fun isChinese(c: Char) = c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'
        fun isPunct(c: Char) = !isChinese(c) && !c.isLetter()

        if (level == 1) {
            var start = 0
            while (i < n) {
                val c = sentence[i]
                if (isPunct(c)) {
                    i++
                    while (i < n && isPunct(sentence[i])) i++
                    out.add(EditToken(sentence.substring(start, i), start until i))
                    start = i
                } else i++
            }
            if (start < n) out.add(EditToken(sentence.substring(start), start until n))
            return if (out.size <= 1) listOf(EditToken(sentence, 0 until sentence.length)) else out
        }

        val chunk = if (level == 2) 2 else 1
        var tokenStart = 0
        while (i < n) {
            val c = sentence[i]
            when {
                isChinese(c) -> {
                    var runEnd = i
                    while (runEnd < n && isChinese(sentence[runEnd])) runEnd++
                    var s = i
                    while (s < runEnd) {
                        val e = minOf(s + chunk, runEnd)
                        out.add(EditToken(sentence.substring(s, e), s until e))
                        s = e
                    }
                    i = runEnd
                    tokenStart = i
                }
                c.isLetter() -> {
                    var runEnd = i
                    while (runEnd < n && sentence[runEnd].isLetter() && !isChinese(sentence[runEnd])) runEnd++
                    out.add(EditToken(sentence.substring(i, runEnd), i until runEnd))
                    i = runEnd
                    tokenStart = i
                }
                else -> {
                    i++
                    while (i < n && isPunct(sentence[i])) i++
                    val end = i
                    if (out.isNotEmpty()) {
                        val last = out.removeAt(out.size - 1)
                        out.add(EditToken(last.text + sentence.substring(last.range.last + 1, end), last.range.first until end))
                    } else {
                        out.add(EditToken(sentence.substring(tokenStart, end), tokenStart until end))
                    }
                }
            }
        }
        return out
    }

    /**
     * 区间集合在 level 粒度下是否「完整对齐」：每个区间恰好是若干连续完整 token 的并集。
     * 用于恢复已保存配置的粒度时校验（粒度更粗时已存区间可能切在 token 中间）。
     */
    fun rangesAligned(sentence: String, level: Int, ranges: List<IntRange>): Boolean {
        if (ranges.isEmpty()) return true
        val tokens = tokensFor(sentence, level).map { it.range }
        return ranges.all { r ->
            if (r.first < 0 || r.last >= sentence.length) return@all false
            val covered = tokens.filter { it.first >= r.first && it.last <= r.last }
            if (covered.isEmpty()) return@all false
            var cursor = r.first
            for (t in covered.sortedBy { it.first }) {
                if (t.first != cursor) return@all false
                cursor = t.last + 1
            }
            cursor == r.last + 1
        }
    }

    /**
     * 恢复配置时的粒度：从偏好粒度 prefLevel 起，逐级细化（最多 3）
     * 直到该句全部已存区间对齐；到 3 仍不对齐（理论罕见）则维持 3。
     */
    fun restoreLevel(sentence: String, prefLevel: Int, ranges: List<IntRange>): Int {
        var level = prefLevel.coerceIn(0, 3)
        while (level < 3 && !rangesAligned(sentence, level, ranges)) level++
        return level
    }
}
