// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「今天练什么」的唯一推导层：今日计划、时间预算、通知里的「开始复习」共用这一份规则。
 *
 * 为什么必须只有一处：首页到期卡、统计页建议卡、任务流此前各算一遍「谁最该练」，
 * 三处只要有一处改了优先级，用户就会在两个界面看到互相矛盾的结论。
 * 本对象不读盘、不读时钟、不碰 Android，所有输入由调用方提供，JVM 单测直接钉住规则。
 *
 * 顺序的取舍（从高到低）：
 * 1. **未完成练习**：已经开过头，回来练完是成本最低的进展；
 * 2. **到期文章**：真正「到点该还」的内容，按逾期先后；
 * 3. **薄弱篇**：没有到期内容时，补最弱的一篇比泛复习更直接；
 * 4. **句子卡**：穿插性的句级复习；
 * 5. **错题集训**：只剩明确错处可练时，把它排上；
 * 6. **每日次数目标**：以上都没有时，用目标补齐一天的节奏。
 * 一次最多 3 条：清单超过三项又会退化成「自己选」。
 */
object StudyCoach {

    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** 一篇到期文章的最小输入（标题由调用方从文章库取，决策层不依赖 Article）。 */
    data class DueArticle(
        val articleId: Long,
        val title: String,
        val due: Long,
        val lastReview: Long = 0L,
        val reviewCount: Int = 0,
        val lapses: Int = 0,
        /** 预计本次要填的空数；取自最近一次同类练习或正文长度估算 */
        val estimatedBlanks: Int = 0,
        /** 最近一次练习实际耗时（毫秒，0 = 无数据） */
        val lastDurationMs: Long = 0L,
    )

    /** 未完成练习（扫描 practice_state_*.json 得到）。 */
    data class Resumable(
        val articleId: Long,
        val title: String,
        val mode: String,
        val answered: Int,
        val total: Int,
        val lastTime: Long,
    )

