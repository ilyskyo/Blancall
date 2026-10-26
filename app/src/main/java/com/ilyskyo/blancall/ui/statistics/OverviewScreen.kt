// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import androidx.activity.compose.BackHandler
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.MistakeNotebook
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MarkdownText
import com.ilyskyo.blancall.ui.common.PullReleaseDirection
import com.ilyskyo.blancall.ui.common.PullReleaseIndicator
import com.ilyskyo.blancall.ui.common.StateSwap
import com.ilyskyo.blancall.ui.common.pullReleaseLatched
import com.ilyskyo.blancall.ui.common.pullReleaseReached
import com.ilyskyo.blancall.ui.common.pullReleaseTravel
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.TagDot
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.ai.AiAnalysisItem
import com.ilyskyo.blancall.ui.ai.loadAiAnalyses
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AchievementManager
import com.ilyskyo.blancall.algorithm.CsvExporter
import com.ilyskyo.blancall.algorithm.ForgettingPredictor
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.AchievementTimelineStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.model.toErrorType
import com.ilyskyo.blancall.algorithm.DateRange
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.algorithm.RangePreset
import com.ilyskyo.blancall.algorithm.WeaknessMath
import com.ilyskyo.blancall.algorithm.presetRange
import kotlinx.coroutines.delay
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.navigation.navigateToTab
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.CalendarHeatmap
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.DateRangePickerSheet
import com.ilyskyo.blancall.ui.common.TimeRangeSelector
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GridMaxWidth
import com.ilyskyo.blancall.ui.common.LocalIsLargeScreen
import com.ilyskyo.blancall.ui.common.MemoryDecayChart
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.RadarChart
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.practice.PickerSelection
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 易错榜里的一篇。
 *
 * 为什么不用 `Triple<Long, Float, Int>`：三个位置各是什么（文章 id / 正确率 / 练习次数）只存在于
 * 作者脑子里，而 `it.first` / `.second` / `.third` 在调用点完全读不出来 —— 一旦有人调换构造顺序，
 * 编译器不会报错、界面会静默显示错数据。命名类型把「位置」变成「名字」，这类错就不存在了。
 */
private data class WeakArticle(
    val articleId: Long,
    /** 累计正确率 0..1（展示用；排序用的是收缩后的错误率，见聚合处注释） */
    val correctRate: Float,
    /** 该篇的练习会话数 */
    val practiceCount: Int,
)

/**
 * 学习日历的默认时间维度。所有「默认」「恢复默认」的出口都读它 ——
 * 两处各写一个枚举字面量，改默认值时必然漏掉一处。
 */
private val DEFAULT_CALENDAR_RANGE = RangePreset.LAST_90

/**
 * 全量统计聚合结果。用 remember(allRecords) 包裹，避免每次重组重算。
 */
private data class OverviewStats(
    val totalPractices: Int,
    val totalCorrect: Int,
    val totalBlanks: Int,
    val overallRate: Float,
    val articleCount: Int,
    val sentenceCount: Int,
    val wordCount: Int,
    val reverseCount: Int,
    val sentenceRate: Float,
    val wordRate: Float,
    val reverseRate: Float,
    val recentCount: Int,
    val recentRate: Float,
    val olderCount: Int,
    val olderRate: Float,
    val weakestArticles: List<WeakArticle>,
    val typoCount: Int,
    val glyphCount: Int,
    val missingCount: Int,
    val extraCount: Int,
    val orderCount: Int,
    val incorrectCount: Int,
    val totalMistakes: Int,
    val totalDuration: Long,
    val dailyCounts: List<Int>,   // 近84天每日练习次数
    val todayCount: Int            // 今日已练习次数
)

/**
 * 「句子记忆」统计快照：全部取自句子级 FSRS 状态（不写练习记录，与练习统计口径隔离）。
 * - 遗忘率 = Σlapses / ΣreviewCount（记住率的补数）
 * - 平均留存 = mean([FsrsEngine.retentionRate])，与文章记忆留存率同函数同参数
 */
private data class SentenceMemoryStats(
    val learnedCount: Int,
    val reviews: Int,
    val lapseRate: Float,
    val avgRetention: Float,
    val dueCount: Int,
)

/** 弱点画像六维度占比（换字类拆成「记成他字」与「字形混淆」两维） */
private data class WeaknessProfile(
    val typo: Float,
    val glyph: Float,
    val missing: Float,
    val extra: Float,
    val order: Float,
    val incorrect: Float,
    val totalMistakes: Int
)

