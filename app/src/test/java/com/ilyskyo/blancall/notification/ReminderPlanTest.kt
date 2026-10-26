// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReminderPlan.decide] 的规则与优先级。
 *
 * 每个用例对应 KDoc 里的一条规则（R1–R8）：规则写在注释里、断言写在测试里，
 * 以后有人「顺手」放宽某条时，至少有一条红着提醒他当初为什么这么定。
 */
class ReminderPlanTest {

    /** 全部「无事发生」的基线输入，用例只改自己关心的那一两项。 */
    private fun decide(
        hasStudiedToday: Boolean = false,
        todayCount: Int = 0,
        dailyGoal: Int = 0,
        dueArticleCount: Int = 0,
        dueSentenceCount: Int = 0,
        firstDueArticleId: Long = -1L,
        sentenceCardPracticedToday: Boolean = true,
        hasIncompletePractice: Boolean = false,
        incompleteArticleId: Long = -1L,
        incompleteRemaining: Int = 0,
        weakCount: Int = 0,
        streakDays: Int = 0,
        dueArticleTitle: String = "",
        incompleteArticleTitle: String = "",
        upcomingTasks: List<ReminderUpcomingTask> = emptyList(),
        forgettingRisks: List<ReminderForgettingRisk> = emptyList(),
        lastPractice: ReminderLastPractice? = null,
        allowReasons: Set<ReminderReason> = ReminderReason.entries.toSet(),
    ): List<ReminderPlanItem> = ReminderPlan.decide(
        now = 1_700_000_000_000L,
        hasStudiedToday = hasStudiedToday,
        todayCount = todayCount,
        dailyGoal = dailyGoal,
        dueArticleCount = dueArticleCount,
        dueSentenceCount = dueSentenceCount,
        firstDueArticleId = firstDueArticleId,
        sentenceCardPracticedToday = sentenceCardPracticedToday,
        hasIncompletePractice = hasIncompletePractice,
        incompleteArticleId = incompleteArticleId,
        incompleteRemaining = incompleteRemaining,
        weakCount = weakCount,
        streakDays = streakDays,
        dueArticleTitle = dueArticleTitle,
        incompleteArticleTitle = incompleteArticleTitle,
        upcomingTasks = upcomingTasks,
        forgettingRisks = forgettingRisks,
        lastPractice = lastPractice,
        allowReasons = allowReasons,
    )

    // ── R1 今天已练过 → 不发 NoStudyToday ──

    @Test
    fun `今天已练过就不再发今日未学习`() {
        val plans = decide(hasStudiedToday = true)
        assertTrue(
            "明明练过了还提醒「今天没练」是自相矛盾：$plans",
            plans.none { it.reason == ReminderReason.NoStudyToday },
        )
    }

    @Test
    fun `今天没练且无事可做时发今日未学习`() {
        val plans = decide(hasStudiedToday = false)
        assertEquals(listOf(ReminderReason.NoStudyToday), plans.map { it.reason })
        assertEquals("连续天数应带进 NoStudyToday 供文案区分", 0, plans.single().count)
    }

    // ── R2 有到期复习 → 不发 NoStudyToday ──

    @Test
    fun `有到期复习就不再单独发今日未学习`() {
        val plans = decide(
            hasStudiedToday = false,
            dueArticleCount = 2,
            firstDueArticleId = 7L,
        )
        assertEquals(listOf(ReminderReason.DueReview), plans.map { it.reason })
        val item = plans.single()
        assertEquals("到期数应为文章+句子之和", 2, item.count)
        assertEquals("最早到期的文章 id 要带出去做深链", 7L, item.articleId)
    }

    @Test
    fun `到期句也算到期复习`() {
        val plans = decide(hasStudiedToday = false, dueSentenceCount = 3)
        assertEquals(listOf(ReminderReason.DueReview), plans.map { it.reason })
        assertEquals(3, plans.single().count)
    }

    // ── R3 有未完成练习 → 不发 NoStudyToday / DailyGoalMissed ──

    @Test
    fun `有未完成练习时不再叠加泛化召唤`() {
        val plans = decide(
            hasStudiedToday = false,
            hasIncompletePractice = true,
            incompleteArticleId = 42L,
            incompleteRemaining = 5,
            dailyGoal = 3,
        )
        assertEquals(listOf(ReminderReason.IncompletePractice), plans.map { it.reason })
        val item = plans.single()
        assertEquals(42L, item.articleId)
        assertEquals(5, item.count)
    }

