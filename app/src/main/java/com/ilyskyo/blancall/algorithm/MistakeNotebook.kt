// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.toErrorType

/**
 * 错题本聚合：把「每次练习里散落的错」收成「同一个字反复错」的清单。
 *
 * ## 为什么不能只拿 mistakes 聚合
 * `mistakes` 只记错处。只按它分组，一个三周前错、后来每次都对字会永远挂在
 * 「未攻克」里，用户会开始不信这个清单。新记录因此同时落 [PracticeRecord.answeredBlanks]
 * （逐空全量结果）；本对象用「同一句 + 同一个答案」的**最后一次结果**判断是否攻克。
 *
 * ## 为什么以文字为锚而不是句子索引
 * 句子索引是全文切句下标，文章一编辑整体错位，跨文练习甚至写 -1。
 * 旧报告里「跳回原句会跳错」说的就是它。这里用判分当时保存的 [sentenceText]
 * 作为身份与定位依据；索引只在没有文字时退用，并标记 `exact=false`。
 *
 * ## 旧记录
 * 没有 answeredBlanks 的历史记录无法判断后来是否写对。条目不丢（仍是证据），
 * 但 `exact=false`，界面必须显示「旧记录无法判断是否攻克」，不能假装已解决。
 */
object MistakeNotebook {

    /** 单次判分里的一个空（答对或答错都算一次 outcome）。 */
    data class Outcome(
        val recordId: Long,
        val articleId: Long,
        val at: Long,
        val blankIndex: Int,
        val correctAnswer: String,
        val userAnswer: String,
        val errorType: String,
        val sentenceText: String,
        val sentenceIndex: Int,
        val similarity: Float,
        val correct: Boolean,
        /** true = 来自 answeredBlanks（逐空全量历史）；false = 旧记录只有错处 */
        val exact: Boolean,
    )

    data class Entry(
        val articleId: Long,
        /** 聚合身份：同句同答案视为同一处 */
        val key: String,
        val correctAnswer: String,
        val sentenceText: String,
        val sentenceIndex: Int,
        /** 这个空一共出现过几次（新记录才有完整计数） */
        val attempts: Int,
        val wrongCount: Int,
        val lastAt: Long,
        val lastUserAnswer: String,
        val lastErrorType: String,
        val lastSimilarity: Float,
        /** 最近一次发生错误的那条记录（墨迹按它取） */
        val lastWrongRecordId: Long,
        val lastWrongBlankIndex: Int,
        /** 最近一次出现时是否答对 */
        val resolved: Boolean,
        /** 是否有逐空全量历史（false = 旧记录，无法判断攻克） */
        val exact: Boolean,
    ) {
        val errorType: com.ilyskyo.blancall.data.model.ErrorType
            get() = lastErrorType.toErrorType()
    }

    /** 展开全部记录为逐空 outcomes；旧记录只有错处。 */
    fun outcomes(records: List<PracticeRecord>): List<Outcome> = buildList {
        for (r in records) {
            if (r.answeredBlanks.isNotEmpty()) {
                for (a in r.answeredBlanks) {
                    add(
                        Outcome(
                            recordId = r.id,
                            articleId = r.articleId,
                            at = r.timestamp,
                            blankIndex = a.blankIndex,
                            correctAnswer = a.correctAnswer,
                            userAnswer = a.userAnswer,
                            errorType = a.errorType,
                            sentenceText = a.sentenceText,
                            sentenceIndex = a.sentenceIndex,
                            similarity = a.similarity,
                            correct = a.correct,
                            exact = true,
                        )
                    )
                }
            } else {
                for (m in r.mistakes) {
                    add(
                        Outcome(
                            recordId = r.id,
                            articleId = r.articleId,
                            at = r.timestamp,
                            blankIndex = m.blankIndex,
                            correctAnswer = m.correctAnswer,
                            userAnswer = m.userAnswer,
                            errorType = m.errorType,
                            sentenceText = m.sentenceText,
                            sentenceIndex = m.sentenceIndex,
                            similarity = m.similarity,
                            correct = false,
                            exact = false,
                        )
                    )
                }
            }
        }
    }

