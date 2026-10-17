// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

/**
 * 一条提醒的「原因」。
 *
 * 为什么是枚举而不是原来的 sealed class：旧写法把「原因」和「随原因变化的数据」（连续天数、
 * 薄弱条数、文章 id、剩余空数）绑在一起，于是「今天发过哪些原因」这种按天去重没法用一个稳定的
 * 标识去记 —— 每次构造的 data class 相等性都依赖字段，同一天两次运行只要数字变了就当成两条不同原因。
 * 拆开后：**原因只回答「为什么发」**，数量与深链交给 [ReminderPlanItem] 承载。
 *
 * 值只增不改不删：`ReminderStateStore` 落盘的是 `name`，删/改名会让盘上那条去重记录对不上。
 */
enum class ReminderReason {
    /** 今天还没学习 */
    NoStudyToday,
    /** 存在薄弱内容 */
    WeakContent,
    /** 存在未完成练习 */
    IncompletePractice,
    /** 连续学习（纯正反馈） */
    StreakContinue,
    /** 有文章 / 句子到期复习 */
    DueReview,
    /** 句子卡有到期句，或今天还没练句子卡 */
    SentenceCardDue,
    /** 今天练习次数未达目标 */
    DailyGoalMissed,
}

/**
 * 一条待发提醒：判定结果本身，**不含文案与 Intent**。
 *
 * 这样拆是为了让 [ReminderPlan.decide] 保持纯函数（能进 JVM 单测），
 * 文案与深链的解析都留在 Android 侧的 NotificationHelper。
 *
 * @param articleId 有具体文章时给出，用于深链（无则为 -1）
 * @param count 相关数量：到期项数 / 到期句数 / 还差几次 / 连续天数 / 薄弱条数（含义随 reason 而定）
 */
data class ReminderPlanItem(
    val reason: ReminderReason,
    val articleId: Long = -1L,
    val count: Int = 0,
)

/**
 * 「此刻该发哪些提醒」的唯一判定处：纯函数、无 Android 依赖，供 JVM 单测直接覆盖。
 *
 * ## 优先级（从高到低）
 * `IncompletePractice > DueReview > DailyGoalMissed > NoStudyToday > WeakContent > SentenceCardDue > StreakContinue`
 *
 * 拆成两层理解：
 * - **行动召唤层**（前四个）：都指向「现在去做点什么」，四者互斥，最多产出一条；
 * - **补充提示层**（后三个）：在召唤之外补一句，最多产出一条。
 * 一次最多两条（行动召唤 + 薄弱内容），其余情况都只发一条。
 *
 * ## 去重 / 互斥规则与理由
 * - **R1 今天已练过 → 不发 `NoStudyToday`**：状态自相矛盾，用户明明练了还被告知「今天没练」。
 * - **R2 有到期复习 → 不发 `NoStudyToday`**：两者语义重叠（都等于「去练习」），同时发是双重打扰；
 *   且到期复习信息量更大，留下它、丢掉泛化的那条。
 * - **R3 有未完成练习 → 不发 `NoStudyToday` 与 `DailyGoalMissed`**：用户已有一个明确、成本更低的
 *   进行中目标，再叠加泛化召唤只会造成选择困难；先把开了头的做完。
 * - **R4 `DailyGoalMissed` 仅在今天已练过、且目标 >0、且未达标时产出**：没练过时 `NoStudyToday`
 *   已承担「去练」的召唤，再报「还差几次」是重复；目标 <=0 视为未设置。
 * - **R5 `WeakContent` 仅在今天已练过时产出**：没练过时应先给门槛最低的召唤；薄弱巩固是
 *   「已经开始之后的下一步」。它允许与**行动召唤**并存 —— 它是一条**具体到「练哪几处」的建议**，
 *   与「该去练习」不重复（这是补充层里唯一的例外，句子卡与连续学习都必须独占）。
 * - **R6 `StreakContinue` 只在「连句子卡提示都没发」时产出**：它是纯正反馈，
 *   只要有任何一个更具体的提示（含句子卡），就不该用正反馈把它顶掉。
 * - **R7 `SentenceCardDue` 只在**没有任何其他提醒**时产出**：句子卡是每日锦上添花，
 *   不与任何提醒争夺注意力（尤其不与到期复习同发），保证一天最多一条额外打扰。
 * - **R8 `allowReasons` 在**选择阶段**生效（不是产出后再过滤）**：渠道被关 / 免打扰等外部约束
 *   应让该原因「缺席」，由次高优先级的规则顶上，而不是把整次提醒清空。
 *
 * 调用方按返回顺序发送即可；建议再设一个上限（见 ReminderWorker.MAX_PER_RUN）兜底。
 */
object ReminderPlan {

    /** 优先级序号（越小越先发）；显式列出以保证排序稳定，新增原因时必须补一项。 */
    private val PRIORITY: Map<ReminderReason, Int> = mapOf(
        ReminderReason.IncompletePractice to 0,
        ReminderReason.DueReview to 1,
        ReminderReason.DailyGoalMissed to 2,
        ReminderReason.NoStudyToday to 3,
        ReminderReason.WeakContent to 4,
        ReminderReason.SentenceCardDue to 5,
        ReminderReason.StreakContinue to 6,
    )