    @Test
    fun `未完成练习优先于到期复习`() {
        val plans = decide(
            hasStudiedToday = false,
            hasIncompletePractice = true,
            incompleteArticleId = 42L,
            incompleteRemaining = 1,
            dueArticleCount = 9,
        )
        assertEquals(listOf(ReminderReason.IncompletePractice), plans.map { it.reason })
    }

    // ── R4 DailyGoalMissed ──

    @Test
    fun `已练过但没达标时发今日目标`() {
        val plans = decide(hasStudiedToday = true, todayCount = 1, dailyGoal = 3)
        assertEquals(listOf(ReminderReason.DailyGoalMissed), plans.map { it.reason })
        assertEquals("count 应是还差几次", 2, plans.single().count)
    }

    @Test
    fun `没练过时不发今日目标`() {
        // 没练过时 NoStudyToday 已承担召唤，再报「还差几次」是重复
        val plans = decide(hasStudiedToday = false, todayCount = 0, dailyGoal = 3)
        assertTrue(plans.none { it.reason == ReminderReason.DailyGoalMissed })
    }

    @Test
    fun `目标未设置时不发今日目标`() {
        val plans = decide(hasStudiedToday = true, todayCount = 0, dailyGoal = 0)
        assertTrue("dailyGoal<=0 视为未设置：$plans", plans.none { it.reason == ReminderReason.DailyGoalMissed })
    }

    @Test
    fun `已达标时不发今日目标`() {
        val plans = decide(hasStudiedToday = true, todayCount = 3, dailyGoal = 3)
        assertTrue(plans.none { it.reason == ReminderReason.DailyGoalMissed })
    }

    // ── R5 WeakContent ──

    @Test
    fun `没练过时不发薄弱内容`() {
        val plans = decide(hasStudiedToday = false, weakCount = 5)
        assertTrue(plans.none { it.reason == ReminderReason.WeakContent })
    }

    @Test
    fun `已练过且有薄弱内容时发薄弱内容`() {
        val plans = decide(hasStudiedToday = true, weakCount = 4)
        assertEquals(listOf(ReminderReason.WeakContent), plans.map { it.reason })
        assertEquals(4, plans.single().count)
    }

    // ── R6 StreakContinue ──

    @Test
    fun `没有任何待办时发连续学习`() {
        val plans = decide(hasStudiedToday = true, streakDays = 6)
        assertEquals(listOf(ReminderReason.StreakContinue), plans.map { it.reason })
        assertEquals(6, plans.single().count)
    }

    @Test
    fun `有实际待办时不用正反馈淹没召唤`() {
        val plans = decide(hasStudiedToday = true, streakDays = 6, todayCount = 0, dailyGoal = 3)
        assertTrue("有今日目标待办时不该发连续学习：$plans", plans.none { it.reason == ReminderReason.StreakContinue })
    }

    // ── R7 SentenceCardDue ──

    @Test
    fun `句子卡只在没有任何其他提醒时才发`() {
        val plans = decide(
            hasStudiedToday = true,
            todayCount = 3,
            dailyGoal = 3,
            sentenceCardPracticedToday = false,
        )
        assertEquals(listOf(ReminderReason.SentenceCardDue), plans.map { it.reason })
    }

    @Test
    fun `有到期复习时句子卡不参与`() {
        val plans = decide(
            hasStudiedToday = true,
            dueArticleCount = 1,
            sentenceCardPracticedToday = false,
            dueSentenceCount = 2,
        )
        assertTrue("句子卡不该与到期复习同时发：$plans", plans.none { it.reason == ReminderReason.SentenceCardDue })
    }

    @Test
    fun `有薄弱内容时句子卡也不参与`() {
        // 两者都是轻提示，只留更具体的薄弱内容，别让用户在两件轻任务里选
        val plans = decide(
            hasStudiedToday = true,
            todayCount = 3,
            dailyGoal = 3,
            weakCount = 2,
            sentenceCardPracticedToday = false,
        )
        assertEquals(listOf(ReminderReason.WeakContent), plans.map { it.reason })
    }

