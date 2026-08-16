// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯算法层单元测试：覆盖答案判定、挖空生成、难度计算三个核心模块。
 * 这些模块不依赖 Android 框架，可在 JVM 上直接运行，作为包名重构后的编译 + 行为回归保障。
 */
class AlgorithmTest {

    // ───────────── AnswerChecker ─────────────

    @Test
    fun exactMatchIsCorrect() {
        val d = AnswerChecker.check("明月", "明月")
        assertEquals(AnswerChecker.Result.CORRECT, d.result)
        assertEquals(1f, d.similarity, 0.001f)
    }

    @Test
    fun punctuationDiffStillCorrect() {
        val d = AnswerChecker.check("明月", "明月。")
        assertEquals(AnswerChecker.Result.CORRECT, d.result)
    }

    @Test
    fun englishCaseInsensitive() {
        val d = AnswerChecker.check("moon", "MOON")
        assertEquals(AnswerChecker.Result.CORRECT, d.result)
    }

    @Test
    fun fullWidthNormalizedToHalfWidth() {
        val d = AnswerChecker.check("ｍｏｏｎ", "moon")
        assertEquals(AnswerChecker.Result.CORRECT, d.result)
    }

    @Test
    fun fewerCharsIsMissingOrIncorrect() {
        val d = AnswerChecker.check("明月", "明")
        assertTrue(
            "少字应判定为 MISSING 或 INCORRECT，实际=${d.result}",
            d.result == AnswerChecker.Result.MISSING || d.result == AnswerChecker.Result.INCORRECT
        )
    }

    @Test
    fun typoDetected() {
        val d = AnswerChecker.check("明月", "明星")
        assertEquals(AnswerChecker.Result.TYPO, d.result)
    }

    @Test
    fun emptyAnswerIsMissing() {
        val d = AnswerChecker.check("明月", "   ")
        assertEquals(AnswerChecker.Result.MISSING, d.result)
    }

    @Test
    fun levenshteinDistanceCorrect() {
        assertEquals(0, AnswerChecker.levenshtein("abc", "abc"))
        assertEquals(1, AnswerChecker.levenshtein("abc", "abd"))
        assertEquals(3, AnswerChecker.levenshtein("kitten", "sitting"))
    }

    @Test
    fun stripPunctKeepsDecimalPoint() {
        val r = AnswerChecker.stripPunctAndSpace("3.14")
        assertEquals("3.14", r)
    }

    // ───────────── BlancallGenerator ─────────────

    @Test
    fun sentenceClozeMakesAtLeastOneBlank() {
        val content = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val res = BlancallGenerator.generateSentenceCloze(content)
        assertTrue("应至少挖一个空，实际=${res.blanks.size}", res.blanks.isNotEmpty())
        assertTrue("显示文本应包含 ___ 占位符", res.displayText.contains("___"))
    }

    @Test
    fun wordClozeRespectsRequestedCount() {
        val content = "锄禾日当午，汗滴禾下土。谁知盘中餐，粒粒皆辛苦。"
        val res = BlancallGenerator.generateWordCloze(content, count = 3)
        assertTrue("应按指定数量生成空，实际=${res.blanks.size}", res.blanks.size == 3)
        assertTrue(res.displayText.contains("___"))
    }

    @Test
    fun dictationShufflesAndBlanksClauses() {
        val content = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val res = BlancallGenerator.generateDictation(content)
        assertEquals(4, res.clauses.size)
        assertEquals(4, res.shuffledClauses.size)
        assertTrue(res.shuffledClauses.all { it.displayText.contains("___") })
    }

    // ───────────── DifficultyCalculator ─────────────

    @Test
    fun nonChineseCharHasLowDifficulty() {
        assertEquals(0.2f, DifficultyCalculator.calculateCharDifficulty('A'), 0.001f)
        assertEquals(0.2f, DifficultyCalculator.calculateCharDifficulty('1'), 0.001f)
    }

    @Test
    fun difficultyWithinZeroToOne() {
        val chars = listOf('的', '月', '鑫', 'a', '盈', '矗')
        for (c in chars) {
            val d = DifficultyCalculator.calculateCharDifficulty(c)
            assertTrue("难度应在 [0,1]，实际=$d (char=$c)", d in 0f..1f)
        }
    }

    @Test
    fun highFreqCharEasierThanRareChar() {
        // 两字笔画相同(8画)，但「的」在高频集、「盈」不在，难度应更低
        val common = DifficultyCalculator.calculateCharDifficulty('的')
        val rare = DifficultyCalculator.calculateCharDifficulty('盈')
        assertTrue("高频字难度应低于生僻字，common=$common rare=$rare", common < rare)
    }
}
