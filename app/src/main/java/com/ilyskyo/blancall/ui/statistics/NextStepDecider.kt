// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.algorithm.StudyCoach

/**
 * 统计页「下一步」对今日计划第一条的映射结果。
 *
 * 只表达「第一条是什么、要多少」，不携带任何优先级 ——
 * 优先级只存在于 [StudyCoach.buildPlan]，统计页不再拥有第二套排序。
 */
internal sealed interface NextStep {
    /** 继续一篇未完成的练习 */
    data class Resume(val articleId: Long) : NextStep

    /** 到期文章复习 */
    data class DueArticle(val articleId: Long) : NextStep

    /** 薄弱篇训练 */
    data class WeaknessDrill(val articleId: Long) : NextStep

    /** 句子卡（[count] 为本次到期句数） */
    data class SentenceCards(val count: Int) : NextStep

    /** 错题集训（[count] 为未攻克条数） */
    data class MistakeDrill(val count: Int) : NextStep

    /** 补齐每日练习次数（[gap] 为还差几次） */
    data class DailyGoal(val gap: Int) : NextStep

    /** 无待办 */
    data object AllClear : NextStep
}

/**
 * 把 [StudyCoach.Plan] 的第一条翻译成统计页可渲染的动作。
 *
 * 以前这里自己排优先级（到期文章 > 薄弱 > 句子），与首页今日计划不同序，
 * 于是同一时刻两个页面推荐不同的事。现在它只是映射器：改首页顺序，统计页自动跟随。
 */
internal object NextStepDecider {

    fun fromPlan(plan: StudyCoach.Plan): NextStep = fromItem(plan.items.firstOrNull())

    fun fromItem(item: StudyCoach.Item?): NextStep = when (item?.kind) {
        StudyCoach.Kind.RESUME -> NextStep.Resume(item.articleId)
        StudyCoach.Kind.REVIEW_ARTICLE -> NextStep.DueArticle(item.articleId)
        StudyCoach.Kind.WEAK_ARTICLE -> NextStep.WeaknessDrill(item.articleId)
        StudyCoach.Kind.SENTENCE_CARDS -> NextStep.SentenceCards(item.count)
        StudyCoach.Kind.MISTAKE_DRILL -> NextStep.MistakeDrill(item.count)
        StudyCoach.Kind.DAILY_GOAL -> NextStep.DailyGoal(item.count)
        null -> NextStep.AllClear
    }
}