    @Test
    fun `今天练过句子卡就不再发句子卡`() {
        val plans = decide(
            hasStudiedToday = true,
            streakDays = 3,
            sentenceCardPracticedToday = true,
        )
        assertTrue(plans.none { it.reason == ReminderReason.SentenceCardDue })
        assertEquals("句子卡无需提示时才轮到连续学习", listOf(ReminderReason.StreakContinue), plans.map { it.reason })
    }

    // ── R8 allowReasons 在选择阶段生效 ──

    @Test
    fun `被禁的原因缺席且由次高优先级顶上`() {
        // 关掉「到期复习」渠道后，不应整次静默，而应退回泛化的「今日未学习」
        val plans = decide(
            hasStudiedToday = false,
            dueArticleCount = 2,
            allowReasons = ReminderReason.entries.toSet() - ReminderReason.DueReview,
        )
        assertEquals(listOf(ReminderReason.NoStudyToday), plans.map { it.reason })
    }

    @Test
    fun `全部被禁时不发任何提醒`() {
        val plans = decide(hasStudiedToday = false, allowReasons = emptySet())
        assertTrue(plans.isEmpty())
    }

    // ── 优先级排序 ──

    @Test
    fun `结果按优先级从高到低排序`() {
        // 已练过 + 有薄弱内容 + 有今日目标：行动召唤（目标）应排在补充提示（薄弱）之前
        val plans = decide(hasStudiedToday = true, todayCount = 0, dailyGoal = 3, weakCount = 2)
        assertEquals(
            listOf(ReminderReason.DailyGoalMissed, ReminderReason.WeakContent),
            plans.map { it.reason },
        )
    }

    @Test
    fun `最多产出两条`() {
        val plans = decide(hasStudiedToday = true, todayCount = 0, dailyGoal = 3, weakCount = 2, streakDays = 5)
        assertTrue("一次不该刷屏：$plans", plans.size <= 2)
    }

    // ── (a)(b)(c)(d)(e) 具体内容与预警层 ──

    @Test
    fun `到期复习带上最早到期那篇的标题与 id`() {
        val plans = decide(
            hasStudiedToday = true,
            dueArticleCount = 2,
            firstDueArticleId = 7L,
            dueArticleTitle = "出师表",
        )
        val item = plans.single()
        assertEquals(ReminderReason.DueReview, item.reason)
        assertEquals("出师表", item.articleTitle)
        assertEquals(7L, item.articleId)
    }

    @Test
    fun `未完成练习带上文章标题`() {
        val plans = decide(
            hasStudiedToday = true,
            hasIncompletePractice = true,
            incompleteArticleId = 42L,
            incompleteRemaining = 3,
            incompleteArticleTitle = "岳阳楼记",
        )
        val item = plans.single()
        assertEquals(ReminderReason.IncompletePractice, item.reason)
        assertEquals("岳阳楼记", item.articleTitle)
        assertEquals(3, item.count)
    }

    @Test
    fun `没有行动召唤时提醒即将到期的任务并选中最早一篇`() {
        val dueAt1 = 1_700_010_000_000L
        val dueAt2 = 1_700_005_000_000L
        val plans = decide(
            hasStudiedToday = true,
            upcomingTasks = listOf(
                ReminderUpcomingTask(1L, "后到期", dueAt1),
                ReminderUpcomingTask(2L, "先到期", dueAt2),
            ),
        )
        val item = plans.single()
        assertEquals(ReminderReason.TaskDueSoon, item.reason)
        assertEquals(2L, item.articleId)
        assertEquals("先到期", item.articleTitle)
        assertEquals("到期时间要原样带给文案层", dueAt2, item.dueAt)
        assertEquals("count 是候选总数", 2, item.count)
    }

    @Test
    fun `即将到期不抢已到期复习的注意力`() {
        val plans = decide(
            hasStudiedToday = true,
            dueArticleCount = 1,
            dueArticleTitle = "已到期",
            upcomingTasks = listOf(ReminderUpcomingTask(2L, "快到期", 1_700_005_000_000L)),
        )
        assertEquals(listOf(ReminderReason.DueReview), plans.map { it.reason })
    }

