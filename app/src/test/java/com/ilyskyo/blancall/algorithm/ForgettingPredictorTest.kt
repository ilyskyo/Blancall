// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗忘预测的口径回归。
 *
 * 要守住的是一条很容易再分叉的东西：同一个页面里「还记得多少」只能有一个定义。
 * 之前已调度文章与句子走 FSRS-6 幂律，而升级前的存量文章走一条自造的艾宾浩斯指数曲线
 * `e^(-Δt/S)`（S 还由 `S×(1+0.5·正确率)` 逐次猜出来），两套模型并排显示、共用同一套紧急度阈值。
 */
class ForgettingPredictorTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()

    private val article = Article(id = 11L, title = "岳阳楼记", content = "庆历四年春，谪守巴陵郡。")

    private fun record(daysAgo: Long, correct: Int, blanks: Int, rating: Int = 0) = PracticeRecord(
        articleId = article.id,
        mode = "SENTENCE",
        totalBlanks = blanks,
        correctCount = correct,
        timestamp = now - daysAgo * day,
        rating = rating
    )

    private fun predict(records: List<PracticeRecord>, template: ReviewTemplate = ReviewTemplate.STANDARD) =
        ForgettingPredictor.predict(listOf(article), records, template, emptyMap()).single()

    @Test
    fun 存量文章的留存率出自同一条FSRS曲线() {
        // 没有持久化 FSRS 状态的文章必须按【反推出来的状态】报留存率，
        // 而不是另一条指数曲线 —— 这条断言直接对照 FsrsEngine 的公开算法。
        val records = listOf(record(daysAgo = 6, correct = 4, blanks = 5, rating = 3),
            record(daysAgo = 2, correct = 5, blanks = 5, rating = 4))
        val prediction = predict(records)
        val replayed = ForgettingPredictor.initialStateFromRecords(records)
        assertNotNull(replayed)
        assertEquals(FsrsEngine.retentionRate(replayed!!, now).toFloat(), prediction.retentionRate, 2e-3f)
        // 自造模型给的 S 是「1 天起步、每次乘一个系数」，与 FSRS 的稳定性不同量级；
        // 若这里退回旧实现，memoryStrength 就不再等于反推状态的 stability
        assertEquals(replayed.stability.toFloat(), prediction.memoryStrength, 1e-4f)
    }

    @Test
    fun 曲线与当下那个点是同一个模型() {
        // 卡片上那个百分比与折线必须出自同一状态（旧实现的点与线也同源，但出自另一条曲线；
        // 这里守的是「点 == 曲线第 0 格」这个不变量，改动模型时最容易先坏在这）
        val records = listOf(record(daysAgo = 3, correct = 3, blanks = 6, rating = 2))
        val prediction = predict(records)
        assertEquals(prediction.retentionRate, prediction.decayCurve.first(), 1e-6f)
        assertTrue("曲线应在衰减上单调不增",
            prediction.decayCurve.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test
    fun 用户所选计划真的改变兜底排期() {
        // 三条记录落在三个不同日子 → 第二轮之后的间隔：sprint=2 天、standard=4 天。
        // 正确率刻意取 0.8：既让 sprint 的 autoAdjust 落在「不微调」那一档（否则会把它自己
        // 的 1 天抬到 2 天，两个模板撞成同一个数，这条断言就白跑），又不至于被判成未掌握。
        val records = listOf(
            record(daysAgo = 8, correct = 4, blanks = 5, rating = 3),
            record(daysAgo = 6, correct = 4, blanks = 5, rating = 3),
            record(daysAgo = 4, correct = 4, blanks = 5, rating = 3)
        )
        val sprint = predict(records, ReviewTemplate.SPRINT)
        val standard = predict(records, ReviewTemplate.STANDARD)
        assertTrue("sprint 应比 standard 更早要求复习：${sprint.reviewDate} vs ${standard.reviewDate}",
            sprint.reviewDate < standard.reviewDate)
    }

    @Test
    fun 紧急度判定的四条边界只有一份() {
        // urgencyOf 是从两处重复的 when 分支合并出来的纯函数；边界逐条钉住，
        // 将来谁再写一份就知道该改这里
        assertEquals(ForgettingPredictor.Urgency.OVERDUE, ForgettingPredictor.urgencyOf(-2 * day, -1))
        assertEquals(ForgettingPredictor.Urgency.TODAY, ForgettingPredictor.urgencyOf(0L, 0))
        assertEquals(ForgettingPredictor.Urgency.TODAY, ForgettingPredictor.urgencyOf(-day / 2, 0))
        assertEquals(ForgettingPredictor.Urgency.SOON, ForgettingPredictor.urgencyOf(3 * day, 3))
        assertEquals(ForgettingPredictor.Urgency.LATER, ForgettingPredictor.urgencyOf(4 * day, 4))
    }

    @Test
    fun 没有练习就没有可反推的状态() {
        assertNull(ForgettingPredictor.initialStateFromRecords(emptyList()))
        val prediction = predict(emptyList())
        assertEquals(ForgettingPredictor.Urgency.NEW, prediction.urgency)
        assertEquals(0f, prediction.retentionRate, 0f)
    }

    @Test
    fun 旧记录没有评级时按正确率映射不静默当全对() {
        // rating=0 是升级前的库：拿不到当时的评级，就按正确率映射 —— 但不能因为字段缺省就报 EASY
        val state = ForgettingPredictor.initialStateFromRecords(
            listOf(record(daysAgo = 1, correct = 1, blanks = 10))
        )
        assertNotNull(state)
        val good = ForgettingPredictor.initialStateFromRecords(
            listOf(record(daysAgo = 1, correct = 10, blanks = 10, rating = 3))
        )!!
        assertTrue("1/10 的稳定性不该高于 GOOD 那次：${state!!.stability} vs ${good.stability}",
            state.stability < good.stability)
    }

    @Test
    fun 暂停的文章被打上暂停标且不进入待复习() {
        val dueState = FsrsEngine.CardState(
            difficulty = 4.0,
            stability = 2.0,
            due = now - 2 * day,
            lastReview = now - 3 * day,
            reviewCount = 2,
        )
        val paused = ForgettingPredictor.predict(
            listOf(article), emptyList(), ReviewTemplate.STANDARD,
            mapOf(article.id to dueState), setOf(article.id),
        ).single()
        assertTrue("暂停状态必须随预测一起返回，界面才不用另算一套", paused.paused)
        assertEquals(ForgettingPredictor.Urgency.OVERDUE, paused.urgency)
        assertTrue("暂停项不得进入 dueSoon", ForgettingPredictor.dueSoon(listOf(paused)).isEmpty())

        val active = ForgettingPredictor.predict(
            listOf(article), emptyList(), ReviewTemplate.STANDARD,
            mapOf(article.id to dueState), emptySet(),
        ).single()
        assertTrue(!active.paused)
        assertEquals(1, ForgettingPredictor.dueSoon(listOf(active)).size)
    }
}
