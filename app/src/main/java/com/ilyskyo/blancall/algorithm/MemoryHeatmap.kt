// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.MistakeDetail
import androidx.compose.ui.graphics.Color

/**
 * 记忆热力图生成器（F5）
 *
 * 从练习记录中提取每个句子/字符的错误率，映射为暖色→冷色渐变：
 * - 暖色（红/橙）：经常出错，记忆薄弱
 * - 冷色（绿）：掌握牢固
 * - 无色：从未练习过
 */
object MemoryHeatmap {

    // 错误率→颜色 阈值常量（从高到低，避免魔法数字散落）
    private const val THRESHOLD_HIGH = 0.7f          // ≥0.7 深红：薄弱
    private const val THRESHOLD_MEDIUM_HIGH = 0.5f   // ≥0.5 橙：较难
    private const val THRESHOLD_MEDIUM = 0.3f         // ≥0.3 黄：一般
    private const val THRESHOLD_LOW = 0.1f            // ≥0.1 浅绿：熟悉

    // 颜色常量
    private val COLOR_DEEP_RED = Color(0xFFE53935)
    private val COLOR_ORANGE = Color(0xFFEF6C00)
    private val COLOR_YELLOW = Color(0xFFF9A825)
    private val COLOR_LIGHT_GREEN = Color(0xFF7CB342)
    private val COLOR_DEEP_GREEN = Color(0xFF43A047)
    private val COLOR_VIRIDIAN = Color(0xFF66BB6A)   // 从未错过

    /** 单句热力数据 */
    data class SentenceHeat(
        val sentenceIndex: Int,
        val text: String,
        val errorRate: Float,     // 0-1，该句错误率
        val practiceCount: Int,   // 练习次数（当前为整篇练习会话数，见下方注释）
        val heatColor: Color      // 映射后的热力颜色
    )

    /** 整文热力图数据 */
    data class HeatmapData(
        val sentences: List<SentenceHeat>,
        val overallErrorRate: Float,
        val totalPractices: Int
    )

    /**
     * 从练习记录生成整篇文章的热力图数据（向后兼容重载）。
     * 内部按 blankIndex 比例估算错题所属句子（精度有限）。
     */
    fun generate(content: String, records: List<PracticeRecord>): HeatmapData =
        generate(content, records) { null }

