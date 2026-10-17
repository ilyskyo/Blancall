// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 智能答案检测算法：标点容错 + 模糊匹配 + 详细诊断
 * 支持中英文混排：英文大小写容错、数字小数点保留、全半角归一化
 */
object AnswerChecker {

    /** 「写错」与「没记住」之间的那条线：等长答案里对不上的位置不超过 3 个（或长度的三分之一） */
    private const val TYPO_MAX_MISMATCH = 3

    enum class Result {
        CORRECT,      // 正确（含仅标点差异）
        TYPO,         // 记成他字：长度一致、同一位置换成了**不相干**的字（判题不辨字形/读音，故不叫「错别字」）
        GLYPH_CONFUSED, // 字形混淆：换上的字与正字同属一个形近组（己/已/巳、未/末…），看得见却写错
        MISSING,      // 少字
        EXTRA,        // 多字
        WRONG_ORDER,  // 顺序错误
        INCORRECT     // 完全不对
    }

    data class CheckDetail(
        val result: Result,
        val correctAnswer: String,
        val userAnswer: String,
        /** 机器可读的差异明细：文案由 UI 层按 [Result] + 本结构组装（算法层不再产出用户可见文案） */
        val diff: Diff? = null,
        /** 答案相似度 0..1（基于核心文本归一化编辑距离） */
        val similarity: Float = 0f
    )

    /**
     * 判题差异的结构化明细：只携带事实（字符、位置、计数），不含任何文案。
     *
     * 早先这里是拼好的中文句（"字形混淆（第3字「己」→「已」）"）——界面切到英/日/韩/俄时
     * 整行仍显示简体中文，而所有多语言守卫全绿。文案如今按 result 取 strings.xml（键 `check_*`），
     * 这里只留机器可读的部分。
     */
    sealed interface Diff {
        /** 标点/空白层面的差异（仅 [Result.CORRECT] 情形携带；两串都空表示"略有差异"） */
        data class Surface(val missingPunct: String, val extraPunct: String) : Diff

        /** 逐位替换（[Result.GLYPH_CONFUSED] / [Result.TYPO]）：1-based 位置 + 期望字 → 实写字，最多 3 条 */
        data class Replacement(val items: List<Item>) : Diff {
            data class Item(val position: Int, val expected: Char, val written: Char)
        }

        /** 缺字（[Result.MISSING]）：缺几个；[fragment] 为单个连续缺失段（不连续或过长时空串，避免误导） */
        data class Missing(val count: Int, val fragment: String) : Diff

        /** 多字（[Result.EXTRA]）：多几个；[fragment] 同上 */
        data class Extra(val count: Int, val fragment: String) : Diff

        /** 完全不对（[Result.INCORRECT]）：期望与实写的开头片段（各至多 6 字） */
        data class FarOff(val expectedSnippet: String, val writtenSnippet: String) : Diff
    }

    // 「怎么算等价」只有一份，在 TextNormalizer 里：繁简、中文数字、全半角、空白、大小写
    // 加上标点开关。这里原先自带一套（删标点 + 全半角 + lowercase，不折繁简），
    // 与评级那条通道各算各的，于是繁体作答「页面判错、系统记成 GOOD」。
    // 标点集也搬过去了 —— 只有描述差异时还要认得哪些字符算标点。

    /** 判「算不算对」用的核心文本：标点不参与比对 */
    private fun core(s: String): String = TextNormalizer.forAnswerCompare(s)

