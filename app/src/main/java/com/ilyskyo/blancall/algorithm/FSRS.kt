// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FSRS-6 间隔重复算法（移植自官方实现，按本应用"文章级练习"形态适配）。
 *
 * 相比旧混合实现（v4 遗忘曲线 + v4.5 参数），FSRS-6 的核心差异：
 * - 遗忘曲线 decay 可训练：R(t,S) = (1 + factor·t/S)^(-w20)，factor = 0.9^(-1/w20) - 1
 * - 同日复习稳定性带收敛项：S' = S·e^(w17(G-3+w18))·S^(-w19)，S 越大增长越慢
 * - 难度/稳定性状态语义与 v5 兼容，存量 fsrs_state.json 无需迁移
 *
 * ## 公式依据（官方 awesome-fsrs wiki The-Algorithm，2026-07 版）
 * - 初始稳定性：S0(G) = w[G-1]
 * - 初始难度：D0(G) = w4 - e^(w5·(r-1)) + 1，clamp [1, 10]
 * - 间隔：I(S) = S/factor · (R^(1/decay) - 1)，factor = 0.9^(-1/w20) - 1，decay = -w20
 *   （当目标留存率 R=0.9 时 I(S) ≈ S）
 * - 难度更新：ΔD = -w6·(r-3)·(10-D)/9，均值回归到 D0(EASY)
 * - 成功复习稳定性：S' = S·(1 + e^w8·(11-D)·S^(-w9)·(e^((1-r)·w10)-1)·hardPenalty·easyBonus)
 * - 遗忘稳定性：S' = min(w11·D^(-w12)·((S+1)^w13-1)·e^((1-r)·w14), S/e^(w17·w18))
 * - 同日复习：S' = S·e^(w17·(G-3+w18))·S^(-w19)
 * - 间隔扰动（fuzz）：±5% 随机化，避免同日复习堆积
 *
 * ## 引用
 * 本实现基于 FSRS（Free Spaced Repetition Scheduler）算法：
 * Ye, J., Su, J., & Cao, Y. (2022). A Stochastic Shortest Path Algorithm for Optimizing
 * Spaced Repetition Scheduling [Conference paper]. https://doi.org/10.1145/3534678.3539081
 * 公式依据与参数来源（FSRS-6）：
 * https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
 * 实现参考：fsrs-rs（Anki 开源实现）与 FSRS-Kotlin：
 * https://github.com/open-spaced-repetition/fsrs-rs
 * https://github.com/open-spaced-repetition/FSRS-Kotlin
 */
object FsrsEngine {

    /** 评级（与官方 Rating 一致：1=忘记 2=困难 3=良好 4=轻松） */
    enum class Rating(val value: Int) {
        AGAIN(1), HARD(2), GOOD(3), EASY(4)
    }

    /** 单篇文章的记忆状态（持久化到 fsrs_state.json） */
    data class CardState(
        /** 难度 D（1-10，越大越难） */
        var difficulty: Double = 0.0,
        /** 稳定性 S（天） */
        var stability: Double = 0.0,
        /** 下次复习时间戳（毫秒） */
        var due: Long = 0L,
        /** 最近一次练习时间戳 */
        var lastReview: Long = 0L,
        /** 练习次数 */
        var reviewCount: Int = 0,
        /** 遗忘次数（评级为 AGAIN 的次数） */
        var lapses: Int = 0
    )

