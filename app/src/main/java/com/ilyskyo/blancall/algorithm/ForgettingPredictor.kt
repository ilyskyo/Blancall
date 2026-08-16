// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import kotlin.math.ceil
import kotlin.math.exp

/**
 * 遗忘曲线预测器：在艾宾浩斯调度基础上，叠加指数衰减记忆留存率模型。
 *
 * ## 数学模型
 *
 * **留存率（Retention Rate）** —— 艾宾浩斯-斯皮尔丁指数衰减公式：
 * ```
 * R(Δt) = e^(-Δt / S)
 * ```
 * - Δt = 当前时间 - 最近一次练习时间（天）
 * - S  = 记忆强度（天），由练习历史估算
 * - 该公式为标准艾宾浩斯曲线的连续形式，与原始离散实验数据
 *   （1天后≈33.7%、2天后≈27.8%、6天后≈25.4%）拟合良好：
 *   取 S=1 时，1天后 R=e^(-1)≈36.8%，接近实测 33.7%。
 *
 * **记忆强度（Memory Strength）估算** —— 借鉴 SM-2 算法的稳定性增长思想：
 * ```
 * S_1 = 1 天                  （首次学习后的初始稳定性）
 * S_{n+1} = S_n × (1 + g × a_n)  （每次成功复习后稳定性增长）
 * ```
 * - g = 0.5（增益系数，间隔效应的经验值）
 * - a_n = 第 n 次练习的正确率（0-1）
 * - 当 a_n < 0.3 时视为未掌握，S 不增长（防止"错得多却以为记得牢"）
 *
 * ## 复习调度
 *
 * 下次复习时间仍调用 [EbbinghausScheduler.nextReviewTime]，
 * 保证与首页提醒、通知调度逻辑全局一致，不另起一套间隔表。
 *
 * ## 调度紧急度 vs 留存率
 *
 * - [Urgency]/[daysLeft]：基于模板间隔的"该不该复习"判断，用于排序。
 * - [retentionRate]：基于数学模型的"还记得多少"估算，用于可视化与展示。
 * 二者互补：调度告诉你何时复习，留存率告诉你为何要复习。
 */
object ForgettingPredictor {

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** 记忆强度增益系数 g（每次成功复习使稳定性增长的倍率） */
    private const val STRENGTH_GAIN = 0.5f

    /** 初始记忆强度 S_1（天），拟合艾宾浩斯 1 天后≈33.7% 的实测留存率 */
    private const val INITIAL_STRENGTH_DAYS = 1f

    /** 未掌握阈值：正确率低于此值时稳定性不增长 */
    private const val UNMASTERED_THRESHOLD = 0.3f

    /** 衰减曲线采样天数（覆盖一个标准复习周期） */
    private const val DECAY_CURVE_DAYS = 30

    /**
     * 预测紧急度。ordinal 由高到低排序，数值越小越需要尽快复习。
     */
    enum class Urgency {
        /** 已逾期（应复习时间已过且超过 1 天） */
        OVERDUE,
        /** 今天到期（含恰好到期与已过期不足 1 天） */
        TODAY,
        /** 3 天内到期 */
        SOON,
        /** 更远（3 天以上） */
        LATER,
        /** 尚未开始练习 */
        NEW,
        /** 已完成全部复习轮次，视为已掌握 */
        MASTERED
    }

    data class Prediction(
        val articleId: Long,
        val title: String,
        val urgency: Urgency,
        /** 应复习时间戳；未开始/已掌握为 0 */
        val reviewDate: Long,
        /** 距今天数：负数=已逾期，0=今天，正数=未来；未开始/已掌握为 [Int.MAX_VALUE] */
        val daysLeft: Int,
        val practiceCount: Int,
        /** 最近 N 次加权衰减正确率（越近权重越大），0-1 */
        val lastAccuracy: Float,
        /** 当前记忆留存率 R(Δt)，0-1 */
        val retentionRate: Float,
        /** 当前记忆强度 S（天） */
        val memoryStrength: Float,
        /** 未来 [DECAY_CURVE_DAYS] 天的留存率序列（用于绘制衰减曲线），index 0 = 今天 */
        val decayCurve: List<Float>
    )

