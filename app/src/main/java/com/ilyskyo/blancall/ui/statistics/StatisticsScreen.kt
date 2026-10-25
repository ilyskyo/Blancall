// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import androidx.compose.animation.core.Animatable
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import androidx.compose.animation.core.animateFloatAsState
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.MemoryHeatmap
import com.ilyskyo.blancall.algorithm.ReviewTimeline
import com.ilyskyo.blancall.algorithm.WeakSentenceRank
import com.ilyskyo.blancall.algorithm.WeaknessMath
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.BlankInk
import com.ilyskyo.blancall.data.repository.InkBatch
import com.ilyskyo.blancall.data.repository.InkStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.ReviewLogStore
import com.ilyskyo.blancall.data.repository.StatsRepository
import com.ilyskyo.blancall.data.repository.ArticleStats
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.animateListItem
import androidx.compose.foundation.layout.Box
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.handwriting.InkThumbnail
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatisticsScreen(navController: NavController, articleId: Long) {
    val context = LocalContext.current
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val allRecords by recordRepo.records.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val streakDays by ReminderPrefs.studyStreakDaysFlow.collectAsState()
    val longestStreak by ReminderPrefs.longestStreakDaysFlow.collectAsState()

    // ── 过滤+排序：仅在 allRecords 或 articleId 变化时重算 ──
    val records = remember(allRecords, articleId) {
        allRecords.filter { it.articleId == articleId }.sortedByDescending { it.timestamp }
    }

    // ── 基础统计：单遍聚合（B1-1，口径与单测都在 StatsRepository）──
    val stats = remember(records) { StatsRepository.aggregateArticle(records) }

    // 该文章累计阅读时长（秒，沉浸阅读模式记录）
    val readingSeconds = remember(articleId) { AppPrefs.getReadingSeconds(articleId) }

    // ── 记忆热力图（F5）──
    val articleRepo = remember { ArticleRepository(context.filesDir.resolve("articles.json").absolutePath) }
    // 句级 FSRS 状态（B3-2 起是热力图主指标「当前留存率」的数据来源）
    val fsrsStore = remember { FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath) }
    var heatmapData by remember { mutableStateOf<MemoryHeatmap.HeatmapData?>(null) }
    // 句级薄弱榜（B3-1）：与热力图同源的一次计算，点一行直达该句所在段
    var weakSentences by remember { mutableStateOf<List<WeakSentenceRank.WeakSentence>>(emptyList()) }
    // key 用 records 本身，不用「条数 + 时间戳」拼出来的指纹：
    // ① 指纹会漏掉「条数与时间戳都没变、内容变了」的改动（例如记录被替换）；
    // ② 更实际的是**记录被清空**这一支 —— 旧写法在 records 为空时什么都不做，
    //    于是删光记录后页面上还挂着上一份热力图，而那份数据已经不存在了。
    // 这里直接以 records 为 key（同一实例时 equals 走恒等短路，开销可忽略），并把空集显式归零。
    LaunchedEffect(articleId, records) {
        // 读文件走 IO、全文切分+聚合走 Default，避免阻塞主线程
        val article = withContext(Dispatchers.IO) { articleRepo.getArticleById(articleId) }
        // 句级 FSRS 状态快照（留存分档用）：等 store 加载完成再取（取到的是深拷贝）
        val sentenceStates = withContext(Dispatchers.IO) {
            fsrsStore.awaitLoaded()
            fsrsStore.allSentenceStates()
        }
        val heat = if (article != null && records.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                MemoryHeatmap.generate(article.content, records, sentenceStates, articleId)
            }
        } else {
            null
        }
        heatmapData = heat
        // 句级薄弱榜复用同一份热力图结果：两者句级口径必须同源，也不该算两遍
        weakSentences = if (article != null && heat != null) {
            WeakSentenceRank.rank(heat, article.content)
        } else {
            emptyList()
        }
    }

    // ── 「刚练完回来」的变化标注（B3-1 的反馈闭环）──
    // 练完一句回到本页时，那一行的数字其实已经变了（records 是仓库 StateFlow，回来即刷新），
    // 但静止的数字看不出「刚刚发生了什么」。这里与上次打开本页时的榜单快照比对，
    // 把「数值变了的行」和「新上榜的行」挑出来闪一下，让变化自己说话。
    //
    // 快照必须用 rememberSaveable：去练习页时本页会离开组合，remember 会被丢掉，
    // 只有存进导航目的地的保存状态才能在返回时拿出来对比（同一惯例见 ImportScreen 的注释）。
    // 首次打开本页没有基线 → 一行都不闪，否则每次进页都像刚练完。
    var weakSnapshot by rememberSaveable(articleId) { mutableStateOf("") }
    var changedWeakSentences by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(weakSentences) {
        // 榜单还没算出来（本页刚进组合时先是空表）→ 不动：不能把「还没数据」当成变化
        if (weakSentences.isEmpty()) return@LaunchedEffect
        val current = weakSentences.associate { it.sentenceIndex to "${it.practiceCount}|${it.errorRate}" }
        val previous = parseWeakSnapshot(weakSnapshot)
        changedWeakSentences = if (previous.isEmpty()) {
            emptySet()
        } else {
            current.filter { (idx, v) -> previous[idx] != v }.keys
        }
        weakSnapshot = current.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    // ── 真实复习轨迹（消费 fsrs_reviews.jsonl）──
    // 日志只写不读是旧审计里唯一的「纯收益」缺口：这里按文章级 key（a:<id>）取事件，
    // 给出实际间隔与「第几次才稳住」。日志不进备份，卡片上必须标明「仅本机」。
    var reviewSeries by remember(articleId) { mutableStateOf<ReviewTimeline.Series?>(null) }
    LaunchedEffect(articleId, records) {
        val series = withContext(Dispatchers.IO) {
            val file = context.filesDir.resolve(ReviewLogStore.FILE_NAME)
            if (!file.isFile) return@withContext ReviewTimeline.build(emptyList())
            val events = ReviewLogStore.read(file)
                .filter { it.key == "a:$articleId" }
                .map {
                    ReviewTimeline.Event(
                        ts = it.ts,
                        rating = it.rating,
                        elapsedMs = it.elapsedMs,
                        stability = it.state.stability,
                    )
                }
            ReviewTimeline.build(events)
        }
        reviewSeries = series
    }

    // ── 历史筛选状态 ──
    var filterMode by remember { mutableStateOf<String?>(null) } // null=全部
    val filteredRecords = remember(records, filterMode) {
        if (filterMode == null) records else records.filter { it.mode == filterMode }
    }

    // ── 练习历史：按月分组 + 首屏预览（B3-3，与热力图卡的「预览 + 展开」规范同款）──
    var historyExpanded by remember { mutableStateOf(false) }
    val historyRows = remember(filteredRecords, historyExpanded) {
        HistoryGrouping.build(filteredRecords, historyExpanded)
    }
    // 月份标签跟随系统 locale（与卡片里 dateFormat 的口径一致）：用 ICU skeleton 求最佳 pattern，
    // 而不是写死 "yyyy年M月" —— 六语用户各得其所（en: October 2026 / ko: 2026년 10월）
    val monthFormat = remember {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMM")
        SimpleDateFormat(pattern, Locale.getDefault())
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        // 毛玻璃氛围背景（背景色之上、内容之下）
        AmbientBackground()
        // 固定顶部栏（不随记录滚动）：返回 + 标题
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.statistics_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    onClick = { navController.navigate("mistakes?articleId=$articleId") },
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        stringResource(R.string.mistake_notebook_entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // 记录列表：统计卡片与练习记录可滚动
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                // 底部留白：悬浮导航栏覆盖屏幕底部约 100dp，避免最后内容被遮挡
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
        // ═══════ 统计总览卡片（仪表盘 + 连续天数 + 趋势） ═══════
        item {
            AnimatedStatCard {
                GlassCard(
                    // 清新马卡龙：薰衣草淡彩卡面
                    containerColor = Macaron.lavender().fill
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.stats_overview),
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
                                    Text(stringResource(com.ilyskyo.blancall.R.string.streak_days, streakDays),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(pluralStringResource(com.ilyskyo.blancall.R.plurals.longest_streak, longestStreak, longestStreak),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                if (stats.totalDuration > 0) {
                                    val minutes = stats.totalDuration / 60_000L
                                    Text(stringResource(com.ilyskyo.blancall.R.string.total_practice_time,
                                        minutes, (stats.totalDuration / 1000L) % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (readingSeconds > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(com.ilyskyo.blancall.R.string.total_reading_time,
                                        readingSeconds / 60, readingSeconds % 60),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        StatItem(stringResource(com.ilyskyo.blancall.R.string.practice_count), "${stats.totalPractices}")
                                        Spacer(Modifier.height(6.dp))
                                        StatItem(stringResource(com.ilyskyo.blancall.R.string.best_ever), "${(stats.bestRate * 100).toInt()}%")
                                    }
                                    Column {
                                        StatItem(stringResource(com.ilyskyo.blancall.R.string.latest_run), "${(stats.latestRate * 100).toInt()}%")
                                        Spacer(Modifier.height(6.dp))
                                        StatItem(stringResource(com.ilyskyo.blancall.R.string.total_correct), "${stats.totalCorrect}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════ 每日练习趋势 ═══════
        if (stats.totalPractices > 0) {
            item {
                AnimatedStatCard {
                    GlassCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.daily_trend),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(12.dp))
                            DailyTrendChart(dailyCounts = stats.dailyCounts)
                        }
                    }
                }
            }
        }

        // ═══════ 模式对比（含反向默写） ═══════
        if (stats.sentenceCount > 0 || stats.wordCount > 0 || stats.reverseCount > 0) {
            item {
                AnimatedStatCard {
                    GlassCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.mode_compare),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                if (stats.sentenceCount > 0)
                                    StatItem(
                                        stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
                                        pluralStringResource(com.ilyskyo.blancall.R.plurals.mode_stat_value,
                                            stats.sentenceCount, stats.sentenceCount,
                                            (stats.sentenceRate * 100).toInt())
                                    )
                                if (stats.wordCount > 0)
                                    StatItem(
                                        stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
                                        pluralStringResource(com.ilyskyo.blancall.R.plurals.mode_stat_value,
                                            stats.wordCount, stats.wordCount,
                                            (stats.wordRate * 100).toInt())
                                    )
                                if (stats.reverseCount > 0)
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
        }

        // ═══════ 错误类型分布 ═══════
        if (stats.totalMistakes > 0) {
            item {
                AnimatedStatCard {
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

        // ═══════ 记忆热力图 ═══════
        heatmapData?.let { heatmap ->
            if (heatmap.sentences.isNotEmpty()) {
                item {
                    AnimatedStatCard {
                        StatisticsHeatmapCard(heatmap)
                    }
                }
            }
        }

        // ═══════ 最常写错的句子（B3-1）═══════
        if (weakSentences.isNotEmpty()) {
            item {
                AnimatedStatCard {
                    WeakSentencesCard(
                        rows = weakSentences,
                        changedIndexes = changedWeakSentences,
                        onDrill = { row ->
                            navController.navigate(
                                "practice/$articleId?mode=SENTENCE&focusSentence=${row.sentenceIndex}"
                            )
                        }
                    )
                }
            }
        }

        // ═══════ 真实复习轨迹（复习日志的读取方）═══════
        reviewSeries?.let { series ->
            item {
                AnimatedStatCard {
                    ReviewTimelineCard(series = series)
                }
            }
        }

        // ═══════ 练习历史标题 + 筛选 chips ═══════
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(com.ilyskyo.blancall.R.string.practice_history),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Row {
                    FilterChip(
                        selected = filterMode == null,
                        onClick = { filterMode = null },
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.filter_all), style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(Modifier.width(6.dp))
                    if (stats.sentenceCount > 0) {
                        FilterChip(
                            selected = filterMode == "SENTENCE",
                            onClick = { filterMode = if (filterMode == "SENTENCE") null else "SENTENCE" },
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.filter_sentence), style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (stats.wordCount > 0) {
                        FilterChip(
                            selected = filterMode == "WORD",
                            onClick = { filterMode = if (filterMode == "WORD") null else "WORD" },
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.filter_word), style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (stats.reverseCount > 0) {
                        FilterChip(
                            selected = filterMode == "REVERSE",
                            onClick = { filterMode = if (filterMode == "REVERSE") null else "REVERSE" },
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.filter_reverse), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        // ═══════ 记录列表 ═══════
        if (records.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.no_practice_history),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (filteredRecords.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.no_records_in_mode),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(
                historyRows,
                key = { row ->
                    when (row) {
                        is HistoryGrouping.Row.MonthHeader -> "month_${row.monthStartMs}"
                        is HistoryGrouping.Row.RecordRow -> row.record.id
                    }
                }
            ) { row ->
                when (row) {
                    // 月份组头：本地化年月 + 一条细线（B3-3）
                    is HistoryGrouping.Row.MonthHeader -> HistoryMonthHeader(row.monthStartMs, monthFormat)
                    is HistoryGrouping.Row.RecordRow -> {
                        // 筛选条件一变，记录集就整体换血：item 的入场/换位/退场统一由 animateListItem 管
                        val record = row.record
                        Box(modifier = animateListItem()) {
                            RecordCard(record, dateFormat) { sentenceIndex ->
                                // 单条错题「复习」：只复习那一句及其前后几句（focusSentence）；
                                // 拿不到具体错句（旧记录 sentenceIndex = -1）时才退回薄弱集训
                                if (sentenceIndex >= 0) {
                                    navController.navigate(
                                        "practice/$articleId?mode=${record.mode}&focusSentence=$sentenceIndex"
                                    )
                                } else {
                                    // 按该记录的实际练习模式进入薄弱集训：
                                    // 反向默写 → REVERSE、句子挖空 → SENTENCE、字词挖空 → WORD
                                    navController.navigate("practice/$articleId?mode=${record.mode}&sectionMode=WEAKNESS")
                                }
                            }
                        }
                    }
                }
            }
            // 长历史：预览之外的部分用「提示 + 查看全部」收口（与热力图卡同规范）
            if (filteredRecords.size > HistoryGrouping.PREVIEW_COUNT) {
                item(key = "history_toggle") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!historyExpanded) {
                            Text(
                                pluralStringResource(
                                    com.ilyskyo.blancall.R.plurals.history_truncated,
                                    filteredRecords.size, filteredRecords.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        TextButton(
                            onClick = { historyExpanded = !historyExpanded },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (historyExpanded) com.ilyskyo.blancall.R.string.collapse_plain
                                    else com.ilyskyo.blancall.R.string.view_all_plain
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
}

// ── 卡片入场动画包装 ──
@Composable
private fun AnimatedStatCard(content: @Composable () -> Unit) {
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = MotionFade.number(MotionFade.scoreReveal, 40),
        label = "statCardEnter"
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

// ── 练习历史的月份组头（B3-3）──

/** 月份组头：本地化年月（跟随系统 locale）+ 一条细线，把长历史切成可扫视的段 */
@Composable
private fun HistoryMonthHeader(monthStartMs: Long, monthFormat: java.text.DateFormat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            monthFormat.format(java.util.Date(monthStartMs)),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * 真实复习轨迹：显示的是**事实**（这次隔了几天、复习后稳定度怎么变），
 * 与热力图上的预测曲线是两回事。卡片上常驻「仅本机」：日志不进备份，换机归零。
 */
@Composable
private fun ReviewTimelineCard(series: ReviewTimeline.Series) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.review_timeline_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        stringResource(R.string.review_timeline_local_only),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (series.summary) {
                    ReviewTimeline.Summary.NO_DATA -> stringResource(R.string.review_timeline_none)
                    ReviewTimeline.Summary.ONLY_ONE -> stringResource(R.string.review_timeline_one)
                    ReviewTimeline.Summary.STEADY_FROM_START ->
                        stringResource(R.string.review_timeline_steady_start)
                    ReviewTimeline.Summary.STEADY_AFTER ->
                        stringResource(
                            R.string.review_timeline_steady_after,
                            series.steadyFromReview ?: 1
                        )
                    ReviewTimeline.Summary.STILL_SHAKY -> stringResource(R.string.review_timeline_shaky)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (series.points.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.review_timeline_interval_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                series.points.takeLast(6).forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.review_timeline_review_no, p.review),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            if (p.review == 1) stringResource(R.string.review_timeline_first)
                            else stringResource(R.string.review_timeline_gap, formatDays(p.intervalDays)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            ratingLabel(p.rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (p.rating <= ReviewTimeline.RATING_HARD) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatDays(days: Double): String {
    if (days < 1.0) return "<1"
    val rounded = kotlin.math.round(days * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun ratingLabel(rating: Int): String = stringResource(
    when (rating) {
        1 -> R.string.rating_again
        2 -> R.string.rating_hard
        3 -> R.string.rating_good
        else -> R.string.rating_easy
    }
)

// ── 单条练习记录 ──
@Composable
private fun RecordCard(
    record: PracticeRecord,
    dateFormat: SimpleDateFormat,
    onReview: (Int) -> Unit
) {
    val context = LocalContext.current
    // 错题墨迹（错题回顾）：惰性加载 —— 仅可见行读一次文件；无存档=空表，静默隐藏
    val inkStore = remember { InkStore.getInstance(context.filesDir.resolve("ink").absolutePath) }
    var inkByBlank by remember(record.id) {
        mutableStateOf<Map<Int, List<InkBatch>>>(emptyMap())
    }
    var enlarged by remember(record.id) { mutableStateOf<BlankInk?>(null) }
    LaunchedEffect(record.id) {
        inkByBlank = withContext(Dispatchers.IO) {
            if (!inkStore.has(record.id)) return@withContext emptyMap<Int, List<InkBatch>>()
            // 新存档每个空只有一份最终识别快照；旧存档按每次上屏分批次，
            // 全部画出来会叠成一团 —— 统一只取最后一份，回看才看得清写了什么
            inkStore.load(record.id)?.blanks
                ?.associate { it.blankIndex to it.batches.takeLast(1) }
                ?: emptyMap()
        }
    }
    val modeLabel = when (record.mode) {
        "SENTENCE" -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence)
        "WORD" -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word)
        "REVERSE" -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse)
        else -> stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word)
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Macaron.info().accent.copy(alpha = 0.15f)
                ) {
                    Text(
                        modeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Macaron.info().accent,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(dateFormat.format(Date(record.timestamp)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // 正确率与对空数：仅当有实际成绩时显示（0 分记录不显示，避免误导）
            val rate = if (record.totalBlanks > 0) record.correctCount * 100 / record.totalBlanks else 0
            if (rate > 0 && record.totalBlanks > 0) {
                Text(stringResource(com.ilyskyo.blancall.R.string.accuracy_with_detail, rate,
                        record.correctCount, record.totalBlanks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
            }
            // 用时（分钟 + 秒数）
            if (record.duration > 0) {
                val mins = record.duration / 60_000L
                val secs = (record.duration % 60_000L) / 1000L
                Text(stringResource(com.ilyskyo.blancall.R.string.time_used, mins, secs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.mistakes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                record.mistakes.forEach { m ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressClick { onReview(m.sentenceIndex) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                mistakeDescription(m),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(com.ilyskyo.blancall.R.string.review_arrow),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        // 错题回顾：该空有手写墨迹存档时，展示「当时写的」缩略图，点击放大对照
                        val batches = inkByBlank[m.blankIndex]
                        if (!batches.isNullOrEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp, bottom = 2.dp, start = 4.dp, end = 4.dp)
                                    .pressClick { enlarged = BlankInk(m.blankIndex, batches) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.ink_at_time),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    InkThumbnail(
                                        batches = batches,
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(com.ilyskyo.blancall.R.string.zoom_arrow),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            } else {
                // 无逐项错误明细（如反向默写）：仍提供复习入口，
                // 有落过「答错句子」的记录就聚焦到首句，否则退回薄弱集训（-1）
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressClick { onReview(record.mistakeSentenceIndices.firstOrNull() ?: -1) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.no_mistake_detail),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text(stringResource(com.ilyskyo.blancall.R.string.review_arrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    // 放大对照：大图墨迹 + 你填的/应为 对照（内容与错误行一致，避免来回滚动）
    AnimatedOverlayPresence(target = enlarged) { blank ->
        val m = record.mistakes.firstOrNull { it.blankIndex == blank.blankIndex }
        BlancallAlertDialog(
            onDismissRequest = { enlarged = null },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.ink_dialog_title, blank.blankIndex + 1)) },
            text = {
                Column {
                    InkThumbnail(
                        batches = blank.batches,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 2.dp
                    )
                    if (m != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            mistakeDescription(m),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { enlarged = null }) { Text(stringResource(com.ilyskyo.blancall.R.string.close)) }
            }
        )
    }
}

// ── 错误描述：代码 → 人话 ──
@Composable
private fun mistakeDescription(m: MistakeDetail): String {
    val correct = m.correctAnswer
    val user = m.userAnswer
    return when (m.errorType) {
        "TYPO" -> stringResource(com.ilyskyo.blancall.R.string.mistake_typo, user, correct)
        "GLYPH_CONFUSED" -> stringResource(com.ilyskyo.blancall.R.string.mistake_glyph, user, correct)
        "MISSING" -> stringResource(com.ilyskyo.blancall.R.string.mistake_missing, correct)
        "EXTRA" -> if (user.isNotEmpty()) stringResource(com.ilyskyo.blancall.R.string.mistake_extra, user, correct)
        else stringResource(com.ilyskyo.blancall.R.string.mistake_blank, correct)
        "WRONG_ORDER" -> stringResource(com.ilyskyo.blancall.R.string.mistake_order, user, correct)
        else -> stringResource(com.ilyskyo.blancall.R.string.mistake_plain, user, correct)
    }
}

// ── 记忆热力图卡片（F5）──
/**
 * 热力图色带的档位名：数据层只给稳定的 ASCII 键（`MemoryHeatmap.BAND_*`），文案在这里按界面语言取。
 *
 * 为什么不让 algorithm 直接返回中文：徽章那一处就是把中文写死在数据层，结果六语用户的统计页上
 * 徽章名全是简体，而所有多语言守卫都是绿的（它只比 res 目录）。色带名同一种形状，不再重复一次。
 */
@Composable
private fun heatBandLabel(band: String): String = stringResource(
    when (band) {
        MemoryHeatmap.BAND_WEAK -> com.ilyskyo.blancall.R.string.heat_band_weak
        MemoryHeatmap.BAND_HARD -> com.ilyskyo.blancall.R.string.heat_band_hard
        MemoryHeatmap.BAND_MID -> com.ilyskyo.blancall.R.string.heat_band_mid
        MemoryHeatmap.BAND_FAMILIAR -> com.ilyskyo.blancall.R.string.heat_band_familiar
        MemoryHeatmap.BAND_FIRM -> com.ilyskyo.blancall.R.string.heat_band_firm
        MemoryHeatmap.BAND_UNPRACTICED -> com.ilyskyo.blancall.R.string.heat_band_unpracticed
        else -> com.ilyskyo.blancall.R.string.heat_band_low_sample
    }
)

@OptIn(ExperimentalLayoutApi::class)
/**
 * 句级薄弱榜（B3-1）：把「哪句最常写错」从热力图的色块里捞出来，变成可以点的一行行。
 * 点一行 → 带 focusSentence 进练习页，只练该句及其前后各几句（见 PracticeViewModel.loadArticle + PracticeContentOps.focusWindow）。
 *
 * 展示用【观测】错误率（错 X% = 真错过的比例），不用排序用的收缩率 ——
 * 后者是给排序去噪的估计值，不该冒充用户练出来的数。
 *
 * [changedIndexes] 是「这次练完回来数值变了的句子」：那一行会闪一下底色、数字短暂换色 ——
 * 数据本来就会即时刷新，这层只负责让变化被看见（见 StatisticsScreen 里的快照比对）。
 */
@Composable
private fun WeakSentencesCard(
    rows: List<WeakSentenceRank.WeakSentence>,
    changedIndexes: Set<Int>,
    onDrill: (WeakSentenceRank.WeakSentence) -> Unit,
) {
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.weak_sentences_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.weak_sentences_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            rows.forEachIndexed { idx, row ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                // key 绑句子索引而不是位置：高亮/闪动状态必须跟着「这一句」走 ——
                // 榜单按薄弱度重排时，位置会换人，状态不能跟着串到别的行上
                key(row.sentenceIndex) {
                    WeakSentenceRow(
                        row = row,
                        changed = row.sentenceIndex in changedIndexes,
                        onDrill = { onDrill(row) }
                    )
                }
            }
        }
    }
}

/** 榜单一行；[changed] 为真时播一次「变化高亮」 */
@Composable
private fun WeakSentenceRow(
    row: WeakSentenceRank.WeakSentence,
    changed: Boolean,
    onDrill: () -> Unit
) {
    val glow = rememberChangeGlow(changed)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 高亮底色只铺到这一行，且随 glow 淡回透明 —— 静态列表里只有变过的那行亮
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.14f * glow))
            .pressClick { onDrill() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            row.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                com.ilyskyo.blancall.R.string.weak_sentence_row,
                (row.errorRate * 100).toInt(), row.practiceCount
            ),
            style = MaterialTheme.typography.labelSmall,
            // 数字从错误色短暂偏向主色：错 X% / 练过 N 次 的变化与底色同一条进度，一起淡回
            color = lerp(MaterialTheme.colorScheme.error, accent, glow)
        )
    }
}

/**
 * 「刚练完回来」的一次性高亮：值先顶到 1，再按词表淡回 0（跟在一次练习结果之后的节奏）。
 *
 * 用 Animatable 而不是 animateColorAsState：需要「同一件事再发生一次还能重播」——
 * 布尔驱动的颜色动画在「变了→又变回来」时会中途折返，看不出这是一次新的变化。
 * 减动效下 MotionFade.alpha 给 snap，高亮同帧消失（尊重系统设置，不做无动画的闪一下）。
 */
@Composable
private fun rememberChangeGlow(changed: Boolean): Float {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(changed) {
        if (changed) {
            glow.snapTo(1f)
            glow.animateTo(0f, MotionFade.alpha(MotionFade.scoreReveal))
        }
    }
    return glow.value
}

/** 榜单快照 `sentenceIndex=练过次数|错误率`（`;` 分隔）：rememberSaveable 只存得下简单类型 */
private fun parseWeakSnapshot(snapshot: String): Map<Int, String> =
    if (snapshot.isEmpty()) {
        emptyMap()
    } else {
        snapshot.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else part.take(eq).toIntOrNull()?.let { it to part.substring(eq + 1) }
        }.toMap()
    }

@Composable
private fun StatisticsHeatmapCard(heatmap: MemoryHeatmap.HeatmapData) {
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(com.ilyskyo.blancall.R.string.heatmap_title),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    pluralStringResource(
                        com.ilyskyo.blancall.R.plurals.heatmap_summary, heatmap.totalPractices,
                        heatmap.totalPractices, (heatmap.overallErrorRate * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            // FlowRow 而不是 Row：加上「未练」这一档后共 7 档，英文/俄文标签更长，
            // 窄屏单行放不下时会被裁掉右侧（项目既有惯例：窄屏自动换行而不是被挤出屏幕）。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MemoryHeatmap.legend().forEach { (band, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 未作答档没有颜色，画空心框：填一个色上去就等于给了一句结论，
                        // 而这一句根本没有数据支撑 —— 和「样本不足」用灰而不用红/绿是同一个理由。
                        if (color == Color.Unspecified) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color, RoundedCornerShape(2.dp))
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        Text(
                            heatBandLabel(band),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }

            // 门槛说明：把「几次才算数」与「达标后看什么」讲在图上。
            // 热力图里灰（样本不足）与无色（未作答）都在说「这里没有结论」，不解释就是两个坑：
            // 用户既不知道要练几次才能有颜色，也不知道有颜色之后那行数字是什么。
            Spacer(Modifier.height(6.dp))
            Text(
                // 走 plurals 而不是 string：英文里「3 attempts」与「1 attempt」不能共用一个串
                // （LocaleParityGuardTest 的 PH_COUNTED_NOUN 就是拦这个的）
                pluralStringResource(
                    com.ilyskyo.blancall.R.plurals.heat_threshold_note,
                    WeaknessMath.MIN_SAMPLE,
                    WeaknessMath.MIN_SAMPLE
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))
            // 超过 20 句先给预览 + 「展开全部」入口（项目既有规范：>10 句就走预览+展开）。
            // 原先只截断、不给入口，用户看到「仅显示前 20 句」却没有任何办法看剩下的句子。
            var sentencesExpanded by remember { mutableStateOf(false) }
            val displaySentences = if (sentencesExpanded || heatmap.sentences.size <= 20)
                heatmap.sentences else heatmap.sentences.take(20)

            displaySentences.forEach { sentence ->
                val bgColor = if (sentence.heatColor != Color.Unspecified)
                    sentence.heatColor.copy(alpha = 0.12f)
                else Color.Transparent

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (sentence.heatColor != Color.Unspecified) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .heightIn(min = 18.dp)
                                .background(sentence.heatColor, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            sentence.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 四种状态分开讲：样本不足不等于「错 0%」，估算出来的落点也不该和量出来的一样措辞，
                        // 从未作答的句子更不能留白（留白会被读成「这句没问题」）。
                        // 旧实现只在 practiceCount>0 时给一句「错 X%」，而那时 practiceCount 填的是整篇会话数 ——
                        // 从没答过的句子也显示「错 0%」，用户读成「这句掌握了」。
                        when {
                            // 从未作答：既没有色条也没有比率，用一句说明把「这里为什么什么都没有」讲清楚。
                            sentence.practiceCount <= 0 -> Text(
                                stringResource(com.ilyskyo.blancall.R.string.heat_sentence_unpracticed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 样本不足：把「差几次」写出来 —— 只说「样本不足」，用户不知道
                            // 再练几次才有结论，也就不知道该不该管这一句。
                            // 没有 FSRS 状态的旧记录另给一句：它缺的不是练习次数。
                            sentence.lowSample -> {
                                val hint = if (sentence.retention < 0f) {
                                    stringResource(com.ilyskyo.blancall.R.string.heat_no_retention)
                                } else {
                                    stringResource(
                                        com.ilyskyo.blancall.R.string.heat_low_sample_short,
                                        sentence.practiceCount, WeaknessMath.MIN_SAMPLE
                                    )
                                }
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                // 主信息 = 当前留存率（颜色分档就是它）；错误率退为副信息（B3-2）
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.heat_sentence_retention,
                                        (sentence.retention * 100).toInt()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sentence.heatColor.takeIf { it != Color.Unspecified }
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.error_rate_short,
                                        (sentence.errorRate * 100).toInt()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (sentence.estimated) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(com.ilyskyo.blancall.R.string.heatmap_estimated_tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
            }

            if (heatmap.sentences.size > 20) {
                if (!sentencesExpanded) {
                    Text(
                        pluralStringResource(
                            com.ilyskyo.blancall.R.plurals.heatmap_truncated, heatmap.sentences.size, heatmap.sentences.size
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(
                    onClick = { sentencesExpanded = !sentencesExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(
                            if (sentencesExpanded) com.ilyskyo.blancall.R.string.collapse_plain
                            else com.ilyskyo.blancall.R.string.view_all_plain
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