    /** FSRS-6 官方默认参数（Anki 开源默认权重 w[0..20]，21 个） */
    val DEFAULT_PARAMS: List<Double> = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    )

    /** 目标留存率（Anki 默认 90%） */
    const val DEFAULT_REQUEST_RETENTION = 0.9

    private const val MIN_STABILITY = 0.1
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val MAX_INTERVAL_DAYS = 36500

    private val rng = Random(System.currentTimeMillis())

    @Volatile
    private var params: List<Double> = DEFAULT_PARAMS

    @Volatile
    private var requestRetention: Double = DEFAULT_REQUEST_RETENTION

    private val decay: Double get() = -params[20]
    private val factor: Double get() = 0.9.pow(1.0 / decay) - 1

    /**
     * 配置算法参数（默认即官方 FSRS-6 参数）。
     * 保留该入口，便于未来基于用户练习数据进行个性化校准（FSRS optimizer）。
     */
    fun configure(w: List<Double>, retention: Double) {
        if (w.size == 21 && w.all { it.isFinite() } && w[20] > 0) {
            params = w
            requestRetention = retention.coerceIn(0.01, 0.99)
        }
    }

    /** 正确率 → 评级映射（练习结束后由实际正确率得出；保留兼容旧路径） */
    fun ratingFromAccuracy(accuracy: Float): Rating = when {
        accuracy < 0.4f -> Rating.AGAIN
        accuracy < 0.7f -> Rating.HARD
        accuracy < 0.9f -> Rating.GOOD
        else -> Rating.EASY
    }

    /**
     * 默写文本相似度 → 评级映射（逐字复现产品语义）：
     * - <0.60：核心内容有误 → AGAIN
     * - <0.85：存在字词错误 → HARD
     * - <0.97：仅标点/归一化级差异 → GOOD
     * - ≥0.97：逐字准确 → EASY
     */
    fun gradeFromSimilarity(similarity: Float): Rating = when {
        similarity < 0.6f -> Rating.AGAIN
        similarity < 0.85f -> Rating.HARD
        similarity < 0.97f -> Rating.GOOD
        else -> Rating.EASY
    }

    /**
     * 复习模板 → 目标留存率映射（设置页「复习频率」控制 FSRS 复习强度）：
     * - 冲刺备考：85%（间隔短，练得勤）
     * - 标准记忆：90%（Anki 默认，均衡）
     * - 深度长期：95%（间隔长，要求高留存）
     */
    fun retentionForTemplate(templateId: String): Double = when (templateId) {
        "sprint" -> 0.85
        "deep" -> 0.95
        else -> DEFAULT_REQUEST_RETENTION // standard 及未知值
    }

    /** 当前记忆留存率 R(t) = (1 + factor·t/S)^(-w20)，0-1（FSRS-6 幂律曲线） */
    fun retentionRate(state: CardState, now: Long = System.currentTimeMillis()): Double {
        if (state.stability <= 0.0) return 0.0
        val elapsed = (now - state.lastReview).toDouble() / MILLIS_PER_DAY
        return forgettingCurve(elapsed, state.stability)
    }

    /** 距复习到期天数：负数=已逾期，0=今天到期，正数=未来 */
    fun daysUntilDue(state: CardState, now: Long = System.currentTimeMillis()): Int {
        if (state.reviewCount == 0) return Int.MAX_VALUE
        val diffMs = state.due - now
        return kotlin.math.ceil(diffMs.toDouble() / MILLIS_PER_DAY).toInt()
    }

    /** 该文章是否已到复习时间 */
    fun isDue(state: CardState, now: Long = System.currentTimeMillis()): Boolean =
        state.reviewCount > 0 && now >= state.due

    /**
     * 练习后更新记忆状态（核心入口）。
     *
     * @param state 现有状态（首次练习传新 CardState()）
     * @param rating 本次练习评级（由正确率映射）
     * @return 更新后的新状态（调用方负责持久化）
     */
    fun review(state: CardState, rating: Rating, now: Long = System.currentTimeMillis()): CardState {
        val isNew = state.reviewCount == 0 || state.stability <= 0.0

        val (newDifficulty, newStability) = if (isNew) {
            initDifficulty(rating) to initStability(rating)
        } else {
            val elapsedDays = (now - state.lastReview).toDouble() / MILLIS_PER_DAY
            // 同日复习（同一日历日且未到期）：稳定性走 FSRS-6 同日公式，不推进间隔
            val sameDay = now >= state.lastReview &&
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
                java.time.Instant.ofEpochMilli(state.lastReview).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            if (sameDay && now < state.due) {
                val newS = state.stability *
                    exp(params[17] * (rating.value - 3 + params[18])) *
                    state.stability.pow(-params[19])
                nextDifficulty(state.difficulty, rating) to newS.coerceAtLeast(MIN_STABILITY)
            } else {
                val retrievability = forgettingCurve(elapsedDays, state.stability)
                nextDifficulty(state.difficulty, rating) to when (rating) {
                    Rating.AGAIN -> nextForgetStability(state.difficulty, state.stability, retrievability)
                    else -> nextRecallStability(state.difficulty, state.stability, retrievability, rating)
                }
            }
        }

        val intervalDays = nextInterval(newStability)
        return CardState(
            difficulty = newDifficulty,
            stability = newStability,
            due = now + intervalDays * MILLIS_PER_DAY,
            lastReview = now,
            reviewCount = state.reviewCount + 1,
            lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0
        )
    }

    // ────────────────────────────────────────────────
    // 内部公式（与官方 fsrs-rs 一致）
    // ────────────────────────────────────────────────

    private fun forgettingCurve(elapsedDays: Double, stability: Double): Double {
        // 幂律曲线守卫：elapsed<=0 或基≤0 时直接返回边界值，防止 pow NaN
        if (elapsedDays <= 0.0) return 1.0
        if (stability <= 0.0) return 0.0
        return (1 + factor * elapsedDays / stability).pow(-params[20])
    }

    private fun initDifficulty(rating: Rating): Double {
        val raw = params[4] - exp(params[5] * (rating.value - 1)) + 1
        return raw.coerceIn(1.0, 10.0)
    }

    private fun initStability(rating: Rating): Double {
        // 注意：官方为 coerceAtLeast(MIN_STABILITY)；部分移植版误写为 coerceAtMost 会压坏初始状态
        return params[rating.value - 1].coerceAtLeast(MIN_STABILITY)
    }

    private fun nextInterval(stability: Double): Int {
        val rawInterval = stability / factor * (requestRetention.pow(1.0 / decay) - 1)
        val fuzzed = applyFuzz(rawInterval)
        return fuzzed.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    /** 间隔扰动：仅对 >= 2.5 天的间隔生效，±5% 随机化防堆积 */
    private fun applyFuzz(interval: Double): Double {
        if (interval < 2.5) return interval
        val ivl = interval.roundToInt()
        val minIvl = max(2, (ivl * 0.95 - 1).roundToInt())
        val maxIvl = (ivl * 1.05 + 1).roundToInt()
        return floor(rng.nextDouble() * (maxIvl - minIvl + 1) + minIvl)
    }

    private fun nextDifficulty(currentD: Double, rating: Rating): Double {
        val deltaD = -params[6] * (rating.value - 3)
        val damped = deltaD * (10 - currentD) / 9
        val nextD = currentD + damped
        val reverted = params[7] * initDifficulty(Rating.EASY) + (1 - params[7]) * nextD
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) params[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) params[16] else 1.0
        val grow = exp(params[8]) * (11 - d) * s.pow(-params[9]) *
            (exp((1 - r) * params[10]) - 1) * hardPenalty * easyBonus
        return (s * (1 + grow)).coerceAtLeast(MIN_STABILITY)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val sMin = s / exp(params[17] * params[18])
        val result = params[11] * d.pow(-params[12]) * ((s + 1).pow(params[13]) - 1) *
            exp((1 - r) * params[14])
        return min(result, sMin).coerceAtLeast(MIN_STABILITY)
    }
}
