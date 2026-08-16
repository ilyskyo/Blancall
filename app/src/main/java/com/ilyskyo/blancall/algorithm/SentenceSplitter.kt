// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 句子切割器：将文章按中英文标点精确切分为句子
 *
 * 支持：
 * - 中文句末标点：。！？
 * - 英文句末标点：. ! ?
 * - 换行符作为句子分隔（可关闭，用于 PDF 软换行场景）
 * - 避免引号、括号内嵌标点的错误切分
 * - 英文缩写（Mr./Dr./U.S./e.g. 等）与小数（3.14）不被误切
 * - 中文双省略号 …… 不会被切成垃圾句
 * - 保留原句中的换行和空白信息
 */
object SentenceSplitter {

    // 句末标点字符
    private val SENTENCE_END_CHARS = setOf(
        '。', '！', '？',  // 中文
        '.', '!', '?',     // 英文
        '…', '~'           // 省略号等
    )

    // 强句末标点（不含英文句点，用于右引号后切分判断，避免缩写/小数干扰）
    private val STRONG_SENTENCE_END = setOf('。', '！', '？', '!', '?', '…')

    // 右半部分配对符号（这些符号前的句末标点不会导致切分，改由右配对符号处统一判断）
    private val RIGHT_PAIRED_CHARS = setOf('"', '\'', '」', '』', '】', '）', ')', '》', '>')

