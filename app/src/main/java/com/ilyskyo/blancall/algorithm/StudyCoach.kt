// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.PracticeRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「今天练什么」的唯一推导层：今日计划与时间预算共用这一份规则；
 * 统计页与复习通知共享它产出的输入口径（[Snapshot]），但通知的渠道优先级仍由
 * [com.ilyskyo.blancall.notification.ReminderPlan] 自己决定。
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
    ) {
        val remaining: Int get() = (total - answered).coerceAtLeast(0)
    }

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

    // ── 今日工作快照：全站「今天该做什么」的唯一取数口 ──

    /** 未完成练习的原始扫描结果（只读事实；暂停/已删除的过滤在 [prepare] 内统一完成）。 */
    data class InProgressPractice(
        val articleId: Long,
        val mode: String,
        val answered: Int,
        val total: Int,
        val lastTime: Long,
    ) {
        val remaining: Int get() = (total - answered).coerceAtLeast(0)
    }

    /**
     * 快照的静态输入：记录分组、薄弱篇、错题、未完成扫描只在数据变化时重算；
     * 分钟 tick 只把 [Prepared] 交给 [snapshot] 重做时间相关计数。
     */
    data class Source(
        val articles: List<Article> = emptyList(),
        val records: List<PracticeRecord> = emptyList(),
        val states: Map<Long, FsrsEngine.CardState> = emptyMap(),
        val pauses: Map<Long, StudyFlow.Pause> = emptyMap(),
        val sentenceStates: Map<String, FsrsEngine.CardState> = emptyMap(),
        val template: ReviewTemplate = ReviewTemplate.STANDARD,
        val resumables: List<InProgressPractice> = emptyList(),
        /** 今日一句的句子键（只读；用于从「全部到期句」里扣除，不触发抽句） */
        val todaySentenceKey: String? = null,
    )

    /** 最弱一篇：与统计页易错榜同一口径（[WeaknessMath.MIN_BLANKS] + 收缩错误率）。 */
    data class Weakest(
        val articleId: Long,
        val title: String,
        val mistakeCount: Int,
        val correctRate: Float,
        val practiceCount: Int,
    )

    data class Snapshot(
        val input: Input,
        /** 精确到期（[Input.now] 之前已到）且未暂停的篇数，含逾期 */
        val dueArticleCount: Int,
        val overdueArticleCount: Int,
        /** 本地日历日内、但此刻还没到期的篇数 */
        val laterTodayArticleCount: Int,
        val pausedArticleCount: Int,
        /** 全部到期句（含今日一句）——统计页与通知使用 */
        val sentenceTotalDue: Int,
        /** 不含今日一句的到期句——今日计划条目使用 */
        val sentenceOtherDue: Int,
        val firstResumable: Resumable?,
        val weakest: Weakest?,
        val mistakeCount: Int,
        val allScheduledPaused: Boolean,
        /** 已按暂停/删除过滤并按最近练习倒序的未完成练习（首页「继续练习」卡与今日计划共用） */
        val resumables: List<Resumable> = emptyList(),
    )

    /** [prepare] 的产物：不含「现在几点」，可被多次 [snapshot] 复用。 */
    class Prepared internal constructor(
        internal val dueCandidates: List<DueArticle>,
        internal val resumables: List<Resumable>,
        internal val weakest: Weakest?,
        internal val mistakeCount: Int,
        internal val pausedArticleCount: Int,
        internal val allScheduledPaused: Boolean,
        internal val sentenceDue: List<SentenceDue>,
        internal val practiceCountByDate: Map<LocalDate, Int>,
        internal val todaySentenceKey: String?,
        internal val secondsPerBlank: Double,
    )

    internal data class SentenceDue(val key: String, val due: Long)

    /**
     * 把「取数、过滤、计数」收在一处：所有暂停过滤只在这里做一次。
     *
     * @param records 全部练习记录（含跨文练习，articleId <= 0）
     * @param resumables 未完成练习扫描器的原始结果（未过滤）
     * @param todaySentenceKey 今日一句的键；只读，不抽句
     */
    fun prepare(source: Source, zone: ZoneId = ZoneId.systemDefault()): Prepared {
        val recordsByArticle = source.records.groupBy { it.articleId }
        val articleIds = source.articles.mapTo(HashSet(source.articles.size)) { it.id }
        val pausedIds = source.pauses.keys.filterTo(HashSet()) { it in articleIds }
        val articleById = source.articles.associateBy { it.id }

        val dueCandidates = ArrayList<DueArticle>(source.articles.size)
        source.articles.forEach { article ->
            if (article.id in pausedIds) return@forEach
            val records = recordsByArticle[article.id].orEmpty()
            val state = source.states[article.id]
            val dueAt = when {
                state != null && state.reviewCount > 0 -> state.due
                else -> EbbinghausScheduler.nextReviewAt(source.template, records) ?: return@forEach
            }
            val last = records.maxByOrNull { it.timestamp }
            dueCandidates += DueArticle(
                articleId = article.id,
                title = article.title,
                due = dueAt,
                lastReview = state?.lastReview ?: 0L,
                reviewCount = state?.reviewCount ?: 0,
                lapses = state?.lapses ?: 0,
                estimatedBlanks = last?.totalBlanks
                    ?: (article.content.length / 25).coerceIn(3, 80),
                lastDurationMs = last?.duration ?: 0L,
            )
        }

        val resumables = source.resumables.asSequence()
            .filter { it.remaining > 0 && it.articleId in articleIds && it.articleId !in pausedIds }
            .sortedWith(compareByDescending<InProgressPractice> { it.lastTime }.thenBy { it.articleId })
            .map { r ->
                Resumable(
                    articleId = r.articleId,
                    title = articleById[r.articleId]?.title.orEmpty(),
                    mode = r.mode,
                    answered = r.answered,
                    total = r.total,
                    lastTime = r.lastTime,
                )
            }
            .toList()

        val mistakeEntries = MistakeNotebook.entries(source.records, onlyUnresolved = true)
            .filter { it.articleId <= 0L || it.articleId !in pausedIds }
        val weakest = weakestOf(source.articles, recordsByArticle, mistakeEntries, pausedIds)

        val scheduledIds = source.states
            .filterValues { it.reviewCount > 0 }
            .keys
            .filter { it in articleIds }
        val allScheduledPaused = scheduledIds.isNotEmpty() && scheduledIds.all { it in pausedIds }

        val sentenceDue = source.sentenceStates.asSequence()
            .filter { (key, state) ->
                key.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX) && state.reviewCount > 0
            }
            .mapNotNull { (key, state) ->
                val articleId = SentenceSelector.articleIdOf(key) ?: return@mapNotNull null
                if (articleId in pausedIds) return@mapNotNull null
                SentenceDue(key, state.due)
            }
            .toList()

        return Prepared(
            dueCandidates = dueCandidates,
            resumables = resumables,
            weakest = weakest,
            mistakeCount = mistakeEntries.size,
            pausedArticleCount = pausedIds.size,
            allScheduledPaused = allScheduledPaused,
            sentenceDue = sentenceDue,
            practiceCountByDate = source.records.groupingBy {
                DayBucket.localDate(it.timestamp, zone)
            }.eachCount(),
            todaySentenceKey = source.todaySentenceKey,
            secondsPerBlank = secondsPerBlank(source.records.map { it.duration to it.totalBlanks }),
        )
    }

    /**
     * 按「此刻」重建今日工作：到期只认精确 due，任务流分组才用日历日。
     *
     * 全部暂停且没有其它可做内容时不再补每日目标 —— 否则用户暂停全部后，
     * 首页仍会顶着一条「还差 N 次」催他练，暂停等于没暂停。
     */
    fun snapshot(
        prepared: Prepared,
        now: Long,
        goalMinutes: Int = 10,
        dailyPracticeGoal: Int = 0,
        examEpochDay: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        val today = DayBucket.localDate(now, zone)
        val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val startTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val dueNow = prepared.dueCandidates
            .filter { it.due <= now }
            .sortedWith(compareBy({ it.due }, { -it.lapses }))
        val laterToday = prepared.dueCandidates.count { it.due > now && it.due < startTomorrow }
        val overdue = dueNow.count { it.due < startToday }

        val sentenceTotal = prepared.sentenceDue.count { it.due <= now }
        val todaySentenceDue = prepared.sentenceDue.any {
            it.due <= now && it.key == prepared.todaySentenceKey
        }
        val sentenceOther = (sentenceTotal - if (todaySentenceDue) 1 else 0).coerceAtLeast(0)

        val todayPracticeCount = prepared.practiceCountByDate[today] ?: 0
        val firstResumable = prepared.resumables.firstOrNull()
        val weakest = prepared.weakest
        val mistakeCount = prepared.mistakeCount
        val hasOtherContent = dueNow.isNotEmpty() || firstResumable != null ||
            weakest != null || mistakeCount > 0 || sentenceOther > 0
        val effectiveGoal = if (prepared.allScheduledPaused && !hasOtherContent) 0 else dailyPracticeGoal

        val input = Input(
            now = now,
            dueArticles = dueNow,
            resumables = prepared.resumables,
            dueSentenceCount = sentenceOther,
            weakestArticleId = weakest?.articleId,
            weakestArticleTitle = weakest?.title.orEmpty(),
            weakMistakeCount = weakest?.mistakeCount ?: 0,
            mistakeCount = mistakeCount,
            goalMinutes = goalMinutes,
            secondsPerBlank = prepared.secondsPerBlank,
            todayPracticeCount = todayPracticeCount,
            dailyPracticeGoal = effectiveGoal,
            examEpochDay = examEpochDay,
            zone = zone,
        )
        return Snapshot(
            input = input,
            dueArticleCount = dueNow.size,
            overdueArticleCount = overdue,
            laterTodayArticleCount = laterToday,
            pausedArticleCount = prepared.pausedArticleCount,
            sentenceTotalDue = sentenceTotal,
            sentenceOtherDue = sentenceOther,
            firstResumable = firstResumable,
            weakest = weakest,
            mistakeCount = mistakeCount,
            allScheduledPaused = prepared.allScheduledPaused,
            resumables = prepared.resumables,
        )
    }

    /** 一次性构造（测试与通知等低频调用方）；页面内应缓存 [prepare] 的结果。 */
    fun snapshot(
        source: Source,
        now: Long,
        goalMinutes: Int = 10,
        dailyPracticeGoal: Int = 0,
        examEpochDay: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot = snapshot(prepare(source, zone), now, goalMinutes, dailyPracticeGoal, examEpochDay, zone)

    private fun weakestOf(
        articles: List<Article>,
        recordsByArticle: Map<Long, List<PracticeRecord>>,
        mistakeEntries: List<MistakeNotebook.Entry>,
        pausedIds: Set<Long>,
    ): Weakest? {
        val totalBlanks = recordsByArticle.values.sumOf { rs -> rs.sumOf { it.totalBlanks } }
        val totalCorrect = recordsByArticle.values.sumOf { rs -> rs.sumOf { it.correctCount } }
        val prior = if (totalBlanks > 0) 1f - totalCorrect.toFloat() / totalBlanks else 0f
        var best: Weakest? = null
        var bestError = -1f
        articles.forEach { article ->
            if (article.id in pausedIds) return@forEach
            val records = recordsByArticle[article.id].orEmpty()
            val total = records.sumOf { it.totalBlanks }
            if (total < WeaknessMath.MIN_BLANKS) return@forEach
            val correct = records.sumOf { it.correctCount }
            val error = WeaknessMath.adjustedErrorRate(
                (total - correct).coerceAtLeast(0), total, prior
            )
            if (error > bestError) {
                bestError = error
                best = Weakest(
                    articleId = article.id,
                    title = article.title,
                    mistakeCount = mistakeEntries.count { it.articleId == article.id },
                    correctRate = correct.toFloat() / total,
                    practiceCount = records.size,
                )
            }
        }
        return best
    }

    // ── 时间预算 ──

    data class Budget(
        val articleIds: List<Long> = emptyList(),
        /** 本次预算内可进入的句子卡数（只有一篇文章都排不下时才会 >0） */
        val sentenceCardCount: Int = 0,
        val estimatedMinutes: Int = 0,
        val requestedMinutes: Int = 0,
    ) {
        val empty: Boolean get() = articleIds.isEmpty() && sentenceCardCount == 0
    }

    /**
     * 「我只有 [minutes] 分钟」：按到期先后装文章，装到 85% 预算就停，宁少不多。
     *
     * 为什么不是精确排满：估算必然有误差，承诺 15 分钟却练 22 分钟比不承诺更伤信任。
     * 先装未完成的练习（剩余空数已知、误差最小），再装到期文章。
     * 有文章可排时**只承诺文章** —— 练习页不会串接句子卡，承诺了也交付不了；
     * 一篇文章都排不下时才退回句子卡，并按预算折算本次可进入的句数上限。
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

        val cardCount = if (ids.isEmpty() && input.dueSentenceCount > 0) {
            (cap / SENTENCE_CARD_MINUTES).toInt()
                .coerceAtLeast(1)
                .coerceAtMost(input.dueSentenceCount)
        } else {
            0
        }
        if (cardCount > 0) estimated = cardCount * SENTENCE_CARD_MINUTES
        return Budget(
            articleIds = ids.toList(),
            sentenceCardCount = cardCount,
            estimatedMinutes = ceil(estimated).toInt().coerceAtLeast(if (cardCount > 0) 1 else 0),
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
