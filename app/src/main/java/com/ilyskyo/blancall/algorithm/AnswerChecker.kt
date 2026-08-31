// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 智能答案检测算法：标点容错 + 模糊匹配 + 详细诊断
 * 支持中英文混排：英文大小写容错、数字小数点保留、全半角归一化
 */
object AnswerChecker {

    enum class Result {
        CORRECT,      // 正确（含仅标点差异）
        TYPO,         // 错别字/近似正确
        MISSING,      // 少字
        EXTRA,        // 多字
        WRONG_ORDER,  // 顺序错误
        INCORRECT     // 完全不对
    }

    data class CheckDetail(
        val result: Result,
        val correctAnswer: String,
        val userAnswer: String,
        val message: String,
        /** 答案相似度 0..1（基于核心文本归一化编辑距离） */
        val similarity: Float = 0f
    )

    // ── 中文标点 ──
    private val CHINESE_PUNCT = setOf(
        '，', '。', '！', '？', '；', '：', '、',
        '“', '”', '‘', '’', '（', '）', '【', '】', '《', '》',
        '…', '—', '～', '「', '」', '『', '』',
        '·', '﹐', '﹑'
    )
    // ── 英文标点 ──
    private val ENGLISH_PUNCT = setOf(
        ',', '.', '!', '?', ';', ':', '(', ')', '[', ']', '<', '>',
        '"', '\'', '-', '_', '/', '\\', '|', '@', '#', '$', '%', '^', '&', '*',
        '+', '=', '`', '~'
    )

    fun check(correct: String, user: String): CheckDetail {
        val correctTrimmed = correct.trim()
        val userTrimmed = user.trim()

        // ── 空答案 ──
        if (userTrimmed.isEmpty()) {
            return CheckDetail(Result.MISSING, correctTrimmed, userTrimmed, "未作答", 0f)
        }

        // ── 完全一致 ──
        if (correctTrimmed == userTrimmed) {
            return CheckDetail(Result.CORRECT, correctTrimmed, userTrimmed, "正确", 1f)
        }

        // ── 标点/空白归一化后比对（含全半角 + 大小写归一化，英文容错，中文不受影响） ──
        val correctCore = toHalfWidth(stripPunctAndSpace(correctTrimmed)).lowercase()
        val userCore = toHalfWidth(stripPunctAndSpace(userTrimmed)).lowercase()

        if (correctCore.isEmpty() && userCore.isEmpty()) {
            return CheckDetail(Result.CORRECT, correctTrimmed, userTrimmed, "正确", 1f)
        }

        if (correctCore == userCore) {
            // 核心内容完全一致，差异仅在于标点/空白/全半角/大小写
            val punctNote = describeSurfaceDiff(correctTrimmed, userTrimmed)
            return CheckDetail(Result.CORRECT, correctTrimmed, userTrimmed, "正确$punctNote", 1f)
        }

        // ── 基于核心文本做长度分析 ──
        val coreLen = correctCore.length
        val userCoreLen = userCore.length
        val editDist = levenshtein(correctCore, userCore)
        // 核心文本相似度（用于 UI 展示，与反向默写统一口径）
        val sim = similarity(correctCore, userCore)

        // ── 少字 ──
        if (userCoreLen < correctCore.length) {
            val ratio = editDist.toFloat() / correctCore.length
            return if (ratio <= 0.5f && editDist <= 2) {
                val diffInfo = diffChars(correctCore, userCore)
                CheckDetail(Result.MISSING, correctTrimmed, userTrimmed, "少字${diffInfo}（缺 ${correctCore.length - userCoreLen} 个字）", sim)
            } else {
                val diffInfo = diffChars(correctCore, userCore)
                CheckDetail(Result.INCORRECT, correctTrimmed, userTrimmed, "不正确${diffInfo}", sim)
            }
        }

        // ── 多字 ──
        if (userCoreLen > correctCore.length) {
            val ratio = editDist.toFloat() / userCoreLen
            return if (ratio <= 0.5f && editDist <= 2) {
                val diffInfo = diffChars(correctCore, userCore)
                CheckDetail(Result.EXTRA, correctTrimmed, userTrimmed, "多字${diffInfo}（多了 ${userCoreLen - correctCore.length} 个字）", sim)
            } else {
                val diffInfo = diffChars(correctCore, userCore)
                CheckDetail(Result.INCORRECT, correctTrimmed, userTrimmed, "不正确${diffInfo}", sim)
            }
        }

        // ── 长度相同 → 错别字 vs 顺序错误 vs 完全不同 ──
        val correctSorted = correctCore.toCharArray().sorted()
        val userSorted = userCore.toCharArray().sorted()

        if (correctSorted == userSorted) {
            return CheckDetail(Result.WRONG_ORDER, correctTrimmed, userTrimmed, "顺序错误（字都对，但顺序不对）", sim)
        }

        // 编辑距离很小 → 错别字
        if (editDist <= 2 && editDist.toFloat() / correctCore.length <= 0.5f) {
            val diffInfo = diffPositions(correctCore, userCore)
            return CheckDetail(Result.TYPO, correctTrimmed, userTrimmed, "错别字（$diffInfo 不一样）", sim)
        }

        // 兜底：完全不对
        val diffInfo = diffChars(correctCore, userCore)
        return CheckDetail(Result.INCORRECT, correctTrimmed, userTrimmed, "不正确${diffInfo}", sim)
    }