    fun check(correct: String, user: String): CheckDetail {
        val correctTrimmed = correct.trim()
        val userTrimmed = user.trim()

        // ── 空答案 ──
        if (userTrimmed.isEmpty()) {
            return CheckDetail(Result.MISSING, correctTrimmed, userTrimmed, similarity = 0f)
        }

        // ── 完全一致 ──
        if (correctTrimmed == userTrimmed) {
            return CheckDetail(Result.CORRECT, correctTrimmed, userTrimmed, similarity = 1f)
        }

        // ── 标点/空白归一化后比对（与 FSRS 评级通道同一份等价类：全半角 + 大小写 + 繁简 + 中文数字） ──
        val correctCore = core(correctTrimmed)
        val userCore = core(userTrimmed)

        if (correctCore.isEmpty() && userCore.isEmpty()) {
            return CheckDetail(Result.CORRECT, correctTrimmed, userTrimmed, similarity = 1f)
        }

        if (correctCore == userCore) {
            // 核心内容完全一致，差异仅在于标点/空白/全半角/大小写
            return CheckDetail(
                Result.CORRECT, correctTrimmed, userTrimmed,
                diff = surfaceDiff(correctTrimmed, userTrimmed), similarity = 1f
            )
        }

        // ── 基于核心文本做长度分析 ──
        val coreLen = correctCore.length
        val userCoreLen = userCore.length
        // 核心文本相似度（用于 UI 展示，与反向默写统一口径）
        val sim = similarity(correctCore, userCore)

        // ── 分型：只看差异的结构，不看「差几个字」 ──
        // 旧实现三道门限都是 `ratio <= 0.5f && editDist <= 2`，于是两种最主流的作答形态
        // 一辈子拿不到分型：句子空错 3 个字（长答案 editDist>2）落 INCORRECT，
        // 字词空（长度 1）ratio 恒为 1.0 也恒落 INCORRECT。按 errorType 分桶的错题统计、
        // 弱点雷达、AI 讲的「错因」因此实际只有「对 / 错」两种，「漏字 vs 记错 vs 顺序颠倒」
        // 这个产品上很好用的区分从来没出现过。
        // 注：TYPO 的展示名是「记错」而非「错别字」—— 判题只比字形是否相同，不辨形近/音近，
        // 把它叫「错别字」会让用户以为系统在判定笔误，实际它只管「这个位置上的字不对」。
        val typed = classifyDiff(correctCore, userCore)
        return when (typed) {
            Result.WRONG_ORDER -> CheckDetail(
                typed, correctTrimmed, userTrimmed, similarity = sim
            )
            Result.GLYPH_CONFUSED -> CheckDetail(
                typed, correctTrimmed, userTrimmed,
                diff = Diff.Replacement(replacements(correctCore, userCore)), similarity = sim
            )
            Result.TYPO -> CheckDetail(
                typed, correctTrimmed, userTrimmed,
                diff = Diff.Replacement(replacements(correctCore, userCore)), similarity = sim
            )
            Result.MISSING -> CheckDetail(
                typed, correctTrimmed, userTrimmed,
                diff = Diff.Missing(
                    coreLen - userCoreLen, singleMissingRun(correctCore, userCore)
                ), similarity = sim
            )
            Result.EXTRA -> CheckDetail(
                typed, correctTrimmed, userTrimmed,
                diff = Diff.Extra(
                    userCoreLen - coreLen, singleExtraRun(correctCore, userCore)
                ), similarity = sim
            )
            else -> CheckDetail(
                typed, correctTrimmed, userTrimmed,
                diff = Diff.FarOff(correctCore.take(6), userCore.take(6)), similarity = sim
            )
        }
    }

