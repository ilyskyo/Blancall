// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.ErrorType
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.toErrorType
import com.ilyskyo.blancall.data.model.toPracticeMode
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.animateListItem
import com.ilyskyo.blancall.ui.common.pressClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * 全局练习记录：跨文章看「哪天练了什么」。
 *
 * 单篇统计页只有本篇历史；总览的日历/热力图能按天计数，却点不开某一天。
 * 跨文练习（articleId=-1）更是从来没有一个列表可看，只剩总览里的一个总数。
 * 这个页面补上这三件事：全部记录、按日筛选、错误类型筛选（供弱点画像下钻）。
 *
 * @param initialDayEpoch 日历格点进来的那一天（epochDay）；0 = 不按日筛选
 * @param initialErrorType 弱点画像点进来的错误类型；空 = 不按错误筛选
 */
@Composable
fun PracticeHistoryScreen(
    navController: NavController,
    initialDayEpoch: Long = 0L,
    initialErrorType: String = "",
) {
    val context = LocalContext.current
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    var dayEpoch by rememberSaveable { mutableLongStateOf(initialDayEpoch) }
    var errorType by rememberSaveable { mutableStateOf(initialErrorType.takeIf { it.isNotBlank() }) }
    var crossDetail by remember { mutableStateOf<PracticeRecord?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            recordRepo.awaitLoaded()
            articleRepo.awaitLoaded()
        }
    }

    val articleById = remember(articles) { articles.associateBy { it.id } }
    val filtered = remember(allRecords, dayEpoch, errorType) {
        allRecords
            .filter { record ->
                val dayOk = dayEpoch <= 0L ||
                    DayBucket.localDate(record.timestamp).toEpochDay() == dayEpoch
                val errorOk = errorType == null || record.mistakes.any {
                    it.errorType.toErrorType().name == errorType
                }
                dayOk && errorOk
            }
            .sortedByDescending { it.timestamp }
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dayFormat = remember {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(
            Locale.getDefault(), "yMMMd"
        )
        SimpleDateFormat(pattern, Locale.getDefault())
    }
    val navConn = com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.practice_history),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${filtered.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (dayEpoch > 0L || errorType != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dayEpoch > 0L) {
                        FilterChip(
                            selected = true,
                            onClick = { dayEpoch = 0L },
                            label = {
                                Text(
                                    dayFormat.format(
                                        Date.from(
                                            LocalDate.ofEpochDay(dayEpoch)
                                                .atStartOfDay(java.time.ZoneId.systemDefault())
                                                .toInstant()
                                        )
                                    ) + " ×",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                    errorType?.let { type ->
                        FilterChip(
                            selected = true,
                            onClick = { errorType = null },
                            label = {
                                Text(
                                    errorLabel(type) + " ×",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(
                                if (dayEpoch > 0L) R.string.practice_history_empty_day
                                else R.string.no_practice_history
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (dayEpoch > 0L || errorType != null) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { dayEpoch = 0L; errorType = null }) {
                                Text(stringResource(R.string.clear_filter))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(navConn),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(filtered, key = { it.id }) { record ->
                        Box(modifier = animateListItem()) {
                            HistoryRecordCard(
                                record = record,
                                articleById = articleById,
                                dateFormat = dateFormat,
                                onClick = {
                                    if (record.articleId > 0L) {
                                        navController.navigate("statistics/${record.articleId}")
                                    } else {
                                        crossDetail = record
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    crossDetail?.let { record ->
        val sources = record.sourceArticleIds.mapNotNull { articleById[it] }
        BlancallAlertDialog(
            onDismissRequest = { crossDetail = null },
            title = { Text(stringResource(R.string.reader_cross_cloze)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.practice_history_sources),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (sources.isEmpty()) {
                        Text(
                            stringResource(R.string.practice_history_no_sources),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sources.forEach { article ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressClick {
                                        crossDetail = null
                                        navController.navigate("reader/${article.id}")
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    article.title.ifBlank { stringResource(R.string.untitled_article) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.open),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { crossDetail = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun HistoryRecordCard(
    record: PracticeRecord,
    articleById: Map<Long, Article>,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val isCross = record.articleId <= 0L
    val title = if (isCross) {
        val n = record.sourceArticleIds.size
        if (n > 0) {
            stringResource(R.string.reader_cross_cloze) + " · " +
                stringResource(R.string.article_count_chip, n)
        } else {
            stringResource(R.string.reader_cross_cloze)
        }
    } else {
        articleById[record.articleId]?.title?.ifBlank { null }
            ?: stringResource(R.string.ov_article_fallback, record.articleId)
    }
    val accuracy = if (record.totalBlanks > 0) {
        (record.correctCount.toFloat() / record.totalBlanks * 100).toInt()
    } else 0
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$accuracy%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (accuracy >= 80) MaterialTheme.colorScheme.primary
                    else if (accuracy >= 60) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(3.dp))
            val modeText = modeLabel(record.mode)
            Text(
                buildString {
                    append(modeText)
                    append(" · ")
                    append(dateFormat.format(Date(record.timestamp)))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun modeLabel(mode: String): String = stringResource(
    when (mode.toPracticeMode()) {
        com.ilyskyo.blancall.data.model.PracticeMode.SENTENCE -> R.string.cloze_mode_sentence
        com.ilyskyo.blancall.data.model.PracticeMode.WORD -> R.string.cloze_mode_word
        com.ilyskyo.blancall.data.model.PracticeMode.REVERSE -> R.string.cloze_mode_reverse
        null -> R.string.cloze_mode_sentence
    }
)

@Composable
private fun errorLabel(type: String): String = stringResource(
    when (type.toErrorType()) {
        ErrorType.TYPO -> R.string.mistake_bar_typo
        ErrorType.GLYPH_CONFUSED -> R.string.mistake_bar_glyph
        ErrorType.MISSING -> R.string.mistake_bar_missing
        ErrorType.EXTRA -> R.string.mistake_bar_extra
        ErrorType.WRONG_ORDER -> R.string.mistake_bar_order
        ErrorType.INCORRECT -> R.string.mistake_bar_incorrect
        ErrorType.OTHER -> R.string.mistake_bar_incorrect
    }
)
