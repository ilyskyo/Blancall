// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.MemoryHeatmap
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.DailyTrendChart
import com.ilyskyo.blancall.ui.common.GaugeProgress
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.MistakeBar
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单篇文章的统计聚合结果。用 remember 包裹，避免每次重组重算。
 */
private data class ArticleStats(
    val totalPractices: Int,
    val totalCorrect: Int,
    val totalBlanks: Int,
    val overallRate: Float,
    val latestRate: Float,
    val bestRate: Float,
    val firstRate: Float,
    val trendIcon: String,
    val sentenceCount: Int,
    val wordCount: Int,
    val reverseCount: Int,
    val sentenceRate: Float,
    val wordRate: Float,
    val reverseRate: Float,
    val typoCount: Int,
    val missingCount: Int,
    val extraCount: Int,
    val orderCount: Int,
    val incorrectCount: Int,
    val totalMistakes: Int,
    val totalDuration: Long,
    val dailyCounts: List<Int>   // 近14天每日练习次数，下标0=最早
)

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

    // ── 基础统计：仅在 records 变化时重算 ──
    val stats = remember(records) {
        val totalPractices = records.size
        val totalCorrect = records.sumOf { it.correctCount }
        val totalBlanks = records.sumOf { it.totalBlanks }
        val overallRate = if (totalBlanks > 0) totalCorrect.toFloat() / totalBlanks else 0f

        val latestRate = records.firstOrNull()?.let {
            if (it.totalBlanks > 0) it.correctCount.toFloat() / it.totalBlanks else 0f
        } ?: 0f

        // 最高正确率：排除 totalBlanks=0 的空记录
        val bestRate = records.filter { it.totalBlanks > 0 }
            .maxOfOrNull { it.correctCount.toFloat() / it.totalBlanks } ?: 0f

        val firstRate = records.lastOrNull()?.let {
            if (it.totalBlanks > 0) it.correctCount.toFloat() / it.totalBlanks else 0f
        } ?: 0f
        val trendIcon = when {
            records.size < 2 -> ""
            latestRate > firstRate + 0.05f -> " 📈"
            latestRate < firstRate - 0.05f -> " 📉"
            else -> " ➡️"
        }

        // 模式统计（含反向默写）
        val sentenceCount = records.count { it.mode == "SENTENCE" }
        val wordCount = records.count { it.mode == "WORD" }
        val reverseCount = records.count { it.mode == "REVERSE" }
        val sentenceRate = records.filter { it.mode == "SENTENCE" }.let { rs ->
            val t = rs.sumOf { it.totalBlanks }
            if (t > 0) rs.sumOf { it.correctCount }.toFloat() / t else 0f
        }
        val wordRate = records.filter { it.mode == "WORD" }.let { rs ->
            val t = rs.sumOf { it.totalBlanks }
            if (t > 0) rs.sumOf { it.correctCount }.toFloat() / t else 0f
        }
        val reverseRate = records.filter { it.mode == "REVERSE" }.let { rs ->
            val t = rs.sumOf { it.totalBlanks }
            if (t > 0) rs.sumOf { it.correctCount }.toFloat() / t else 0f
        }

        // 错误类型分布
        val allMistakes = records.flatMap { it.mistakes }
        val typoCount = allMistakes.count { it.errorType == "TYPO" }
        val missingCount = allMistakes.count { it.errorType == "MISSING" }
        val extraCount = allMistakes.count { it.errorType == "EXTRA" }
        val orderCount = allMistakes.count { it.errorType == "WRONG_ORDER" }
        val incorrectCount = allMistakes.count { it.errorType == "INCORRECT" }
        val totalMistakes = allMistakes.size

        // 累计练习时长（毫秒）
        val totalDuration = records.sumOf { it.duration }

        // 近14天每日练习次数（系统时区，避免凌晨 0-8 点算到前一天）
        val today = LocalDate.now()
        val zoneId = java.time.ZoneId.systemDefault()
        val recordDates = records.map { r ->
            try { java.time.Instant.ofEpochMilli(r.timestamp).atZone(zoneId).toLocalDate() }
            catch (_: Exception) { null }
        }.filterNotNull()
        val dailyCounts = (13 downTo 0).map { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            recordDates.count { it == day }
        }

        ArticleStats(
            totalPractices = totalPractices,
            totalCorrect = totalCorrect,
            totalBlanks = totalBlanks,
            overallRate = overallRate,
            latestRate = latestRate,
            bestRate = bestRate,
            firstRate = firstRate,
            trendIcon = trendIcon,
            sentenceCount = sentenceCount,
            wordCount = wordCount,
            reverseCount = reverseCount,
            sentenceRate = sentenceRate,
            wordRate = wordRate,
            reverseRate = reverseRate,
            typoCount = typoCount,
            missingCount = missingCount,
            extraCount = extraCount,
            orderCount = orderCount,
            incorrectCount = incorrectCount,
            totalMistakes = totalMistakes,
            totalDuration = totalDuration,
            dailyCounts = dailyCounts
        )
    }

    // ── 记忆热力图（F5）──
    val articleRepo = remember { ArticleRepository(context.filesDir.resolve("articles.json").absolutePath) }
    var heatmapData by remember { mutableStateOf<MemoryHeatmap.HeatmapData?>(null) }
    LaunchedEffect(articleId, records.size, records.lastOrNull()?.timestamp) {
        // 读文件走 IO、全文切分+聚合走 Default，避免阻塞主线程
        val article = withContext(Dispatchers.IO) { articleRepo.getArticleById(articleId) }
        if (article != null && records.isNotEmpty()) {
            heatmapData = withContext(Dispatchers.Default) {
                MemoryHeatmap.generate(article.content, records)
            }
        }
    }

    // ── 历史筛选状态 ──
    var filterMode by remember { mutableStateOf<String?>(null) } // null=全部
    val filteredRecords = remember(records, filterMode) {
        if (filterMode == null) records else records.filter { it.mode == filterMode }
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
                Text("学习统计", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            // 记录列表：统计卡片与练习记录可滚动
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
        // ═══════ 统计总览卡片（仪表盘 + 连续天数 + 趋势） ═══════
        item {
            AnimatedStatCard {
                GlassCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("统计总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
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
                                    val minutes = stats.totalDuration / 60_000L
                                    Text("累计练习 ${minutes}分${(stats.totalDuration / 1000L) % 60}秒",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        StatItem("练习次数", "${stats.totalPractices}")
                                        Spacer(Modifier.height(6.dp))
                                        StatItem("历史最佳", "${(stats.bestRate * 100).toInt()}%")
                                    }
                                    Column {
                                        StatItem("最近一次", "${(stats.latestRate * 100).toInt()}%")
                                        Spacer(Modifier.height(6.dp))
                                        StatItem("累计正确", "${stats.totalCorrect}")
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
                            Text("每日练习趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
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
                            Text("模式对比", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                if (stats.sentenceCount > 0)
                                    StatItem("句子挖空", "${stats.sentenceCount}次\n${(stats.sentenceRate * 100).toInt()}%")
                                if (stats.wordCount > 0)
                                    StatItem("字词挖空", "${stats.wordCount}次\n${(stats.wordRate * 100).toInt()}%")
                                if (stats.reverseCount > 0)
                                    StatItem("反向默写", "${stats.reverseCount}次\n${(stats.reverseRate * 100).toInt()}%")
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
                            Text("薄弱环节", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
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

        // ═══════ 练习历史标题 + 筛选 chips ═══════
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("练习历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Row {
                    FilterChip(
                        selected = filterMode == null,
                        onClick = { filterMode = null },
                        label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(Modifier.width(6.dp))
                    if (stats.sentenceCount > 0) {
                        FilterChip(
                            selected = filterMode == "SENTENCE",
                            onClick = { filterMode = if (filterMode == "SENTENCE") null else "SENTENCE" },
                            label = { Text("句子", style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (stats.wordCount > 0) {
                        FilterChip(
                            selected = filterMode == "WORD",
                            onClick = { filterMode = if (filterMode == "WORD") null else "WORD" },
                            label = { Text("字词", style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (stats.reverseCount > 0) {
                        FilterChip(
                            selected = filterMode == "REVERSE",
                            onClick = { filterMode = if (filterMode == "REVERSE") null else "REVERSE" },
                            label = { Text("默写", style = MaterialTheme.typography.labelSmall) }
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
                        "暂无练习记录",
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
                        "该模式下暂无记录",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filteredRecords, key = { it.id }) { record ->
                RecordCard(record, dateFormat) {
                    // 按该记录的实际练习模式进入薄弱集训：
                    // 反向默写 → REVERSE、句子挖空 → SENTENCE、字词挖空 → WORD
                    navController.navigate("practice/$articleId?mode=${record.mode}&sectionMode=WEAKNESS")
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
        animationSpec = tween(380, delayMillis = 40),
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

// ── 单条练习记录 ──
@Composable
private fun RecordCard(
    record: PracticeRecord,
    dateFormat: SimpleDateFormat,
    onErrorClick: () -> Unit
) {
    val modeLabel = when (record.mode) {
        "SENTENCE" -> "句子挖空"
        "WORD" -> "字词挖空"
        "REVERSE" -> "反向默写"
        else -> "字词挖空"
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Text(
                        modeLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
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
                Text("正确率 $rate%（${record.correctCount}/${record.totalBlanks}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
            }
            // 用时（分钟 + 秒数）
            if (record.duration > 0) {
                val mins = record.duration / 60_000L
                val secs = (record.duration % 60_000L) / 1000L
                Text("用时 ${mins}分${secs}秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.mistakes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                record.mistakes.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onErrorClick() }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            mistakeDescription(m),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text("复习 ›", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                // 无逐项错误明细（如反向默写）：仍提供复习入口
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onErrorClick() }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("本次练习无逐项错误明细",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text("复习 ›", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ── 错误描述：代码 → 人话 ──
private fun mistakeDescription(m: MistakeDetail): String {
    val correct = m.correctAnswer
    val user = m.userAnswer
    return when (m.errorType) {
        "TYPO" -> "✗ 错别字：你填了「$user」，应为「$correct」"
        "MISSING" -> "✗ 漏填：这里应填「$correct」"
        "EXTRA" -> if (user.isNotEmpty())
            "✗ 多填了「$user」，应为「$correct」"
        else
            "✗ 未填写，应填「$correct」"
        "WRONG_ORDER" -> "✗ 顺序反了：你填「$user」，应为「$correct」"
        else -> "✗「$user」应为「$correct」"
    }
}

// ── 记忆热力图卡片（F5）──
@Composable
private fun StatisticsHeatmapCard(heatmap: MemoryHeatmap.HeatmapData) {
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("记忆热力图", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "练习${heatmap.totalPractices}次 · 错误率${(heatmap.overallErrorRate * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MemoryHeatmap.getLegendColors().forEach { (label, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))

            val displaySentences = if (heatmap.sentences.size > 20)
                heatmap.sentences.take(20) else heatmap.sentences

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
                        if (sentence.practiceCount > 0) {
                            Text(
                                "错${(sentence.errorRate * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = sentence.heatColor.takeIf { it != Color.Unspecified }
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
            }

            if (heatmap.sentences.size > 20) {
                Text(
                    "…共 ${heatmap.sentences.size} 句，仅显示前 20 句",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
