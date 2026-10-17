// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [StatsRepository] 的逐字段回归：这套口径原先只活在 StatisticsScreen 的 remember 块里，
 * 单遍化后必须把每个字段的语义钉住 —— 统计页的数字是用户最直接的「我练得怎么样」，
 * 悄悄变了不会崩、只会误导。
 *
 * `today` 固定注入（不用 `LocalDate.now()`），否则跨午夜的运行会出现偶发边界失败。
 */
class StatsRepositoryTest {

    private val today = LocalDate.of(2026, 10, 16)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun epochMs(daysAgo: Long, hour: Int = 12): Long =
        today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun rec(
        mode: String = "SENTENCE",
        correct: Int = 0,
        blanks: Int = 0,
        mistakes: List<MistakeDetail> = emptyList(),
        daysAgo: Long = 0,
        duration: Long = 0,
    ) = PracticeRecord(
        articleId = 1L,
        mode = mode,
        totalBlanks = blanks,
        correctCount = correct,
        mistakes = mistakes,
        timestamp = epochMs(daysAgo),
        duration = duration
    )

    private fun mistake(type: String) =
        MistakeDetail(blankIndex = 0, correctAnswer = "a", userAnswer = "b", errorType = type)

    private fun aggregate(records: List<PracticeRecord>) =
        StatsRepository.aggregateArticle(records, today = today, zoneId = zone)

    @Test
    fun `空列表全零`() {
        val s = aggregate(emptyList())
        assertEquals(0, s.totalPractices)
        assertEquals(0, s.totalCorrect)
        assertEquals(0, s.totalBlanks)
        assertEquals(0f, s.overallRate, 0.0001f)
        assertEquals(0f, s.latestRate, 0.0001f)
        assertEquals(0f, s.bestRate, 0.0001f)
        assertEquals(0, s.totalMistakes)
        assertEquals(0L, s.totalDuration)
        assertEquals(List(14) { 0 }, s.dailyCounts)
    }

    @Test
    fun `求和与整体正确率`() {
        val s = aggregate(
            listOf(
                rec(correct = 8, blanks = 10, daysAgo = 0),   // 80%，最近一次
                rec(correct = 5, blanks = 10, daysAgo = 1),   // 50%
            )
        )
        assertEquals(2, s.totalPractices)
        assertEquals(13, s.totalCorrect)
        assertEquals(20, s.totalBlanks)
        assertEquals(0.65f, s.overallRate, 0.0001f)
        assertEquals(0.8f, s.latestRate, 0.0001f)   // 倒序首条
        assertEquals(0.8f, s.bestRate, 0.0001f)
    }

    @Test
    fun `空记录不参与比率也不拉低最高正确率`() {
        val s = aggregate(
            listOf(
                rec(correct = 0, blanks = 0, daysAgo = 0),   // 旧数据里的空记录
                rec(correct = 3, blanks = 10, daysAgo = 1),
            )
        )
        assertEquals(0.3f, s.overallRate, 0.0001f)   // 分母只算有效空数
        assertEquals(0.3f, s.bestRate, 0.0001f)
        assertEquals(0f, s.latestRate, 0f)           // 最近一次是空记录 → 0（与旧口径一致）
    }

    @Test
    fun `模式分桶计数与比率，未知模式不入桶但计入总练习数`() {
        val s = aggregate(
            listOf(
                rec(mode = "SENTENCE", correct = 6, blanks = 10),
                rec(mode = "WORD", correct = 4, blanks = 5),
                rec(mode = "WORD", correct = 1, blanks = 5),
                rec(mode = "REVERSE", correct = 2, blanks = 4),
                rec(mode = "SOMETHING_NEW", correct = 1, blanks = 1),
            )
        )
        assertEquals(1, s.sentenceCount)
        assertEquals(2, s.wordCount)
        assertEquals(1, s.reverseCount)
        assertEquals(5, s.totalPractices)
        assertEquals(0.6f, s.sentenceRate, 0.0001f)
        assertEquals(0.5f, s.wordRate, 0.0001f)      // (4+1)/(5+5)
        assertEquals(0.5f, s.reverseRate, 0.0001f)
    }

