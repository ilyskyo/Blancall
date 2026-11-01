// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.algorithm.EbbinghausScheduler
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.pressClick
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「待复习」清单页：首页那张卡点开的全量列表。
 *
 * 为什么单独一页：首页只该回答「今天先做什么」，欠账总数适合给个数字；
 * 一旦要看具体是哪几篇、哪几篇已经逾期，就该给它一个完整页面，而不是在首页展开。
 *
 * 分组口径与卡片上的两个数字**必须同源**：今日到期 = 到期日落在今天这一天；
 * 已逾期 = 到期日早于今天零点。练过没练过都算（加入计划但没练的到期日 = 加入时刻）。
 */
@Composable
fun DueListScreen(navController: NavController) {
    val context = LocalContext.current
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    // 旧版/CSV 回灌只有记录、没有 FSRS 状态的文章同样算「练过」
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    val recordsByArticle = remember(allRecords) {
        allRecords.filter { it.articleId > 0L }.groupBy { it.articleId }
    }
    val reviewTemplateId by AppPrefs.reviewTemplateFlow.collectAsState()
    val reviewTemplate = remember(reviewTemplateId) { ReviewTemplate.fromId(reviewTemplateId) }

    var states by remember { mutableStateOf(fsrsStore.allStates()) }
    var pauses by remember { mutableStateOf(fsrsStore.pausedArticles()) }
    var enrolled by remember { mutableStateOf(fsrsStore.enrolledArticles()) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        states = fsrsStore.allStates()
        pauses = fsrsStore.pausedArticles()
        enrolled = fsrsStore.enrolledArticles()
    }

    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember(nowTick, zone) { DayBucket.localDate(nowTick, zone) }

    // 到期候选：练过的看 FSRS due；只有旧记录的按模板兜底；加入计划但没练的，到期日 = 加入时刻
    val dueItems = remember(articles, states, pauses, enrolled, recordsByArticle, reviewTemplate, nowTick) {
        articles.mapNotNull { article ->
            if (article.id in pauses) return@mapNotNull null
            val state = states[article.id]
            val articleRecords = recordsByArticle[article.id].orEmpty()
            val practicedByFsrs = state != null && state.reviewCount > 0
            val practiced = practicedByFsrs || articleRecords.isNotEmpty()
            val due = when {
                practicedByFsrs -> state!!.due
                article.id in enrolled -> enrolled.getValue(article.id)
                articleRecords.isNotEmpty() ->
                    EbbinghausScheduler.nextReviewAt(reviewTemplate, articleRecords)
                        ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            if (due > nowTick) return@mapNotNull null
            DueRowItem(article, due, practiced)
        }.sortedBy { it.due }
    }

    val overdue = remember(dueItems, today) {
        dueItems.filter { DayBucket.localDate(it.due, zone).isBefore(today) }
    }
    val dueToday = remember(dueItems, today) {
        dueItems.filter { DayBucket.localDate(it.due, zone) == today }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 与任务流、任务详情同一套：内容整体让开状态栏，标题 Row 再从 12dp 起算
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.due_list_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 40.dp)
            ) {
                // hero 目标：与首页「要复习的任务」卡共用节点
                item(key = "due_hero") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heroElement(DUE_LIST_HERO_KEY, renderInOverlay = false),
                        backdrop = false,
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                stringResource(
                                    R.string.due_tasks_overdue_only,
                                    overdue.size
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (overdue.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                stringResource(R.string.due_tasks_today_only, dueToday.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (dueItems.isEmpty()) {
                    item(key = "due_empty") {
                        Text(
                            stringResource(R.string.due_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }

                dueSection(
                    key = "overdue",
                    titleRes = R.string.section_overdue,
                    items = overdue,
                    navController = navController,
                )
                dueSection(
                    key = "today",
                    titleRes = R.string.section_today,
                    items = dueToday,
                    navController = navController,
                )
            }
        }
    }
}

/** hero 共享节点的键：首页待复习卡与这里必须一致 */
internal const val DUE_LIST_HERO_KEY = "due_card"

private fun LazyListScope.dueSection(
    key: String,
    titleRes: Int,
    items: List<DueRowItem>,
    navController: NavController,
) {
    if (items.isEmpty()) return
    item(key = "hdr_$key") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                items.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
    items(items, key = { "$key/${it.article.id}" }) { row ->
        DueListRow(
            title = row.article.title,
            practiced = row.practiced,
            onClick = { navController.navigate("practice/${row.article.id}?mode=SENTENCE") },
        )
    }
}

/** 列表行需要的最小信息（避免把整个 data class 暴露出去） */
private data class DueRowItem(
    val article: Article,
    val due: Long,
    val practiced: Boolean,
)

@Composable
private fun DueListRow(
    title: String,
    practiced: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = false,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (practiced) R.string.review_action else R.string.start_action),
                style = MaterialTheme.typography.labelMedium,
                color = if (practiced) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
            AppIcon(
                AppIconKind.ChevronRight,
                modifier = Modifier.size(18.dp).padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