    // ═══════════════════════════════════════════
    //  工具函数
    // ═══════════════════════════════════════════

    /** 去除中文+英文标点 + 所有空白（小数点在数字之间保留，避免数字答案误判） */
    internal fun stripPunctAndSpace(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (c.isWhitespace()) continue
            if (c in CHINESE_PUNCT || c in ENGLISH_PUNCT) {
                // 小数点在数字之间保留（如 3.14 / 1.0），其余标点去掉
                if (c == '.' && i > 0 && i < s.length - 1 &&
                    s[i - 1].isDigit() && s[i + 1].isDigit()
                ) {
                    sb.append(c)
                }
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** 全角 ASCII → 半角 */
    internal fun toHalfWidth(s: String): String {
        return s.map { c ->
            when {
                c in '！'..'～' -> (c.code - 0xFEE0).toChar()
                c == '　' -> ' '
                else -> c
            }
        }.joinToString("")
    }

    /** 描述标点/空白层面的差异（仅当核心内容一致时调用） */
    private fun describeSurfaceDiff(correct: String, user: String): String {
        val corPunct = correct.filter { it.isWhitespace() || it in CHINESE_PUNCT || it in ENGLISH_PUNCT }
        val usrPunct = user.filter { it.isWhitespace() || it in CHINESE_PUNCT || it in ENGLISH_PUNCT }
        return when {
            corPunct.isEmpty() && usrPunct.isNotEmpty() -> "（多了标点「$usrPunct」）"
            corPunct.isNotEmpty() && usrPunct.isEmpty() -> "（漏了标点「$corPunct」）"
            else -> "（标点略有差异）"
        }
    }

    /** 简短描述两个字符串的差异 */
    private fun diffChars(expected: String, actual: String): String {
        if (expected.isEmpty() && actual.isEmpty()) return ""
        if (actual.isEmpty()) return "（期望「$expected」）"
        if (expected.isEmpty()) return "（你写了「$actual」）"

        // 找最长公共子串
        val lcs = longestCommonSubstring(expected, actual)
        if (lcs.length >= 2) {
            val before = expected.substringBefore(lcs).take(4)
            val after = expected.substringAfter(lcs).take(4)
            val userBefore = actual.substringBefore(lcs).take(4)
            val userAfter = actual.substringAfter(lcs).take(4)
            val parts = mutableListOf<String>()
            if (userBefore.isNotEmpty()) parts.add("多「$userBefore」")
            if (before.isNotEmpty()) parts.add("缺「$before」")
            if (after != userAfter) parts.add("差异「$after」→「$userAfter」")
            return if (parts.isEmpty()) "" else "（${parts.joinToString("；")}）"
        }

        // 完全无公共子串
        return "（期望「${expected.take(6)}」，你写的是「${actual.take(6)}」）"
    }

    /** 最长公共子串（滚动数组，O(min(m,n)) 空间；时间仍 O(m*n)） */
    private fun longestCommonSubstring(a: String, b: String): String {
        val m = a.length; val n = b.length
        if (m == 0 || n == 0) return ""
        // 长串放外层、短串放内层，使滚动数组长度 = min(m,n)+1
        val (longer, shorter) = if (m >= n) a to b else b to a
        val L = shorter.length
        var maxLen = 0; var endIdxLonger = 0
        var prev = IntArray(L + 1)
        var curr = IntArray(L + 1)
        for (i in 1..longer.length) {
            for (j in 1..L) {
                if (longer[i - 1] == shorter[j - 1]) {
                    curr[j] = prev[j - 1] + 1
                    if (curr[j] > maxLen) {
                        maxLen = curr[j]
                        endIdxLonger = i
                    }
                } else {
                    curr[j] = 0
                }
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return longer.substring(endIdxLonger - maxLen, endIdxLonger)
    }

    /** 逐位比较，列出不同位置 */
    private fun diffPositions(expected: String, actual: String): String {
        val maxLen = maxOf(expected.length, actual.length)
        val diffs = mutableListOf<String>()
        for (i in 0 until maxLen) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(i)
            if (e != a) {
                val desc = buildString {
                    if (e != null) append("「$e」")
                    append("→")
                    if (a != null) append("「$a」")
                }
                diffs.add("第${i + 1}字$desc")
            }
        }
        return diffs.take(3).joinToString("；")
    }

    /** Levenshtein 编辑距离（滚动数组，O(min(m,n)) 空间） */
    internal fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // delete
                    curr[j - 1] + 1,   // insert
                    prev[j - 1] + cost // substitute
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    // ═══════════════════════════════════════════
    //  反向默写（段落打散默写）整段判分
    // ═══════════════════════════════════════════

    /** 单句匹配结果 */
    data class DictationSentenceResult(
        val userText: String,
        val matchedOriginal: String?,      // 匹配到的原文句子（null=未匹配）
        val matchIndex: Int,               // 匹配到的原文句子索引（-1=未匹配）
        val similarity: Float,             // 相似度 0..1
        val result: Result
    )

    /** 整段默写判分结果 */
    data class DictationCheckResult(
        val sentences: List<DictationSentenceResult>,
        val coverageRate: Float,          // 覆盖率：匹配到的句数 / 原文句数
        val accuracyRate: Float,           // 准确率：平均相似度
        val orderCorrectRate: Float,      // 顺序正确率：顺序递增的句数 / 用户句数
        val overallScore: Float           // 综合得分 0..1
    )

    /**
     * 反向默写判分：用户默写整段，与原文从句逐句最佳匹配
     * @param originalClauses 原文从句列表（正确顺序，逗号/句号粒度）
     * @param userInput 用户默写的整段文本
     */
    fun checkDictation(
        originalClauses: List<String>,
        userInput: String
    ): DictationCheckResult {
        // 防御：超长输入截断（编辑距离为 O(m*n)，超长无标点文本会让计算爆炸导致卡死/OOM）
        val safeInput = if (userInput.length > 20_000) userInput.take(20_000) else userInput
        // 用户输入按从句粒度切分（逗号/句号等），与原文从句对齐匹配
        val userClauses = splitUserInputIntoClauses(safeInput)
        if (originalClauses.isEmpty()) {
            return DictationCheckResult(emptyList(), 0f, 0f, 0f, 0f)
        }
        // 原文从句归一化缓存
        val originalNorm = originalClauses.map { toHalfWidth(stripPunctAndSpace(it)).lowercase() }
        val used = BooleanArray(originalClauses.size) { false }
        val sentenceResults = mutableListOf<DictationSentenceResult>()

        for (uIdx in userClauses.indices) {
            val userSent = userClauses[uIdx]
            // 防御：单从句超长截断（编辑距离为 O(m*n)，保护极端输入）
            val userSentSafe = if (userSent.length > 5_000) userSent.take(5_000) else userSent
            val userNorm = toHalfWidth(stripPunctAndSpace(userSentSafe)).lowercase()
            if (userNorm.isEmpty()) {
                sentenceResults.add(DictationSentenceResult(userSent, null, -1, 0f, Result.MISSING))
                continue
            }
            // 在未匹配的原文从句中找相似度最高的
            var bestIdx = -1
            var bestSim = 0f
            val userLen = userNorm.length
            for (oIdx in originalClauses.indices) {
                if (used[oIdx]) continue
                val oNorm = originalNorm[oIdx]
                if (oNorm.isEmpty()) continue
                // 长度预筛：编辑距离 >= |lenA - lenB|，故相似度上界 = 1 - |lenA-lenB|/maxLen。
                // 若该上界低于当前 bestSim 或匹配阈值 0.5，similarity 必然不达标，跳过 levenshtein 计算。
                val oLen = oNorm.length
                val maxLen = maxOf(userLen, oLen)
                val lenDiff = kotlin.math.abs(userLen - oLen)
                val simUpperBound = 1f - lenDiff.toFloat() / maxLen
                if (simUpperBound < bestSim || simUpperBound < 0.5f) continue
                val sim = similarity(oNorm, userNorm)
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = oIdx
                    // 提前终止：已达 CORRECT 阈值，不可能更高
                    if (bestSim >= 0.95f) break
                }
            }
            if (bestIdx >= 0 && bestSim >= 0.5f) {
                used[bestIdx] = true
                val res = when {
                    bestSim >= 0.95f -> Result.CORRECT
                    bestSim >= 0.7f -> Result.TYPO
                    else -> Result.INCORRECT
                }
                sentenceResults.add(DictationSentenceResult(
                    userSent, originalClauses[bestIdx], bestIdx, bestSim, res
                ))
            } else {
                sentenceResults.add(DictationSentenceResult(userSent, null, -1, bestSim, Result.INCORRECT))
            }
        }

        val matchedCount = sentenceResults.count { it.matchIndex >= 0 }
        val coverageRate = matchedCount.toFloat() / originalClauses.size
        val accuracyRate = if (sentenceResults.isEmpty()) 0f
            else sentenceResults.map { it.similarity }.average().toFloat()
        // 顺序正确率：用户从句中，匹配到的且 matchIndex 递增的比例
        var orderCorrect = 0
        var lastMatchedIdx = -1
        for (r in sentenceResults) {
            if (r.matchIndex >= 0) {
                if (r.matchIndex > lastMatchedIdx) orderCorrect++
                lastMatchedIdx = r.matchIndex
            }
        }
        val orderCorrectRate = if (userClauses.isEmpty()) 0f
            else orderCorrect.toFloat() / userClauses.size
        val overallScore = (coverageRate * 0.4f + accuracyRate * 0.4f + orderCorrectRate * 0.2f)
            .coerceIn(0f, 1f)
        return DictationCheckResult(
            sentences = sentenceResults,
            coverageRate = coverageRate,
            accuracyRate = accuracyRate,
            orderCorrectRate = orderCorrectRate,
            overallScore = overallScore
        )
    }

    /** 把用户默写的整段文本按从句粒度切分（逗号/句号等标点保留在前一个从句末尾） */
    private fun splitUserInputIntoClauses(text: String): List<String> {
        val punct = setOf('，', ',', ';', '；', '、', '。', '.', '！', '!', '？', '?', '…', '\n')
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in punct) {
                val s = current.toString().trim()
                if (s.isNotEmpty()) result.add(s)
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            val s = current.toString().trim()
            if (s.isNotEmpty()) result.add(s)
        }
        return result
    }

    /** 相似度：1 - 归一化编辑距离 */
    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        val dist = levenshtein(a, b)
        return 1f - dist.toFloat() / maxLen
    }
}