    // 常见英文缩写（小写，含内部点）。命中这些 token 内部的句点不切分。
    // 覆盖任务列举的 Mr./Mrs./Dr./Prof./Sr./Jr./St./U.S./e.g./i.e./vs./etc. 等
    private val ENGLISH_ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "u.s", "e.g", "i.e", "u.k", "a.m", "p.m"
    )

    /**
     * 将文本切割为句子列表
     * @param text 原文本
     * @param treatNewlineAsSentence 是否将单换行视为句子分隔。
     *        默认 true（保持兼容）。PDF 等含软换行的文本可传 false，
     *        此时单换行不切分（仅按句末标点切），避免软换行被过度切分。
     * @return 每个元素为一个完整句子（已去除首尾空白）
     */
    fun split(text: String, treatNewlineAsSentence: Boolean = true): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0

        while (i < text.length) {
            val ch = text[i]
            current.append(ch)

            // 检查是否为句子结束位置
            if (isSentenceEnd(text, i, treatNewlineAsSentence)) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
                current.clear()
            }

            i++
        }

        // 处理最后一句（如果没有句末标点）
        val lastSentence = current.toString().trim()
        if (lastSentence.isNotEmpty()) {
            sentences.add(lastSentence)
        }

        return sentences
    }

    /**
     * 判断当前位置是否为句子结束位置
     * @param treatNewlineAsSentence 是否把单换行当作切分点
     */
    private fun isSentenceEnd(text: String, index: Int, treatNewlineAsSentence: Boolean = true): Boolean {
        val ch = text[index]

        // 换行符处理（连续多个换行只算一次）
        if (ch == '\n') {
            if (!treatNewlineAsSentence) {
                // 软换行模式：单换行不切分（PDF 段落内软换行）
                return false
            }
            // 逆向查找最后一个非空白、非换行的字符，避免创建 substring
            var j = index - 1
            while (j >= 0 && text[j].isWhitespace() && text[j] != '\n') j--
            if (j >= 0) {
                val lastCh = text[j]
                if (lastCh != '\n' && !SENTENCE_END_CHARS.contains(lastCh)) {
                    return true
                }
            }
            return false
        }

        // 当前字符是右配对符号（右引号/右括号等）：若其前的字符链（跳过连续右配对符号）
        // 可追溯到强句末标点，则在配对符号链的末尾切分，避免「他说："你好！"然后走了。」
        // 中引号后内容被并入上一句，也支持嵌套引号如「他说："她说：『你好。』"然后走了。」
        if (RIGHT_PAIRED_CHARS.contains(ch)) {
            var p = index - 1
            while (p >= 0 && RIGHT_PAIRED_CHARS.contains(text[p])) p--
            if (p >= 0 && STRONG_SENTENCE_END.contains(text[p])) {
                val nextIndex = index + 1
                if (nextIndex < text.length && RIGHT_PAIRED_CHARS.contains(text[nextIndex])) {
                    return false  // 链路未到末尾，延后切分
                }
                return true
            }
            return false
        }

        // 检查是否为句末标点
        if (!SENTENCE_END_CHARS.contains(ch)) {
            return false
        }

        // 英文句点：处理缩写与小数，避免误切
        if (ch == '.') {
            if (isAbbreviationOrDecimal(text, index)) {
                return false
            }
        }

        // 省略号：连续的 … 只在最后一个之后切分（避免 …… 被切成垃圾句 "…"）
        if (ch == '…') {
            val nextIndex = index + 1
            if (nextIndex < text.length && text[nextIndex] == '…') {
                return false  // 后面还有 …，延后切分
            }
            return true
        }

        // 检查下一个字符是否为右配对符号（引号等）
        // 若是，则不在此处切分，由右配对符号处的逻辑统一处理
        val nextIndex = index + 1
        if (nextIndex < text.length) {
            val nextCh = text[nextIndex]
            if (RIGHT_PAIRED_CHARS.contains(nextCh)) {
                return false
            }
        }

        return true
    }

    /**
     * 判断英文句点是否属于缩写或小数，属于则不切分。
     * - 小数：前后都是数字（如 3.14）
     * - 缩写：包含此点的连续「字母+点」token 命中缩写集合（如 Mr. / U.S. / e.g.）
     *   注意：缩写后跟新句子的边界判定存在固有歧义（如 "etc. The"），
     *   此处选择不切以保护缩写完整性，属可接受限制。
     */
    private fun isAbbreviationOrDecimal(text: String, index: Int): Boolean {
        val prev = if (index > 0) text[index - 1] else return false
        val next = if (index + 1 < text.length) text[index + 1] else return false

        // 小数：前后都是数字
        if (prev.isDigit() && next.isDigit()) return true

        // 提取包含此点的连续「字母+点」token
        val sb = StringBuilder()
        var j = index - 1
        while (j >= 0 && (text[j].isLetter() || text[j] == '.')) {
            sb.insert(0, text[j])
            j--
        }
        sb.append('.')
        var k = index + 1
        while (k < text.length && (text[k].isLetter() || text[k] == '.')) {
            sb.append(text[k])
            k++
        }
        val token = sb.toString().lowercase().trimEnd('.')
        return token in ENGLISH_ABBREVIATIONS
    }

    /**
     * 带位置信息切割，返回每句在原文本中的起止位置
     */
    data class SentenceWithPosition(
        val text: String,
        val startIndex: Int,
        val endIndex: Int   // exclusive（不包含该位置的字符）
    )

    fun splitWithPositions(text: String, treatNewlineAsSentence: Boolean = true): List<SentenceWithPosition> {
        val result = mutableListOf<SentenceWithPosition>()
        var sentenceStart = 0

        // 跳过开头的空白
        while (sentenceStart < text.length && text[sentenceStart].isWhitespace()) {
            sentenceStart++
        }

        var i = sentenceStart
        while (i < text.length) {
            if (isSentenceEnd(text, i, treatNewlineAsSentence)) {
                val sentence = text.substring(sentenceStart, i + 1).trim()
                if (sentence.isNotEmpty()) {
                    // endIndex 统一使用 exclusive 语义（i + 1）
                    result.add(SentenceWithPosition(sentence, sentenceStart, i + 1))
                }
                sentenceStart = i + 1
                // 跳过后续空白
                while (sentenceStart < text.length && text[sentenceStart].isWhitespace()) {
                    sentenceStart++
                }
                i = sentenceStart - 1
            }
            i++
        }

        // 最后一句
        if (sentenceStart < text.length) {
            val sentence = text.substring(sentenceStart).trim()
            if (sentence.isNotEmpty()) {
                // endIndex 统一使用 exclusive 语义（text.length）
                result.add(SentenceWithPosition(sentence, sentenceStart, text.length))
            }
        }

        return result
    }
}
