// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * 挖空生成器：支持句子挖空和字词挖空两种模式
 * - 句子挖空：可挖从句、半句、整句（按逗号/分号粒度切分，相邻选中从句自动合并）
 * - 字词挖空：挖 1-3 字词或英文单词，相邻选中词自动合并为一个空
 * - 支持动态自适应策略（均衡/薄弱点优先/全覆盖）
 * - 支持反向默写（段落打散后默写原文）
 * - 支持古文模式（优先挖实词，虚词可选不挖）
 * - 支持中英文混排
 */
object BlancallGenerator {

    /** 逗号/分号切分正则（短文本兜底用，编译一次） */
    private val CLAUSE_SPLIT_REGEX = Regex("[，,;；]")
    /** 从句切分标点（编译一次） */
    private val CLAUSE_PUNCT = setOf('，', ',', ';', '；', '、')
    /** 英文单词匹配（无汉字时兜底挖空，编译一次避免重复编译） */
    private val ENGLISH_WORD_REGEX = Regex("[A-Za-z]+")

    /** 挖空策略 */
    enum class Strategy {
        BALANCED,         // 均衡模式
        WEAKNESS_FOCUS,   // 薄弱点优先
        FULL_COVERAGE     // 全篇全覆盖
    }

    /** 错误历史权重数据 */
    data class ErrorProfile(
        val sentenceErrorRates: Map<Int, Float> = emptyMap(),  // 句子索引 → 错误率
        val charErrorRates: Map<Char, Float> = emptyMap(),     // 字符 → 错误率
        val wordErrorRates: Map<String, Float> = emptyMap(),   // 词 → 错误率（英文用小写）
        /** 记忆强度因子（由 FSRS 留存率导出）：1=记忆正常，>1=记忆偏弱 */
        val memoryFactor: Float = 1f
    )

    data class SentenceClozeResult(
        val sentences: List<String>,
        val blanks: List<SentenceBlankInfo>,
        val displayText: String
    )

    data class SentenceBlankInfo(
        val index: Int,
        val originalText: String,          // 被挖掉的原文（可能是从句、半句或整句）
        val sentenceIndex: Int,
        val startInSentence: Int,          // 在句内的起始字符位置
        val endInSentence: Int             // 在句内的结束字符位置（exclusive）
    )

    data class WordClozeResult(
        val sentences: List<WordClozeSentence>,
        val blanks: List<WordBlankInfo>,
        val displayText: String,
        val maxBlanks: Int = 0,
        val suggestedBlanks: Int = 0
    )

    data class WordClozeSentence(
        val text: String,
        val blanks: List<Int> = emptyList()
    )

    data class WordBlankInfo(
        val index: Int,
        val originalChar: String,
        val position: Int
    )

    // ========== 句子挖空：支持从句/半句/整句 ==========

