// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.ilyskyo.blancall.algorithm.MistakeNotebook
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.repository.InkStore
import com.ilyskyo.blancall.data.repository.OneShotCloze
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.handwriting.InkThumbnail
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 错题本：按「同一句 + 同一个字」聚合的错处清单。
 *
 * 只做筛选与重练，不是第三种练习模式：重练走一次性自定义挖空，不落盘、不进配置列表。
 * 每条三栏并排（你写的 / 应该是 / 错因），有手写墨迹优先显示影像，没墨迹才退回文字；
 * 旧记录没有逐空历史时如实标注「无法判断是否攻克」，不把未知当已解决。
 */
@Composable
fun MistakeNotebookScreen(navController: NavController, initialArticleId: Long = -1L) {
    val context = LocalContext.current
    val articleRepo = remember { ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath) }
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())
    val records by recordRepo.records.collectAsState()
    var onlyUnresolved by remember { mutableStateOf(true) }
    val articleById = remember(articles) { articles.associateBy { it.id } }

    val entries = remember(records, initialArticleId, onlyUnresolved) {
        MistakeNotebook.entries(
            records,
            articleId = initialArticleId.takeIf { it > 0L },
            onlyUnresolved = onlyUnresolved,
        )
    }
    val grouped = remember(entries) { entries.groupBy { it.articleId } }
    val navBarConn = rememberAutoHideNavBarOnScroll()

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mistake_notebook_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.notebook_entry_count, entries.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = onlyUnresolved,
                    onClick = { onlyUnresolved = true },
                    label = { Text(stringResource(R.string.notebook_unresolved)) }
                )
                FilterChip(
                    selected = !onlyUnresolved,
                    onClick = { onlyUnresolved = false },
                    label = { Text(stringResource(R.string.notebook_all)) }
                )
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.notebook_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.notebook_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        GlassButton(onClick = { navController.popBackStack() }) {
                            Text(
                                stringResource(R.string.back),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(navBarConn),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    grouped.forEach { (articleId, articleEntries) ->
                        val article = articleById[articleId]
                        item(key = "head_$articleId") {
                            ArticleGroupHeader(
                                title = article?.title ?: stringResource(R.string.ov_article_fallback, articleId),
                                count = articleEntries.size,
                                onPractice = article?.let {
                                    { startBatch(context, navController, it, articleEntries) }
                                }
                            )
                        }
                        items(articleEntries, key = { "e_${it.articleId}_${it.key}" }) { entry ->
                            MistakeEntryCard(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleGroupHeader(title: String, count: Int, onPractice: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.notebook_article_count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onPractice != null) {
            GlassButton(onClick = onPractice, modifier = Modifier.height(36.dp)) {
                Text(
                    stringResource(R.string.notebook_practice_batch),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MistakeEntryCard(entry: MistakeNotebook.Entry) {
    val context = LocalContext.current
    val inkStore = remember { InkStore.getInstance(context.filesDir.resolve("ink").absolutePath) }
    val ink by produceState<List<com.ilyskyo.blancall.data.repository.InkBatch>>(
        emptyList(), entry.lastWrongRecordId
    ) {
        value = withContext(Dispatchers.IO) {
            if (entry.lastWrongRecordId <= 0L) return@withContext emptyList()
            inkStore.load(entry.lastWrongRecordId)
                ?.blanks?.firstOrNull { it.blankIndex == entry.lastWrongBlankIndex }
                ?.batches?.takeLast(1)
                ?: emptyList()
        }
    }
    var enlarged by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        when {
                            !entry.exact -> R.string.notebook_unverified
                            entry.resolved -> R.string.notebook_resolved
                            else -> R.string.notebook_unresolved_badge
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !entry.exact -> MaterialTheme.colorScheme.onSurfaceVariant
                        entry.resolved -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.notebook_wrong_times, entry.wrongCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.notebook_yours),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (ink.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().height(52.dp).pressClick { enlarged = true }
                        ) {
                            InkThumbnail(
                                batches = ink,
                                modifier = Modifier.fillMaxSize().padding(4.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 1.8.dp
                            )
                        }
                    } else {
                        Text(
                            text = entry.lastUserAnswer.ifBlank { stringResource(R.string.notebook_blank_answer) },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Box(Modifier.width(1.dp).height(64.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.notebook_should_be),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.correctAnswer,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(Modifier.width(1.dp).height(64.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.notebook_reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = errorLabel(entry.errorType.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    MistakeNotebook.glyphPair(entry.correctAnswer, entry.lastUserAnswer)?.let { (a, b) ->
                        Text(
                            text = stringResource(R.string.notebook_glyph_pair, a.toString(), b.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (entry.sentenceText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.sentenceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (enlarged && ink.isNotEmpty()) {
        BlancallAlertDialog(
            onDismissRequest = { enlarged = false },
            title = { Text(stringResource(R.string.notebook_ink_title)) },
            text = {
                InkThumbnail(
                    batches = ink,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.dp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { enlarged = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun errorLabel(type: String): String = stringResource(
    when (type) {
        "TYPO" -> R.string.mistake_bar_typo
        "GLYPH_CONFUSED" -> R.string.mistake_bar_glyph
        "MISSING" -> R.string.mistake_bar_missing
        "EXTRA" -> R.string.mistake_bar_extra
        "WRONG_ORDER" -> R.string.mistake_bar_order
        else -> R.string.mistake_bar_incorrect
    }
)

/**
 * 「只练这一批」：把当前文章的未攻克条目映射回正文句区间，构造一次性挖空并直接开始。
 * 映射失败的条目由 [MistakeNotebook.Drill.unresolved] 计数并在 Toast 里如实说明。
 */
private fun startBatch(
    context: android.content.Context,
    navController: NavController,
    article: Article,
    entries: List<MistakeNotebook.Entry>,
) {
    val drill = MistakeNotebook.buildDrill(article.content, article.id, entries.filter { !it.resolved })
    if (drill.blanks.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.notebook_drill_empty), Toast.LENGTH_SHORT).show()
        return
    }
    val cfg = CustomClozeStore.CustomConfig(
        id = -1L,
        name = context.getString(R.string.notebook_drill_name),
        createdAt = 0L,
        blanks = drill.blanks.map { CustomClozeStore.BlankSpec(it.sentenceIndex, it.start, it.endExclusive) },
        mode = "SENTENCE",
    )
    val token = OneShotCloze.put(article.id, cfg)
    if (drill.unresolved > 0) {
        Toast.makeText(
            context,
            context.getString(R.string.notebook_drill_partial, drill.unresolved),
            Toast.LENGTH_LONG
        ).show()
    }
    navController.navigate("practice/${article.id}?mode=SENTENCE&oneShot=$token")
}

