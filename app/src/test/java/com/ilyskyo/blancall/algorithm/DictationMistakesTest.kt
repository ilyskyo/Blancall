// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向默写的逐句错题装配 + 它在记忆热力图里的那道闸。
 *
 * 落盘形状以前塌成「一个 correctCount + 空 mistakes」，所以这三条断言在旧代码上必然是红的
 * （旧代码根本没有这些明细可查）—— 这就是「先证明它会红」的那一步。
 */
class DictationMistakesTest {

    private val clauses = listOf("庆历四年春，", "谪守巴陵郡。", "越明年，", "政通人和。")

    private fun sentence(
        user: String,
        matched: String?,
        index: Int,
        sim: Float,
        result: AnswerChecker.Result
    ) = AnswerChecker.DictationSentenceResult(user, matched, index, sim, result)

    private fun check(vararg s: AnswerChecker.DictationSentenceResult) =
        AnswerChecker.DictationCheckResult(s.toList(), 0.5f, 0.6f, 0.7f, 0.6f)

    @Test
    fun `写错的句子逐句留下应为与实际`() {
        val mistakes = DictationMistakes.assemble(
            clauses,
            check(
                sentence("庆历四年春，", clauses[0], 0, 1f, AnswerChecker.Result.CORRECT),
                sentence("谪守巴陵君。", clauses[1], 1, 0.75f, AnswerChecker.Result.TYPO),
                sentence("越明年，", clauses[2], 2, 1f, AnswerChecker.Result.CORRECT)
            )
        )
        // 两句全对的不进明细；剩下的正好是「一处写错」加「一整句没默写出来」
        assertEquals(2, mistakes.size)
        val only = mistakes.first { it.errorType == "TYPO" }
        assertEquals("谪守巴陵郡。", only.correctAnswer)
        assertEquals("谪守巴陵君。", only.userAnswer)
        assertEquals(0.75f, only.similarity, 1e-6f)
        val missing = mistakes.first { it.errorType == "MISSING" }
        assertEquals(clauses[3], missing.correctAnswer)
        assertTrue(missing.userAnswer.isEmpty())
    }

    @Test
    fun `整句没默写出来记成漏字而不是凭空消失`() {
        // checkDictation 的 sentences 只遍历【用户写出来的】分句：整句漏掉时那一句话语不在列表里，
        // 不补这一步，「漏了一整句」在明细里就完全不存在
        val mistakes = DictationMistakes.assemble(
            clauses,
            check(sentence("庆历四年春，", clauses[0], 0, 1f, AnswerChecker.Result.CORRECT))
        )
        val missing = mistakes.filter { it.errorType == "MISSING" }
        assertEquals("其余三句都要留下漏字记录", 3, missing.size)
        assertEquals(listOf("谪守巴陵郡。", "越明年，", "政通人和。"), missing.map { it.correctAnswer })
        assertTrue(missing.all { it.userAnswer.isEmpty() })
    }

    @Test
    fun `分句序号不许冒充全文句子索引`() {
        // 默写的 blankIndex 是分句序号，与热力图那套全文切句子索引不同源；
        // 填了就会把默写的错算到别的句子上，所以这里必须一律 -1
        val mistakes = DictationMistakes.assemble(
            clauses,
            check(
                sentence("越明年，", clauses[2], 2, 0.9f, AnswerChecker.Result.TYPO),
                sentence("乱七八糟", null, -1, 0.2f, AnswerChecker.Result.INCORRECT)
            )
        )
        assertTrue("有句子索引落了盘：${mistakes.map { it.sentenceIndex }}",
            mistakes.all { it.sentenceIndex == -1 })
    }

    @Test
    fun `默写明细不会让热力图把错算到猜的句子上`() {
        val content = "床前明月光。疑是地上霜！举头望明月？低头思故乡。"
        val starts = SentenceSplitter.splitWithPositions(content).map { it.startIndex }
        val dictationRecord = PracticeRecord(
            articleId = 5L, mode = "REVERSE", totalBlanks = 2, correctCount = 0,
            timestamp = 1_700_000_000_000L,
            mistakes = listOf(
                MistakeDetail(0, "床前", "床上", "TYPO", -1, 0.5f),
                MistakeDetail(1, "明月光", "名月光", "TYPO", -1, 0.6f)
            ),
            answeredSentenceStarts = listOf(starts[0])
        )
        val data = MemoryHeatmap.generate(content, listOf(dictationRecord))
        // 落点认不出来就整条不计：宁可这句显示「未练」，也不给一个说不出口的红
        assertTrue("默写的错被按比例猜到了句子上：${data.sentences.map { it.errorRate }}",
            data.sentences.all { it.errorRate == 0f })
    }

    @Test
    fun `挖空记录的逐空相似度按原样留存`() {
        // 会话级均值（PracticeRecord.similarity）以前是唯一的相似度数字，
        // 于是「一笔之差」和「全错」在盘上长得一样；逐空那份现在必须能原样读回来
        val detail = MistakeDetail(3, "明月", "名月", "TYPO", 1, 0.7f)
        assertEquals(3, detail.blankIndex)
        assertEquals(1, detail.sentenceIndex)
        assertEquals(0.7f, detail.similarity, 1e-6f)
        assertEquals(-1f, MistakeDetail(0, "a", "b", "TYPO").similarity, 1e-6f)
    }
}
