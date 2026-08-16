// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 默写文本相似度评分器：原文复现场景的连续评分（0..1）。
 *
 * 混合双通道评分（缓解长短文本偏差）：
 * - 字符通道（0.7 权重）：整段归一化后 Damerau-Levenshtein 相似度
 *   sim = 1 - dist / maxLen（含换位感知，中文常见"前后颠倒"错误可被正确计为 1 次）
 * - 分句通道（0.3 权重）：按原文分句，用户句贪心最佳匹配后取平均，
 *   未默写出的句子计 0，防止长文本把局部错误稀释
 *
 * 防御：超长输入截断（对齐 AnswerChecker.checkDictation 的 20k/5k 模式）、
 * 编辑距离 maxDist 带状提前终止、全量 catch 防 OOM。
 */
object DictationScorer {

    /** 整段输入截断上限（编辑距离为 O(n·m)，超长无标点文本会计算爆炸） */
    private const val MAX_INPUT_LENGTH = 20_000

    /** 单句截断上限 */
    private const val MAX_CLAUSE_LENGTH = 5_000

    /** 分句匹配的最低相似度阈值（低于视为该句未默写出来） */
    private const val MATCH_THRESHOLD = 0.4f

    /** 字符通道权重（分句通道为 1 - CHAR_WEIGHT） */
    private const val CHAR_WEIGHT = 0.7f

    /**
     * 计算默写相似度（0..1）。空原文返回 0；双方都空返回 1。
     * 可在任意线程调用，但长文本建议放后台线程。
     */
    fun score(correct: String, user: String): Float {
        if (correct.isBlank() && user.isBlank()) return 1f
        if (correct.isBlank()) return 0f
        if (user.isBlank()) return 0f
        return try {
            val safeCorrect = if (correct.length > MAX_INPUT_LENGTH) correct.take(MAX_INPUT_LENGTH) else correct
            val safeUser = if (user.length > MAX_INPUT_LENGTH) user.take(MAX_INPUT_LENGTH) else user
            val cNorm = TextNormalizer.normalize(safeCorrect)
            val uNorm = TextNormalizer.normalize(safeUser)
            if (cNorm.isEmpty() && uNorm.isEmpty()) return 1f
            if (cNorm.isEmpty() || uNorm.isEmpty()) return 0f

            // ── 字符通道：整段 DL 相似度 ──
            val maxLen = maxOf(cNorm.length, uNorm.length)
            val maxDist = maxLen / 2
            val dist = damerauLevenshtein(uNorm, cNorm, maxDist)
            val charSim = if (dist > maxDist) 0f else 1f - dist.toFloat() / maxLen

            // ── 分句通道：原文分句，用户句贪心最佳匹配 ──
            val clauseSim = clauseSimilarity(safeCorrect, safeUser)

            (CHAR_WEIGHT * charSim + (1f - CHAR_WEIGHT) * clauseSim).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            // 极端输入（OOM/异常）时兜底：按保守值处理，不影响练习主流程
            0f
        }
    }

    /**
     * 分句通道：原文每句在用户句中找相似度最高的未匹配句；
     * 用户没默写出的句子计 0，取平均（防止长文本稀释局部错误）。
     * 先归一化再分句：保证原文与用户文本切分粒度一致（标点/空白差异不会导致分句错位）。
     */
    private fun clauseSimilarity(correct: String, user: String): Float {
        val cNormFull = TextNormalizer.normalize(correct)
        val uNormFull = TextNormalizer.normalize(user)
        val correctClauses = SentenceSplitter.split(cNormFull).filter { it.isNotBlank() }
        if (correctClauses.isEmpty()) return 1f
        // 用户句按相同分句器切分（与原文粒度一致）
        val userClauses = SentenceSplitter.split(uNormFull).filter { it.isNotBlank() }
        if (userClauses.isEmpty()) return 0f

        val userNorm = userClauses.map { TextNormalizer.normalize(it.take(MAX_CLAUSE_LENGTH)) }
        val used = BooleanArray(userNorm.size) { false }

        var total = 0f
        var count = 0
        for (clause in correctClauses) {
            val cNorm = clause.take(MAX_CLAUSE_LENGTH)
            if (cNorm.isEmpty()) continue
            count++
            var bestSim = 0f
            var bestIdx = -1
            val cLen = cNorm.length
            for (i in userNorm.indices) {
                if (used[i]) continue
                val uLen = userNorm[i].length
                if (uLen == 0) continue
                // 长度预筛：相似度上界 = 1 - |lenA-lenB|/maxLen，低于当前最优或阈值则跳过 DL
                val maxLen = maxOf(cLen, uLen)
                val upperBound = 1f - kotlin.math.abs(cLen - uLen).toFloat() / maxLen
                if (upperBound < bestSim || upperBound < MATCH_THRESHOLD) continue
                val sim = similarityWithBand(cNorm, userNorm[i])
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = i
                    if (bestSim >= 0.95f) break
                }
            }
            if (bestIdx >= 0 && bestSim >= MATCH_THRESHOLD) {
                used[bestIdx] = true
                total += bestSim
            }
            // 未匹配：total += 0（该句没默写出来）
        }
        return if (count == 0) 1f else total / count
    }

    /** 带状 DL 相似度：dist 超过 maxDist 时直接返回 0 */
    private fun similarityWithBand(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        val maxDist = maxLen / 2
        val dist = damerauLevenshtein(a, b, maxDist)
        return if (dist > maxDist) 0f else 1f - dist.toFloat() / maxLen
    }

    /**
     * Damerau-Levenshtein（OSA：最优字符串对齐）编辑距离。
     * 支持相邻换位（transposition）+ maxDist 带状提前终止（滚动数组，O(min(m,n)) 空间）。
     * 距离超过 maxDist 返回 maxDist+1。
     */
    private fun damerauLevenshtein(a: String, b: String, maxDist: Int): Int {
        val m = a.length
        val n = b.length
        if (kotlin.math.abs(m - n) > maxDist) return maxDist + 1
        if (m == 0) return n
        if (n == 0) return m
        // 带状 DL 用三行滚动：prev2（两行前，供换位）、prev、curr
        var prev2 = IntArray(n + 1) { it }
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // delete
                    curr[j - 1] + 1,   // insert
                    prev[j - 1] + cost // substitute
                )
                // 相邻换位：a[i-1]==b[j-2] && a[i-2]==b[j-1]
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    curr[j] = minOf(curr[j], prev2[j - 2] + 1)
                }
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            // 带状截止：整行最小距离已超 maxDist，后续只增不减
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev2
            prev2 = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