    fun generateSentenceCloze(
        content: String,
        count: Int = 0,
        errorProfile: ErrorProfile = ErrorProfile(),
        strategy: Strategy = Strategy.BALANCED
    ): SentenceClozeResult {
        var allSentences = SentenceSplitter.split(content)

        // 短文本兜底：按逗号/分号再次切分
        if (allSentences.size <= 1 && content.length > 5) {
            allSentences = content.split(CLAUSE_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.any { isChinese(it) } }
            if (allSentences.isEmpty()) {
                allSentences = listOf(content.trim())
            }
        }

        if (allSentences.isEmpty()) {
            return SentenceClozeResult(emptyList(), emptyList(), content)
        }

        // 将每句按逗号/分号/顿号拆分为从句
        data class Clause(
            val text: String,
            val sentenceIdx: Int,
            val startInSentence: Int,
            val endInSentence: Int
        )

        val allClauses = mutableListOf<Clause>()
        for (sIdx in allSentences.indices) {
            val sentence = allSentences[sIdx]
            val parts = splitByClausePunctuation(sentence)
            // 用累加指针记录位置，避免 indexOf 在重复短语时定位错误
            var pos = 0
            for (part in parts) {
                if (part.isNotBlank()) {
                    allClauses.add(Clause(part, sIdx, pos, pos + part.length))
                }
                pos += part.length
            }
        }

        // 确定挖几个空（自动档：记忆偏弱时适度加量，更充分复习易忘内容）
        val totalClauses = allClauses.size
        val baseCount = when {
            count > 0 -> count.coerceIn(1, totalClauses)
            totalClauses == 1 -> 1
            totalClauses <= 4 -> maxOf(1, totalClauses / 2)
            else -> maxOf(1, totalClauses / 3)
        }
        val densityScale = if (count > 0) 1f else errorProfile.memoryFactor.coerceIn(1f, 1.6f)
        val actualCount = (baseCount * densityScale).toInt().coerceIn(1, totalClauses)

        // 策略驱动选择要挖的从句
        val selectedClauseIndices = when (strategy) {
            Strategy.WEAKNESS_FOCUS -> {
                // 按错误率加权排序，优先选薄弱从句
                val weighted = allClauses.indices.map { idx ->
                    val sentIdx = allClauses[idx].sentenceIdx
                    val errorRate = errorProfile.sentenceErrorRates[sentIdx] ?: 0f
                    idx to (errorRate + 0.1f)  // 基础权重 0.1 避免零概率
                }.sortedByDescending { it.second }
                weighted.take(actualCount).map { it.first }.toMutableSet()
            }
            Strategy.FULL_COVERAGE -> {
                // 均匀分布，每个从句都有机会（用 toMutableSet 去重后补足）
                val step = (allClauses.size.toFloat() / actualCount).toInt().coerceAtLeast(1)
                val indices = linkedSetOf<Int>()
                var pos = 0
                while (indices.size < actualCount && pos < allClauses.size) {
                    indices.add(pos.coerceAtMost(allClauses.size - 1))
                    pos += step
                }
                // 如果因步长问题不足，从尾部补充
                var fill = allClauses.size - 1
                while (indices.size < actualCount && fill >= 0) {
                    indices.add(fill)
                    fill--
                }
                indices.take(actualCount).toSet()
            }
            else -> {
                // 均衡：随机但有微弱薄弱倾斜；记忆偏弱时倾斜更强（更偏向薄弱句）
                val mf = errorProfile.memoryFactor.coerceIn(1f, 1.6f)
                val weighted = allClauses.indices.map { idx ->
                    val sentIdx = allClauses[idx].sentenceIdx
                    val errorRate = errorProfile.sentenceErrorRates[sentIdx] ?: 0f
                    idx to (0.5f + errorRate * 0.5f * mf)
                }
                // 加权随机采样（每次移除已选项后重新计算总权重）
                val selected = mutableSetOf<Int>()
                val tempWeights = weighted.toMutableList()
                repeat(actualCount) {
                    if (tempWeights.isEmpty()) return@repeat
                    val currentTotal = tempWeights.sumOf { it.second.toDouble() }
                    var r = Random.nextDouble() * currentTotal
                    var chosen = tempWeights.last().first
                    for ((idx, w) in tempWeights) {
                        r -= w
                        if (r <= 0) { chosen = idx; break }
                    }
                    selected.add(chosen)
                    tempWeights.removeAll { it.first == chosen }
                }
                selected
            }
        }

        // 合并同一句内相邻的选中从句（如选中了逗号前的从句和逗号后的从句 → 合并为一个空 = 整句）
        val blankGroups = mutableListOf<List<Clause>>()
        var i = 0
        while (i < allClauses.size) {
            if (i in selectedClauseIndices) {
                val group = mutableListOf(allClauses[i])
                i++
                while (i < allClauses.size && i in selectedClauseIndices
                    && allClauses[i].sentenceIdx == group.last().sentenceIdx
                ) {
                    group.add(allClauses[i])
                    i++
                }
                blankGroups.add(group)
            } else {
                i++
            }
        }

        // 构建 SentenceBlankInfo 列表
        val blanks = blankGroups.mapIndexed { idx, group ->
            val first = group.first()
            val last = group.last()
            SentenceBlankInfo(
                index = idx,
                originalText = group.joinToString("") { it.text },
                sentenceIndex = first.sentenceIdx,
                startInSentence = first.startInSentence,
                endInSentence = last.endInSentence
            )
        }

        // 构建 displayText（在句内用 [N] ___ 标记挖空位置）
        val displayParts = mutableListOf<String>()
        for (sIdx in allSentences.indices) {
            val sentenceBlanks = blanks.filter { it.sentenceIndex == sIdx }.sortedBy { it.startInSentence }
            if (sentenceBlanks.isEmpty()) {
                displayParts.add(allSentences[sIdx])
            } else {
                val sb = StringBuilder(allSentences[sIdx])
                for (b in sentenceBlanks.reversed()) {
                    sb.replace(b.startInSentence, b.endInSentence, "[${b.index + 1}] ___")
                }
                displayParts.add(sb.toString())
            }
        }

        return SentenceClozeResult(allSentences, blanks, displayParts.joinToString("\n"))
    }

