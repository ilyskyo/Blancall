// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 小样本比率的置信收缩 —— 热力图分档、易错榜、薄弱选段共用的那一份数学。
 *
 * 为什么必须有它：这三处都拿「错误数 ÷ 作答数」当结论，而分母很小的比率没有意义 ——
 * 练 1 次错 1 次就是 100%，直接落进最深的「薄弱」档；练 1 次全对就是 0%，直接落进「牢固」。
 * 新用户整篇文章因此铺满两端饱和色，再随着样本累积大面积翻脸，用户读成「记忆变了」，
 * 实际只是噪声退场。同一份数据在不同练习量下会给出相反的建议，这是展示层的错，不是用户的错。
 */
object WeaknessMath {

    /**
     * 先验强度，折合成「虚拟作答次数」。取 4 的用意：真实样本到 12 次时观测值仍占 3/4 权重
     * （12/(12+4)），而第 1 次作答只有 1/5 —— 起步被全文均值压住，练够了就交给实测。
     */
    private const val PRIOR_WEIGHT = 4f

    /** 低于这个作答次数不足以定档：不给红也不给绿，单独走「样本不足」这一视觉态 */
    const val MIN_SAMPLE = 3

    /** 易错榜的最小样本量，按「空」计：个位数填空的文章，正确率说明不了这篇弱不弱 */
    const val MIN_BLANKS = 8

    /**
     * 贝叶斯收缩后的错误率：`(errors + prior·PRIOR_WEIGHT) / (total + PRIOR_WEIGHT)`。
     *
     * `prior` 传该篇（或全局）的平均错误率 —— 样本不足时先回答「和整体差不多」，
     * 而不是把一次作答放大成定论。分母为 0 时返回 0f：没有作答就没有可陈述的弱。
     */
    fun adjustedErrorRate(errors: Int, total: Int, prior: Float): Float {
        if (total <= 0) return 0f
        val p = prior.coerceIn(0f, 1f)
        return ((errors + p * PRIOR_WEIGHT) / (total + PRIOR_WEIGHT)).toFloat().coerceIn(0f, 1f)
    }

    /** 样本量是否够定档（够 = 至少 [MIN_SAMPLE] 次作答） */
    fun enoughSample(total: Int): Boolean = total >= MIN_SAMPLE

    /** 「现在弱不弱」的门槛：收缩后的**真实**错误率到这一步才推进集训池 */
    const val WEAK_RATE_THRESHOLD = 0.3f

    /**
     * 跨文练习里只有相对排名值（混合坐标下算不出真实比率），门槛另立一个：
     * 与最差段落同量级（≥ 其一半）才算弱。这比旧口径「曾经错过一次就永久在池子里」紧，
     * 也比拿相对值当错误率诚实。
     */
    const val WEAK_RELATIVE_THRESHOLD = 0.5f

    /** 段落薄弱判定：达到门槛的段落序号集合；一个都没到就交空表，由调用方回退全篇 */
    fun weakSectionIndexes(ranked: List<Pair<Int, Float>>, threshold: Float): Set<Int> =
        ranked.filter { it.second >= threshold }.map { it.first }.toSet()

    /**
     * 句子级「现在有多弱」：出错会话数 ÷ 被作答会话数，再向整篇均值收缩。
     *
     * 分母是**被作答的会话数**，所以一句被反复练对之后这个数会自己往下走 ——
     * 旧口径喂给段落排序的是「历史上错过几次」的相对排名（除以最大值），错过一次就永远在池子里，
     * 而 FSRS 早就知道那句练熟了。统计页、薄弱集训与调度器从这里开始共用同一个「弱」。
     *
     * 样本不足（< [MIN_SAMPLE] 次作答）的句子直接不进表：它现在还没有资格被说弱。
     */
    fun sentenceWeakness(
        records: List<com.ilyskyo.blancall.data.model.PracticeRecord>,
        content: String
    ): Map<Int, Float> {
        val positioned = SentenceSplitter.splitWithPositions(content)
        if (positioned.isEmpty()) return emptyMap()
        val startToIndex = positioned.withIndex().associate { it.value.startIndex to it.index }
        val errors = mutableMapOf<Int, Int>()
        val totals = mutableMapOf<Int, Int>()
        records.forEach { record ->
            record.answeredSentenceStarts.mapNotNull { startToIndex[it] }.distinct().forEach {
                totals[it] = (totals[it] ?: 0) + 1
            }
            record.mistakeSentenceIndices.filter { it in positioned.indices }.distinct().forEach {
                errors[it] = (errors[it] ?: 0) + 1
            }
        }
        val answeredSessions = totals.values.sum()
        val prior = if (answeredSessions > 0)
            errors.values.sum().toFloat() / answeredSessions else 0f
        return totals.mapNotNull { (sentenceIndex, total) ->
            if (!enoughSample(total)) return@mapNotNull null
            sentenceIndex to adjustedErrorRate(errors[sentenceIndex] ?: 0, total, prior)
        }.toMap()
    }
}