    @Test
    fun `错误六桶各自计数，兜底桶只进总数`() {
        val s = aggregate(
            listOf(
                rec(
                    mistakes = listOf(
                        mistake("TYPO"), mistake("GLYPH_CONFUSED"), mistake("MISSING"),
                        mistake("EXTRA"), mistake("WRONG_ORDER"), mistake("INCORRECT"),
                        mistake("OTHER"), mistake("未来新增的值"),
                    )
                )
            )
        )
        assertEquals(1, s.typoCount)
        assertEquals(1, s.glyphCount)
        assertEquals(1, s.missingCount)
        assertEquals(1, s.extraCount)
        assertEquals(1, s.orderCount)
        assertEquals(1, s.incorrectCount)
        assertEquals(8, s.totalMistakes)   // 两处兜底值不进六桶、但计入总数（口径不丢数）
    }

    @Test
    fun `累计练习时长`() {
        val s = aggregate(listOf(rec(duration = 60_000), rec(duration = 30_000)))
        assertEquals(90_000L, s.totalDuration)
    }

    @Test
    fun `每日窗口边界：今天进末位、13天前进首位、窗口外与未来日期都不计`() {
        // 列表按时间倒序（未来日期最前）
        val s = aggregate(
            listOf(
                rec(daysAgo = -1),   // 明天（时间戳在未来）→ 不计
                rec(daysAgo = 0),    // 今天 → index 13
                rec(daysAgo = 0),    // 今天再来一条
                rec(daysAgo = 13),   // 窗口首日 → index 0
                rec(daysAgo = 14),   // 窗口外 → 不计
            )
        )
        assertEquals(2, s.dailyCounts[13])
        assertEquals(1, s.dailyCounts[0])
        assertEquals(3, s.dailyCounts.sum())
    }

    @Test
    fun `多字段混合聚合（整表回归）`() {
        val s = aggregate(
            listOf(
                rec(
                    mode = "SENTENCE", correct = 9, blanks = 10, duration = 6000, daysAgo = 0,
                    mistakes = listOf(mistake("TYPO"))
                ),
                rec(
                    mode = "WORD", correct = 3, blanks = 5, duration = 3000, daysAgo = 1,
                    mistakes = listOf(mistake("EXTRA"), mistake("OTHER"))
                ),
                rec(mode = "REVERSE", correct = 0, blanks = 4, duration = 1000, daysAgo = 2),
            )
        )
        assertEquals(3, s.totalPractices)
        assertEquals(12, s.totalCorrect)
        assertEquals(19, s.totalBlanks)
        assertEquals(12f / 19f, s.overallRate, 0.0001f)
        assertEquals(0.9f, s.latestRate, 0.0001f)
        assertEquals(0.9f, s.bestRate, 0.0001f)
        assertEquals(1, s.sentenceCount)
        assertEquals(1, s.wordCount)
        assertEquals(1, s.reverseCount)
        assertEquals(0.9f, s.sentenceRate, 0.0001f)
        assertEquals(0.6f, s.wordRate, 0.0001f)
        assertEquals(0f, s.reverseRate, 0.0001f)
        assertEquals(1, s.typoCount)
        assertEquals(0, s.glyphCount)
        assertEquals(0, s.missingCount)
        assertEquals(1, s.extraCount)
        assertEquals(0, s.orderCount)
        assertEquals(0, s.incorrectCount)
        assertEquals(3, s.totalMistakes)
        assertEquals(10_000L, s.totalDuration)
        assertEquals(1, s.dailyCounts[13])   // 今天
        assertEquals(1, s.dailyCounts[12])   // 1 天前
        assertEquals(1, s.dailyCounts[11])   // 2 天前
    }
}