    /** 按逗号/分号/顿号拆分从句，标点保留在前一个从句末尾 */
    private fun splitByClausePunctuation(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in CLAUSE_PUNCT) {
                result.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.ifEmpty { listOf(text) }
    }

    // ========== 字词挖空：1-3字词 + 相邻合并 ==========

    fun generateWordCloze(
        content: String,
        count: Int = 0,
        errorProfile: ErrorProfile = ErrorProfile(),
        strategy: Strategy = Strategy.BALANCED,
        classicalMode: Boolean = false
    ): WordClozeResult {
        val allSentences = SentenceSplitter.split(content)

        // 词候选（连续汉字 1-3 字，或英文单词整体）
        data class Candidate(
            val text: String,
            val sentenceIdx: Int,
            val charStart: Int,
            val charEnd: Int,       // exclusive
            val difficulty: Float
        )

        val candidates = mutableListOf<Candidate>()
        for (sIdx in allSentences.indices) {
            val sentence = allSentences[sIdx]
            var i = 0
            while (i < sentence.length) {
                if (isChinese(sentence[i])) {
                    val runStart = i
                    while (i < sentence.length && isChinese(sentence[i])) i++
                    val runEnd = i
                    for (start in runStart until runEnd) {
                        for (len in 1..minOf(3, runEnd - start)) {
                            val word = sentence.substring(start, start + len)
                            val avgDiff = word.map { DifficultyCalculator.calculateCharDifficulty(it) }.average().toFloat()
                            // 错误历史加权（字级别 + 词级别）；记忆偏弱时错误历史权重更大
                            val mf = errorProfile.memoryFactor.coerceIn(1f, 1.6f)
                            val charErrorBonus = word.maxOfOrNull { errorProfile.charErrorRates[it] ?: 0f } ?: 0f
                            val wordErrorBonus = errorProfile.wordErrorRates[word] ?: 0f
                            val mistakeBonus = (charErrorBonus * 0.3f + wordErrorBonus * 0.4f) * mf
                            // 古文模式：虚词降权
                            val functionWordPenalty = if (classicalMode && isFunctionWord(word)) -0.3f else 0f
                            val difficulty = (avgDiff + mistakeBonus + functionWordPenalty).coerceIn(0f, 1f)
                            candidates.add(Candidate(word, sIdx, start, start + len, difficulty))
                        }
                    }
                } else if (sentence[i].isLetter()) {
                    // 英文单词候选（整个单词作为一个空，错误率用小写 key 匹配）
                    val runStart = i
                    while (i < sentence.length && sentence[i].isLetter()) i++
                    val word = sentence.substring(runStart, i)
                    val wordErrorBonus = (errorProfile.wordErrorRates[word.lowercase()] ?: 0f) * errorProfile.memoryFactor.coerceIn(1f, 1.6f)
                    val difficulty = (0.5f + wordErrorBonus).coerceIn(0f, 1f)
                    candidates.add(Candidate(word, sIdx, runStart, i, difficulty))
                } else {
                    i++
                }
            }
        }

        if (candidates.isEmpty()) {
            return WordClozeResult(
                allSentences.map { WordClozeSentence(it) }, emptyList(), content,
                maxBlanks = 0, suggestedBlanks = 0
            )
        }

        // 最大可挖空数 = 全部中文字符数 + 英文单词数
        val maxBlanks = allSentences.sumOf { s -> s.count { isChinese(it) } + countEnglishWords(s) }
        val totalChineseChars = allSentences.sumOf { s -> s.count { isChinese(it) } }

        // 建议挖空数（保守建议，不等于最大值）
        val suggestedBlanks = when {
            maxBlanks <= 3 -> maxOf(1, maxBlanks)
            maxBlanks <= 10 -> maxOf(2, maxBlanks / 2)
            maxBlanks <= 30 -> maxOf(3, maxBlanks / 3)
            maxBlanks <= 80 -> maxOf(4, maxBlanks / 5)
            else -> maxOf(5, maxBlanks / 8)
        }.coerceIn(1, maxBlanks)

        // 贪心选取不重叠的词
        val sorted = candidates.sortedByDescending { it.difficulty }
        val occupiedBySentence = mutableMapOf<Int, MutableList<IntRange>>()
        val selected = mutableListOf<Candidate>()

        for (c in sorted) {
            val occupied = occupiedBySentence.getOrPut(c.sentenceIdx) { mutableListOf() }
            val range = c.charStart until c.charEnd
            if (occupied.none { it.first <= range.last && range.first <= it.last }) {
                selected.add(c)
                occupied.add(range)
            }
        }

        val finalSelection: List<Candidate>

        if (count > 0) {
            val targetCount = count.coerceIn(1, maxBlanks)

            if (targetCount <= selected.size) {
                // 多字候选够用 → 取前 targetCount 个，不合并
                finalSelection = selected
                    .sortedByDescending { it.difficulty }
                    .take(targetCount)
                    .sortedWith(compareBy({ it.sentenceIdx }, { it.charStart }))
            } else {
                // 多字候选不够 → 混合多字 + 单字补充
                val usedPositions = selected.flatMap {
                    (it.charStart until it.charEnd).map { p -> it.sentenceIdx to p }
                }.toSet()

                val singleChars = candidates
                    .filter { it.text.length == 1 && (it.sentenceIdx to it.charStart) !in usedPositions }
                    .sortedByDescending { it.difficulty }

                val combined = selected + singleChars.take(targetCount - selected.size)

                if (combined.size >= targetCount) {
                    finalSelection = combined.sortedWith(compareBy({ it.sentenceIdx }, { it.charStart }))
                } else {
                    // 多字候选占位过多 → 全部改用单字重选
                    val singleOnly = candidates
                        .filter { it.text.length == 1 }
                        .sortedByDescending { it.difficulty }

                    val redoOccupied = mutableMapOf<Int, MutableList<IntRange>>()
                    val redoSelected = mutableListOf<Candidate>()
                    for (c in singleOnly) {
                        if (redoSelected.size >= targetCount) break
                        val occ = redoOccupied.getOrPut(c.sentenceIdx) { mutableListOf() }
                        val r = c.charStart until c.charEnd
                        if (occ.none { it.first <= r.last && r.first <= it.last }) {
                            redoSelected.add(c)
                            occ.add(r)
                        }
                    }
                    finalSelection = redoSelected.sortedWith(compareBy({ it.sentenceIdx }, { it.charStart }))
                }
            }
        } else {
            // 自动模式
            val actualCount = when {
                maxBlanks <= 5 -> selected.size
                maxBlanks <= 20 -> maxOf(1, selected.size / 3)
                else -> maxOf(1, selected.size / 4)
            }

            val sortedSelected = selected.take(actualCount).sortedWith(compareBy({ it.sentenceIdx }, { it.charStart }))

            // 合并相邻的空
            val merged = mutableListOf<Candidate>()
            var mi = 0
            while (mi < sortedSelected.size) {
                var m = sortedSelected[mi]
                var mj = mi + 1
                while (mj < sortedSelected.size
                    && sortedSelected[mj].sentenceIdx == m.sentenceIdx
                    && sortedSelected[mj].charStart == m.charEnd
                ) {
                    m = Candidate(
                        text = m.text + sortedSelected[mj].text,
                        sentenceIdx = m.sentenceIdx,
                        charStart = m.charStart,
                        charEnd = sortedSelected[mj].charEnd,
                        difficulty = maxOf(m.difficulty, sortedSelected[mj].difficulty)
                    )
                    mj++
                }
                merged.add(m)
                mi = mj
            }
            finalSelection = merged
        }

        // 构建显示文本
        val resultBlanks = mutableListOf<WordBlankInfo>()
        val resultSentences = mutableListOf<WordClozeSentence>()
        val sentenceBuilders = allSentences.map { StringBuilder(it) }.toMutableList()

        // 从后往前替换（用复合比较器，避免整数键冲突）
        for (m in finalSelection.sortedWith(
            compareByDescending<Candidate> { it.sentenceIdx }.thenByDescending { it.charStart }
        )) {
            sentenceBuilders[m.sentenceIdx].replace(m.charStart, m.charEnd, "___")
        }

        var globalIdx = 0
        for (sIdx in allSentences.indices) {
            val displayText = sentenceBuilders[sIdx].toString()
            val blankIndicesInSentence = mutableListOf<Int>()
            for (m in finalSelection) {
                if (m.sentenceIdx == sIdx) {
                    resultBlanks.add(WordBlankInfo(globalIdx, m.text, m.charStart))
                    blankIndicesInSentence.add(globalIdx)
                    globalIdx++
                }
            }
            resultSentences.add(WordClozeSentence(displayText, blankIndicesInSentence))
        }

        val displayText = resultSentences.joinToString("\n") { it.text }
        return WordClozeResult(resultSentences, resultBlanks, displayText,
            maxBlanks = maxBlanks, suggestedBlanks = suggestedBlanks)
    }

