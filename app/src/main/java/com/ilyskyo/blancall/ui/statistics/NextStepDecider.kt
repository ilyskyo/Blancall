// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

/**
 * 「下一步练什么」的决策：把整页统计卡片的陈列收成一个动作。
 *
 * 规则（优先级从高到低，与 [com.ilyskyo.blancall.algorithm.StudyCoach.buildPlan] 对齐）：
 * 1. 有到期文章 → 直接复习那一篇（真正「到点该还」的内容优先）；
 * 2. 否则有最弱篇 → 薄弱篇训练；
 * 3. 否则有到期句 → 去句子卡复习；
 * 4. 都没有 → 明确空态。
 *
 * 抽成纯函数是为了让「优先级」这条产品规则**可被单测钉住** ——
 * 它此前散在 [NextStepCard] 的若干判断里，改错一处不会有任何守卫变红。
 * 只给一个动作：选择本身就是负担，三选一必然有两项是当下不该做的。
 */
internal sealed interface NextStep {
    /** 到期文章复习（[articleId] 为最早到期的一篇） */
    data class DueArticle(val articleId: Long) : NextStep

    /** 薄弱篇训练（[articleId] 为最弱一篇） */
    data class WeaknessDrill(val articleId: Long) : NextStep

    /** 到期复习（句子卡） */
    data object Review : NextStep

    /** 无待办 */
    data object AllClear : NextStep
}

/**
 * 决策实现：只看两个输入（到期句数、最弱篇 id），无任何 Android 依赖，JVM 单测直接覆盖。
 *
 * 为什么不并入 [NextStep] 的伴生对象：决策是「规则」、类型是「结果」，
 * 混在一起后单测类名与生产类名会互相污染（`NextStep.decide` 的读法也不清晰）。
 */
internal object NextStepDecider {

    /**
     * @param dueSentences 到期句数（来自句级 FSRS 状态）
     * @param weakestArticleId 最弱一篇的 id；无最弱篇时为 null
     * @param dueArticleId 最早到期的一篇文章 id；无到期文章时为 null
     */
    fun decide(
        dueSentences: Int,
        weakestArticleId: Long?,
        dueArticleId: Long? = null,
    ): NextStep = when {
        dueArticleId != null -> NextStep.DueArticle(dueArticleId)
        weakestArticleId != null -> NextStep.WeaknessDrill(weakestArticleId)
        dueSentences > 0 -> NextStep.Review
        else -> NextStep.AllClear
    }
}