    @Test
    fun `遗忘预知优先于即将到期并选中留存率最低的一篇`() {
        val plans = decide(
            hasStudiedToday = true,
            upcomingTasks = listOf(ReminderUpcomingTask(2L, "快到期", 1_700_005_000_000L)),
            forgettingRisks = listOf(
                ReminderForgettingRisk(3L, "较稳", retentionPercent = 92),
                ReminderForgettingRisk(4L, "最危险", retentionPercent = 88),
            ),
        )
        val item = plans.single()
        assertEquals(ReminderReason.ForgettingSoon, item.reason)
        assertEquals(4L, item.articleId)
        assertEquals("最危险", item.articleTitle)
        assertEquals(88, item.retentionPercent)
        assertEquals(2, item.count)
    }

    @Test
    fun `正确率低于阈值时提醒并带上哪一次`() {
        val at = 1_700_000_000_000L - 60 * 60 * 1000L
        val plans = decide(
            hasStudiedToday = true,
            lastPractice = ReminderLastPractice(9L, "兰亭集序", accuracyPercent = 62, practicedAt = at),
        )
        val item = plans.single()
        assertEquals(ReminderReason.PracticeAccuracy, item.reason)
        assertEquals("兰亭集序", item.articleTitle)
        assertEquals(9L, item.articleId)
        assertEquals(62, item.accuracyPercent)
        assertEquals("练习时间用于文案里指明哪一次", at, item.practicedAt)
    }

    @Test
    fun `正确率达标不再提醒复习错题`() {
        val at = 1_700_000_000_000L - 60 * 60 * 1000L
        listOf(PRACTICE_ACCURACY_REVIEW_THRESHOLD, 95).forEach { accuracy ->
            val plans = decide(
                hasStudiedToday = true,
                lastPractice = ReminderLastPractice(9L, "兰亭集序", accuracy, at),
            )
            assertTrue(
                "正确率 $accuracy 已达到复习门槛：$plans",
                plans.none { it.reason == ReminderReason.PracticeAccuracy },
            )
        }
    }

    @Test
    fun `超过24小时的练习不再拿来说事`() {
        val tooOld = 1_700_000_000_000L - PRACTICE_ACCURACY_FRESH_MS - 1
        val plans = decide(
            hasStudiedToday = true,
            lastPractice = ReminderLastPractice(9L, "兰亭集序", 30, tooOld),
        )
        assertTrue(plans.none { it.reason == ReminderReason.PracticeAccuracy })
    }

    @Test
    fun `时钟偏差导致的未来练习时间应忽略`() {
        val future = 1_700_000_000_000L + 60 * 60 * 1000L
        val plans = decide(
            hasStudiedToday = true,
            lastPractice = ReminderLastPractice(9L, "兰亭集序", 30, future),
        )
        assertTrue(plans.none { it.reason == ReminderReason.PracticeAccuracy })
    }

    @Test
    fun `到期复习优先于正确率提醒`() {
        val plans = decide(
            hasStudiedToday = true,
            dueArticleCount = 1,
            lastPractice = ReminderLastPractice(9L, "兰亭集序", 20, 1_700_000_000_000L - 1000L),
        )
        assertEquals(listOf(ReminderReason.DueReview), plans.map { it.reason })
    }

    @Test
    fun `有行动召唤时预警不出现`() {
        val plans = decide(
            hasStudiedToday = true,
            hasIncompletePractice = true,
            incompleteRemaining = 2,
            upcomingTasks = listOf(ReminderUpcomingTask(2L, "快到期", 1_700_005_000_000L)),
            forgettingRisks = listOf(ReminderForgettingRisk(3L, "快忘了", 89)),
        )
        assertEquals(listOf(ReminderReason.IncompletePractice), plans.map { it.reason })
    }

    @Test
    fun `关闭遗忘预知渠道时退回泛化召唤而不是发别的预警`() {
        val plans = decide(
            hasStudiedToday = false,
            forgettingRisks = listOf(ReminderForgettingRisk(3L, "快忘了", 89)),
            allowReasons = ReminderReason.entries.toSet() - ReminderReason.ForgettingSoon,
        )
        assertEquals(listOf(ReminderReason.NoStudyToday), plans.map { it.reason })
    }

    // ── 通知共享的输入口径：暂停必须穿透到「未完成 / 到期 / 薄弱」 ──

