// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.data.model.ErrorType
import com.ilyskyo.blancall.data.model.PracticeMode
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.toErrorType
import com.ilyskyo.blancall.data.model.toPracticeMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单篇文章的统计聚合结果。
 *
 * 原先是 StatisticsScreen 的私藏数据类（B1-1 单遍化时搬进数据层），以便 JVM 单测直接覆盖每个字段口径。
 * 原实现还算了 `firstRate`（首次正确率）——grep 全仓无任何消费方，属与 trendIcon 同类的
 * 「算了不说的死字段」，随搬迁一并删除。
 */
internal data class ArticleStats(
    val totalPractices: Int,
    val totalCorrect: Int,
    val totalBlanks: Int,
    val overallRate: Float,
    val latestRate: Float,
    val bestRate: Float,
    val sentenceCount: Int,
    val wordCount: Int,
    val reverseCount: Int,
    val sentenceRate: Float,
    val wordRate: Float,
    val reverseRate: Float,
    val typoCount: Int,
    val glyphCount: Int,
    val missingCount: Int,
    val extraCount: Int,
    val orderCount: Int,
    val incorrectCount: Int,
    val totalMistakes: Int,
    val totalDuration: Long,
    /** 近 [StatsRepository.DAILY_WINDOW_DAYS] 天每日练习次数；下标 0 = 最早（窗口首日），末位 = 今天 */
    val dailyCounts: List<Int>
)

/**
 * 统计页的聚合逻辑（B1-1）。
 *
 * ## 为什么单遍
 * 这些字段原先散在 StatisticsScreen 的 remember 块里，对同一份 records 做约 15 遍扫描
 * （3 次 `filter` 分模式、3 次 `count`、`flatMap` 取 mistakes、`map` 转日期……），
 * 中途还分配若干临时集合；每次进入统计页都会全量重算一次，重练上千次的老文章要多花
 * 十几倍的迭代与分配。这里改成一趟 fold —— 对 N 条记录恰好扫 1 遍。
 *
 * ## 为什么是纯函数
 * 口径（哪类错进哪个桶、14 天窗口怎么切、哪些记录按「空记录」排除）以前只活在界面层的
 * 一个 remember 块里，连测试都碰不到；搬进数据层后由 [StatsRepositoryTest] 逐字段锁住。
 *
 * ## 线程模型（对原计划的一处收敛）
 * 既定方案里写了「StateFlow + Dispatchers.Default，页面只订阅」。实测单遍聚合是 O(N) 的
 * 纯整数累加：千条记录在几十微秒量级，异步化反而会引入「首帧空值 / 二次计算」一类复杂度，
 * 并改变「进入页面首帧即正确」的现有行为。故保持同步调用；若未来记录量级显著增长，
 * 升级路径是把本函数直接包进 `withContext(Dispatchers.Default)`，聚合口径不用再动。
 */
internal object StatsRepository {

    /** 每日趋势窗口：近 14 天（含今天） */
    const val DAILY_WINDOW_DAYS = 14

    /** 六个错误桶的固定序（与六桶展示顺序一致），避免散落的魔数下标重复出现 */
    private const val BUCKET_TYPO = 0
    private const val BUCKET_GLYPH = 1
    private const val BUCKET_MISSING = 2
    private const val BUCKET_EXTRA = 3
    private const val BUCKET_ORDER = 4
    private const val BUCKET_INCORRECT = 5

