// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 文章正文的字符统计：主计数单位按正文实际使用的文字体系决定。
 *
 * 为什么不直接用 `content.length`：同一个数字对中文是「字」、对英文是「字符」，
 * 而英文一个六字母单词的阅读量只相当于两个汉字 —— 「多少字符」既不符合母语直觉，
 * 也无法让人判断一篇文章要练多久。这里把主计数收敛成用户熟悉的单位：
 * 汉字（中文）、文字（日文）、单词（英俄韩等空格分词语言），其余文字体系退回字符。
 *
 * 统计口径：
 * - 主计数 = 汉字数（中文）/ 汉字 + 假名数（日文）/ 单词数（空格分词语言）/ 可见字符数（其他）
 * - 「字符数（计空格）」不含换行；「字符数（不计空格）」再减去空格
 * - 标点只算 Unicode 标点类；emoji、货币等符号单列，不混进标点，避免「符号也算标点」
 * - 段落按空行分隔（与详情页正文的渲染口径一致），硬换行不另起一段
 */
object TextStatistics {

    enum class CountUnit {
        /** 中文：按汉字计数 */
        HAN,

        /** 日文：按汉字 + 假名计数 */
        KANA,

        /** 空格分词语言（英/俄/韩等）：按单词计数 */
        WORD,

        /** 其他文字体系：按去掉空白的字符计数 */
        CHARACTER,
    }

    data class Stats(
        val unit: CountUnit,
        /** 顶栏与统计弹窗里那个「主要数字」 */
        val primaryCount: Int,
        /** 全部码点，含换行与空格 */
        val totalChars: Int,
        /** 字符数（计空格）：不计换行 */
        val charsWithSpaces: Int,
        /** 字符数（不计空格） */
        val charsNoSpaces: Int,
        val han: Int,
        val kana: Int,
        val hangul: Int,
        val latinLetters: Int,
        val cyrillicLetters: Int,
        val otherLetters: Int,
        val digits: Int,
        val punctuation: Int,
        val symbols: Int,
        val spaces: Int,
        val paragraphs: Int,
        /** 空格分词的单词数（含汉字/假名/谚文串；仅 [CountUnit.WORD] 用） */
        val words: Int,
        /** 含非中日韩字符的单词数：中文文章里就是「英文/数字单词」的数量 */
        val nonCjkWords: Int,
    ) {
        val letters: Int get() = latinLetters + cyrillicLetters + otherLetters
    }

    fun analyze(text: String): Stats {
        var totalChars = 0
        var lineBreakChars = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var latinLetters = 0
        var cyrillicLetters = 0
        var otherLetters = 0
        var digits = 0
        var punctuation = 0
        var symbols = 0
        var spaces = 0

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            totalChars++
            when {
                cp == '\n'.code || cp == '\r'.code -> lineBreakChars++
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> spaces++
                isHan(cp) -> han++
                isKana(cp) -> kana++
                isHangul(cp) -> hangul++
                Character.isLetter(cp) -> when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.LATIN -> latinLetters++
                    Character.UnicodeScript.CYRILLIC -> cyrillicLetters++
                    else -> otherLetters++
                }
                Character.isDigit(cp) -> digits++
                isPunctuation(Character.getType(cp)) -> punctuation++
                isSymbol(Character.getType(cp)) -> symbols++
                // 组合记号、控制符、格式符等：计入总字符数，不归入上面任何一类
            }
            i += Character.charCount(cp)
        }

        val (words, nonCjkWords) = countWords(text)
        val charsWithSpaces = totalChars - lineBreakChars
        val charsNoSpaces = totalChars - lineBreakChars - spaces

        // 中文判定看「汉字数 vs 非中文单词数」：一个英文单词的阅读量约等于两个汉字，
        // 但按字符数比会把「学习 Kotlin 很有趣」这种句子误判成英文。
        val unit = when {
            kana > 0 -> CountUnit.KANA
            hangul > 0 && hangul >= han -> CountUnit.WORD
            han > 0 && han > nonCjkWords -> CountUnit.HAN
            latinLetters + cyrillicLetters > 0 -> CountUnit.WORD
            // 希腊/阿拉伯/泰文等：有空白就按词，没有空白（泰文连写）退回字符
            otherLetters > 0 -> if (spaces > 0 || lineBreakChars > 0) CountUnit.WORD else CountUnit.CHARACTER
            else -> CountUnit.CHARACTER
        }
        val primaryCount = when (unit) {
            CountUnit.HAN -> han
            CountUnit.KANA -> han + kana
            CountUnit.WORD -> words
            CountUnit.CHARACTER -> charsNoSpaces
        }

        return Stats(
            unit = unit,
            primaryCount = primaryCount,
            totalChars = totalChars,
            charsWithSpaces = charsWithSpaces,
            charsNoSpaces = charsNoSpaces,
            han = han,
            kana = kana,
            hangul = hangul,
            latinLetters = latinLetters,
            cyrillicLetters = cyrillicLetters,
            otherLetters = otherLetters,
            digits = digits,
            punctuation = punctuation,
            symbols = symbols,
            spaces = spaces,
            paragraphs = countParagraphs(text),
            words = words,
            nonCjkWords = nonCjkWords,
        )
    }

    private fun isHan(cp: Int): Boolean =
        cp == 0x3007 || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    private fun isKana(cp: Int): Boolean =
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA ||
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA

    private fun isHangul(cp: Int): Boolean =
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL

    private fun isCjk(cp: Int): Boolean = isHan(cp) || isKana(cp) || isHangul(cp)

    private fun isPunctuation(type: Int): Boolean = when (type) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

    private fun isSymbol(type: Int): Boolean = when (type) {
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        -> true
        else -> false
    }

    /**
     * 单词按空白切分，词内撇号/连字符不拆词。
     * [nonCjkWords] 额外要求词里至少有一个非中日韩字符，供中文文章的「英文单词」一行使用。
     */
    private fun countWords(text: String): Pair<Int, Int> {
        var words = 0
        var nonCjkWords = 0
        var inWord = false
        var wordHasNonCjk = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                Character.isLetterOrDigit(cp) -> {
                    if (!inWord) {
                        inWord = true
                        wordHasNonCjk = false
                    }
                    if (!isCjk(cp)) wordHasNonCjk = true
                }
                // 词内连接符：don't / well-known 仍算一个词
                inWord && (cp == '\''.code || cp == '’'.code || cp == '-'.code) -> Unit
                else -> {
                    if (inWord) {
                        words++
                        if (wordHasNonCjk) nonCjkWords++
                        inWord = false
                    }
                }
            }
            i += Character.charCount(cp)
        }
        if (inWord) {
            words++
            if (wordHasNonCjk) nonCjkWords++
        }
        return words to nonCjkWords
    }

    /** 空行分隔的段落数；连续的非空行视为同一段，避免 PDF 硬换行把段数放大。 */
    private fun countParagraphs(text: String): Int {
        if (text.isEmpty()) return 0
        var paragraphs = 0
        var hasContent = false
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                if (hasContent) {
                    paragraphs++
                    hasContent = false
                }
            } else {
                hasContent = true
            }
        }
        if (hasContent) paragraphs++
        return paragraphs
    }
}