    @Test
    fun `暂停文章的未完成与到期不会进入通知输入`() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val snap = com.ilyskyo.blancall.algorithm.StudyCoach.snapshot(
            com.ilyskyo.blancall.algorithm.StudyCoach.Source(
                articles = listOf(
                    com.ilyskyo.blancall.data.model.Article(
                        id = 1L, title = "暂停篇", content = "文".repeat(400)
                    )
                ),
                states = mapOf(
                    1L to com.ilyskyo.blancall.algorithm.FsrsEngine.CardState(
                        due = now - day, lastReview = now - 2 * day, reviewCount = 3
                    )
                ),
                pauses = mapOf(
                    1L to com.ilyskyo.blancall.algorithm.StudyFlow.Pause(now - day, 0L)
                ),
                resumables = listOf(
                    com.ilyskyo.blancall.algorithm.StudyCoach.InProgressPractice(
                        1L, "SENTENCE", 2, 10, now
                    )
                ),
            ),
            now = now,
        )
        val plans = decide(
            hasStudiedToday = false,
            dueArticleCount = snap.dueArticleCount,
            dueSentenceCount = snap.sentenceTotalDue,
            hasIncompletePractice = snap.firstResumable != null,
            weakCount = snap.mistakeCount,
        )
        assertTrue(
            "暂停文章不应催未完成/到期/薄弱：$plans",
            plans.none {
                it.reason == ReminderReason.IncompletePractice ||
                    it.reason == ReminderReason.DueReview ||
                    it.reason == ReminderReason.WeakContent
            },
        )
    }

    @Test
    fun `模板兜底文章到期时通知与首页同样认为到期`() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val record = com.ilyskyo.blancall.data.model.PracticeRecord(
            articleId = 2L,
            mode = "SENTENCE",
            totalBlanks = 10,
            correctCount = 8,
            timestamp = now - 2 * day,
            duration = 60_000L,
        )
        val snap = com.ilyskyo.blancall.algorithm.StudyCoach.snapshot(
            com.ilyskyo.blancall.algorithm.StudyCoach.Source(
                articles = listOf(
                    com.ilyskyo.blancall.data.model.Article(
                        id = 2L, title = "模板篇", content = "文".repeat(400)
                    )
                ),
                records = listOf(record),
            ),
            now = now,
        )
        assertEquals(1, snap.dueArticleCount)
        assertEquals(2L, snap.input.dueArticles.single().articleId)
        val plans = decide(
            hasStudiedToday = false,
            dueArticleCount = snap.dueArticleCount,
            firstDueArticleId = 2L,
            dueArticleTitle = "模板篇",
        )
        assertEquals(listOf(ReminderReason.DueReview), plans.map { it.reason })
    }

    // ── 免打扰窗口（纯函数） ──

    @Test
    fun `跨午夜的免打扰窗口`() {
        // 22 → 8：22 点后与 8 点前静默
        assertTrue(ReminderPlan.isQuietHour(hour = 22, startHour = 22, endHour = 8))
        assertTrue(ReminderPlan.isQuietHour(hour = 3, startHour = 22, endHour = 8))
        assertTrue(ReminderPlan.isQuietHour(hour = 0, startHour = 22, endHour = 8))
        assertFalse(ReminderPlan.isQuietHour(hour = 8, startHour = 22, endHour = 8))
        assertFalse(ReminderPlan.isQuietHour(hour = 20, startHour = 22, endHour = 8))
        assertFalse(ReminderPlan.isQuietHour(hour = 12, startHour = 22, endHour = 8))
    }

    @Test
    fun `不跨午夜的免打扰窗口`() {
        assertTrue(ReminderPlan.isQuietHour(hour = 13, startHour = 12, endHour = 14))
        assertFalse(ReminderPlan.isQuietHour(hour = 14, startHour = 12, endHour = 14))
        assertFalse(ReminderPlan.isQuietHour(hour = 11, startHour = 12, endHour = 14))
    }

    @Test
    fun `起止相同时视为不静默`() {
        // 配置写坏时宁可多提醒一次，也不要让用户从此收不到提醒
        assertFalse(ReminderPlan.isQuietHour(hour = 3, startHour = 8, endHour = 8))
        assertFalse(ReminderPlan.isQuietHour(hour = 8, startHour = 8, endHour = 8))
    }
}