    /**
     * 单遍聚合。
     *
     * @param records 单篇文章的练习记录，**必须已过滤（articleId）且按时间倒序**（最新在前）——
     *   [ArticleStats.latestRate] 取首条依赖这个契约；本函数不再重复扫列表排序（那会让「单遍」失去意义）。
     * @param today 每日窗口的「今天」。可注入以便单测把日期固定下来（否则跨午夜会出现偶发边界失败）。
     * @param zoneId 时间戳 → 本地日期的换算时区，默认系统时区（与「凌晨 0-8 点算前一天」的既有口径一致）。
     */
    fun aggregateArticle(
        records: List<PracticeRecord>,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ArticleStats {
        var totalCorrect = 0
        var totalBlanks = 0
        var bestRate = -1f
        var latestRate = -1f
        val modeCount = IntArray(3)
        val modeCorrect = IntArray(3)
        val modeBlanks = IntArray(3)
        val mistakeBuckets = IntArray(6)
        var totalMistakes = 0
        var totalDuration = 0L
        val daily = IntArray(DAILY_WINDOW_DAYS)

        val todayEpochDay = today.toEpochDay()
        val windowStartEpochDay = todayEpochDay - (DAILY_WINDOW_DAYS - 1)

        for (r in records) {
            totalCorrect += r.correctCount
            totalBlanks += r.totalBlanks
            val rate = if (r.totalBlanks > 0) r.correctCount.toFloat() / r.totalBlanks else 0f
            if (latestRate < 0f) latestRate = rate                 // 倒序列表的首条 = 最近一次
            if (r.totalBlanks > 0 && rate > bestRate) bestRate = rate

            when (r.mode.toPracticeMode()) {
                // 认不出的模式（手工编辑/未来新增）不计入模式桶，其余字段照常累计
                PracticeMode.SENTENCE -> {
                    modeCount[0]++
                    modeCorrect[0] += r.correctCount
                    modeBlanks[0] += r.totalBlanks
                }
                PracticeMode.WORD -> {
                    modeCount[1]++
                    modeCorrect[1] += r.correctCount
                    modeBlanks[1] += r.totalBlanks
                }
                PracticeMode.REVERSE -> {
                    modeCount[2]++
                    modeCorrect[2] += r.correctCount
                    modeBlanks[2] += r.totalBlanks
                }
                null -> Unit
            }

            totalMistakes += r.mistakes.size
            for (m in r.mistakes) {
                when (m.errorType.toErrorType()) {
                    ErrorType.TYPO -> mistakeBuckets[BUCKET_TYPO]++
                    ErrorType.GLYPH_CONFUSED -> mistakeBuckets[BUCKET_GLYPH]++
                    ErrorType.MISSING -> mistakeBuckets[BUCKET_MISSING]++
                    ErrorType.EXTRA -> mistakeBuckets[BUCKET_EXTRA]++
                    ErrorType.WRONG_ORDER -> mistakeBuckets[BUCKET_ORDER]++
                    ErrorType.INCORRECT -> mistakeBuckets[BUCKET_INCORRECT]++
                    // 兜底桶（手工编辑/未来新增的值）不计入「用户能看见的六桶」，但已计入 totalMistakes
                    ErrorType.OTHER -> Unit
                }
            }

            totalDuration += r.duration

            val epochDay = epochDayOrNull(r.timestamp, zoneId)
            if (epochDay != null && epochDay in windowStartEpochDay..todayEpochDay) {
                daily[(epochDay - windowStartEpochDay).toInt()]++
            }
        }

        return ArticleStats(
            totalPractices = records.size,
            totalCorrect = totalCorrect,
            totalBlanks = totalBlanks,
            overallRate = if (totalBlanks > 0) totalCorrect.toFloat() / totalBlanks else 0f,
            latestRate = if (latestRate < 0f) 0f else latestRate,
            bestRate = if (bestRate < 0f) 0f else bestRate,
            sentenceCount = modeCount[0],
            wordCount = modeCount[1],
            reverseCount = modeCount[2],
            sentenceRate = rateOf(modeCorrect[0], modeBlanks[0]),
            wordRate = rateOf(modeCorrect[1], modeBlanks[1]),
            reverseRate = rateOf(modeCorrect[2], modeBlanks[2]),
            typoCount = mistakeBuckets[BUCKET_TYPO],
            glyphCount = mistakeBuckets[BUCKET_GLYPH],
            missingCount = mistakeBuckets[BUCKET_MISSING],
            extraCount = mistakeBuckets[BUCKET_EXTRA],
            orderCount = mistakeBuckets[BUCKET_ORDER],
            incorrectCount = mistakeBuckets[BUCKET_INCORRECT],
            totalMistakes = totalMistakes,
            totalDuration = totalDuration,
            dailyCounts = daily.toList()
        )
    }

    private fun rateOf(correct: Int, blanks: Int): Float =
        if (blanks > 0) correct.toFloat() / blanks else 0f

    /** 时间戳 → 本地日期的 epochDay；异常时间戳（手工改坏）返回 null 并跳过计数（与旧口径一致） */
    private fun epochDayOrNull(timestampMs: Long, zoneId: ZoneId): Long? = try {
        Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate().toEpochDay()
    } catch (_: Exception) {
        null
    }
}