    /**
     * 决定此刻该发哪些提醒。
     *
     * @param now 当前时间（毫秒）——由调用方传入，便于测试（本函数自身不读时钟）
     * @param hasStudiedToday 今天是否已练过
     * @param todayCount 今天已练习次数
     * @param dailyGoal 每日练习次数目标（<=0 视为未设置）
     * @param dueArticleCount 到期文章数
     * @param dueSentenceCount 到期句子数
     * @param firstDueArticleId 最早到期的那篇文章 id（无则 -1），用于深链
     * @param sentenceCardPracticedToday 句子卡今天是否已练
     * @param hasIncompletePractice 是否有未完成的练习
     * @param incompleteArticleId 未完成练习所属文章 id（无则 -1）
     * @param incompleteRemaining 未完成练习剩余待填空数
     * @param weakCount 薄弱内容条数
     * @param streakDays 当前连续学习天数
     * @param allowReasons 本次允许发送的原因集合（渠道关闭 / 外部约束）；默认全开
     */
    fun decide(
        now: Long,
        hasStudiedToday: Boolean,
        todayCount: Int,
        dailyGoal: Int,
        dueArticleCount: Int,
        dueSentenceCount: Int,
        firstDueArticleId: Long = -1L,
        sentenceCardPracticedToday: Boolean,
        hasIncompletePractice: Boolean,
        incompleteArticleId: Long = -1L,
        incompleteRemaining: Int = 0,
        weakCount: Int = 0,
        streakDays: Int = 0,
        allowReasons: Set<ReminderReason> = ReminderReason.entries.toSet(),
    ): List<ReminderPlanItem> {
        // now 目前不参与分支（跨天由 ReminderStateStore 用 DayBucket 负责），但保留在签名里：
        // 判定将来要「按当天剩余时间收敛」（如临近午夜不再催目标），且调用方统一传时钟
        // 能让测试完全脱离真实时间。当前刻意不使用，避免写出无法验证的伪时区逻辑。
        val dueCount = dueArticleCount + dueSentenceCount
        val out = ArrayList<ReminderPlanItem>(2)

        // ── 行动召唤层：互斥，最多一条（R8：allowReasons 在分支里判，缺席时由次高优先级顶上）──
        when {
            hasIncompletePractice && ReminderReason.IncompletePractice in allowReasons ->
                out += ReminderPlanItem(
                    ReminderReason.IncompletePractice,
                    articleId = incompleteArticleId,
                    count = incompleteRemaining,
                )

            dueCount > 0 && ReminderReason.DueReview in allowReasons ->
                out += ReminderPlanItem(
                    ReminderReason.DueReview,
                    articleId = firstDueArticleId,
                    count = dueCount,
                )

            // R4：仅「已练过 + 设了目标 + 没达标」
            hasStudiedToday && dailyGoal > 0 && todayCount < dailyGoal &&
                ReminderReason.DailyGoalMissed in allowReasons ->
                out += ReminderPlanItem(
                    ReminderReason.DailyGoalMissed,
                    count = dailyGoal - todayCount,
                )

            // R1 / R2 / R3 都收敛到这一条：只有「今天没练、且没有更具体的召唤」才发
            !hasStudiedToday && ReminderReason.NoStudyToday in allowReasons ->
                out += ReminderPlanItem(ReminderReason.NoStudyToday, count = streakDays)
        }

        // ── 补充提示层 ──
        // R5：薄弱内容可与行动召唤并存（补充层里唯一的例外）
        if (hasStudiedToday && weakCount > 0 && ReminderReason.WeakContent in allowReasons) {
            out += ReminderPlanItem(ReminderReason.WeakContent, count = weakCount)
        } else if (out.isEmpty()) {
            // 到这里说明：没有任何行动召唤，也没有薄弱内容 —— 才轮得到下面两条轻提示
            when {
                // R7：句子卡只在「一条都没有」时发
                !sentenceCardPracticedToday && ReminderReason.SentenceCardDue in allowReasons ->
                    out += ReminderPlanItem(ReminderReason.SentenceCardDue, count = dueSentenceCount)

                // R6：连续学习再退一步，只在句子卡也无需提示时发
                hasStudiedToday && streakDays > 0 && ReminderReason.StreakContinue in allowReasons ->
                    out += ReminderPlanItem(ReminderReason.StreakContinue, count = streakDays)
            }
        }

        return out.sortedBy { PRIORITY.getValue(it.reason) }
    }

    /**
     * 免打扰判定：当前小时是否落在 [startHour, endHour) 的静默窗口内（纯函数，便于单测）。
     *
     * 支持跨午夜（如 22 → 8 表示 22:00 到次日 08:00 静默）：`start > end` 时窗口是两段之并。
     * `start == end` 视为**不静默**（而不是静默一整天）：配置写坏时宁可多提醒一次，
     * 也不要让用户从此再也收不到提醒。
     */
    fun isQuietHour(hour: Int, startHour: Int, endHour: Int): Boolean {
        val h = hour.coerceIn(0, 23)
        val s = startHour.coerceIn(0, 23)
        val e = endHour.coerceIn(0, 23)
        if (s == e) return false
        return if (s < e) h in s until e else h >= s || h < e
    }
}