    /**
     * 从练习记录生成整篇文章的热力图数据。
     * @param content 文章原文
     * @param records 该文章的所有练习记录
     * @param sentenceIndexProvider 错题→真实句子索引的提供器。
     *        优先使用其返回值；返回 null 时回退到按 blankIndex 比例估算，
     *        从而避免错误归因导致热力图失真。
     */
    fun generate(
        content: String,
        records: List<PracticeRecord>,
        sentenceIndexProvider: (MistakeDetail) -> Int?
    ): HeatmapData {
        // 用带位置的切句作为唯一锚点：字符起始位置 → 句索引。
        // 练习记录存的是"句子在全文中的字符起始位置"（见 PracticeRecord.answeredSentenceStarts），
        // 这样不受"全文切句 vs 各段分别切句"口径差异影响（标题行/段落拼接都不会错位）。
        val positioned = SentenceSplitter.splitWithPositions(content)
        val allSentences = positioned.map { it.text }
        if (allSentences.isEmpty()) {
            return HeatmapData(emptyList(), 0f, 0)
        }
        val startToIndex = positioned.withIndex().associate { it.value.startIndex to it.index }

        // 统计每句的错误次数和总练习次数
        // sentenceErrors[sIdx] = errorCount
        // sentenceTotal[sIdx] = totalPracticeCount（按句近似）
        val sentenceErrors = mutableMapOf<Int, Int>()
        val sentenceTotal = mutableMapOf<Int, Int>()

        for (record in records) {
            for (mistake in record.mistakes) {
                // 优先用 provider 返回的真实句子索引；为空才回退比例估算
                val sentIdx = sentenceIndexProvider(mistake)
                    ?: estimateSentenceIndex(mistake.blankIndex, allSentences.size, record.totalBlanks)
                sentenceErrors[sentIdx] = (sentenceErrors[sentIdx] ?: 0) + 1
            }
            // 本次实际作答的句子：新记录按字符位置锚定（未完成提交被跳过的空不计入，
            // 避免未作答题被当成"答对/0错"）；旧记录无该字段 → 回退整篇都练到。
            if (record.answeredSentenceStarts.isNotEmpty()) {
                for (start in record.answeredSentenceStarts) {
                    val sIdx = startToIndex[start] ?: continue
                    sentenceTotal[sIdx] = (sentenceTotal[sIdx] ?: 0) + 1
                }
            } else {
                val blanksPerSentence = if (allSentences.size > 0)
                    record.totalBlanks / allSentences.size.coerceAtLeast(1) else 1
                for (sIdx in allSentences.indices) {
                    sentenceTotal[sIdx] = (sentenceTotal[sIdx] ?: 0) + blanksPerSentence.coerceAtLeast(1)
                }
            }
        }

        val totalPractices = records.size

        // 构建每句的热力数据
        // 注意：practiceCount 当前取整篇练习会话数（records.size）。
        // 现阶段练习以整篇为单位，每条记录都练到所有句子，故每句练习次数=总会话数。
        // 若将来支持按句/按段练习且记录可区分覆盖范围，应改为按句精确统计。
        val sentences = allSentences.mapIndexed { idx, text ->
            val errors = sentenceErrors[idx] ?: 0
            val total = sentenceTotal[idx] ?: 0
            // 未作答句（从未被练到）→ 未练习：无色，不再显示“错 0%”
            val heatColor = if (total <= 0) Color.Unspecified
                            else errorRateToColor((errors.toFloat() / total).coerceIn(0f, 1f), totalPractices > 0)
            val errorRate = if (total > 0) errors.toFloat() / total.coerceAtLeast(1) else 0f
            SentenceHeat(idx, text, errorRate, totalPractices, heatColor)
        }

        val overallError = if (records.isNotEmpty()) {
            val totalMistakes = records.sumOf { it.mistakes.size }
            val totalBlanks = records.sumOf { it.totalBlanks }
            if (totalBlanks > 0) totalMistakes.toFloat() / totalBlanks else 0f
        } else 0f

        return HeatmapData(sentences, overallError, totalPractices)
    }

    /**
     * 估算错题所属句子索引（回退方案）。
     * 由于空白索引是全局的，缺乏真实句子索引时按比例映射到句子。
     */
    private fun estimateSentenceIndex(
        blankIndex: Int,
        totalSentences: Int,
        totalBlanks: Int
    ): Int {
        if (totalSentences <= 0 || totalBlanks <= 0) return 0
        // 按比例映射
        val ratio = blankIndex.toFloat() / totalBlanks.coerceAtLeast(1)
        return (ratio * totalSentences).toInt().coerceIn(0, totalSentences - 1)
    }

    /**
     * 错误率 → 颜色映射（阈值见上方常量）
     * 0.0 (无错误) → 绿色
     * 0.5 (中等)   → 橙色
     * 1.0 (高错误) → 红色
     */
    fun errorRateToColor(errorRate: Float, hasHistory: Boolean): Color {
        if (!hasHistory) return Color.Unspecified
        return when {
            errorRate >= THRESHOLD_HIGH -> COLOR_DEEP_RED        // 深红
            errorRate >= THRESHOLD_MEDIUM_HIGH -> COLOR_ORANGE  // 橙
            errorRate >= THRESHOLD_MEDIUM -> COLOR_YELLOW       // 黄
            errorRate >= THRESHOLD_LOW -> COLOR_LIGHT_GREEN      // 浅绿
            errorRate > 0f -> COLOR_DEEP_GREEN                  // 深绿
            else -> COLOR_VIRIDIAN                              // 翠绿（从未错过）
        }
    }

    /** 获取热力渐变色带（用于图例） */
    fun getLegendColors(): List<Pair<String, Color>> = listOf(
        "薄弱" to COLOR_DEEP_RED,
        "较难" to COLOR_ORANGE,
        "一般" to COLOR_YELLOW,
        "熟悉" to COLOR_LIGHT_GREEN,
        "牢固" to COLOR_DEEP_GREEN
    )
}