@Composable
fun OverviewScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // 根 tab 页面：返回键 = 退出应用（与「我的文章」「素材库」tab 平级语义一致，绝不 pop 回上一 tab/首页）
    BackHandler {
        (context as? android.app.Activity)?.finish()
    }
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    val articleRepo = remember { ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath) }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    // 连续天数的两个数都从练习记录反推（DayBucket），不再读 ReminderPrefs 的计数器：
    // 那个计数器只由「提交成功」自增、从不回推，清过数据/导过备份/换过机之后它会停在
    // 一个无据可查的数字上，而同一屏的「今日次数」「日历热力图」全是按记录算的。
    val dailyGoal by ReminderPrefs.dailyPracticeGoalFlow.collectAsState()
    val templateId by AppPrefs.reviewTemplateFlow.collectAsState()
    val scope = rememberCoroutineScope()

    // ── 文章标签：「标签分布」聚合（多标签文章计入各自标签；跨文练习−1 无单篇归属自然排除）──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val tagStats = remember(articles, allRecords, tagData) {
        TagOps.aggregateTagStats(articles, allRecords, tagData)
    }

    // 最近一条训练分析（Pro：退出批改页后仍可回看）
    var latestAnalysis by remember { mutableStateOf<AiAnalysisItem?>(null) }
    var showLatestAnalysis by remember { mutableStateOf(false) }
    val analysisDateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    LaunchedEffect(Unit) {
        latestAnalysis = withContext(Dispatchers.IO) { loadAiAnalyses(context).firstOrNull() }
    }

    // 模式选择弹窗（即将遗忘 → 去练习）
    var showModePicker by remember { mutableStateOf(false) }
    var pendingPracticeArticleId by remember { mutableLongStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }

    // 当前复习模板
    val template = remember(templateId) { ReviewTemplate.fromId(templateId) }

    // 页面开着跨过午夜时，「今日次数」与「到期数」必须跟着换天：原先 today 和 nowTs 都取在
    // remember{} 内部、key 只有数据集合，于是整屏数字会冻在昨天。ticker 按分钟检查，
    // 只有真的换了日历天才让 stats 重算（不是每分钟全量重扫）。
    var todayKey by remember { mutableStateOf(LocalDate.now()) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            val d = LocalDate.now()
            if (d != todayKey) todayKey = d
            nowTick = System.currentTimeMillis()
        }
    }

    // ── 学习日历的时间维度 ──
    // 预设存 name 而不是枚举：rememberSaveable 的值要能进 Bundle，字符串最稳；
    // 自定义区间存 epochDay，0 = 还没选过（那时回落到默认窗口，而不是画一个 0 天的空区间）。
    var rangePresetName by rememberSaveable { mutableStateOf(DEFAULT_CALENDAR_RANGE.name) }
    var customStartDay by rememberSaveable { mutableLongStateOf(0L) }
    var customEndDay by rememberSaveable { mutableLongStateOf(0L) }
    var showRangePicker by remember { mutableStateOf(false) }
    val rangePreset = remember(rangePresetName) { RangePreset.valueOf(rangePresetName) }
    val customRange = remember(customStartDay, customEndDay) {
        if (customStartDay > 0 && customEndDay > 0) {
            DateRange.of(LocalDate.ofEpochDay(customStartDay), LocalDate.ofEpochDay(customEndDay))
        } else null
    }
    // 区间随 todayKey 重算：页面开着跨过午夜时，「近 7 天」的右端要跟着挪一天
    val activeRange = remember(rangePreset, customRange, todayKey) {
        if (rangePreset == RangePreset.CUSTOM) customRange ?: presetRange(DEFAULT_CALENDAR_RANGE, todayKey)
        else presetRange(rangePreset, todayKey)
    }
    // 学习日历的区间可以是任意天数（近一年 = 365 天），不能再复用 stats 里那 84 天的定长数组，
    // 单独建一张「日期 → 次数」的表，由区间去查。
    val countByDate = remember(allRecords) {
        allRecords.groupingBy { DayBucket.localDate(it.timestamp) }.eachCount()
    }

    // 错误类型单次聚合：供下方 stats 与 weakness 共用，避免重复 flatMap + 多次 count 扫描。
    // 必须经 toErrorType() 归一再计数：落盘的是字符串，而盘上出现过枚举里没有的名字（INCORRECT），
    // 不归一时那一类错误在「共 N 处」里有数、在五个桶里却一个都不显示。
    val mistakeAgg = remember(allRecords) {
        val counts = HashMap<String, Int>(6)
        var total = 0
        allRecords.forEach { r ->
            r.mistakes.forEach { m ->
                counts.merge(m.errorType.toErrorType().name, 1, Int::plus)
                total++
            }
        }
        counts to total
    }

    // ── 全量统计聚合：仅在 allRecords 变化或跨午夜时重算 ──
    val stats = remember(allRecords, todayKey) {
        val today = todayKey
        val totalPractices = allRecords.size
        val totalCorrect = allRecords.sumOf { it.correctCount }
        val totalBlanks = allRecords.sumOf { it.totalBlanks }
        val overallRate = if (totalBlanks > 0) totalCorrect.toFloat() / totalBlanks else 0f

        val byArticle = allRecords.groupBy { it.articleId }
        // 覆盖文章数不含跨文练习（articleId=-1，无单篇归属）
        val articleCount = byArticle.keys.count { it > 0L }

        // 各模式统计（含反向默写）
        val sentenceRecords = allRecords.filter { it.mode == "SENTENCE" }
        val wordRecords = allRecords.filter { it.mode == "WORD" }
        val reverseRecords = allRecords.filter { it.mode == "REVERSE" }
        val sentenceRate = if (sentenceRecords.sumOf { it.totalBlanks } > 0)
            sentenceRecords.sumOf { it.correctCount }.toFloat() / sentenceRecords.sumOf { it.totalBlanks } else 0f
        val wordRate = if (wordRecords.sumOf { it.totalBlanks } > 0)
            wordRecords.sumOf { it.correctCount }.toFloat() / wordRecords.sumOf { it.totalBlanks } else 0f
        val reverseRate = if (reverseRecords.sumOf { it.totalBlanks } > 0)
            reverseRecords.sumOf { it.correctCount }.toFloat() / reverseRecords.sumOf { it.totalBlanks } else 0f

        // 趋势（最近 7 个**日历天** vs 更早）：分界与热力图、今日次数同源。
        // 旧实现用滚动窗口 `now - 7*86400000`，于是一天里不同时区/夏令时的用户看到的
        // 「最近 7 天」和热力图上那 7 列根本不是同七天。
        val weekStart = DayBucket.startOfLastDays(today, 7)
        val recentRecords = allRecords.filter { it.timestamp >= weekStart }
        val recentCorrect = recentRecords.sumOf { it.correctCount }
        val recentBlanks = recentRecords.sumOf { it.totalBlanks }
        val recentRate = if (recentBlanks > 0) recentCorrect.toFloat() / recentBlanks else 0f
        val olderRecords = allRecords.filter { it.timestamp < weekStart && it.timestamp > 0 }
        val olderCorrect = olderRecords.sumOf { it.correctCount }
        val olderBlanks = olderRecords.sumOf { it.totalBlanks }
        val olderRate = if (olderBlanks > 0) olderCorrect.toFloat() / olderBlanks else 0f

        // 易错文章（正确率最低的 3 篇；不含跨文练习——articleId=-1 无单篇统计页）。
        // 两道门槛都是为了让这张榜说真话：
        // ① 空提交（totalBlanks=0）不进榜 —— 旧实现里 t==0 会算出 rate=0f，而 sortedBy 把 0 排最前，
        //    于是一篇只交过空练习的文章会被判为「最弱」并置顶，用户点进去什么也看不见；
        // ② 填空总数不足 MIN_BLANKS 的样本不进榜 —— 练 1 次得 6/10 与练 30 次得 78/100 混排时，
        //    前者几乎必然占前排，而那只是「刚巧只练了一次」。
        // 排序用收缩后的错误率（先验=整页平均错误率），展示仍用真实累计正确率：
        // 前者只决定谁先上榜，后者是用户练出来的数，不能偷偷改写。
        val overallErrorPrior = 1f - overallRate
        val weakestArticles = byArticle.entries
            .mapNotNull { (articleId, articleRecords) ->
                if (articleId <= 0L) return@mapNotNull null
                val t = articleRecords.sumOf { it.totalBlanks }
                if (t < WeaknessMath.MIN_BLANKS) return@mapNotNull null
                val c = articleRecords.sumOf { it.correctCount }
                WeakArticle(articleId, c.toFloat() / t, articleRecords.size) to
                    WeaknessMath.adjustedErrorRate((t - c).coerceAtLeast(0), t, overallErrorPrior)
            }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }

        // 错误类型汇总：复用上方单次聚合结果，不再 flatMap 重复扫描。
        // GLYPH_CONFUSED 是后加的桶：旧记录里那一类错落在 TYPO（当时判题分不出形近），
        // 不追溯重算 —— 两桶分开计，旧数据随新练习积累自然稀释。
        val typoCount = mistakeAgg.first["TYPO"] ?: 0
        val glyphCount = mistakeAgg.first["GLYPH_CONFUSED"] ?: 0
        val missingCount = mistakeAgg.first["MISSING"] ?: 0
        val extraCount = mistakeAgg.first["EXTRA"] ?: 0
        val orderCount = mistakeAgg.first["WRONG_ORDER"] ?: 0
        val incorrectCount = mistakeAgg.first["INCORRECT"] ?: 0
        val totalMistakes = mistakeAgg.second

        val totalDuration = allRecords.sumOf { it.duration }

        // 近 84 天（12 周）每日练习次数：与趋势、streak 同一个 DayBucket 口径（设备时区的日历天）
        val countByDate = allRecords.groupingBy { DayBucket.localDate(it.timestamp) }.eachCount()
        val dailyCounts = (83 downTo 0).map { daysAgo ->
            countByDate[today.minusDays(daysAgo.toLong())] ?: 0
        }

        // 今日已练习次数（同样用系统时区）
        val todayCount = countByDate[today] ?: 0

        OverviewStats(
            totalPractices = totalPractices,
            totalCorrect = totalCorrect,
            totalBlanks = totalBlanks,
            overallRate = overallRate,
            articleCount = articleCount,
            sentenceCount = sentenceRecords.size,
            wordCount = wordRecords.size,
            reverseCount = reverseRecords.size,
            sentenceRate = sentenceRate,
            wordRate = wordRate,
            reverseRate = reverseRate,
            recentCount = recentRecords.size,
            recentRate = recentRate,
            olderCount = olderRecords.size,
            olderRate = olderRate,
            weakestArticles = weakestArticles,
            typoCount = typoCount,
            glyphCount = glyphCount,
            missingCount = missingCount,
            extraCount = extraCount,
            orderCount = orderCount,
            incorrectCount = incorrectCount,
            totalMistakes = totalMistakes,
            totalDuration = totalDuration,
            dailyCounts = dailyCounts,
            todayCount = todayCount
        )
    }

    // 连续天数：以练习记录反推。ReminderPrefs 那个计数器只由「提交成功」自增、从不回推，
    // 所以删过记录、换过时区、或某天没练而计数器没归零时，它会一直显示一个无据可查的数字，
    // 而同一屏上「今日次数」「日历热力图」却是从记录算的 —— 三个数各说各话。
    // 「历史最长」同样反推：它原先读那个计数器，于是徽章在发奖时信一套真相、页面显示时信另一套。
    val studiedDays = remember(allRecords) {
        allRecords.mapTo(mutableSetOf()) { DayBucket.localDate(it.timestamp) }
    }
    val streakFromRecords = remember(studiedDays, todayKey) {
        DayBucket.consecutiveDays(studiedDays, todayKey)
    }
    val longestFromRecords = remember(studiedDays) { DayBucket.longestRun(studiedDays) }

    // 跨文复习（articleId=-1）计入总量、趋势与热力图，却被单篇统计刻意排除（它没有单篇归属）。
    // 页面里必须写出来，否则「总次数」和「覆盖文章数 ×各篇次数」永远对不上且无人解释。
    val crossArticlePractices = remember(allRecords, todayKey) {
        allRecords.count {
            it.articleId <= 0L && it.timestamp >= DayBucket.startOfLastDays(todayKey, 14)
        }
    }

    // 累计阅读时长（秒）：跨所有文章汇总沉浸阅读模式记录
    val totalReadingSeconds = remember(articles) {
        articles.sumOf { AppPrefs.getReadingSeconds(it.id) }
    }

    // ── 遗忘曲线预测：FSRS 自适应调度（无 FSRS 状态的文章回退模板）──
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    // FSRS 状态后台加载：首帧先渲染，加载完成后刷新，避免同步读文件卡顿导致预测短暂失真
    var fsrsStates by remember { mutableStateOf(fsrsStore.allStates()) }
    // 句子卡片（每日一句）的句子级状态：同源加载，供「句子记忆」统计块使用
    var sentenceStates by remember { mutableStateOf(fsrsStore.allSentenceStates()) }
    // 暂停中的文章：预测与句子到期数都不再把它们读成「该复习」
    var pausedArticleIds by remember { mutableStateOf(fsrsStore.pausedArticleIds()) }
    var fsrsReady by remember { mutableStateOf(false) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        fsrsStates = fsrsStore.allStates()
        sentenceStates = fsrsStore.allSentenceStates()
        pausedArticleIds = fsrsStore.pausedArticleIds()
        fsrsReady = true
    }
    val predictions = remember(articles, allRecords, template, fsrsStates, pausedArticleIds) {
        ForgettingPredictor.predict(articles, allRecords, template, fsrsStates, pausedArticleIds)
    }
    val dueSoonAll = remember(predictions) { ForgettingPredictor.dueSoon(predictions) }
    val dueSoon = remember(dueSoonAll) { dueSoonAll.take(5) }
    // 「这条结论是 FSRS 算的还是模板估的」——判据与 ForgettingPredictor 走哪条分支用的是同一个条件
    // （有 FSRS 状态且 reviewCount>0 才是自适应调度），不在这里另立一套口径。
    val adaptiveIds = remember(fsrsStates) { fsrsStates.filterValues { it.reviewCount > 0 }.keys }

    // 「下一步」的到期文章输入：与任务流同一份 FSRS 到期判定，取最早到期的一篇
    val firstDueArticleId = remember(fsrsStates, pausedArticleIds, nowTick) {
        fsrsStates.entries
            .filter { (id, state) ->
                state.reviewCount > 0 && id !in pausedArticleIds && nowTick >= state.due
            }
            .minByOrNull { it.value.due }
            ?.key
    }

    // ── 句子记忆统计：取自句子级 FSRS 状态，留存率口径与文章一致（同函数同参数）──
    val sentenceStats = remember(sentenceStates, nowTick, pausedArticleIds) {
        val seen = sentenceStates.values.filter { it.reviewCount > 0 }
        val reviews = seen.sumOf { it.reviewCount }
        val lapses = seen.sumOf { it.lapses }
        val nowTs = nowTick
        SentenceMemoryStats(
            learnedCount = seen.size,
            reviews = reviews,
            lapseRate = if (reviews > 0) lapses.toFloat() / reviews else 0f,
            avgRetention = if (seen.isEmpty()) 0f
            else (seen.sumOf { FsrsEngine.retentionRate(it, nowTs) } / seen.size).toFloat(),
            dueCount = sentenceStates.count { (key, state) ->
                state.reviewCount > 0 && nowTs >= state.due &&
                    SentenceSelector.articleIdOf(key) !in pausedArticleIds
            },
        )
    }

    // ── 弱点画像：六维度错误占比 ──
    val weakness = remember(mistakeAgg) {
        val total = mistakeAgg.second.coerceAtLeast(1)
        WeaknessProfile(
            typo = (mistakeAgg.first["TYPO"] ?: 0).toFloat() / total,
            glyph = (mistakeAgg.first["GLYPH_CONFUSED"] ?: 0).toFloat() / total,
            missing = (mistakeAgg.first["MISSING"] ?: 0).toFloat() / total,
            extra = (mistakeAgg.first["EXTRA"] ?: 0).toFloat() / total,
            order = (mistakeAgg.first["WRONG_ORDER"] ?: 0).toFloat() / total,
            incorrect = (mistakeAgg.first["INCORRECT"] ?: 0).toFloat() / total,
            totalMistakes = mistakeAgg.second
        )
    }

    // 错题本入口：未攻克数量与错题本页同一口径（MistakeNotebook），不在界面另算一次
    val unresolvedMistakes = remember(allRecords) {
        MistakeNotebook.entries(allRecords, onlyUnresolved = true).size
    }

    // ── 成就徽章 ──
    val achievements = remember(allRecords, longestFromRecords, sentenceStats) {
        AchievementManager.evaluate(
            allRecords, longestFromRecords,
            // 记忆向那一族的输入：与「句子记忆」卡同一份数据，不另算一套口径
            AchievementManager.MemoryStats(
                learnedSentences = sentenceStats.learnedCount,
                avgRetention = if (sentenceStats.learnedCount > 0) sentenceStats.avgRetention else null,
                dueSentences = sentenceStats.dueCount
            )
        )
    }
    // 解锁时刻：派生只管「是不是」，这张表只管「什么时候」（见 AchievementTimelineStore）。
    // 记不上盘也不影响徽章本身，所以整段都放在 IO + 失败容忍里。
    val achievementTimeline = remember { AchievementTimelineStore.getInstance(context.filesDir) }
    var achievedAt by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var justUnlocked by remember { mutableStateOf<List<String>>(emptyList()) }
    val unlockHaptic = rememberConfirmHaptic()
    LaunchedEffect(achievements) {
        val unlocked = achievements.filter { it.unlocked }.map { it.id }.toSet()
        val fresh = withContext(Dispatchers.IO) { achievementTimeline.recordUnlocked(unlocked) }
        achievedAt = withContext(Dispatchers.IO) { achievementTimeline.snapshot() }
        if (fresh.isNotEmpty()) {
            // 解锁是低频事件，不会变成通知噪音；给一次触感 + 卡内一行提示就够，不弹层
            justUnlocked = fresh.toList()
            unlockHaptic()
        }
    }

    // 易错文章标题解析（异步）
    var weakTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(stats) {
        val ids = stats.weakestArticles.map { it.articleId }
        val titles = withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                articleRepo.getArticleById(id)?.title ?: resources.getString(com.ilyskyo.blancall.R.string.ov_article_fallback, id)
            }
        }
        weakTitles = titles
    }

    // CSV 导出中状态
    var isExporting by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        // 毛玻璃氛围背景（背景色之上、内容之下）
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 大屏放宽上限：平板横屏用满宽度展示统计卡，窄屏不受影响（600dp 内铺满）
                .widthIn(max = if (LocalIsLargeScreen) GridMaxWidth else 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── 顶部栏：返回 + 标题 + 筛选 + 导出 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 底部导航固定启用：OverviewScreen 作为底部导航 tab，无返回键（切 home 即返回）

                Text(
                    stringResource(com.ilyskyo.blancall.R.string.ov_global_stats),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                // 顶部按钮组：与「我的文章」页同款间距（8dp），保持全局统一
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 文章筛选：点击后下拉列出所有文章，选择跳转该篇统计页
                    var showArticleFilter by remember { mutableStateOf(false) }
                    Box {
                        GlassButton(
                            onClick = { showArticleFilter = true },
                            enabled = articles.isNotEmpty(),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.filter),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        GlassDropdownMenu(
                            expanded = showArticleFilter,
                            onDismissRequest = { showArticleFilter = false }
                        ) {
                            articles.forEach { article ->
                                GlassMenuItem(
                                    onClick = {
                                        showArticleFilter = false
                                        navController.navigate("statistics/${article.id}")
                                    },
                                    label = {
                                        Text(
                                            article.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                            }
                        }
                    }
                    // CSV 导出按钮
                    GlassButton(
                        onClick = {
                            if (!isExporting && allRecords.isNotEmpty()) {
                                scope.launch {
                                    isExporting = true
                                    try {
                                        val ok = CsvExporter.exportAndShare(context, allRecords, articleRepo)
                                        if (!ok) {
                                            android.widget.Toast.makeText(
                                                context,
                                                resources.getString(com.ilyskyo.blancall.R.string.ov_export_failed_retry),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            resources.getString(com.ilyskyo.blancall.R.string.ov_export_failed_reason,
                                                e.message ?: ""),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            }
                        },
                        enabled = !isExporting && allRecords.isNotEmpty(),
                        modifier = Modifier.height(40.dp)
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isExporting) stringResource(com.ilyskyo.blancall.R.string.ov_exporting)
                        else stringResource(com.ilyskyo.blancall.R.string.export_action),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        Spacer(Modifier.height(4.dp))

        val isWide = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp()
        } >= 600.dp

        // 滚动驱动的底栏自动收起：内容前进时导航栏让位、回滚时恢复（不再需要避让留白）
        val navBarScrollConn = rememberAutoHideNavBarOnScroll()

        // ── 下拉进入学习任务流 ──
        // 任务流不再做成一张可点的卡片：滚到列表顶部后继续下拉，圆环随位移填充。
        // **手指不松开就不进入**：拉满只给一次 Threshold 触感（「到界了」），
        // 真正的跳转发生在抬手那一刻 —— 本次手势满过格就算数，没满则回弹。
        // 满格后回退超过 OVERVIEW_FLOW_PULL_CANCEL_SLACK 才算主动撤回：抬手时的
        // 几像素回带不再把「已画满」误判成「没拉满」。
        val flowListState = rememberLazyListState()
        // 唯一的位移真值：拖动时**同步**写入，绘制也从它读。
        // Animatable 只用来驱动「没拉满 → 回弹」那一段动画，不再承担手势状态。
        // 旧实现把 Animatable 当手势真值，snapTo 走协程，抬手判定可能读到上一帧的旧值：
        // 圆圈已经画满、松手却差 1–2dp（「有的时候进不去」的根因之一）。
        val flowPullPx = remember { mutableFloatStateOf(0f) }
        val flowPullOffset = remember { Animatable(0f) }
        var flowPullRecoilJob by remember { mutableStateOf<Job?>(null) }
        val flowPullScope = rememberCoroutineScope()
        val flowPullThresholdPx = with(LocalDensity.current) { OVERVIEW_FLOW_PULL_THRESHOLD.toPx() }
        val flowPullOvershootPx = with(LocalDensity.current) { OVERVIEW_FLOW_PULL_OVERSHOOT.toPx() }
        val flowPullCancelSlackPx = with(LocalDensity.current) { OVERVIEW_FLOW_PULL_CANCEL_SLACK.toPx() }
        // AtomicBoolean：判定发生在 NestedScrollConnection 的闭包里，捕获的必须是每帧最新的值。
        // armed 保证「越界触感」一次拖动只响一次（退回阈值下再给额度）；
        // ready 是「本次手势满过格」的闩锁 —— 抬手时手指常会回带几像素，不能因此把满格判成没满；
        // navigated 保证抬手判定只导航一次。
        val flowPullArmed = remember { AtomicBoolean(true) }
        val flowPullReady = remember { AtomicBoolean(false) }
        val flowPullNavigated = remember { AtomicBoolean(false) }
        // 触感强度在设置里改完要立刻生效，所以经 rememberUpdatedState 透传。
        val flowPullHaptic by rememberUpdatedState(rememberHaptic(HapticTier.Threshold))
        // 无障碍入口：可见面不再写字（提示行已移除，见使用帮助「数据页 · 学习任务流」），
        // 读屏用户拿不到手势，所以把入口挂在列表节点的自定义操作上。
        val flowActionLabel = stringResource(com.ilyskyo.blancall.R.string.flow_pull_action)

        /**
         * 按手势增量推进位移（正 = 继续拉、负 = 往回收），返回本段手势被消费掉的 y。
         *
         * 位移在这一帧**同步**写入 [flowPullPx]（绘制与抬手判定读同一份值），
         * 不再经 `snapTo` 协程绕一圈。这里只改位移与触感，**绝不导航**：
         * 导航统一由 [releaseFlowPull] 在抬手时决定。
         */
        fun advanceFlowPull(dy: Float): Float {
            val current = flowPullPx.floatValue
            if (current <= 0f && dy < 0f) return 0f
            // 从零重新往下拉 = 新一次手势：解除上一次的一次性导航锁，
            // 否则一次「导航被忽略」会让后续所有下拉永远进不去。
            if (current <= 0.5f && dy > 0f) {
                flowPullNavigated.set(false)
                flowPullReady.set(false)
            }
            // 新拖动抢占回弹：让回弹停在这一帧，位移从这里继续跟手
            flowPullRecoilJob?.cancel()
            flowPullRecoilJob = null
            val next = pullReleaseTravel(
                current = current,
                delta = dy,
                threshold = flowPullThresholdPx,
                overshootMax = flowPullOvershootPx,
                overshootRatio = OVERVIEW_FLOW_PULL_OVERSHOOT_RATIO,
            )
            if (next != current) flowPullPx.floatValue = next
            flowPullReady.set(
                pullReleaseLatched(
                    wasLatched = flowPullReady.get(),
                    pullPx = next,
                    thresholdPx = flowPullThresholdPx,
                    cancelSlackPx = flowPullCancelSlackPx,
                )
            )
            if (pullReleaseReached(next, flowPullThresholdPx)) {
                // 跨过阈值：给一次「到界」触感；继续拉、原地停留都不再重复
                if (flowPullArmed.compareAndSet(true, false)) flowPullHaptic()
            } else {
                // 退回阈值以内 ⇒ 下次再拉满还能再响一次
                flowPullArmed.set(true)
            }
            return next - current
        }

        /** 没拉满（或已回退取消）：从当前位移回弹归零。 */
        fun settleFlowPull() {
            val pull = flowPullPx.floatValue
            if (pull <= 0.5f) return
            flowPullRecoilJob?.cancel()
            flowPullRecoilJob = flowPullScope.launch {
                try {
                    // Animatable 在拖动期间不跟手，可能落后于手势真值：回弹从真值起步
                    flowPullOffset.snapTo(pull)
                    flowPullOffset.animateTo(0f, Motion.pullRecoil()) {
                        flowPullPx.floatValue = value
                    }
                    // 完整播完才清闩锁；被新拖动抢占时交给新拖动判断
                    flowPullReady.set(false)
                } finally {
                    // 动画被新的拖动取消时也要重新给一次「到位」额度，否则第二次下拉不响
                    flowPullArmed.set(true)
                }
            }
        }

        fun enterStudyFlow() {
            if (flowPullNavigated.compareAndSet(false, true)) {
                navController.navigate("study_flow")
                // 兜底：导航若被系统忽略（例如转场中重复调用），本页仍留在组合里，
                // 800ms 后解除一次性锁并回弹，下一次下拉可以重试；
                // 导航成功时本页销毁，协程随组合取消，不会执行。
                flowPullScope.launch {
                    delay(800)
                    // 期间用户已开始新手势（advance 会复位锁）或导航已发生 → 兜底不必再动
                    if (!flowPullNavigated.get()) return@launch
                    if (navController.currentDestination?.route?.startsWith("study_flow") != true) {
                        flowPullNavigated.set(false)
                        flowPullReady.set(false)
                        settleFlowPull()
                    }
                }
            }
        }

        /** 抬手判定：本次手势满过格就进任务流，否则回弹归零。拖动过程中永远不走到这里。 */
        fun releaseFlowPull() {
            if (flowPullNavigated.get()) return
            if (flowPullPx.floatValue <= 0.5f) return
            if (flowPullReady.get()) {
                enterStudyFlow()
                return
            }
            settleFlowPull()
        }

        val flowPullConnection = remember(flowListState, flowPullThresholdPx, flowPullOvershootPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 松手后的惯性帧不参与蓄力：只有手指拖拽才算数
                    if (source != NestedScrollSource.Drag) return Offset.Zero
                    val dy = available.y
                    if (dy > 0f && !flowListState.canScrollBackward) {
                        // 已在顶部还继续下拉：整段转成蓄力位移，不交给系统过度滚动光效
                        advanceFlowPull(dy)
                        return Offset(0f, dy)
                    }
                    if (dy < 0f && flowPullPx.floatValue > 0f) {
                        // 手指往回收：先抵掉蓄力位移（圆环倒转），抵完剩下的才让列表滚
                        val used = advanceFlowPull(dy)
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // 本帧列表把位移吃到底后还剩的向下余量，接到蓄力上：从列表中间一路下拉也连贯
                    if (source != NestedScrollSource.Drag) return Offset.Zero
                    if (available.y <= 0f || flowListState.canScrollBackward) return Offset.Zero
                    advanceFlowPull(available.y)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    releaseFlowPull()
                    return Velocity.Zero
                }
            }
        }

        // 空态 ⇄ 数据面的切换：用户练完第一篇再回到本页时就会跨过这一次。
        // weight 在 AnimatedContent 的子树里是失效的（只 modifyParentData，没有 Column 去消费），
        // 所以撑剩余高度上提到这里的 modifier；两个分支各自收成一个根节点，内部改回 fillMaxSize。
        StateSwap(
            targetState = allRecords.isEmpty(),
            label = "overviewRecords",
            modifier = Modifier.weight(1f)
        ) { empty ->
            if (empty) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AppIcon(
                        kind = AppIconKind.Insights,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(com.ilyskyo.blancall.R.string.no_practice_history),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(com.ilyskyo.blancall.R.string.ov_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    GlassButton(onClick = { navController.navigateToTab("list") }) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.go_practice),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            // 松手兜底：慢松手时嵌套滚动不会派发 fling 回调（同首页纪律），
                            // 这里在 Final pass 仅观察、不消费：指针全抬起时做一次抬手判定。
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        if (event.changes.none { it.pressed }) releaseFlowPull()
                                    }
                                }
                            }
                    ) {
                        // 指示器画在列表下层：内容跟手下移后，顶部让出的空隙里露出来。
                        // 读到的是同步位移真值（绘相位），不会每帧重组整页。
                        PullReleaseIndicator(
                            pullPx = { flowPullPx.floatValue },
                            thresholdPx = flowPullThresholdPx,
                            direction = PullReleaseDirection.Down,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        LazyColumn(
                            state = flowListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationY = flowPullPx.floatValue }
                                // 手势入口不再有可见文字：这道自定义操作是读屏用户的等价入口
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(flowActionLabel) {
                                            enterStudyFlow()
                                            true
                                        }
                                    )
                                }
                                .nestedScroll(flowPullConnection)
                                .nestedScroll(navBarScrollConn),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            // 底部只留呼吸留白：导航栏改为「滚动自动收起」后不再需要 120dp 避让底距
                            contentPadding = PaddingValues(bottom = 12.dp)
                        ) {
                        // ── 最近训练分析（有则显示） ──
                        latestAnalysis?.let { item ->
                            item(key = "latestAnalysis") {
                                GlassCard(
                                    // 清新马卡龙：天空蓝淡彩卡面
                                    containerColor = Macaron.info().fill,
                                    onClick = { showLatestAnalysis = true }
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(Macaron.info().accent, RoundedCornerShape(50))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(com.ilyskyo.blancall.R.string.ov_recent_analysis),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "${item.modeLabel} · ${analysisDateFormat.format(Date(item.createdAt))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            item.articleTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Macaron.info().accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.preview.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                item.preview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(
                                                onClick = { navController.navigate("ai_history?section=analysis") },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text(stringResource(com.ilyskyo.blancall.R.string.view_all_plain),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.tertiary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // ── 「下一步」行动卡（置顶）──
                        // 整页其余卡片都在陈列「练了多少 / 错在哪一类 / 哪篇弱」，用户看完仍要自己回答
                        // 「所以现在练什么」。这张卡把已有结论汇成一个动词：到期句数 + 最弱一篇 → 一个按钮。
                        // 只给「最该做的那一件」：到期文章 > 薄弱篇 > 到期句，与首页今日计划同序。
                        val nextWeakest = stats.weakestArticles.firstOrNull()
                        item(key = "overview_mistake_notebook") {
                            AnimatedOverviewCard {
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pressClick { navController.navigate("mistakes") }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.mistake_notebook_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                if (unresolvedMistakes > 0) {
                                                    stringResource(R.string.ov_notebook_count, unresolvedMistakes)
                                                } else {
                                                    stringResource(R.string.notebook_empty)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        AppIcon(
                                            AppIconKind.ChevronRight,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "overview_card_next_step") {
                            AnimatedOverviewCard {
                                NextStepCard(
                                    dueSentences = sentenceStats.dueCount,
                                    weakest = nextWeakest,
                                    weakestTitle = nextWeakest?.let { w ->
                                        weakTitles[w.articleId] ?: stringResource(
                                            com.ilyskyo.blancall.R.string.ov_article_fallback, w.articleId
                                        )
                                    }.orEmpty(),
                                    dueArticleId = firstDueArticleId,
                                    dueArticleTitle = firstDueArticleId?.let { id ->
                                        articles.firstOrNull { it.id == id }?.title
                                            ?: stringResource(
                                                com.ilyskyo.blancall.R.string.ov_article_fallback, id
                                            )
                                    }.orEmpty(),
                                    onWeaknessDrill = { articleId ->
                                        navController.navigate("practice/$articleId?mode=SENTENCE&sectionMode=WEAKNESS")
                                    },
                                    onGoReview = { navController.navigate("sentence_cards") },
                                    onGoArticle = { articleId ->
                                        navController.navigate("practice/$articleId?mode=SENTENCE")
                                    }
                                )
                            }
                        }
                        // ── 总览卡片（仪表盘 + 连续天数 + 时长） ──
                        // 每张卡都要稳定 key：裸 `item {` 的卡没有 key，滚动把卡片回收再滚回来时
                        // Compose 认不出「这是同一张卡」，会当成新卡重新组合。
                        // 注意**稳定 key 本身不足以**防住入场动画重播 —— 动画的「已播」标记还必须
                        // 用 rememberSaveable 才活得下来（见 AnimatedOverviewCard 的注释）。
                        // 这里的卡片顺序在源码里写死、不会重排，所以按出现顺序的常量 key 既唯一又稳定。
                        item(key = "overview_card_1") {
                            AnimatedOverviewCard {
                                GlassCard(
                                    // 清新马卡龙：薰衣草淡彩卡面
                                    containerColor = Macaron.lavender().fill
                                ) {
                                    Column(Modifier.padding(18.dp)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ov_data_overview),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(14.dp))
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            GaugeProgress(
                                                progress = stats.overallRate,
                                                label = stringResource(com.ilyskyo.blancall.R.string.overall_accuracy),
                                                modifier = Modifier.size(110.dp)
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("🔥", style = MaterialTheme.typography.titleMedium)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(stringResource(com.ilyskyo.blancall.R.string.streak_days, streakFromRecords),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface)
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                Text(pluralStringResource(com.ilyskyo.blancall.R.plurals.longest_streak,
                                                        longestFromRecords, longestFromRecords),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.height(8.dp))
                                                if (stats.totalDuration > 0) {
                                                    val hours = stats.totalDuration / 3_600_000L
                                                    val mins = (stats.totalDuration / 60_000L) % 60
                                                    Text(stringResource(com.ilyskyo.blancall.R.string.ov_practice_hm, hours, mins),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(Modifier.height(4.dp))
                                                }
                                                if (totalReadingSeconds > 0) {
                                                    val rHours = totalReadingSeconds / 3600
                                                    val rMins = (totalReadingSeconds / 60) % 60
                                                    Text(stringResource(com.ilyskyo.blancall.R.string.ov_reading_hm, rHours, rMins),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(Modifier.height(4.dp))
                                                }
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    StatItem(stringResource(com.ilyskyo.blancall.R.string.practice_count),
                                                        "${stats.totalPractices}")
                                                    StatItem(stringResource(com.ilyskyo.blancall.R.string.stat_articles_touched),
                                                        "${stats.articleCount}")
                                                    StatItem(stringResource(com.ilyskyo.blancall.R.string.stat_total_blanks_short),
                                                        "${stats.totalBlanks}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── 今日目标卡片（每日练习次数目标 + 完成度） ──
                        item(key = "overview_card_2") {
                            AnimatedOverviewCard {
                                TodayGoalCard(
                                    todayCount = stats.todayCount,
                                    goal = dailyGoal,
                                    onGoalChange = { ReminderPrefs.dailyPracticeGoal = it }
                                )
                            }
                        }

                        // ── 遗忘曲线预测卡片 ──
                        // FSRS 状态还在后台加载时不给结论：加载完成前每篇都按「无状态」判成未开始/已掌握，
                        // 加载完又集体翻牌，用户看到的是页面在自我纠正。按「空态不放假进度圈、
                        // 宁给一句正文」的既有规矩，这里只给一句正文。
                        if (!fsrsReady && allRecords.isNotEmpty()) {
                            item(key = "overview_card_3") {
                                GlassCard {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.predictions_loading),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        } else if (dueSoon.isNotEmpty()) {
                            item(key = "overview_card_3") {
                                AnimatedOverviewCard {
                                    ForgettingPredictionCard(
                                        predictions = dueSoon,
                                        adaptiveIds = adaptiveIds,
                                        dueTotal = dueSoonAll.size,
                                        anchorArticleId = pendingPracticeArticleId,
                                        onPracticeButtonPlaced = { _, rect -> practiceButtonRect = rect },
                                        onPractice = { articleId ->
                                            // 先弹模式选择选项卡，选定后再进入练习；返回时仍回到本统计页
                                            pendingPracticeArticleId = articleId
                                            showModePicker = true
                                        }
                                    )
                                }
                            }
                        }

                        // ── 句子记忆（首页「句子卡片」有数据时显示；不写练习记录，与练习统计口径隔离） ──
                        if (sentenceStats.learnedCount > 0) {
                            item(key = "overview_card_4") {
                                AnimatedOverviewCard {
                                    GlassCard {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(stringResource(com.ilyskyo.blancall.R.string.sentence_memory_title),
                                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(10.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                StatItem(
                                                    stringResource(com.ilyskyo.blancall.R.string.learned_sentences_label),
                                                    pluralStringResource(
                                                        com.ilyskyo.blancall.R.plurals.learned_sentences_count,
                                                        sentenceStats.learnedCount, sentenceStats.learnedCount
                                                    ),
                                                    fontSize = 16.sp
                                                )
                                                StatItem(
                                                    stringResource(com.ilyskyo.blancall.R.string.stat_total_reviews),
                                                    pluralStringResource(
                                                        com.ilyskyo.blancall.R.plurals.times_short,
                                                        sentenceStats.reviews, sentenceStats.reviews
                                                    ),
                                                    fontSize = 16.sp
                                                )
                                                StatItem(stringResource(com.ilyskyo.blancall.R.string.stat_lapse_rate),
                                                    "${(sentenceStats.lapseRate * 100).toInt()}%", fontSize = 16.sp)
                                                StatItem(stringResource(com.ilyskyo.blancall.R.string.stat_avg_retention),
                                                    "${(sentenceStats.avgRetention * 100).toInt()}%", fontSize = 16.sp)
                                            }
                                            if (sentenceStats.dueCount > 0) {
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    pluralStringResource(
                                                        com.ilyskyo.blancall.R.plurals.due_sentences_today,
                                                        sentenceStats.dueCount, sentenceStats.dueCount
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── 趋势分析分组 ──
                        item(key = "overview_card_5") {
                            Text(stringResource(com.ilyskyo.blancall.R.string.trend_analysis_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        // ── 每日练习趋势 ──
                        item(key = "overview_card_6") {
                            AnimatedOverviewCard {
                                GlassCard {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.daily_trend),
                                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(12.dp))
                                        DailyTrendChart(dailyCounts = stats.dailyCounts.takeLast(14))
                                        if (crossArticlePractices > 0) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(pluralStringResource(com.ilyskyo.blancall.R.plurals.scope_note_cross_article, crossArticlePractices, crossArticlePractices),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // ── 日历热力图 ──
                        item(key = "overview_card_7") {
                            AnimatedOverviewCard {
                                GlassCard {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(stringResource(com.ilyskyo.blancall.R.string.study_calendar_title),
                                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            // 时间维度选择器：预设走下拉，自定义打开区间面板。
                                            // 胶囊前挂「时间维度」小标签：单看「近 90 天」三个字，
                                            // 用户并不知道这个胶囊管的是整张日历的范围。
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    stringResource(com.ilyskyo.blancall.R.string.calendar_range_label),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                TimeRangeSelector(
                                                    selected = rangePreset,
                                                    customRange = customRange,
                                                    onSelect = { rangePresetName = it.name },
                                                    onOpenCustom = { showRangePicker = true },
                                                    // 默认档与复位动作必须同源：胶囊上的"默认"就是这里给的那一档，
                                                    // 恢复默认同时清掉自定义区间，否则 CUSTOM 的旧值会留在 Bundle 里
                                                    defaultPreset = DEFAULT_CALENDAR_RANGE,
                                                    onResetToDefault = {
                                                        rangePresetName = DEFAULT_CALENDAR_RANGE.name
                                                        customStartDay = 0L
                                                        customEndDay = 0L
                                                    }
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        CalendarHeatmap(
                                            range = activeRange,
                                            countOn = { countByDate[it] ?: 0 }
                                        )
                                    }
                                }
                            }
                        }

                        // ── 能力画像分组 ──
                        item(key = "overview_card_8") {
                            Text(stringResource(com.ilyskyo.blancall.R.string.ability_profile_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        // ── 弱点画像（雷达图 + 训练建议） ──
                        if (stats.totalMistakes > 0) {
                            item(key = "overview_card_9") {
                                AnimatedOverviewCard {
                                    WeaknessProfileCard(weakness = weakness)
                                }
                            }
                        }

                        // ── 模式对比（含反向默写） ──
                        item(key = "overview_card_10") {
                            AnimatedOverviewCard {
                                GlassCard {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.mode_compare),
                                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(10.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                            StatItem(
                                                stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
                                                pluralStringResource(com.ilyskyo.blancall.R.plurals.mode_stat_value,
                                                    stats.sentenceCount, stats.sentenceCount,
                                                    (stats.sentenceRate * 100).toInt())
                                            )
                                            StatItem(
                                                stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
                                                pluralStringResource(com.ilyskyo.blancall.R.plurals.mode_stat_value,
                                                    stats.wordCount, stats.wordCount,
                                                    (stats.wordRate * 100).toInt())
                                            )
                                            StatItem(
                                                stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse),
                                                pluralStringResource(com.ilyskyo.blancall.R.plurals.mode_stat_value,
                                                    stats.reverseCount, stats.reverseCount,
                                                    (stats.reverseRate * 100).toInt())
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── 趋势 ──
                        if (stats.olderCount > 0) {
                            item(key = "overview_card_11") {
                                AnimatedOverviewCard {
                                    GlassCard {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(stringResource(com.ilyskyo.blancall.R.string.progress_trend_title),
                                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(10.dp))
                                            val trend = stats.recentRate - stats.olderRate
                                            val trendText = when {
                                                trend > 0.05f -> stringResource(
                                                    com.ilyskyo.blancall.R.string.trend_up, (trend * 100).toInt()
                                                )
                                                trend < -0.05f -> stringResource(
                                                    com.ilyskyo.blancall.R.string.trend_down, (-trend * 100).toInt()
                                                )
                                                else -> stringResource(com.ilyskyo.blancall.R.string.trend_flat)
                                            }
                                            Text(trendText, style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(6.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                StatItem(
                                                    stringResource(com.ilyskyo.blancall.R.string.stat_last_7d),
                                                    pluralStringResource(com.ilyskyo.blancall.R.plurals.rate_with_count,
                                                        stats.recentCount,
                                                        (stats.recentRate * 100).toInt(), stats.recentCount)
                                                )
                                                StatItem(
                                                    stringResource(com.ilyskyo.blancall.R.string.stat_earlier),
                                                    pluralStringResource(com.ilyskyo.blancall.R.plurals.rate_with_count,
                                                        stats.olderCount,
                                                        (stats.olderRate * 100).toInt(), stats.olderCount)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── 薄弱环节 ──
                        if (stats.totalMistakes > 0) {
                            item(key = "overview_card_12") {
                                AnimatedOverviewCard {
                                    GlassCard {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(stringResource(com.ilyskyo.blancall.R.string.weak_spots),
                                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.height(10.dp))
                                            MistakeBar(
                                                typo = stats.typoCount,
                                                glyph = stats.glyphCount,
                                                missing = stats.missingCount,
                                                extra = stats.extraCount,
                                                order = stats.orderCount,
                                                incorrect = stats.incorrectCount
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── 成就徽章卡片 ──
                        item(key = "overview_card_13") {
                            AnimatedOverviewCard {
                                AchievementGridCard(
                                    achievements = achievements,
                                    achievedAt = achievedAt,
                                    justUnlocked = justUnlocked,
                                    onDismissUnlocked = { justUnlocked = emptyList() }
                                )
                            }
                        }

                        // ── 易错文章（可点击跳转） ──
                        if (stats.weakestArticles.isNotEmpty()) {
                            item(key = "overview_card_14") {
                                Text(stringResource(com.ilyskyo.blancall.R.string.weak_articles_title),
                                    style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 4.dp))
                            }
                            if (isWide) {
                                item(key = "overview_card_15") {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        for ((articleId, rate, count) in stats.weakestArticles) {
                                            GlassCard(
                                                modifier = Modifier
                                                    .weight(1f),
                                                containerColor = Macaron.neutral().fill,
                                                onClick = { navController.navigate("statistics/$articleId") }
                                            ) {
                                                Row(
                                                    Modifier.padding(12.dp).fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        weakTitles[articleId] ?: stringResource(
                                                            com.ilyskyo.blancall.R.string.ov_article_fallback, articleId
                                                        ),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(pluralStringResource(
                                                        com.ilyskyo.blancall.R.plurals.weak_row_value, count,
                                                        (rate * 100).toInt(), count
                                                    ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(stats.weakestArticles, key = { it.articleId }) { (articleId, rate, count) ->
                                    GlassCard(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        containerColor = Macaron.neutral().fill,
                                        onClick = { navController.navigate("statistics/$articleId") }
                                    ) {
                                        Row(
                                            Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                weakTitles[articleId] ?: stringResource(
                                                    com.ilyskyo.blancall.R.string.ov_article_fallback, articleId
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(pluralStringResource(
                                                com.ilyskyo.blancall.R.plurals.weak_row_value, count,
                                                (rate * 100).toInt(), count
                                            ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        // ── 标签分布（按标签聚合学习数据；无标签时整块隐藏）──
                        if (tagStats.isNotEmpty()) {
                            item(key = "overview_card_16") {
                                Text(stringResource(com.ilyskyo.blancall.R.string.tag_distribution_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            item(key = "overview_card_17") {
                                GlassCard {
                                    Column(Modifier.padding(16.dp)) {
                                        val maxArticles = tagStats.maxOf { it.articleCount }.coerceAtLeast(1)
                                        tagStats.forEachIndexed { index, stat ->
                                            if (index > 0) Spacer(Modifier.height(12.dp))
                                            TagStatRow(stat = stat, maxArticles = maxArticles)
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    }

    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect.takeIf { it != Rect.Zero },
        onDismiss = { showModePicker = false },
        onModeSelected = { sel ->
            showModePicker = false
            if (pendingPracticeArticleId > 0) {
                // 从全局统计进入练习，返回时自然回到本页
                when (sel) {
                    is PickerSelection.Base ->
                        navController.navigate("practice/${pendingPracticeArticleId}?mode=${sel.mode.name}")
                    PickerSelection.Custom ->
                        navController.navigate("custom_cloze_list/${pendingPracticeArticleId}?pick=true")
                }
            }
        }
    )

    // 最近训练分析详情
    AnimatedOverlayPresence(target = latestAnalysis.takeIf { showLatestAnalysis }) { item ->
            BlancallAlertDialog(
                onDismissRequest = { showLatestAnalysis = false },
                shape = RoundedCornerShape(20.dp),
                title = { Text(stringResource(com.ilyskyo.blancall.R.string.analysis_dialog_title, item.articleTitle)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.analysis_hints_line,
                                item.modeLabel, item.weakHints, item.strongHints),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MarkdownText(item.analysis)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLatestAnalysis = false }) { Text(stringResource(com.ilyskyo.blancall.R.string.close)) }
                }
            )
    }

    // 自定义区间选择面板：确认后写回 epochDay，并把预设切到「自定义」
    AnimatedOverlayPresence(visible = showRangePicker) {
        DateRangePickerSheet(
            initial = activeRange,
            today = todayKey,
            onDismiss = { showRangePicker = false },
            onConfirm = { picked ->
                customStartDay = picked.start.toEpochDay()
                customEndDay = picked.end.toEpochDay()
                rangePresetName = RangePreset.CUSTOM.name
                showRangePicker = false
            }
        )
    }
}

// ── 卡片入场动画包装 ──
@Composable
private fun AnimatedOverviewCard(content: @Composable () -> Unit) {
    // rememberSaveable 而不是 remember：这张卡住在 LazyColumn 的 item 里，滚出视口后整棵子树会被
    // 回收 —— 用 remember 的话「已播」标记跟着丢，滚回来时又从 false 开始，入场动画会**重播一遍**
    // （用户读成「数据自己刷新了」）。带稳定 key 的 item 由 LazyColumn 的 SaveableStateHolder 保管状态，
    // 所以 rememberSaveable 能活过回收 —— 两者要一起用，缺一不可。
    var played by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = MotionFade.number(MotionFade.scoreReveal, 40),
        label = "overviewCardEnter"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 20.dp.toPx()
            }
    ) { content() }
}

/**
 * 标签分布单行：色点/中性点 + 名称 + 「N 篇 · 练习 X 次 · 正确率 Y%」+ 占比条（条长 ∝ 文章数）。
 * 多标签文章计入各自标签（各标签之和会大于总量）；「未分类」= 无任何标签的文章。
 */
@Composable
private fun TagStatRow(stat: TagOps.TagStat, maxArticles: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stat.tag != null) {
            TagDot(tag = TagChipUi(stat.tag.name, stat.tag.color), size = 10.dp)
        } else {
            Box(
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stat.tag?.name ?: stringResource(com.ilyskyo.blancall.R.string.uncategorized),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(
                com.ilyskyo.blancall.R.string.stats_article_line,
                stat.articleCount, stat.practices, (stat.rate * 100).toInt()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(4.dp))
    val barColor = stat.tag?.let { Color(0xFF000000.toInt() or (it.color and 0xFFFFFF)) }
        ?: MaterialTheme.colorScheme.outlineVariant
    val fraction = (stat.articleCount.toFloat() / maxArticles).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(barColor, RoundedCornerShape(3.dp))
        )
    }
}

/**
 * 「建议下一步」卡：把整页的「陈列」收成一个动词。
 *
 * 为什么需要它：其余卡片都在回答「我练了多少 / 错在哪一类 / 哪篇弱」，
 * 而用户合上页面时真正要的是「所以现在练什么」。
 *
 * 改造前后的差别（用户反馈"根本没反应过来是哪个模式"）：
 * - 旧卡只写「今日待复习 17 句 / 去练习」。**17 句属于哪个模块？点了会进什么？** 界面没说，
 *   用户点进去才发现是句子卡片 —— 这不是文案瑕疵，是让用户先付出一次试错。
 *   现在标题直接写明来源（句子卡片 / 文章练习），并单列一行「记忆方式」。
 * - 旧卡没说它支持哪些挖空方式。用户以为"待复习"能覆盖字词挖空与反向默写，
 *   实际句子卡只有「遮挡整句（首字提示）」这一种。现在把能力边界写进卡面：
 *   句子卡 = 只做整句回忆；文章练习 = 句子挖空 / 字词挖空 / 反向默写。
 * - 旧卡同时列出「17 句」和「最弱一篇」，却只给一个按钮，读者无法判断按钮会执行哪一条。
 *   现在主建议（有按钮）与次建议（一行可点文字）分开 —— 只执行一件，但另一件也看得见。
 *
 * 为什么仍只给一个主动作：选择本身就是负担，三选一必然有两项是当下不该做的。
 *
 * 为什么用薄荷绿：`continueP` 是这套马卡龙里语义为「继续 / 前进」的那一支，
 * 与「下一步」的动作含义同向；其余色相在别处已各自绑定了含义（info=信息、warn=警告）。
 *
 * 放在哪：留在全局统计页顶部（诊断结论属于诊断页），不作为首页固定卡。
 * 首页是用户可自由增删排序的卡片画布（HomeCardCanvas / HomeLayoutStore），
 * 系统硬塞一张不可移除的卡会破坏「我的首页我做主」；若以后要在首页看到，
 * 应做成**可选卡片类型**由用户自己添加，而不是默认插入。
 */
@Composable
private fun NextStepCard(
    dueSentences: Int,
    weakest: WeakArticle?,
    weakestTitle: String,
    dueArticleId: Long?,
    dueArticleTitle: String,
    onWeaknessDrill: (Long) -> Unit,
    onGoReview: () -> Unit,
    onGoArticle: (Long) -> Unit,
) {
    // 「下一步练什么」的优先级由纯函数决定（可单测），这里只负责渲染结果
    val step = NextStepDecider.decide(dueSentences, weakest?.articleId, dueArticleId)
    val hue = Macaron.continueP()
    GlassCard(containerColor = hue.fill) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(hue.accent, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.ov_next_step_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            when (step) {
                is NextStep.DueArticle -> {
                    NextStepPrimary(
                        headline = stringResource(
                            com.ilyskyo.blancall.R.string.ov_next_step_due_article_title, dueArticleTitle
                        ),
                        scope = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_scope_article),
                        limit = null,
                        actionLabel = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_action_start),
                        onAction = { onGoArticle(step.articleId) },
                    )
                }
                is NextStep.WeaknessDrill -> {
                    // A2-9：读屏原先把标题和按钮读成两条互不相干的句子，缺「这是什么」。
                    // 标题区合并成一个语义节点，显式给出「最弱一篇 + 标题 + 正确率」（复用既有键）；
                    // 按钮留在合并之外，仍可单独聚焦、双击执行。
                    val weakLabel = stringResource(com.ilyskyo.blancall.R.string.weak_articles_title)
                    val weakValue = weakest?.let {
                        pluralStringResource(
                            com.ilyskyo.blancall.R.plurals.weak_row_value,
                            it.practiceCount,
                            (it.correctRate * 100).toInt(),
                            it.practiceCount
                        )
                    } ?: ""
                    NextStepPrimary(
                        headline = stringResource(
                            com.ilyskyo.blancall.R.string.ov_next_step_weak_title, weakestTitle
                        ),
                        scope = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_scope_practice),
                        limit = null,
                        actionLabel = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_action),
                        onAction = { onWeaknessDrill(step.articleId) },
                        a11y = "$weakLabel $weakestTitle $weakValue",
                    )
                    // 次建议：还有到期句子时给一条出口 —— 不做按钮，只做一行可点文字，
                    // 视觉权重明显低于主建议，读者不会以为要同时做两件
                    if (dueSentences > 0) {
                        Spacer(Modifier.height(8.dp))
                        NextStepSecondary(
                            text = stringResource(
                                com.ilyskyo.blancall.R.string.ov_next_step_also,
                                pluralStringResource(
                                    com.ilyskyo.blancall.R.plurals.ov_next_step_due_sentences,
                                    dueSentences, dueSentences
                                )
                            ),
                            onClick = onGoReview,
                        )
                    }
                }
                NextStep.Review -> {
                    NextStepPrimary(
                        headline = pluralStringResource(
                            com.ilyskyo.blancall.R.plurals.ov_next_step_due_sentences,
                            dueSentences, dueSentences
                        ),
                        scope = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_scope_sentences),
                        limit = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_limit_sentences),
                        actionLabel = stringResource(com.ilyskyo.blancall.R.string.ov_next_step_go_sentences),
                        onAction = onGoReview,
                    )
                }
                NextStep.AllClear -> {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.ov_next_step_all_clear),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.ov_next_step_empty_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 主建议块：一句「做什么」+ 一行「在哪做 / 用什么方式」+ 一行「做不到什么」+ 一个按钮。
 *
 * 三段的分工是刻意的：
 * - headline 说**对象与数量**（复习 17 句到期句子）；
 * - scope 说**入口归属**（句子卡片 / 文章练习）—— 回答"这是哪个模式"；
 * - limit 说**能力边界**（不含字词挖空 / 反向默写）—— 回答"支不支持那几种挖空"。
 * 把这三件压成一句，任何一句都会被读漏；分成三行，每一行都短到不会被跳过。
 */
@Composable
private fun NextStepPrimary(
    headline: String,
    scope: String,
    limit: String?,
    actionLabel: String,
    onAction: () -> Unit,
    /** 读屏描述：非空时把标题区合并成一个语义节点（按钮留在外面，不吞掉「去练习」） */
    a11y: String? = null,
) {
    Column(
        modifier = if (a11y == null) {
            Modifier
        } else {
            Modifier.semantics(mergeDescendants = true) { contentDescription = a11y }
        }
    ) {
        Text(
            headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(3.dp))
        Text(
            scope,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (limit != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                limit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        GlassButton(onClick = onAction) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 次建议：一行可点文字。刻意不做成按钮 —— 权重一高，就变成"两个都该点"。 */
@Composable
private fun NextStepSecondary(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

// ════════════════════════════════════════════════════════
// 新增卡片组件
// ════════════════════════════════════════════════════════

/** 今日目标卡片：每日练习次数目标 + 完成度进度条 */
@Composable
private fun TodayGoalCard(
    todayCount: Int,
    goal: Int,
    onGoalChange: (Int) -> Unit
) {
    val effectiveGoal = goal.coerceAtLeast(1)
    val fraction = (todayCount.toFloat() / effectiveGoal).coerceIn(0f, 1f)
    val achieved = todayCount >= effectiveGoal
    val goalOptions = listOf(1, 3, 5, 10)
    // 自定义目标次数输入
    var showCustomGoalDialog by remember { mutableStateOf(false) }
    var customGoalInput by remember { mutableStateOf("") }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (achieved)
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        else null
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        if (achieved) com.ilyskyo.blancall.R.string.daily_goal_achieved
                        else com.ilyskyo.blancall.R.string.notif_daily_goal_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(
                        com.ilyskyo.blancall.R.string.daily_goal_progress,
                        todayCount, effectiveGoal
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (achieved) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (achieved) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(com.ilyskyo.blancall.R.string.goal_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                goalOptions.forEach { g ->
                    FilterChip(
                        selected = goal == g,
                        onClick = { onGoalChange(g) },
                        label = { AdaptiveChipLabel(stringResource(com.ilyskyo.blancall.R.string.goal_count_chip, g)) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                // 自定义目标：已自定义时按钮显示实际次数
                FilterChip(
                    selected = goal !in goalOptions,
                    onClick = {
                        customGoalInput = if (goal in goalOptions) "" else goal.toString()
                        showCustomGoalDialog = true
                    },
                    label = {
                        AdaptiveChipLabel(
                            if (goal in goalOptions) stringResource(com.ilyskyo.blancall.R.string.custom)
                            else stringResource(com.ilyskyo.blancall.R.string.goal_count_chip, goal)
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }

    // ── 自定义目标次数输入对话框 ──
    AnimatedOverlayPresence(visible = showCustomGoalDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomGoalDialog = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.custom_goal_title)) },
            text = {
                OutlinedTextField(
                    value = customGoalInput,
                    onValueChange = { input ->
                        customGoalInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text(stringResource(com.ilyskyo.blancall.R.string.custom_goal_label)) },
                    suffix = { Text(stringResource(com.ilyskyo.blancall.R.string.goal_times_unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customGoalInput.toIntOrNull() != null,
                    onClick = {
                        customGoalInput.toIntOrNull()?.let { onGoalChange(it.coerceAtLeast(1)) }
                        showCustomGoalDialog = false
                    }
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGoalDialog = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 自适应单行文字：放不下时自动缩小字号，保证永远排在同一行（不换行）。
 * 用于选项卡/FilterChip 的 label，避免不同设备/字号下文字被挤成两行。
 */
@Composable
private fun AdaptiveChipLabel(text: String) {
    val style = MaterialTheme.typography.labelSmall
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = style.copy(fontSize = fontSize),
        onTextLayout = { result ->
            // 溢出时逐步缩小字号（下限 8sp），直到能单行放下
            if (result.hasVisualOverflow && fontSize.value > 8f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}

/** 遗忘曲线预测卡片：即将到期的文章列表，点击展开衰减曲线 */
@Composable
private fun ForgettingPredictionCard(
    predictions: List<ForgettingPredictor.Prediction>,
    adaptiveIds: Set<Long> = emptySet(),
    dueTotal: Int = predictions.size,
    anchorArticleId: Long = 0L,
    onPracticeButtonPlaced: (Long, Rect) -> Unit = { _, _ -> },
    onPractice: (Long) -> Unit
) {
    // 当前展开的 articleId；同一时间只展开一条
    var expandedId by remember { mutableStateOf<Long?>(null) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        // 清新马卡龙：蜜桃淡彩卡面
        containerColor = Macaron.warn().fill
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(com.ilyskyo.blancall.R.string.due_soon_title),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            // 这句话原本无论如何都写「基于 FSRS 遗忘曲线模型预测」，而无 FSRS 状态的文章其实走的是
            // 模板推算（ForgettingPredictor 的 else 分支）—— 冷启动首帧更是每篇都还没状态，
            // 于是页面把编出来的曲线说成模型算的。现在按实际来源改口，并给估算条目单独打标。
            Text(
                if (predictions.isNotEmpty() && predictions.all { it.articleId in adaptiveIds })
                    stringResource(com.ilyskyo.blancall.R.string.fsrs_basis)
                else
                    stringResource(com.ilyskyo.blancall.R.string.fsrs_basis_mixed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 只列 5 条却不报总量，用户会以为「就这么几篇要复习」——那正是这张卡要驱动行动的地方
            if (dueTotal > predictions.size) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(com.ilyskyo.blancall.R.string.due_more_hidden, dueTotal - predictions.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            predictions.forEach { p ->
                val (badge, badgeColor) = urgencyBadge(p)
                val expanded = expandedId == p.articleId
                val retentionPercent = (p.retentionRate * 100).toInt()
                // 语义描述在组合期就拼好：semantics{} 不是 @Composable，里面不能取资源
                val itemDescription = stringResource(
                    com.ilyskyo.blancall.R.string.prediction_item_desc,
                    p.title,
                    retentionPercent,
                    stringResource(
                        if (expanded) com.ilyskyo.blancall.R.string.decay_expanded
                        else com.ilyskyo.blancall.R.string.decay_collapsed
                    )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressClick {
                            // 切换展开/收起；不直接跳练习，避免误触
                            expandedId = if (expanded) null else p.articleId
                        }
                        .semantics {
                            contentDescription = itemDescription
                        }
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (p.articleId !in adaptiveIds) {
                                // 条目级标注：同一张卡里既有 FSRS 结果也有模板推算时，
                                // 只有逐条打标才不会让用户把估算值当成模型结论
                                Text(stringResource(com.ilyskyo.blancall.R.string.prediction_estimated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary)
                            }
                            Text(
                                stringResource(
                                    com.ilyskyo.blancall.R.string.prediction_detail_line,
                                    p.practiceCount,
                                    (p.lastAccuracy * 100).toInt(),
                                    String.format(Locale.ROOT, "%.1f", p.memoryStrength)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(MotionFade.alpha(MotionFade.enter)) +
                            expandVertically(Motion.contentSize()),
                        exit = fadeOut(MotionFade.alpha(MotionFade.exit)) +
                            shrinkVertically(Motion.contentSize()),
                    ) {
                        Column(Modifier.padding(top = 8.dp)) {
                            MemoryDecayChart(
                                decayCurve = p.decayCurve,
                                retentionRate = p.retentionRate,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onPractice(p.articleId) },
                                    modifier = if (p.articleId == anchorArticleId) {
                                        Modifier.onGloballyPositioned { coords ->
                                            val pos = coords.positionInWindow()
                                            onPracticeButtonPlaced(
                                                p.articleId,
                                                Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                                            )
                                        }
                                    } else Modifier,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("${stringResource(com.ilyskyo.blancall.R.string.go_practice)} ›",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 紧急度徽章文本与颜色（@Composable 以读取 MaterialTheme.colorScheme 与语言资源） */
@Composable
private fun urgencyBadge(p: ForgettingPredictor.Prediction): Pair<String, androidx.compose.ui.graphics.Color> {
    val c = MaterialTheme.colorScheme
    return when (p.urgency) {
        ForgettingPredictor.Urgency.OVERDUE -> {
            val days = -p.daysLeft
            pluralStringResource(com.ilyskyo.blancall.R.plurals.overdue_days, days, days) to c.error
        }
        ForgettingPredictor.Urgency.TODAY ->
            stringResource(com.ilyskyo.blancall.R.string.flow_group_today) to c.error
        ForgettingPredictor.Urgency.SOON ->
            pluralStringResource(com.ilyskyo.blancall.R.plurals.flow_days_later, p.daysLeft, p.daysLeft) to c.tertiary
        ForgettingPredictor.Urgency.LATER ->
            pluralStringResource(com.ilyskyo.blancall.R.plurals.flow_days_later, p.daysLeft, p.daysLeft) to c.onSurfaceVariant
        ForgettingPredictor.Urgency.NEW ->
            stringResource(com.ilyskyo.blancall.R.string.urgency_not_started) to c.onSurfaceVariant
        ForgettingPredictor.Urgency.MASTERED ->
            stringResource(com.ilyskyo.blancall.R.string.mastered) to c.primary
    }
}

/** 弱点画像卡片：雷达图 + 针对性训练建议 */
@Composable
private fun WeaknessProfileCard(weakness: WeaknessProfile) {
    // 轴名走资源：这五个字原先在代码里写死中文，六语用户的统计页上整块是简体，
    // 而多语言守卫只比 res 目录、查不出它。
    val axes = listOf(
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_typo),
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_glyph),
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_missing),
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_extra),
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_order),
        stringResource(com.ilyskyo.blancall.R.string.radar_axis_incorrect),
    )
    val values = listOf(
        weakness.typo, weakness.glyph, weakness.missing,
        weakness.extra, weakness.order, weakness.incorrect
    )
    val advice = trainingAdvice(weakness)

    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(com.ilyskyo.blancall.R.string.weakness_profile_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            RadarChart(
                axes = axes,
                values = values,
                label = stringResource(
                    com.ilyskyo.blancall.R.string.weakness_total_mistakes,
                    weakness.totalMistakes
                )
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    advice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 根据弱点占比生成针对性训练建议 */
@Composable
private fun trainingAdvice(w: WeaknessProfile): String {
    if (w.totalMistakes == 0) {
        return stringResource(com.ilyskyo.blancall.R.string.weakness_advice_empty)
    }
    val max = maxOf(w.typo, w.glyph, w.missing, w.extra, w.order, w.incorrect)
    return when {
        w.incorrect == max -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_incorrect, (w.incorrect * 100).toInt()
        )
        w.typo == max -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_typo, (w.typo * 100).toInt()
        )
        w.glyph == max -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_glyph, (w.glyph * 100).toInt()
        )
        w.missing == max -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_missing, (w.missing * 100).toInt()
        )
        w.extra == max -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_extra, (w.extra * 100).toInt()
        )
        else -> stringResource(
            com.ilyskyo.blancall.R.string.weakness_advice_order, (w.order * 100).toInt()
        )
    }
}

/** 成就徽章卡片：按族分组，族内保持「初心→勤学→百炼」的阶梯顺序 */
@Composable
private fun AchievementGridCard(
    achievements: List<AchievementManager.Achievement>,
    achievedAt: Map<String, Long>,
    justUnlocked: List<String>,
    onDismissUnlocked: () -> Unit
) {
    val unlockedCount = achievements.count { it.unlocked }
    val colors = MaterialTheme.colorScheme
    // 点开某一枚才展开它的说明：一行文案塞不进 72dp 宽的格子里，硬塞会让整排徽章变高
    var detailId by remember { mutableStateOf<String?>(null) }

    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 图标走矢量（emoji 在不同 ROM 上会长成完全不同的样子，也会打断读屏）
                    AppIcon(
                        kind = AppIconKind.Celebrate,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(com.ilyskyo.blancall.R.string.achievements_card_title),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(
                        com.ilyskyo.blancall.R.string.achievements_unlocked_count,
                        unlockedCount, achievements.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            // 刚刚解锁的那几枚：卡内给一行回执（低频事件，不弹层、不常驻）
            if (justUnlocked.isNotEmpty()) {
                // joinToString 不是 inline，lambda 里不能调 stringResource —— 边遍历边拼这一行
                val freshNames = StringBuilder()
                achievements.forEach { ach ->
                    if (ach.id in justUnlocked) {
                        if (freshNames.isNotEmpty()) freshNames.append("、")
                        freshNames.append(stringResource(achievementTitleRes(ach.id)))
                    }
                }
                if (freshNames.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            com.ilyskyo.blancall.R.string.achievement_unlocked_notice,
                            freshNames.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.primaryContainer.copy(alpha = 0.30f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismissUnlocked
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 族内阶梯、族间分组；整族都还没解锁时也保留标题，让人看见「还有这一条路」
                AchievementManager.Family.entries.forEach { family ->
                    val members = achievements.filter { it.family == family }
                    if (members.isEmpty()) return@forEach
                    Text(stringResource(achievementFamilyRes(family)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    AchievementBadgeFlow(
                        achievements = members,
                        achievedAt = achievedAt,
                        detailId = detailId,
                        onToggleDetail = { id -> detailId = if (detailId == id) null else id }
                    )
                }
            }
            achievements.firstOrNull { it.id == detailId }?.let { selected ->
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(achievementDescRes(selected.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 徽章项的获得日期：只在表里记过时刻才显示，没有就留空（不显示「未知」这种占位话） */
private fun earnedOnText(epochMilli: Long): String =
    java.time.Instant.ofEpochMilli(epochMilli)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(
            java.time.format.FormatStyle.MEDIUM))

/** 徽章网格（FlowRow 自动换行）：族内保持定义顺序，不再按解锁态打散阶梯 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementBadgeFlow(
    achievements: List<AchievementManager.Achievement>,
    achievedAt: Map<String, Long>,
    detailId: String?,
    onToggleDetail: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        achievements.forEach { ach ->
            AchievementBadgeItem(
                achievement = ach,
                earnedAt = achievedAt[ach.id],
                selected = detailId == ach.id,
                onToggleDetail = { onToggleDetail(ach.id) }
            )
        }
    }
}

/** 徽章 id → 资源键：数据层只给 id，文案一律在这里按界面语言取 */
@Composable
private fun achievementTitleRes(id: String): Int = when (id) {
    "first_practice" -> com.ilyskyo.blancall.R.string.ach_title_first_practice
    "practice_10" -> com.ilyskyo.blancall.R.string.ach_title_practice_10
    "practice_100" -> com.ilyskyo.blancall.R.string.ach_title_practice_100
    "streak_7" -> com.ilyskyo.blancall.R.string.ach_title_streak_7
    "streak_30" -> com.ilyskyo.blancall.R.string.ach_title_streak_30
    "streak_100" -> com.ilyskyo.blancall.R.string.ach_title_streak_100
    "perfect_one" -> com.ilyskyo.blancall.R.string.ach_title_perfect_one
    "perfect_ten" -> com.ilyskyo.blancall.R.string.ach_title_perfect_ten
    "all_modes" -> com.ilyskyo.blancall.R.string.ach_title_all_modes
    "focus_60" -> com.ilyskyo.blancall.R.string.ach_title_focus_60
    "deep_10" -> com.ilyskyo.blancall.R.string.ach_title_deep_10
    "memory_90" -> com.ilyskyo.blancall.R.string.ach_title_memory_90
    "memory_95" -> com.ilyskyo.blancall.R.string.ach_title_memory_95
    "due_cleared" -> com.ilyskyo.blancall.R.string.ach_title_due_cleared
    else -> com.ilyskyo.blancall.R.string.ach_title_first_practice
}

/**
 * 单个徽章项：已解锁 = 主色描边 + 获得日期；未解锁 = 淡显 + 迷你进度条。
 * 点一下展开这一枚的说明（同时给读屏一条完整的 contentDescription）。
 */
@Composable
private fun AchievementBadgeItem(
    achievement: AchievementManager.Achievement,
    earnedAt: Long? = null,
    selected: Boolean = false,
    onToggleDetail: () -> Unit = {}
) {
    val a = achievement
    val c = MaterialTheme.colorScheme
    val title = stringResource(achievementTitleRes(a.id))
    val detail = stringResource(achievementDescRes(a.id))
    val progressLine = stringResource(achievementProgressRes(a.unit), a.current, a.target)
    val stateLine = if (a.unlocked) {
        earnedAt?.let { earnedOnText(it) }?.let {
            stringResource(com.ilyskyo.blancall.R.string.achievement_earned_on, it)
        } ?: title
    } else progressLine

    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleDetail
            )
            .semantics { contentDescription = "$title。$detail。$stateLine" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    // 已解锁：淡主色底即可（外圈已有加粗主色描边区分），无需深色实底
                    if (a.unlocked) c.primaryContainer.copy(alpha = 0.35f)
                    else c.surfaceVariant.copy(alpha = 0.2f)
                )
                .then(
                    if (selected || a.unlocked) Modifier.border(
                        1.5.dp, c.primary.copy(alpha = if (a.unlocked) 0.9f else 0.5f), CircleShape)
                    else Modifier.border(1.dp, c.outlineVariant.copy(alpha = 0.4f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(
                kind = achievementFamilyIcon(a.family),
                modifier = Modifier.size(22.dp),
                tint = if (a.unlocked) c.primary else c.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = if (a.unlocked) FontWeight.Medium else FontWeight.Normal,
            color = if (a.unlocked) c.onSurface
            else c.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!a.unlocked) {
            Spacer(Modifier.height(3.dp))
            // 迷你进度条：一眼看出距离解锁还差多少
            LinearProgressIndicator(
                progress = { a.progress },
                modifier = Modifier
                    .width(52.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = c.primary.copy(alpha = 0.6f),
                trackColor = c.surfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                progressLine,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = c.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        } else if (earnedAt != null && earnedAt > 0L) {
            // 解锁之后不再显示进度条，改成显示「什么时候拿到的」——
            // 徽章从「我还差多少」变成「我做到过什么」，这才是奖励该有的样子
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(com.ilyskyo.blancall.R.string.achievement_earned_on, earnedOnText(earnedAt)),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = c.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

/** 族 → 矢量图标：emoji 在定制 ROM 上会长成另一个样子，也读不出「是否解锁」 */
private fun achievementFamilyIcon(family: AchievementManager.Family): AppIconKind = when (family) {
    AchievementManager.Family.PRACTICE -> AppIconKind.Library
    AchievementManager.Family.STREAK -> AppIconKind.TrackChanges
    AchievementManager.Family.ACCURACY -> AppIconKind.Check
    AchievementManager.Family.BREADTH -> AppIconKind.Insights
    AchievementManager.Family.MEMORY -> AppIconKind.Refresh
}

@Composable
private fun achievementFamilyRes(family: AchievementManager.Family): Int = when (family) {
    AchievementManager.Family.PRACTICE -> com.ilyskyo.blancall.R.string.ach_family_practice
    AchievementManager.Family.STREAK -> com.ilyskyo.blancall.R.string.ach_family_streak
    AchievementManager.Family.ACCURACY -> com.ilyskyo.blancall.R.string.ach_family_accuracy
    AchievementManager.Family.BREADTH -> com.ilyskyo.blancall.R.string.ach_family_breadth
    AchievementManager.Family.MEMORY -> com.ilyskyo.blancall.R.string.ach_family_memory
}

@Composable
private fun achievementProgressRes(unit: AchievementManager.Unit): Int = when (unit) {
    AchievementManager.Unit.COUNT -> com.ilyskyo.blancall.R.string.ach_progress_count
    AchievementManager.Unit.DAY -> com.ilyskyo.blancall.R.string.ach_progress_day
    AchievementManager.Unit.KIND -> com.ilyskyo.blancall.R.string.ach_progress_kind
    AchievementManager.Unit.MINUTE -> com.ilyskyo.blancall.R.string.ach_progress_minute
    AchievementManager.Unit.PERCENT -> com.ilyskyo.blancall.R.string.ach_progress_percent
}

@Composable
private fun achievementDescRes(id: String): Int = when (id) {
    "first_practice" -> com.ilyskyo.blancall.R.string.ach_desc_first_practice
    "practice_10" -> com.ilyskyo.blancall.R.string.ach_desc_practice_10
    "practice_100" -> com.ilyskyo.blancall.R.string.ach_desc_practice_100
    "streak_7" -> com.ilyskyo.blancall.R.string.ach_desc_streak_7
    "streak_30" -> com.ilyskyo.blancall.R.string.ach_desc_streak_30
    "streak_100" -> com.ilyskyo.blancall.R.string.ach_desc_streak_100
    "perfect_one" -> com.ilyskyo.blancall.R.string.ach_desc_perfect_one
    "perfect_ten" -> com.ilyskyo.blancall.R.string.ach_desc_perfect_ten
    "all_modes" -> com.ilyskyo.blancall.R.string.ach_desc_all_modes
    "focus_60" -> com.ilyskyo.blancall.R.string.ach_desc_focus_60
    "deep_10" -> com.ilyskyo.blancall.R.string.ach_desc_deep_10
    "memory_90" -> com.ilyskyo.blancall.R.string.ach_desc_memory_90
    "memory_95" -> com.ilyskyo.blancall.R.string.ach_desc_memory_95
    "due_cleared" -> com.ilyskyo.blancall.R.string.ach_desc_due_cleared
    else -> com.ilyskyo.blancall.R.string.ach_desc_first_practice
}

/**
 * 下拉进入学习任务流的阈值：滚到顶后再拉这么远即「满格」，抬手即可进入（1:1 跟手，不含阻尼）。
 */
private val OVERVIEW_FLOW_PULL_THRESHOLD = 88.dp

/**
 * 满格后「回退多少才算撤回」的容差。
 *
 * 抬手时手指常会回带几像素（系统也可能补一帧反向位移），若拿抬手瞬间的位移直接判阈值，
 * 就会出现「圆圈已画满、松手却差 1–2dp」的随机失败。闩锁只在这个距离内保持满格：
 * 回退超过它，圆环已明显倒退，才视为用户主动反悔。
 */
private val OVERVIEW_FLOW_PULL_CANCEL_SLACK = 12.dp

/**
 * 满格后的阻尼余量：再多拉也只移动这么点，手感是「到顶了、拉不动」。
 * 留得很小，是为了让手往回退一点点就能看见圈环倒转（撤回必须立刻有反馈）。
 */
private val OVERVIEW_FLOW_PULL_OVERSHOOT = 8.dp

/** 满格后继续下拉的阻尼系数（1 = 跟手，0 = 完全拉不动）。 */
private const val OVERVIEW_FLOW_PULL_OVERSHOOT_RATIO = 0.08f