    /**
     * 按差异结构分型：缺失 / 多余 / 换位 / 写错 / 没记住。
     *
     * 判据是结构而不是距离：一方是另一方的按序子序列就叫少字或多字（差几个字都一样），
     * 字符多重集相同而顺序不同就叫顺序错；只有同一位置上换字才叫「换字」——
     * 换上的字若与正字同属一个形近组（[GlyphConfusables]）就是**字形混淆**，
     * 否则是**记成他字**。后两者原先挤在同一个桶里，展示成「错别字」，
     * 把「没记清字形」与「记成了另一个字」混为一谈。
     * 「换字」与「没记住」之间只留一条阈值，讲的是还剩多少内容 —— 三分之一以内算换字，
     * 再多就是没记住。长答案错几个字终于能被记成换字，单字空也不再恒判 INCORRECT。
     */
    internal fun classifyDiff(expected: String, actual: String): Result = when {
        expected == actual -> Result.CORRECT
        expected.toCharArray().sorted() == actual.toCharArray().sorted() -> Result.WRONG_ORDER
        actual.length < expected.length && isSubsequence(actual, expected) -> Result.MISSING
        actual.length > expected.length && isSubsequence(expected, actual) -> Result.EXTRA
        else -> {
            val lenDiff = kotlin.math.abs(expected.length - actual.length)
            when {
                // 又换又漏、不构成子序列时，按长度方向归到少/多字（差 1-2 个字以内）
                lenDiff in 1..2 -> if (actual.length < expected.length) Result.MISSING else Result.EXTRA
                // 长度差 ≥3 又不构成子序列：内容差得太多，直接判「没记住」。
                // 必须显式写出来 —— 旧写法把 mismatch 置成 -1，再靠「-1 落在 0..tolerance 之外」
                // 隐式落到 INCORRECT，读的人得先在脑子里跑一遍区间判断才知道这一支在说什么。
                lenDiff != 0 -> Result.INCORRECT
                else -> {
                    // 走到这里必然等长。逐位比，差异集中在个别位置才算「换字」，
                    // 超过容差就是「没记住」（讲的是还剩多少内容）。
                    val mismatch = expected.indices.count { expected[it] != actual[it] }
                    val tolerance = maxOf(TYPO_MAX_MISMATCH, expected.length / 3)
                    when {
                        mismatch > tolerance -> Result.INCORRECT
                        // 差异位置全是形近字 → 字形混淆；否则记成他字。
                        // 分这两类而不并作一类，是因为复习动作不同（辨析字形 vs 补记忆）。
                        GlyphConfusables.isGlyphConfusedSubstitution(expected, actual) ->
                            Result.GLYPH_CONFUSED
                        else -> Result.TYPO
                    }
                }
            }
        }
    }

    /** shorter 能否按序在 longer 里找到（不要求连续） */
    private fun isSubsequence(shorter: String, longer: String): Boolean {
        var i = 0
        for (c in longer) {
            if (i < shorter.length && shorter[i] == c) i++
        }
        return i == shorter.length
    }

    // ═══════════════════════════════════════════
    //  工具函数
    // ═══════════════════════════════════════════

    /**
     * 去标点与空白（小数点在数字之间保留）。
     * 实现已收进 [TextNormalizer]，这里留一层转发是因为既有测试与调用方认这个名字。
     */
    internal fun stripPunctAndSpace(s: String): String = TextNormalizer.forAnswerCompare(s)

    /** 标点/空白层面的差异（仅当核心内容一致时调用）：只给事实，文案在 UI 层 */
    private fun surfaceDiff(correct: String, user: String): Diff.Surface {
        val corPunct = correct.filter { TextNormalizer.isSurfacePunctuation(it) }
        val usrPunct = user.filter { TextNormalizer.isSurfacePunctuation(it) }
        return when {
            corPunct.isEmpty() && usrPunct.isNotEmpty() -> Diff.Surface(missingPunct = "", extraPunct = usrPunct)
            corPunct.isNotEmpty() && usrPunct.isEmpty() -> Diff.Surface(missingPunct = corPunct, extraPunct = "")
            // 两侧都有标点（或都无）：归入「标点略有差异」，与旧文案同口径
            else -> Diff.Surface(missingPunct = "", extraPunct = "")
        }
    }