    /**
     * 一次决策的全部输入。
     *
     * [todayPracticeCount] 与 [dailyPracticeGoal] 沿用提醒/设置页的既有口径
     * （次数目标），[goalMinutes] 才是「我只有 N 分钟」的时长目标，两者不可混用。
     */
    data class Input(
        val now: Long,
        val dueArticles: List<DueArticle> = emptyList(),
        val resumables: List<Resumable> = emptyList(),
        val dueSentenceCount: Int = 0,
        val weakestArticleId: Long? = null,
        val weakestArticleTitle: String = "",
        /** 最弱一篇自身的未攻克错处数（薄弱篇卡片的文案） */
        val weakMistakeCount: Int = 0,
        /** 全库未攻克错处数（错题集训卡片；与薄弱篇分开，避免「薄弱篇 · 错 40 处」把全库算到一篇头上） */
        val mistakeCount: Int = 0,
        val goalMinutes: Int = 10,
        val secondsPerBlank: Double = DEFAULT_SECONDS_PER_BLANK,
        val todayPracticeCount: Int = 0,
        val dailyPracticeGoal: Int = 0,
        val examEpochDay: Long? = null,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    enum class Kind {
        /** 继续未完成的练习 */
        RESUME,

        /** 到期文章复习 */
        REVIEW_ARTICLE,

        /** 薄弱篇训练 */
        WEAK_ARTICLE,

        /** 错题集训 */
        MISTAKE_DRILL,

        /** 句子卡 */
        SENTENCE_CARDS,

        /** 补齐今天的练习次数 */
        DAILY_GOAL,
    }

    /**
     * 清单里的一条。文案不在这里拼：纯函数只给「是什么 + 多少个」，
     * 具体语言由 UI 按 [Kind] 和数量取字符串资源，避免把六语文案搬进调度层。
     */
    data class Item(
        val kind: Kind,
        val articleId: Long = -1L,
        val title: String = "",
        val count: Int = 0,
        val answered: Int = 0,
        val total: Int = 0,
        val estimatedMinutes: Int = 1,
    )

    enum class ExamPace {
        /** 没设考试日期 */
        NONE,

        /** 按当前每日容量排得完 */
        ON_TRACK,

        /** 按当前每日容量明显排不完（只给两态，不做精确倒计时） */
        BEHIND,
    }

    data class Plan(
        val items: List<Item> = emptyList(),
        val estimatedMinutes: Int = 0,
        val examDaysLeft: Int? = null,
        val examPace: ExamPace = ExamPace.NONE,
    )

    /**
     * 今日计划：最多三条，已去重（同一篇文章不会同时以「到期」和「薄弱」出现）。
     * 没有可做的事时返回空清单，由界面显示空态而不是硬凑一条。
     */
    fun buildPlan(input: Input): Plan {
        val out = ArrayList<Item>(3)
        val usedArticleIds = HashSet<Long>()

        input.resumables
            .sortedByDescending { it.lastTime }
            .firstOrNull()
            ?.let { r ->
                val remaining = (r.total - r.answered).coerceAtLeast(0)
                if (remaining > 0) {
                    out += Item(
                        kind = Kind.RESUME,
                        articleId = r.articleId,
                        title = r.title,
                        count = remaining,
                        answered = r.answered,
                        total = r.total,
                        estimatedMinutes = minutesFor(remaining, input.secondsPerBlank),
                    )
                    usedArticleIds += r.articleId
                }
            }

        input.dueArticles
            .sortedWith(compareBy({ it.due }, { -it.lapses }))
            .firstOrNull { it.articleId !in usedArticleIds }
            ?.let { a ->
                out += Item(
                    kind = Kind.REVIEW_ARTICLE,
                    articleId = a.articleId,
                    title = a.title,
                    count = a.estimatedBlanks,
                    estimatedMinutes = minutesForArticle(a, input.secondsPerBlank),
                )
                usedArticleIds += a.articleId
            }

        val weakId = input.weakestArticleId
        if (weakId != null && weakId > 0L && weakId !in usedArticleIds && out.size < 3) {
            out += Item(
                kind = Kind.WEAK_ARTICLE,
                articleId = weakId,
                title = input.weakestArticleTitle,
                count = input.weakMistakeCount,
                estimatedMinutes = max(2, minutesFor(input.weakMistakeCount.coerceAtLeast(1), input.secondsPerBlank)),
            )
            usedArticleIds += weakId
        }

        if (input.dueSentenceCount > 0 && out.size < 3) {
            out += Item(
                kind = Kind.SENTENCE_CARDS,
                count = input.dueSentenceCount,
                estimatedMinutes = max(1, ceil(input.dueSentenceCount * SENTENCE_CARD_MINUTES).toInt()),
            )
        }

        if (input.mistakeCount > 0 && out.size < 3) {
            out += Item(
                kind = Kind.MISTAKE_DRILL,
                count = input.mistakeCount,
                estimatedMinutes = max(1, ceil(input.mistakeCount * MISTAKE_DRILL_MINUTES).toInt()),
            )
        }

        val goalGap = input.dailyPracticeGoal - input.todayPracticeCount
        if (goalGap > 0 && out.size < 3) {
            out += Item(
                kind = Kind.DAILY_GOAL,
                count = goalGap,
                estimatedMinutes = max(1, goalGap * 2),
            )
        }

        val items = out.take(MAX_ITEMS)
        val examDays = examDaysLeft(input.now, input.examEpochDay, input.zone)
        return Plan(
            items = items,
            estimatedMinutes = items.sumOf { it.estimatedMinutes }.coerceAtLeast(0),
            examDaysLeft = examDays,
            examPace = examPace(input, examDays),
        )
    }

    // ── 时间预算 ──

    data class Budget(
        val articleIds: List<Long> = emptyList(),
        val includeSentenceCards: Boolean = false,
        val estimatedMinutes: Int = 0,
        val requestedMinutes: Int = 0,
    ) {
        val empty: Boolean get() = articleIds.isEmpty() && !includeSentenceCards
    }

    /**
     * 「我只有 [minutes] 分钟」：按到期先后装文章，装到 85% 预算就停，宁少不多。
     *
     * 为什么不是精确排满：估算必然有误差，承诺 15 分钟却练 22 分钟比不承诺更伤信任。
     * 先装未完成的练习（剩余空数已知、误差最小），再装到期文章。
     * 一篇文章都装不下且没有未完成练习时，退回句子卡（最小可交付单位）。
     */
    fun budget(input: Input, minutes: Int): Budget {
        val cap = minutes.coerceIn(1, MAX_BUDGET_MINUTES)
        val ids = LinkedHashSet<Long>()
        var estimated = 0.0

        input.resumables
            .sortedByDescending { it.lastTime }
            .firstOrNull()
            ?.let { r ->
                val remaining = (r.total - r.answered).coerceAtLeast(0)
                if (remaining > 0) {
                    ids += r.articleId
                    estimated += estimateMinutes(remaining, input.secondsPerBlank)
                }
            }

        for (a in input.dueArticles.sortedBy { it.due }) {
            if (a.articleId in ids) continue
            if (estimated >= cap * BUDGET_FILL_RATIO) break
            ids += a.articleId
            estimated += estimateMinutes(max(1, a.estimatedBlanks), input.secondsPerBlank)
        }

        // 没有任何到期/未完成内容时，至少给一篇薄弱篇，别让「选时长」通向空页
        if (ids.isEmpty()) {
            input.weakestArticleId?.takeIf { it > 0L }?.let { ids += it }
            if (ids.isNotEmpty()) {
                estimated += estimateMinutes(
                    max(1, input.weakMistakeCount),
                    input.secondsPerBlank
                )
            }
        }

        val includeCards = input.dueSentenceCount > 0 && estimated < cap * BUDGET_FILL_RATIO
        return Budget(
            articleIds = ids.toList(),
            includeSentenceCards = includeCards,
            estimatedMinutes = ceil(estimated).toInt().coerceAtLeast(if (includeCards) 1 else 0),
            requestedMinutes = cap,
        )
    }

    // ── 速度估算 ──

    /** 无历史时的保守默认：每空 8 秒（古文手写通常更慢，宁可低估题量） */
    const val DEFAULT_SECONDS_PER_BLANK = 8.0

    /**
     * 从练习记录反算「每个空平均几秒」。
     *
     * 只认 duration>0 的记录（旧数据的 0 会把均值拉低）；加权平均按总空数，
     * 不让一次 2 空的练习和一次 60 空的练习等权。夹到 3–30 秒：低于 3 秒不像
     * 真实作答，高于 30 秒多半是中途离开，直接采用会让预算严重少排。
     */
    fun secondsPerBlank(records: List<Pair<Long, Int>>): Double {
        var ms = 0L
        var blanks = 0
        for ((duration, count) in records) {
            if (duration > 0 && count > 0) {
                ms += duration
                blanks += count
            }
        }
        if (blanks <= 0) return DEFAULT_SECONDS_PER_BLANK
        val seconds = ms.toDouble() / 1000.0 / blanks
        return seconds.coerceIn(3.0, 30.0)
    }

    // ── 调度理由 ──

    enum class ReasonKind {
        /** 第一次练 */
        FIRST,

        /** 忘过多次 */
        LAPSED,

        /** 距上次复习很久 */
        LONG_GAP,

        /** 已经逾期 */
        OVERDUE,

        /** 连续多次稳住 */
        STABLE,

        /** 正常按间隔到期 */
        SCHEDULED,
    }

    data class Reason(val kind: ReasonKind, val count: Int = 0, val days: Int = 0)

    /**
     * 把 [FsrsEngine.CardState] 翻译成一句人话需要的原始事实。
     *
     * 不吐术语（S/D/lapse 一律不出现）：用户要的是「凭什么今天轮到它」，
     * 不是算法参数。这些数字在单个状态里都能直接读到，零新数据。
     */
    fun reasonOf(state: FsrsEngine.CardState?, now: Long): Reason {
        if (state == null || state.reviewCount <= 0) return Reason(ReasonKind.FIRST)
        val gapDays = if (state.lastReview > 0L && now > state.lastReview) {
            ((now - state.lastReview) / MILLIS_PER_DAY).toInt()
        } else {
            0
        }
        val overdueDays = if (state.due > 0L && now > state.due) {
            ((now - state.due) / MILLIS_PER_DAY).toInt().coerceAtLeast(1)
        } else {
            0
        }
        return when {
            state.lapses >= LAPSE_REASON_THRESHOLD -> Reason(ReasonKind.LAPSED, state.lapses)
            gapDays >= LONG_GAP_THRESHOLD_DAYS -> Reason(ReasonKind.LONG_GAP, days = gapDays)
            overdueDays > 0 -> Reason(ReasonKind.OVERDUE, days = overdueDays)
            state.lapses == 0 && state.reviewCount >= STABLE_REVIEW_COUNT ->
                Reason(ReasonKind.STABLE, state.reviewCount)
            else -> Reason(ReasonKind.SCHEDULED, days = gapDays)
        }
    }

    // ── 内部 ──

    private const val MAX_ITEMS = 3
    private const val MAX_BUDGET_MINUTES = 180
    private const val BUDGET_FILL_RATIO = 0.85
    private const val SENTENCE_CARD_MINUTES = 0.4
    private const val MISTAKE_DRILL_MINUTES = 0.5
    private const val LAPSE_REASON_THRESHOLD = 2
    private const val LONG_GAP_THRESHOLD_DAYS = 7
    private const val STABLE_REVIEW_COUNT = 3

    /** 估算里带 10% 余量：宁可少排一篇，也不要把「15 分钟」拖成 20 分钟 */
    private fun estimateMinutes(blanks: Int, secondsPerBlank: Double): Double =
        max(1, blanks).coerceAtLeast(1) * secondsPerBlank * 1.1 / 60.0

    private fun minutesFor(blanks: Int, secondsPerBlank: Double): Int =
        ceil(estimateMinutes(blanks, secondsPerBlank)).toInt().coerceAtLeast(1)

    private fun minutesForArticle(a: DueArticle, secondsPerBlank: Double): Int {
        if (a.lastDurationMs > 0L) {
            return ceil(a.lastDurationMs / 60000.0 * 1.1).toInt().coerceIn(1, 120)
        }
        return minutesFor(a.estimatedBlanks, secondsPerBlank)
    }

    private fun examDaysLeft(now: Long, examEpochDay: Long?, zone: ZoneId): Int? {
        if (examEpochDay == null) return null
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val exam = LocalDate.ofEpochDay(examEpochDay)
        return java.time.temporal.ChronoUnit.DAYS.between(today, exam).toInt()
    }

    /**
     * 倒排只给「正常 / 落后」两态：精确到篇的预测在题库变化时必然失准，
     * 一旦给了数字，用户会拿它当承诺。容量 = 每日目标次数（未设时按 3 次），
     * 句子每 10 句折算一篇的练习量，错题每 10 条折算一篇。
     */
    private fun examPace(input: Input, daysLeft: Int?): ExamPace {
        if (daysLeft == null || daysLeft < 0) return ExamPace.NONE
        val dailyCapacity = if (input.dailyPracticeGoal > 0) input.dailyPracticeGoal else DEFAULT_DAILY_CAPACITY
        val capacity = dailyCapacity.toLong() * max(1, daysLeft)
        val backlog = input.dueArticles.size.toLong() +
            ceil(input.dueSentenceCount / 10.0).toLong() +
            ceil(input.mistakeCount / 10.0).toLong()
        return if (backlog > capacity) ExamPace.BEHIND else ExamPace.ON_TRACK
    }

    private const val DEFAULT_DAILY_CAPACITY = 3
}
