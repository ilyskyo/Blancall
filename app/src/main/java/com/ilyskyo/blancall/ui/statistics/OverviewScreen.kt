// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AchievementManager
import com.ilyskyo.blancall.algorithm.CsvExporter
import com.ilyskyo.blancall.algorithm.ForgettingPredictor
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.CalendarHeatmap
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.MemoryDecayChart
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.RadarChart
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

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
    val weakestArticles: List<Triple<Long, Float, Int>>,
    val typoCount: Int,
    val missingCount: Int,
    val extraCount: Int,
    val orderCount: Int,
    val incorrectCount: Int,
    val totalMistakes: Int,
    val totalDuration: Long,
    val dailyCounts: List<Int>,   // 近84天每日练习次数
    val todayCount: Int            // 今日已练习次数
)

/** 弱点画像五维度占比 */
private data class WeaknessProfile(
    val typo: Float,
    val missing: Float,
    val extra: Float,
    val order: Float,
    val incorrect: Float,
    val totalMistakes: Int
)

@Composable
fun OverviewScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    val articleRepo = remember { ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath) }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    val streakDays by ReminderPrefs.studyStreakDaysFlow.collectAsState()
    val longestStreak by ReminderPrefs.longestStreakDaysFlow.collectAsState()
    val dailyGoal by ReminderPrefs.dailyPracticeGoalFlow.collectAsState()
    val templateId by AppPrefs.reviewTemplateFlow.collectAsState()
    val scope = rememberCoroutineScope()

    // 模式选择弹窗（即将遗忘 → 去练习）
    var showModePicker by remember { mutableStateOf(false) }
    var pendingPracticeArticleId by remember { mutableStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }

    // 当前复习模板
    val template = remember(templateId) {
        ReviewTemplate.PRESETS.find { it.id == templateId } ?: ReviewTemplate.STANDARD
    }

    // 错误类型单次聚合：供下方 stats 与 weakness 共用，避免重复 flatMap + 多次 count 扫描
    val mistakeAgg = remember(allRecords) {
        val counts = HashMap<String, Int>(5)
        var total = 0
        allRecords.forEach { r ->
            r.mistakes.forEach { m -> counts.merge(m.errorType, 1, Int::plus); total++ }
        }
        counts to total
    }

    // ── 全量统计聚合：仅在 allRecords 变化时重算 ──
    val stats = remember(allRecords) {
        val totalPractices = allRecords.size
        val totalCorrect = allRecords.sumOf { it.correctCount }
        val totalBlanks = allRecords.sumOf { it.totalBlanks }
        val overallRate = if (totalBlanks > 0) totalCorrect.toFloat() / totalBlanks else 0f

        val byArticle = allRecords.groupBy { it.articleId }
        val articleCount = byArticle.size

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

        // 趋势（最近7天 vs 更早）
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val recentRecords = allRecords.filter { it.timestamp >= sevenDaysAgo }
        val recentCorrect = recentRecords.sumOf { it.correctCount }
        val recentBlanks = recentRecords.sumOf { it.totalBlanks }
        val recentRate = if (recentBlanks > 0) recentCorrect.toFloat() / recentBlanks else 0f
        val olderRecords = allRecords.filter { it.timestamp < sevenDaysAgo && it.timestamp > 0 }
        val olderCorrect = olderRecords.sumOf { it.correctCount }
        val olderBlanks = olderRecords.sumOf { it.totalBlanks }
        val olderRate = if (olderBlanks > 0) olderCorrect.toFloat() / olderBlanks else 0f

        // 易错文章（正确率最低的3篇）
        val weakestArticles = byArticle.entries
            .map { (articleId, records) ->
                val c = records.sumOf { it.correctCount }
                val t = records.sumOf { it.totalBlanks }
                Triple(articleId, if (t > 0) c.toFloat() / t else 0f, records.size)
            }
            .sortedBy { it.second }
            .take(3)

        // 错误类型汇总：复用上方单次聚合结果，不再 flatMap 重复扫描
        val typoCount = mistakeAgg.first["TYPO"] ?: 0
        val missingCount = mistakeAgg.first["MISSING"] ?: 0
        val extraCount = mistakeAgg.first["EXTRA"] ?: 0
        val orderCount = mistakeAgg.first["WRONG_ORDER"] ?: 0
        val incorrectCount = mistakeAgg.first["INCORRECT"] ?: 0
        val totalMistakes = mistakeAgg.second

        val totalDuration = allRecords.sumOf { it.duration }

        // 近 84 天（12 周）每日练习次数：用系统时区转换避免 UTC 错位（凌晨 0-8 点算到前一天）
        val today = LocalDate.now()
        val zoneId = java.time.ZoneId.systemDefault()
        // 先按日期聚合计数（O(R)），再按天查表，替代原来的 84×R 双重循环
        val countByDate = allRecords.mapNotNull { r ->
            try { java.time.Instant.ofEpochMilli(r.timestamp).atZone(zoneId).toLocalDate() }
            catch (_: Exception) { null }
        }.groupingBy { it }.eachCount()
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

    // ── 遗忘曲线预测：FSRS 自适应调度（无 FSRS 状态的文章回退模板）──
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val predictions = remember(articles, allRecords, template) {
        ForgettingPredictor.predict(articles, allRecords, template, fsrsStore.allStates())
    }
    val dueSoon = remember(predictions) { ForgettingPredictor.dueSoon(predictions).take(5) }

    // ── 弱点画像：四维度错误占比 ──
    val weakness = remember(mistakeAgg) {
        val total = mistakeAgg.second.coerceAtLeast(1)
        WeaknessProfile(
            typo = (mistakeAgg.first["TYPO"] ?: 0).toFloat() / total,
            missing = (mistakeAgg.first["MISSING"] ?: 0).toFloat() / total,
            extra = (mistakeAgg.first["EXTRA"] ?: 0).toFloat() / total,
            order = (mistakeAgg.first["WRONG_ORDER"] ?: 0).toFloat() / total,
            incorrect = (mistakeAgg.first["INCORRECT"] ?: 0).toFloat() / total,
            totalMistakes = mistakeAgg.second
        )
    }

    // ── 成就徽章 ──
    val achievements = remember(allRecords, longestStreak) {
        AchievementManager.evaluate(allRecords, longestStreak, streakDays)
    }

    // 易错文章标题解析（异步）
    var weakTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(stats) {
        val ids = stats.weakestArticles.map { it.first }
        val titles = withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                articleRepo.getArticleById(id)?.title ?: "文章 #$id"
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
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ── 顶部栏：返回 + 标题 + 筛选 + 导出 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 底部导航模式：三页面并列，无需返回键（返回首页按底部导航即可）
                val bottomNavEnabled by AppPrefs.bottomNavEnabledFlow.collectAsState()
                if (!bottomNavEnabled) {
                    // 触点展开方式进入时由 onBack 收起（popBackStack 在栈底无效）
                    BackButton(onClick = { onBack?.invoke() ?: navController.popBackStack() })
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    "全局统计",
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
                            Text("筛选", style = MaterialTheme.typography.labelMedium,
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
                                            android.widget.Toast.makeText(context, "导出失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
                        Text(if (isExporting) "导出中…" else "导出", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        Spacer(Modifier.height(4.dp))

        if (allRecords.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📊", fontSize = 48.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("暂无练习记录", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("完成首次练习后，这里将展示你的学习数据分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { navController.navigate("list") }) {
                    Text("去练习")
                }
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))

        val isWide = LocalConfiguration.current.screenWidthDp >= 600

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 总览卡片（仪表盘 + 连续天数 + 时长） ──
            item {
                AnimatedOverviewCard {
                    GlassCard(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("数据总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GaugeProgress(
                                    progress = stats.overallRate,
                                    label = "总体正确率",
                                    modifier = Modifier.size(110.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🔥", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.width(4.dp))
                                        Text("连续 $streakDays 天", style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text("最长 $longestStreak 天",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    if (stats.totalDuration > 0) {
                                        val hours = stats.totalDuration / 3_600_000L
                                        val mins = (stats.totalDuration / 60_000L) % 60
                                        Text("累计练习 ${hours}时${mins}分",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        StatItem("练习次数", "${stats.totalPractices}")
                                        StatItem("涉及文章", "${stats.articleCount}")
                                        StatItem("总填空", "${stats.totalBlanks}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 今日目标卡片（每日练习次数目标 + 完成度） ──
            item {
                AnimatedOverviewCard {
                    TodayGoalCard(
                        todayCount = stats.todayCount,
                        goal = dailyGoal,
                        onGoalChange = { ReminderPrefs.dailyPracticeGoal = it }
                    )
                }
            }

            // ── 遗忘曲线预测卡片 ──
            if (dueSoon.isNotEmpty()) {
                item {
                    AnimatedOverviewCard {
                        ForgettingPredictionCard(
                            predictions = dueSoon,
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

            // ── 趋势分析分组 ──
            item {
                Text("趋势分析", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp))
            }

            // ── 每日练习趋势 ──
            item {
                AnimatedOverviewCard {
                    GlassCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("每日练习趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(12.dp))
                            DailyTrendChart(dailyCounts = stats.dailyCounts.takeLast(14))
                        }
                    }
                }
            }

            // ── 日历热力图 ──
            item {
                AnimatedOverviewCard {
                    GlassCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("学习日历", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(10.dp))
                            CalendarHeatmap(dailyCounts = stats.dailyCounts)
                        }
                    }
                }
            }

            // ── 能力画像分组 ──
            item {
                Text("能力画像", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp))
            }

            // ── 弱点画像（雷达图 + 训练建议） ──
            if (stats.totalMistakes > 0) {
                item {
                    AnimatedOverviewCard {
                        WeaknessProfileCard(weakness = weakness)
                    }
                }
            }

            // ── 模式对比（含反向默写） ──
            item {
                AnimatedOverviewCard {
                    GlassCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("模式对比", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                StatItem("句子挖空", "${stats.sentenceCount}次\n${(stats.sentenceRate * 100).toInt()}%")
                                StatItem("字词挖空", "${stats.wordCount}次\n${(stats.wordRate * 100).toInt()}%")
                                StatItem("反向默写", "${stats.reverseCount}次\n${(stats.reverseRate * 100).toInt()}%")
                            }
                        }
                    }
                }
            }

            // ── 趋势 ──
            if (stats.olderCount > 0) {
                item {
                    AnimatedOverviewCard {
                        GlassCard {
                            Column(Modifier.padding(16.dp)) {
                                Text("进步趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(10.dp))
                                val trend = stats.recentRate - stats.olderRate
                                val trendText = when {
                                    trend > 0.05f -> "📈 近7天提升 ${(trend * 100).toInt()}%"
                                    trend < -0.05f -> "📉 近7天下降 ${(-trend * 100).toInt()}%"
                                    else -> "➡️ 近期保持平稳"
                                }
                                Text(trendText, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    StatItem("近7天", "${(stats.recentRate * 100).toInt()}% (${stats.recentCount}次)")
                                    StatItem("更早", "${(stats.olderRate * 100).toInt()}% (${stats.olderCount}次)")
                                }
                            }
                        }
                    }
                }
            }

            // ── 薄弱环节 ──
            if (stats.totalMistakes > 0) {
                item {
                    AnimatedOverviewCard {
                        GlassCard {
                            Column(Modifier.padding(16.dp)) {
                                Text("薄弱环节汇总", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(10.dp))
                                MistakeBar(
                                    typo = stats.typoCount,
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
            item {
                AnimatedOverviewCard {
                    AchievementGridCard(achievements = achievements)
                }
            }

            // ── 易错文章（可点击跳转） ──
            if (stats.weakestArticles.isNotEmpty()) {
                item {
                    Text("需加强的文章", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp))
                }
                if (isWide) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for ((articleId, rate, count) in stats.weakestArticles) {
                                GlassCard(
                                    modifier = Modifier.weight(1f),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    onClick = { navController.navigate("statistics/$articleId") }
                                ) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            weakTitles[articleId] ?: "文章 #$articleId",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("正确率 ${(rate * 100).toInt()}% · ${count}次",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(stats.weakestArticles, key = { it.first }) { (articleId, rate, count) ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            onClick = { navController.navigate("statistics/$articleId") }
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    weakTitles[articleId] ?: "文章 #$articleId",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正确率 ${(rate * 100).toInt()}% · ${count}次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        onModeSelected = { mode ->
            showModePicker = false
            if (pendingPracticeArticleId > 0) {
                // 从全局统计进入练习，返回时自然回到本页
                navController.navigate("practice/${pendingPracticeArticleId}?mode=${mode.name}")
            }
        }
    )
}

// ── 卡片入场动画包装 ──
@Composable
private fun AnimatedOverviewCard(content: @Composable () -> Unit) {
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = tween(380, delayMillis = 40),
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
                    if (achieved) "🎯 今日目标已达成" else "🎯 今日目标",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "$todayCount / $effectiveGoal 次",
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
                Text("目标", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                goalOptions.forEach { g ->
                    FilterChip(
                        selected = goal == g,
                        onClick = { onGoalChange(g) },
                        label = { AdaptiveChipLabel("$g 次") },
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
                            if (goal in goalOptions) "自定义" else "$goal 次"
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }

    // ── 自定义目标次数输入对话框 ──
    if (showCustomGoalDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomGoalDialog = false },
            title = { Text("自定义每日目标") },
            text = {
                OutlinedTextField(
                    value = customGoalInput,
                    onValueChange = { input ->
                        customGoalInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text("每日练习次数") },
                    suffix = { Text("次") },
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
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGoalDialog = false }) { Text("取消") }
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
    anchorArticleId: Long = 0L,
    onPracticeButtonPlaced: (Long, Rect) -> Unit = { _, _ -> },
    onPractice: (Long) -> Unit
) {
    // 当前展开的 articleId；同一时间只展开一条
    var expandedId by remember { mutableStateOf<Long?>(null) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("⏰ 即将遗忘", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("基于 FSRS 遗忘曲线模型预测",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            predictions.forEach { p ->
                val (badge, badgeColor) = urgencyBadge(p)
                val expanded = expandedId == p.articleId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 切换展开/收起；不直接跳练习，避免误触
                            expandedId = if (expanded) null else p.articleId
                        }
                        .semantics {
                            contentDescription = "${p.title}，" +
                                "记忆留存 ${(p.retentionRate * 100).toInt()}%，" +
                                if (expanded) "已展开衰减曲线" else "点击展开衰减曲线"
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
                            Text(
                                "已练 ${p.practiceCount} 次 · 正确率 ${(p.lastAccuracy * 100).toInt()}% · 记忆强度 ${String.format(Locale.ROOT, "%.1f", p.memoryStrength)}天",
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
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
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
                                    Text("去练习 ›", style = MaterialTheme.typography.labelMedium,
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

/** 紧急度徽章文本与颜色（@Composable 以读取 MaterialTheme.colorScheme） */
@Composable
private fun urgencyBadge(p: ForgettingPredictor.Prediction): Pair<String, androidx.compose.ui.graphics.Color> {
    val c = MaterialTheme.colorScheme
    return when (p.urgency) {
        ForgettingPredictor.Urgency.OVERDUE -> "已逾期 ${-p.daysLeft}天" to c.error
        ForgettingPredictor.Urgency.TODAY -> "今天" to c.error
        ForgettingPredictor.Urgency.SOON -> "${p.daysLeft}天后" to c.tertiary
        ForgettingPredictor.Urgency.LATER -> "${p.daysLeft}天后" to c.onSurfaceVariant
        ForgettingPredictor.Urgency.NEW -> "未开始" to c.onSurfaceVariant
        ForgettingPredictor.Urgency.MASTERED -> "已掌握" to c.primary
    }
}

/** 弱点画像卡片：雷达图 + 针对性训练建议 */
@Composable
private fun WeaknessProfileCard(weakness: WeaknessProfile) {
    val axes = listOf("错字", "漏字", "多填", "乱序", "全错")
    val values = listOf(weakness.typo, weakness.missing, weakness.extra, weakness.order, weakness.incorrect)
    val advice = trainingAdvice(weakness)

    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Text("弱点画像", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            RadarChart(
                axes = axes,
                values = values,
                label = "共 ${weakness.totalMistakes} 处错误"
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
private fun trainingAdvice(w: WeaknessProfile): String {
    if (w.totalMistakes == 0) return "暂无错误数据，继续练习以生成弱点画像"
    val max = maxOf(w.typo, w.missing, w.extra, w.order, w.incorrect)
    return when {
        w.incorrect == max -> "完全答错占比最高（${(w.incorrect * 100).toInt()}%），建议重读原文，从句子挖空起步"
        w.typo == max -> "错字占比最高（${(w.typo * 100).toInt()}%），建议多练字词挖空，注意易混淆字"
        w.missing == max -> "漏字偏多（${(w.missing * 100).toInt()}%），练习时关注句意完整，加强句子挖空"
        w.extra == max -> "多字突出（${(w.extra * 100).toInt()}%），默写时克制多余内容，多用反向默写"
        else -> "顺序错误居多（${(w.order * 100).toInt()}%），用反向默写强化句子顺序记忆"
    }
}

/** 成就徽章卡片 */
@Composable
private fun AchievementGridCard(achievements: List<AchievementManager.Achievement>) {
    val unlockedCount = achievements.count { it.unlocked }

    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🏆 成就徽章", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("已解锁 $unlockedCount/${achievements.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            AchievementBadgeGrid(achievements = achievements)
        }
    }
}

/** 徽章网格（FlowRow 自动换行）：已解锁徽章聚在一起排在前面，未解锁的排在后面 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementBadgeGrid(achievements: List<AchievementManager.Achievement>) {
    // 稳定排序：已解锁 → 未解锁，保持各自原有相对顺序
    val grouped = remember(achievements) {
        achievements.sortedByDescending { it.unlocked }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { ach ->
            AchievementBadgeItem(achievement = ach)
        }
    }
}

/** 单个徽章项：已解锁 = 主色实底高亮；未解锁 = 灰色淡显 + 进度条 */
@Composable
private fun AchievementBadgeItem(achievement: AchievementManager.Achievement) {
    val a = achievement
    val c = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(72.dp),
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
                .border(
                    width = if (a.unlocked) 1.5.dp else 1.dp,
                    color = if (a.unlocked) c.primary.copy(alpha = 0.9f)
                    else c.outlineVariant.copy(alpha = 0.7f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                a.icon,
                fontSize = 22.sp,
                color = if (a.unlocked) c.primary
                else c.outline.copy(alpha = 0.35f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            a.title,
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
                a.progressText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = c.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}