    /**
     * 单个连续缺失段（[Result.MISSING] 用；此时 actual 是 expected 的按序子序列）。
     *
     * 多段缺失或段落过长时返回空串 —— 片段只是辅助提示，宁可不说也不能误导
     * （把两段缺失拼成一个片段会让人以为缺的字是连着的）。
     */
    private fun singleMissingRun(expected: String, actual: String): String {
        val gaps = mutableListOf<IntRange>()
        var j = 0
        var start = -1
        for (i in expected.indices) {
            if (j < actual.length && expected[i] == actual[j]) {
                j++
                if (start >= 0) { gaps.add(start until i); start = -1 }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) gaps.add(start until expected.length)
        if (gaps.size != 1) return ""
        val text = expected.substring(gaps[0].first, gaps[0].last + 1)
        return if (text.length <= 4) text else text.take(4) + "…"
    }

    /** 单个连续多余段（[Result.EXTRA] 用；此时 expected 是 actual 的按序子序列），规则同 [singleMissingRun] */
    private fun singleExtraRun(expected: String, actual: String): String {
        val gaps = mutableListOf<IntRange>()
        var j = 0
        var start = -1
        for (i in actual.indices) {
            if (j < expected.length && actual[i] == expected[j]) {
                j++
                if (start >= 0) { gaps.add(start until i); start = -1 }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) gaps.add(start until actual.length)
        if (gaps.size != 1) return ""
        val text = actual.substring(gaps[0].first, gaps[0].last + 1)
        return if (text.length <= 4) text else text.take(4) + "…"
    }

    /** 逐位比较，收集替换明细（1-based 位置 + 期望字 → 实写字），最多 3 条（超出只提示前面的） */
    private fun replacements(expected: String, actual: String): List<Diff.Replacement.Item> {
        val maxLen = maxOf(expected.length, actual.length)
        val items = mutableListOf<Diff.Replacement.Item>()
        for (i in 0 until maxLen) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(i)
            if (e != null && a != null && e != a) {
                items.add(Diff.Replacement.Item(i + 1, e, a))
            }
        }
        return items.take(3)
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
     * 反向默写判分：用户默写整段，与原文分句逐句最佳匹配
     * @param originalClauses 原文分句列表（正确顺序，逗号/句号粒度）
     * @param userInput 用户默写的整段文本
     */
    fun checkDictation(
        originalClauses: List<String>,
        userInput: String
    ): DictationCheckResult {
        // 防御：超长输入截断（编辑距离为 O(m*n)，超长无标点文本会让计算爆炸导致卡死/OOM）
        val safeInput = if (userInput.length > 20_000) userInput.take(20_000) else userInput
        // 用户输入按分句粒度切分（逗号/句号等），与原文分句对齐匹配
        val userClauses = splitUserInputIntoClauses(safeInput)
        if (originalClauses.isEmpty()) {
            return DictationCheckResult(emptyList(), 0f, 0f, 0f, 0f)
        }
        // 原文分句归一化缓存（与逐空判分同一份等价类）
        val originalNorm = originalClauses.map { core(it) }
        val used = BooleanArray(originalClauses.size) { false }
        val sentenceResults = mutableListOf<DictationSentenceResult>()

        for (uIdx in userClauses.indices) {
            val userSent = userClauses[uIdx]
            // 防御：单分句超长截断（编辑距离为 O(m*n)，保护极端输入）
            val userSentSafe = if (userSent.length > 5_000) userSent.take(5_000) else userSent
            val userNorm = core(userSentSafe)
            if (userNorm.isEmpty()) {
                sentenceResults.add(DictationSentenceResult(userSent, null, -1, 0f, Result.MISSING))
                continue
            }
            // 在未匹配的原文分句中找相似度最高的
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
                    // 相似度落在「像但不对」区间时再分一步：整句只有形近字写错 → 字形混淆，
                    // 否则记成他字。与逐空判分同一张表、同一条判据（口径只有一份）。
                    bestSim >= 0.7f ->
                        if (GlyphConfusables.isGlyphConfusedSubstitution(originalNorm[bestIdx], userNorm))
                            Result.GLYPH_CONFUSED else Result.TYPO
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
        // 顺序正确率：用户分句中，匹配到的且 matchIndex 递增的比例
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

    /** 把用户默写的整段文本按分句粒度切分（逗号/句号等标点保留在前一个分句末尾） */
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