    /**
     * 聚合为错题条目，按最近错误时间倒序。
     *
     * @param articleId 只看这一篇；null = 全部
     * @param onlyUnresolved 只保留「最近一次仍写错」的；旧记录（exact=false）无论此开关都保留，
     *   因为无法证明它已攻克
     */
    fun entries(
        records: List<PracticeRecord>,
        articleId: Long? = null,
        onlyUnresolved: Boolean = false,
    ): List<Entry> {
        val groups = outcomes(records)
            .filter { articleId == null || it.articleId == articleId }
            .groupBy { identity(it) }
        val out = ArrayList<Entry>(groups.size)
        for ((key, group) in groups) {
            if (group.isEmpty()) continue
            val sorted = group.sortedWith(compareBy({ it.at }, { it.recordId }))
            val last = sorted.last()
            val lastWrong = sorted.lastOrNull { !it.correct } ?: continue
            // 只认**最后一次出现**的来源：那一次若来自 answeredBlanks，才有资格判断攻克；
            // 来自旧记录（只有错处）的「最后一次」无法证明中间写对过。
            val exact = last.exact
            val resolved = exact && last.correct
            if (onlyUnresolved && resolved) continue
            out += Entry(
                articleId = last.articleId,
                key = key,
                correctAnswer = last.correctAnswer,
                sentenceText = sorted.lastOrNull { it.sentenceText.isNotBlank() }?.sentenceText.orEmpty(),
                sentenceIndex = sorted.lastOrNull { it.sentenceIndex >= 0 }?.sentenceIndex ?: -1,
                attempts = group.size,
                wrongCount = group.count { !it.correct },
                lastAt = last.at,
                lastUserAnswer = lastWrong.userAnswer,
                lastErrorType = lastWrong.errorType,
                lastSimilarity = lastWrong.similarity,
                lastWrongRecordId = lastWrong.recordId,
                lastWrongBlankIndex = lastWrong.blankIndex,
                resolved = resolved,
                exact = exact,
            )
        }
        return out.sortedWith(compareByDescending<Entry> { it.lastAt }.thenBy { it.correctAnswer })
    }

    /** 同一句 + 同一答案 = 同一处。句子文字缺失时退回索引，再缺就只按答案。 */
    internal fun identity(o: Outcome): String {
        val sentenceKey = o.sentenceText
            .replace(Regex("\\s+"), "")
            .ifBlank { if (o.sentenceIndex >= 0) "i:${o.sentenceIndex}" else "" }
        return "${o.articleId}\u0000${o.correctAnswer.trim().lowercase()}\u0000$sentenceKey"
    }

    /**
     * 形近对照：把「应该是」与「你写的」里第一对形近字找出来，供三栏卡展示。
     * 没有形近关系（或长度不等）返回 null —— 宁可不显示，也不要把不相干的字硬配对。
     */
    fun glyphPair(expected: String, actual: String): Pair<Char, Char>? {
        if (expected.length != actual.length) return null
        for (i in expected.indices) {
            val a = expected[i]
            val b = actual[i]
            if (a != b && GlyphConfusables.isGlyphConfused(a, b)) return a to b
        }
        return null
    }

    // ── 只练这一批 ──

    data class DrillBlank(
        val sentenceIndex: Int,
        val start: Int,
        val endExclusive: Int,
        val answer: String,
    )

    data class Drill(
        val articleId: Long,
        val blanks: List<DrillBlank>,
        /** 有文字但当前正文里找不到的条目数（界面需要如实告知，不静默丢） */
        val unresolved: Int,
    )

    /**
     * 把错题条目映射回**当前**正文的句子区间，生成一次性挖空配置。
     *
     * 定位顺序：句文字精确匹配 → 句文字包含匹配 → 旧索引且该句含答案。
     * 全都找不到的条目计入 [Drill.unresolved]，不猜位置（猜错等于让用户练错句）。
     */
    fun buildDrill(content: String, articleId: Long, entries: List<Entry>): Drill {
        val sentences = SentenceSplitter.split(content)
        val usedRanges = HashSet<Pair<Int, IntRange>>()
        val blanks = ArrayList<DrillBlank>()
        var unresolved = 0
        for (e in entries) {
            if (e.articleId != articleId) continue
            val byText = e.sentenceText.takeIf { it.isNotBlank() }?.let { wanted ->
                val normalized = wanted.replace(Regex("\\s+"), "")
                sentences.indexOfFirst { it.replace(Regex("\\s+"), "") == normalized }
                    .takeIf { it >= 0 }
                    ?: sentences.indexOfFirst { s ->
                        val ns = s.replace(Regex("\\s+"), "")
                        ns.contains(normalized) || normalized.contains(ns)
                    }.takeIf { it >= 0 }
            }
            val idx = byText
                ?: e.sentenceIndex.takeIf { it in sentences.indices && sentences[it].contains(e.correctAnswer) }
            if (idx == null) {
                unresolved++
                continue
            }
            val sentence = sentences[idx]
            val start = sentence.indexOf(e.correctAnswer)
            if (start < 0) {
                unresolved++
                continue
            }
            val range = start until (start + e.correctAnswer.length)
            if (!usedRanges.add(idx to range)) continue
            blanks += DrillBlank(
                sentenceIndex = idx,
                start = start,
                endExclusive = start + e.correctAnswer.length,
                answer = e.correctAnswer,
            )
        }
        return Drill(articleId, blanks.sortedWith(compareBy({ it.sentenceIndex }, { it.start })), unresolved)
    }
}
