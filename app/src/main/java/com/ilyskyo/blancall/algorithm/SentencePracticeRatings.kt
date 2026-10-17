// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.algorithm.FsrsEngine.Rating

/**
 * 逐句评级：把一场 graded 练习里每个句子的作答质量折算成 FSRS 评级。
 *
 * 为什么要它：默写与挖空练习以前只产出**一个**文章级评级（几十个空塌成一个 AGAIN/GOOD），
 * 而句子级 FSRS 状态一直是「句子卡片自评」那套命名空间专用 —— 两套评级体系今天互不相交，
 * 于是产品内核（FSRS 记的是句）与实际数据（只有篇）对不上：统计页说这篇练熟了，
 * 具体哪句生疏调度器一无所知。
 *
 * 键空间沿用 [SentenceSelector.sentenceKey] 的 `s:<articleId>:<hash16>`，
 * 所以存储层、备份格式、句子卡片读取全都是零改动 —— 只是多了一种写入来源。
 */
object SentencePracticeRatings {

    /**
     * @param results 空位序号 → 判题结果（只含本次实际判过的空，未完成提交跳过的空不在内）
     * @param blankSentenceIndex 空位序号 → 该空所属句子在**当次 cloze 句子表**里的下标
     * @return 句子下标 → 评级。没有可判空位的句子不产出（不该被记成「练过且全对」）。
     *
     * 「对」只算 [AnswerChecker.Result.CORRECT]，与 [PracticeRecord.correctCount] 同一口径 ——
     * 一处把近似对也算对、另一处不算，是统计与调度对不上的老源头。
     * 评级映射复用 [FsrsEngine.ratingFromAccuracy]，不在这里另立一档阈值。
     */
    fun bySentence(
        results: Map<Int, AnswerChecker.Result>,
        blankSentenceIndex: (Int) -> Int?
    ): Map<Int, Rating> {
        val total = mutableMapOf<Int, Int>()
        val correct = mutableMapOf<Int, Int>()
        results.forEach { (blankIndex, result) ->
            val sentenceIndex = blankSentenceIndex(blankIndex) ?: return@forEach
            total[sentenceIndex] = (total[sentenceIndex] ?: 0) + 1
            if (result == AnswerChecker.Result.CORRECT) {
                correct[sentenceIndex] = (correct[sentenceIndex] ?: 0) + 1
            }
        }
        return total.mapValues { (sentenceIndex, count) ->
            val ratio = (correct[sentenceIndex] ?: 0).toFloat() / count
            FsrsEngine.ratingFromAccuracy(ratio)
        }
    }
}