    private fun isChinese(ch: Char): Boolean {
        return ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf'
    }

    /** 统计句子中的英文单词数（用于字词挖空的最大空数计算） */
    private fun countEnglishWords(s: String): Int {
        var count = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isLetter() && !isChinese(s[i])) {
                count++
                while (i < s.length && s[i].isLetter()) i++
            } else {
                i++
            }
        }
        return count
    }

    // ═══════════════════════════════════════════
    //  古文虚词（F10）
    // ═══════════════════════════════════════════

    /** 常见文言文 / 白话文虚词（代词、介词、连词、助词、叹词） */
    private val FUNCTION_WORDS = setOf(
        // 单字虚词
        "之", "乎", "者", "也", "矣", "焉", "哉", "耳", "耶", "欤", "邪",
        "而", "以", "于", "其", "为", "所", "与", "则", "且", "乃", "虽",
        "然", "若", "何", "孰", "安", "胡", "曷", "盍", "奚", "恶", "岂",
        "惟", "盖", "夫", "故", "是", "或", "既", "及", "因", "自", "从",
        "诸", "焉", "斯", "兹", "彼", "此", "莫", "勿", "毋", "未", "非",
        "亦", "又", "尚", "犹", "但", "只", "仅", "方", "几", "庶",
        "曾", "尝", "请", "敢", "窃", "辱", "幸", "伏", "忝",
        // 白话文虚词
        "的", "了", "在", "是", "有", "和", "就", "都", "也", "把", "被",
        "让", "给", "向", "对", "从", "到", "用", "以", "为", "因", "所",
        "与", "及", "或", "但", "而", "且", "虽", "然", "如", "若", "则"
    )

    /** 判断是否为虚词（单字直接查表；多字词检查首尾字是否虚词） */
    fun isFunctionWord(word: String): Boolean {
        if (word.length == 1) return word in FUNCTION_WORDS
        if (word.isEmpty()) return false
        // 多字词：检查首尾字是否为虚词
        return word.first().toString() in FUNCTION_WORDS ||
            word.last().toString() in FUNCTION_WORDS
    }

    // ═══════════════════════════════════════════
    //  反向默写（段落打散 + 挖空 + 打乱顺序）
    // ═══════════════════════════════════════════

    /** 打乱后的从句（作为默写线索，已挖空） */
    data class ShuffledClause(
        val displayOrder: Int,      // 展示序号 0,1,2...
        val originalIndex: Int,     // 对应原文从句索引
        val originalText: String,  // 原文从句（完整，用于判分）
        val displayText: String     // 挖好空的从句（含 ___，用于展示/复制）
    )

    /** 反向默写结果：原文从句列表（正确顺序）+ 打乱顺序的挖空从句线索 */
    data class DictationResult(
        val clauses: List<String>,                  // 原文从句列表（正确顺序）
        val shuffledClauses: List<ShuffledClause>    // 打乱顺序的挖空从句
    )

    /** 反向默写切分标点：逗号/分号/顿号/句号/问号/叹号（标点保留在前一个从句末尾） */
    private val DICTATION_CLAUSE_PUNCT = setOf(
        '，', ',', ';', '；', '、', '。', '.', '！', '!', '？', '?', '…'
    )

    /**
     * 反向默写：把段落按从句粒度（逗号/句号等）切分，每个从句挖 1 个空，
     * 然后打乱顺序作为线索，让用户还原顺序并默写原文。
     * @param content 原文（支持中英文混排）
     */
    fun generateDictation(content: String): DictationResult {
        if (content.isBlank()) return DictationResult(emptyList(), emptyList())
        // 先按句末标点切句（处理英文缩写/小数），再按逗号等切从句
        val sentences = SentenceSplitter.split(content)
        val clauses = mutableListOf<String>()
        for (s in sentences) {
            val parts = splitByDictationPunctuation(s)
            clauses.addAll(parts.filter { it.isNotBlank() })
        }
        if (clauses.isEmpty()) return DictationResult(emptyList(), emptyList())

        // 每个从句挖 1 个空，生成展示文本
        val blankedClauses = clauses.map { blankOneWordInClause(it) }

        // 打乱从句顺序
        val indices = clauses.indices.toMutableList()
        indices.shuffle()
        val shuffled = indices.mapIndexed { displayOrder, origIdx ->
            ShuffledClause(
                displayOrder = displayOrder,
                originalIndex = origIdx,
                originalText = clauses[origIdx],
                displayText = blankedClauses[origIdx]
            )
        }
        return DictationResult(clauses, shuffled)
    }

    /** 按反向默写从句标点切分（标点保留在前一个从句末尾） */
    private fun splitByDictationPunctuation(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in DICTATION_CLAUSE_PUNCT) {
                result.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.ifEmpty { listOf(text) }
    }

    /**
     * 在从句中挖 1 个空：优先挖难度最高的中文字符；无汉字时挖英文单词；
     * 都没有则原样返回。挖掉的内容替换为 ___。供本地与 AI 挖空兜底共用。
     */
    internal fun blankOneWordInClause(clause: String): String {
        if (clause.isBlank()) return clause
        // 找难度最高的中文字符
        var bestIdx = -1
        var bestDiff = -1f
        for (i in clause.indices) {
            if (isChinese(clause[i])) {
                val d = DifficultyCalculator.calculateCharDifficulty(clause[i])
                if (d > bestDiff) {
                    bestDiff = d
                    bestIdx = i
                }
            }
        }
        if (bestIdx >= 0) {
            // 扩展挖 1-2 字：若相邻也是高难度汉字，合并挖掉
            var end = bestIdx + 1
            if (end < clause.length && isChinese(clause[end]) &&
                DifficultyCalculator.calculateCharDifficulty(clause[end]) >= bestDiff * 0.8f
            ) {
                end++
            }
            return clause.substring(0, bestIdx) + "___" + clause.substring(end)
        }
        // 无汉字：挖第一个英文单词
        val wordMatch = ENGLISH_WORD_REGEX.find(clause)
        if (wordMatch != null) {
            return clause.replaceRange(wordMatch.range.first, wordMatch.range.last + 1, "___")
        }
        // 都没有：原样返回
        return clause
    }

    // ── 挖空结果 JSON 序列化：供「继续练习」恢复上次挖好的空 ──
    fun sentenceClozeToJson(r: SentenceClozeResult): String {
        val o = JSONObject()
        o.put("sentences", JSONArray(r.sentences))
        val blanksArr = JSONArray()
        r.blanks.forEach { b ->
            blanksArr.put(JSONObject().apply {
                put("index", b.index); put("originalText", b.originalText)
                put("sentenceIndex", b.sentenceIndex); put("startInSentence", b.startInSentence)
                put("endInSentence", b.endInSentence)
            })
        }
        o.put("blanks", blanksArr)
        o.put("displayText", r.displayText)
        return o.toString()
    }

    fun sentenceClozeFromJson(json: String): SentenceClozeResult? = runCatching {
        val o = JSONObject(json)
        val sArr = o.getJSONArray("sentences")
        val sentences = List(sArr.length()) { sArr.getString(it) }
        val bArr = o.getJSONArray("blanks")
        val blanks = List(bArr.length()) { i ->
            val b = bArr.getJSONObject(i)
            SentenceBlankInfo(b.getInt("index"), b.getString("originalText"), b.getInt("sentenceIndex"), b.getInt("startInSentence"), b.getInt("endInSentence"))
        }
        SentenceClozeResult(sentences, blanks, o.getString("displayText"))
    }.getOrNull()

    fun wordClozeToJson(r: WordClozeResult): String {
        val o = JSONObject()
        val sArr = JSONArray()
        r.sentences.forEach { s ->
            sArr.put(JSONObject().apply { put("text", s.text); put("blanks", JSONArray(s.blanks)) })
        }
        o.put("sentences", sArr)
        val bArr = JSONArray()
        r.blanks.forEach { b ->
            bArr.put(JSONObject().apply { put("index", b.index); put("originalChar", b.originalChar); put("position", b.position) })
        }
        o.put("blanks", bArr)
        o.put("displayText", r.displayText); o.put("maxBlanks", r.maxBlanks); o.put("suggestedBlanks", r.suggestedBlanks)
        return o.toString()
    }

    fun wordClozeFromJson(json: String): WordClozeResult? = runCatching {
        val o = JSONObject(json)
        val sArr = o.getJSONArray("sentences")
        val sentences = List(sArr.length()) { i ->
            val s = sArr.getJSONObject(i)
            val bl = s.getJSONArray("blanks")
            WordClozeSentence(s.getString("text"), List(bl.length()) { bl.getInt(it) })
        }
        val bArr = o.getJSONArray("blanks")
        val blanks = List(bArr.length()) { i ->
            val b = bArr.getJSONObject(i)
            WordBlankInfo(b.getInt("index"), b.getString("originalChar"), b.getInt("position"))
        }
        WordClozeResult(sentences, blanks, o.getString("displayText"), o.getInt("maxBlanks"), o.getInt("suggestedBlanks"))
    }.getOrNull()

    fun dictationToJson(r: DictationResult): String {
        val o = JSONObject()
        o.put("clauses", JSONArray(r.clauses))
        val shArr = JSONArray()
        r.shuffledClauses.forEach { s ->
            shArr.put(JSONObject().apply {
                put("displayOrder", s.displayOrder); put("originalIndex", s.originalIndex)
                put("originalText", s.originalText); put("displayText", s.displayText)
            })
        }
        o.put("shuffledClauses", shArr)
        return o.toString()
    }

    fun dictationFromJson(json: String): DictationResult? = runCatching {
        val o = JSONObject(json)
        val cArr = o.getJSONArray("clauses")
        val clauses = List(cArr.length()) { cArr.getString(it) }
        val shArr = o.getJSONArray("shuffledClauses")
        val shuffled = List(shArr.length()) { i ->
            val s = shArr.getJSONObject(i)
            ShuffledClause(s.getInt("displayOrder"), s.getInt("originalIndex"), s.getString("originalText"), s.getString("displayText"))
        }
        DictationResult(clauses, shuffled)
    }.getOrNull()
}
