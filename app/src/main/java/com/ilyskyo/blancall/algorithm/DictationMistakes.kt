// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.MistakeDetail

/**
 * 反向默写的逐句错题装配：把判分阶段已经算出来的逐句诊断落成错题明细。
 *
 * 为什么单独成一个纯函数：这段装配以前不存在 —— `checkDictation` 早就算出
 * coverage / accuracy / orderCorrect 与逐句「应为 X、实际 Y」，落盘时却塌成
 * `correctCount = (overallScore × total).toInt()` 加 `mistakes = emptyList()`。
 * 结果三种模式里唯独默写没有可回顾的错处：进不了错题本、进不了记忆热力图、
 * 弱点雷达对 REVERSE 全盲。用户默写错 5 句，事后只知道自己"72 分"。
 *
 * 抽成纯函数还有第二个理由：默写的 `blankIndex` 是**分句序号**而不是空位序号，
 * 与挖空那套坐标不同源。把换算只留在这一个地方，别处再想用错口径就没那么容易。
 */
object DictationMistakes {

    /**
     * @param clauses 原文分句（判分时的同一份输入，顺序即分句序号）
     * @param check 整段默写的判分结果
     *
     * 产出两类条目：
     * - 用户写出来但没写对的句子：按判分给的分型（TYPO / INCORRECT…）落，带逐句相似度；
     * - 原文里有、用户整句没默写出来的句子：落 MISSING —— 这一类在 `check.sentences` 里
     *   根本不存在（那个列表只遍历用户写出来的分句），不补就等于「漏了一整句」这件事从不留痕。
     *
     * `sentenceIndex` 一律留 -1：分句序号与全文切句句子索引不是同一个空间，
     * 硬填会让热力图把默写的错算到别的句子上（见 [MemoryHeatmap] 里对 REVERSE 的那道闸）。
     */
    fun assemble(
        clauses: List<String>,
        check: AnswerChecker.DictationCheckResult
    ): List<MistakeDetail> {
        val mistakes = mutableListOf<MistakeDetail>()

        check.sentences.forEach { s ->
            if (s.result == AnswerChecker.Result.CORRECT) return@forEach
            mistakes += MistakeDetail(
                blankIndex = s.matchIndex.coerceAtLeast(0),
                correctAnswer = s.matchedOriginal ?: clauses.getOrElse(s.matchIndex) { "" },
                userAnswer = s.userText,
                errorType = s.result.name,
                sentenceIndex = -1,
                similarity = s.similarity
            )
        }

        val matched = check.sentences.map { it.matchIndex }.toSet()
        clauses.forEachIndexed { idx, clause ->
            if (idx in matched || clause.isBlank()) return@forEachIndexed
            mistakes += MistakeDetail(
                blankIndex = idx,
                correctAnswer = clause,
                userAnswer = "",
                errorType = AnswerChecker.Result.MISSING.name,
                sentenceIndex = -1,
                similarity = 0f
            )
        }
        return mistakes.sortedBy { it.blankIndex }
    }
}