    /**
     * 生成所有文章的遗忘预测列表，按紧急度排序。
     *
     * @param articles  所有文章
     * @param allRecords 所有练习记录
     * @param template  复习模板（仅 FSRS 状态缺失时兜底使用）
     * @param fsrsStates 各文章的 FSRS 记忆状态（存在时完全采用 FSRS 自适应调度）
     */
    fun predict(
        articles: List<Article>,
        allRecords: List<PracticeRecord>,
        template: ReviewTemplate = ReviewTemplate.STANDARD,
        fsrsStates: Map<Long, FsrsEngine.CardState> = emptyMap()
    ): List<Prediction> {
        val now = System.currentTimeMillis()
        // O(records) 一次分组，避免 O(articles × records) 重复全量过滤
        val recordsByArticle: Map<Long, List<PracticeRecord>> = allRecords.groupBy { it.articleId }

        return articles.map { article ->
            val records = (recordsByArticle[article.id] ?: emptyList()).sortedBy { it.timestamp }

            // FSRS 状态优先：已由 FSRS 调度的文章不再走模板间隔
            val fsrsState = fsrsStates[article.id]
            if (fsrsState != null && fsrsState.reviewCount > 0) {
                val diffMs = fsrsState.due - now
                val daysLeft = ceil(diffMs.toDouble() / MILLIS_PER_DAY).toInt()
                val urgency = when {
                    diffMs < -MILLIS_PER_DAY -> Urgency.OVERDUE
                    diffMs <= 0 -> Urgency.TODAY
                    daysLeft <= 3 -> Urgency.SOON
                    else -> Urgency.LATER
                }
                val s = fsrsState.stability.toFloat()
                val r = FsrsEngine.retentionRate(fsrsState, now).toFloat()
                Prediction(
                    articleId = article.id, title = article.title, urgency = urgency,
                    reviewDate = fsrsState.due, daysLeft = daysLeft,
                    practiceCount = fsrsState.reviewCount,
                    lastAccuracy = weightedAccuracy(records),
                    retentionRate = r, memoryStrength = s,
                    // FSRS-6 幂律曲线逐日采样（与当前留存率同口径，避免点线不一致）
                    decayCurve = List(DECAY_CURVE_DAYS + 1) { dayOffset ->
                        FsrsEngine.retentionRate(fsrsState, now + dayOffset * MILLIS_PER_DAY).toFloat()
                    }
                )
            } else {
                // ── 模板兜底（无 FSRS 状态：升级前存量练习 / 从未练习）──
                // 按天去重计算复习轮次：同一天多次练习只算一次，避免重做污染 MASTERED 判定
                val zone = java.time.ZoneId.systemDefault()
                val count = records.groupBy {
                    java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
                }.size

                if (records.isEmpty()) {
                    Prediction(
                        articleId = article.id, title = article.title, urgency = Urgency.NEW,
                        reviewDate = 0L, daysLeft = Int.MAX_VALUE, practiceCount = 0,
                        lastAccuracy = 0f, retentionRate = 0f, memoryStrength = 0f,
                        decayCurve = emptyList()
                    )
                } else if (count >= template.intervals.size) {
                    // 已完成全部复习轮次：视为已掌握，留存率按模型实时估算（仍会随时间衰减）
                    val s = memoryStrength(records)
                    val r = retentionRate(s, daysSince(records.last().timestamp, now))
                    Prediction(
                        articleId = article.id, title = article.title, urgency = Urgency.MASTERED,
                        reviewDate = 0L, daysLeft = Int.MAX_VALUE, practiceCount = count,
                        lastAccuracy = weightedAccuracy(records),
                        retentionRate = r, memoryStrength = s,
                        decayCurve = buildDecayCurve(s, now, records.last().timestamp)
                    )
                } else {
                    val lastTime = records.last().timestamp
                    val acc = weightedAccuracy(records)
                    val reviewDate = EbbinghausScheduler.nextReviewTime(template, count - 1, lastTime, acc) ?: 0L

                    if (reviewDate == 0L) {
                        val s = memoryStrength(records)
                        Prediction(
                            articleId = article.id, title = article.title, urgency = Urgency.MASTERED,
                            reviewDate = 0L, daysLeft = Int.MAX_VALUE, practiceCount = count,
                            lastAccuracy = acc, retentionRate = retentionRate(s, daysSince(lastTime, now)),
                            memoryStrength = s,
                            decayCurve = buildDecayCurve(s, now, lastTime)
                        )
                    } else {
                        val diffMs = reviewDate - now
                        val daysLeft = ceil(diffMs.toDouble() / MILLIS_PER_DAY).toInt()
                        val s = memoryStrength(records)
                        val r = retentionRate(s, daysSince(lastTime, now))
                        // 修复：diffMs <= 0（含恰好今天到期与已过期不足 1 天）统一为 TODAY
                        val urgency = when {
                            diffMs < -MILLIS_PER_DAY -> Urgency.OVERDUE
                            diffMs <= 0 -> Urgency.TODAY
                            daysLeft <= 3 -> Urgency.SOON
                            else -> Urgency.LATER
                        }
                        Prediction(
                            articleId = article.id, title = article.title, urgency = urgency,
                            reviewDate = reviewDate, daysLeft = daysLeft, practiceCount = count,
                            lastAccuracy = acc, retentionRate = r, memoryStrength = s,
                            decayCurve = buildDecayCurve(s, now, lastTime)
                        )
                    }
                }
            }
        }.sortedWith(
            // 已逾期/今天到期在前，同紧急度内按应复习时间升序（越早到期越前）
            compareBy({ it.urgency.ordinal }, { it.reviewDate })
        )
    }

