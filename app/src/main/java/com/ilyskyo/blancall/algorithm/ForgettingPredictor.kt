// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import kotlin.math.ceil

/**
 * 遗忘曲线预测器：把「该不该复习」和「还记得多少」两件事算清楚。
 *
 * ## 留存率只有一条定义
 *
 * 留存率一律走 [FsrsEngine.retentionRate]（FSRS-6 幂律 `R = (1 + factor·t/S)^(-w20)`）。
 * 这里**不再自带一条艾宾浩斯指数曲线** `e^(-Δt/S)`：那套模型（连同 `S×(1+0.5·正确率)` 的强度累加）
 * 只服务没有 FSRS 状态的存量文章，于是同一张「遗忘曲线预测」卡上并排着两个模型 ——
 * 同一天、同一份练习历史，句子用幂律、老文章用指数，两个数可以差很远，却共用同一套紧急度阈值。
 * 用户不需要懂算法也能看出这一屏在自相矛盾。
 *
 * 现在没有持久化状态的文章由 [initialStateFromRecords] 从练习记录**反推**一个 [FsrsEngine.CardState]：
 * 按时间顺序把每条记录当时的评级喂进同一个 [FsrsEngine.review]（记录里存了 rating；旧记录没有时
 * 按正确率映射，与练习页当时的评级口径一致）。反推出来的状态只用于展示与排序，**不落盘** ——
 * 用户真练一次，[com.ilyskyo.blancall.ui.viewmodel.PracticeScoring] 会写回真实状态。
 *
 * ## 复习调度
 *
 * 「什么时候该复习」仍分两条路，但都尊重用户在设置里选的复习计划：
 * - 已有 FSRS 状态：由 `state.due` 决定（间隔由 [FsrsEngine] 按目标留存率算，目标来自所选计划）。
 * - 尚无状态的存量文章：用 [EbbinghausScheduler.nextReviewTime] 的模板间隔兜底，
 *   调用方必须把用户所选模板传进来（`ReviewTemplate.fromId`），否则这里会安静地按 STANDARD 排。
 */
object ForgettingPredictor {

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

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
                val urgency = urgencyOf(diffMs, daysLeft)
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
                // ── 存量兜底（还没有 FSRS 状态：升级前的练习 / 从未练过）──
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
                } else {
                    // 留存率、稳定性、未来曲线全部出自【反推出来的同一个 FSRS 状态】——
                    // 与句子侧、与已调度文章同一个模型。这里以前是自造的一条 e^(-Δt/S) 指数曲线。
                    val view = fsrsView(initialStateFromRecords(records) ?: FsrsEngine.CardState(), now)
                    val lastTime = records.last().timestamp
                    val acc = weightedAccuracy(records)
                    val reviewDate = EbbinghausScheduler.nextReviewTime(template, count - 1, lastTime, acc)
                    if (count >= template.intervals.size || reviewDate == null) {
                        // 复习轮次跑完（或模板已排完）：视为已掌握，但留存率仍按模型实时给（会随时间衰减）
                        Prediction(
                            articleId = article.id, title = article.title, urgency = Urgency.MASTERED,
                            reviewDate = 0L, daysLeft = Int.MAX_VALUE, practiceCount = count,
                            lastAccuracy = acc, retentionRate = view.retention,
                            memoryStrength = view.strength, decayCurve = view.curve
                        )
                    } else {
                        val diffMs = reviewDate - now
                        val daysLeft = ceil(diffMs.toDouble() / MILLIS_PER_DAY).toInt()
                        Prediction(
                            articleId = article.id, title = article.title,
                            // 紧急度判定与 FSRS 分支共用一份（原先是两份几乎一样的 when 分支，
                            // 且其中一份靠一行「修复：diffMs<=0 统一为 TODAY」的手改同步过来）
                            urgency = urgencyOf(diffMs, daysLeft),
                            reviewDate = reviewDate, daysLeft = daysLeft, practiceCount = count,
                            lastAccuracy = acc, retentionRate = view.retention,
                            memoryStrength = view.strength, decayCurve = view.curve
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
     * 从练习记录反推 FSRS 状态：按时间顺序把每次练习的评级喂进 [FsrsEngine.review]，
     * 得到的稳定性/难度与「用户当时真练过一遍」算出来的是同一条公式。
     *
     * 只在内存里算，不落盘：这条状态是给「还没有记忆状态的存量文章」用的展示口径。
     * 用户真练一次，[com.ilyskyo.blancall.ui.viewmodel.updateFsrsState] 会写回真实状态，
     * 反推那份随即作废 —— 所以它不该污染 fsrs_state.json。
     */
    fun initialStateFromRecords(records: List<PracticeRecord>): FsrsEngine.CardState? {
        if (records.isEmpty()) return null
        var state = FsrsEngine.CardState()
        for (r in records.sortedBy { it.timestamp }) {
            state = FsrsEngine.review(state, ratingOf(r), r.timestamp)
        }
        return state
    }

    /**
     * 记录当时的评级优先；旧记录（rating=0，升级前落的库）按正确率映射，
     * 用的是 [FsrsEngine.ratingFromAccuracy] —— 与练习页当年给评级时的同一把尺子。
     */
    private fun ratingOf(record: PracticeRecord): FsrsEngine.Rating =
        if (record.rating in 1..4) FsrsEngine.Rating.entries.first { it.value == record.rating }
        else FsrsEngine.ratingFromAccuracy(accuracyOf(record))

    /** 稳定性、当前留存率、未来曲线：出自同一个状态，避免「点」和「线」不是同一个模型 */
    private fun fsrsView(state: FsrsEngine.CardState, now: Long): FsrsView = FsrsView(
        strength = state.stability.toFloat(),
        retention = FsrsEngine.retentionRate(state, now).toFloat(),
        curve = List(DECAY_CURVE_DAYS + 1) { dayOffset ->
            FsrsEngine.retentionRate(state, now + dayOffset * MILLIS_PER_DAY).toFloat()
        }
    )

    private data class FsrsView(val strength: Float, val retention: Float, val curve: List<Float>)

    /**
     * 紧急度判定：FSRS 分支与模板兜底分支共用这一份。
     *
     * 原先是两份逐字重复的 when，其中一份还挂着「修复：diffMs<=0 统一为 TODAY」的手改注释 ——
     * 两份代码修一份忘一份，正是这个函数存在的全部理由。
     */
    internal fun urgencyOf(diffMs: Long, daysLeft: Int): Urgency = when {
        diffMs < -MILLIS_PER_DAY -> Urgency.OVERDUE
        diffMs <= 0 -> Urgency.TODAY
        daysLeft <= 3 -> Urgency.SOON
        else -> Urgency.LATER
    }

    /** 加权衰减正确率：最近 5 次练习，越近权重越大。
     *  它仍参与模板兜底的间隔微调（autoAdjust），但不再决定留存率。
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
