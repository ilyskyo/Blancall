// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.StatItem
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagDot
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.practice.PickerSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 标签主页：一个标签的「文章 + 学习数据 + 出口」。
 *
 * 为什么需要它：标签此前只活在两处 —— 列表顶部的筛选 chip（只能过滤）和设置里的管理页
 * （只能改名/删/拖）。用户在文章卡片、搜索结果、句子卡、统计分布上看到标签，却没有任何
 * 一个地方能回答「这个标签下我练得怎样、现在练哪篇、按这个标签复习什么」。
 * 这里把四个出口收在一个页面里：跨文复习、句子卡筛选、文章列表、管理文章。
 */
@Composable
fun TagDetailScreen(navController: NavController, tagId: Long) {
    val context = LocalContext.current
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val tagData by tagStore.data.collectAsState()
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    val records by recordRepo.records.collectAsState()
    var storeReady by remember { mutableStateOf(false) }

    // 首帧先给加载态：标签不存在与「还没读盘」必须区分，否则删标签/冷启动都会闪一下空页
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            tagStore.snapshot()
            articleRepo.awaitLoaded()
            recordRepo.awaitLoaded()
        }
        storeReady = true
    }

    val tag = tagData.tags.firstOrNull { it.id == tagId }
    val memberArticles = remember(articles, tagData, tagId) {
        articles.filter { article -> tagData.links[article.id]?.contains(tagId) == true }
    }
    val stat = remember(articles, records, tagData, tagId) {
        TagOps.aggregateTagStats(articles, records, tagData).firstOrNull { it.tag?.id == tagId }
    }

    var pendingPracticeArticleId by remember { mutableLongStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    var showModePicker by remember { mutableStateOf(false) }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                if (tag != null) {
                    TagDot(tag = TagChipUi(tag.name, tag.color, tag.id), size = 10.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = tag?.name ?: stringResource(R.string.tag),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tag != null) {
                    GlassButton(
                        onClick = { navController.navigate("tag_manager?tagId=$tagId") },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(
                            stringResource(R.string.manage_short),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            when {
                !storeReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp))
                }
                tag == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_tags),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 24.dp),
                ) {
                    item(key = "tag_summary") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    StatItem(
                                        label = stringResource(R.string.tag_detail_articles),
                                        value = pluralStringResource(
                                            R.plurals.tag_usage_count,
                                            memberArticles.size,
                                            memberArticles.size,
                                        ),
                                    )
                                    StatItem(
                                        label = stringResource(R.string.practice_count),
                                        value = "${stat?.practices ?: 0}",
                                    )
                                    StatItem(
                                        label = stringResource(R.string.overall_accuracy),
                                        value = "${((stat?.rate ?: 0f) * 100).toInt()}%",
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GlassButton(
                                        onClick = {
                                            val ids = memberArticles.map { it.id }
                                            if (ids.size >= 2) {
                                                navController.navigate("cross/${ids.joinToString(",")}")
                                            }
                                        },
                                        enabled = memberArticles.size >= 2,
                                        modifier = Modifier.weight(1f).height(40.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.reader_cross_cloze),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    GlassButton(
                                        onClick = { navController.navigate("sentence_cards?tagId=$tagId") },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.sentence_cards),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (memberArticles.size == 1) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.cross_review_too_few),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }

                    if (memberArticles.isEmpty()) {
                        item(key = "tag_empty") {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        stringResource(R.string.no_articles),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    GlassButton(
                                        onClick = { navController.navigate("tag_manager?tagId=$tagId") },
                                    ) {
                                        Text(
                                            stringResource(R.string.manage_short),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "tag_section") {
                            Text(
                                pluralStringResource(
                                    R.plurals.tag_usage_count,
                                    memberArticles.size,
                                    memberArticles.size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(memberArticles, key = { it.id }) { article ->
                            TagArticleRow(
                                article = article,
                                onOpen = { navController.navigate("reader/${article.id}") },
                                onPractice = {
                                    pendingPracticeArticleId = article.id
                                    practiceButtonRect = Rect.Zero
                                    showModePicker = true
                                },
                                practiceModifier = if (article.id == pendingPracticeArticleId) {
                                    Modifier.onGloballyPositioned { coords ->
                                        val pos = coords.positionInWindow()
                                        practiceButtonRect = Rect(
                                            pos.x,
                                            pos.y,
                                            pos.x + coords.size.width,
                                            pos.y + coords.size.height,
                                        )
                                    }
                                } else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect.takeIf { it != Rect.Zero },
        onDismiss = { showModePicker = false },
        onModeSelected = { selection ->
            showModePicker = false
            if (pendingPracticeArticleId > 0L) {
                when (selection) {
                    is PickerSelection.Base -> navController.navigate(
                        "practice/$pendingPracticeArticleId?mode=${selection.mode.name}"
                    )
                    PickerSelection.Custom -> navController.navigate(
                        "custom_cloze_list/$pendingPracticeArticleId?pick=true"
                    )
                }
            }
        },
    )
}

@Composable
private fun TagArticleRow(
    article: Article,
    onOpen: () -> Unit,
    onPractice: () -> Unit,
    practiceModifier: Modifier,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .pressClick { onOpen() }
            ) {
                Text(
                    text = article.title.ifBlank { stringResource(R.string.untitled_article) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        if (article.author.isNotBlank()) append(article.author.trim()).append(" · ")
                        append(pluralStringResource(
                            R.plurals.char_count,
                            article.content.length,
                            article.content.length,
                        ))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            GlassButton(
                onClick = onPractice,
                modifier = Modifier.height(34.dp).then(practiceModifier),
            ) {
                Text(
                    stringResource(R.string.reader_start_practice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