    /** 仅返回需要尽快复习的文章（逾期 + 今天 + 3 天内） */
    fun dueSoon(predictions: List<Prediction>): List<Prediction> =
        predictions.filter {
            it.urgency == Urgency.OVERDUE || it.urgency == Urgency.TODAY || it.urgency == Urgency.SOON
        }

    // ────────────────────────────────────────────────────────
    // 记忆模型核心
    // ────────────────────────────────────────────────────────

    /**
     * 记忆强度估算 S（天）。
     *
     * ```
     * S_1 = 1
     * S_{n+1} = S_n × (1 + g × a_n)   当 a_n ≥ 0.3
     * S_{n+1} = S_n                   当 a_n < 0.3（未掌握，不增长）
     * ```
     */
    private fun memoryStrength(records: List<PracticeRecord>): Float {
        if (records.isEmpty()) return 0f
        val sorted = records.sortedBy { it.timestamp }
        var s = INITIAL_STRENGTH_DAYS
        // 从第二次练习开始累加稳定性增长（首次仅设定 S_1）
        for (r in sorted.drop(1)) {
            val acc = accuracyOf(r)
            if (acc >= UNMASTERED_THRESHOLD) {
                s *= (1f + STRENGTH_GAIN * acc)
            }
        }
        return s
    }

    /**
     * 留存率 R(Δt) = e^(-Δt / S)。
     *
     * @param strength 记忆强度 S（天）
     * @param deltaDays 距最近练习的天数（可为分数）
     * @return 0..1 的留存率
     */
    private fun retentionRate(strength: Float, deltaDays: Float): Float {
        if (strength <= 0f) return 0f
        return exp(-deltaDays / strength).coerceIn(0f, 1f)
    }

    /**
     * 构建未来 [DECAY_CURVE_DAYS] 天的留存率序列，用于绘制衰减曲线。
     * index 0 = 今天（Δt = 已经过的天数），index i = 第 i 天后。
     */
    private fun buildDecayCurve(strength: Float, now: Long, lastStudyTime: Long): List<Float> {
        if (strength <= 0f) return emptyList()
        val elapsedDays = daysSince(lastStudyTime, now)
        return List(DECAY_CURVE_DAYS + 1) { dayOffset ->
            retentionRate(strength, elapsedDays + dayOffset)
        }
    }

    /** 两条时间戳相差的天数（浮点，保留精度用于模型计算） */
    private fun daysSince(timestamp: Long, now: Long): Float {
        return (now - timestamp).toFloat() / MILLIS_PER_DAY
    }

    /**
     * 加权衰减正确率：最近 5 次练习，越近权重越大。
     * 避免单次正确率波动误导模型与调度微调。
     */
    private fun weightedAccuracy(records: List<PracticeRecord>): Float {
        if (records.isEmpty()) return 0f
        val recent = records.sortedByDescending { it.timestamp }.take(5)
        var weightSum = 0f
        var accSum = 0f
        recent.forEachIndexed { i, r ->
            // 权重 5,4,3,2,1（越近越大）；recent.size<5 时仍单调递减
            val w = (recent.size - i).toFloat()
            accSum += accuracyOf(r) * w
            weightSum += w
        }
        return if (weightSum > 0f) accSum / weightSum else 0f
    }

    private fun accuracyOf(r: PracticeRecord): Float =
        if (r.totalBlanks > 0) r.correctCount.toFloat() / r.totalBlanks else 1f
}
