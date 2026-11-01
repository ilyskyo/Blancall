// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.animateListItem
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.StateSwap
import com.ilyskyo.blancall.ui.common.TagChipRow
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.practice.PickerSelection
import com.ilyskyo.blancall.ui.tag.TagPickerSheet
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索页：在全部文章中检索【标题 / 作者 / 正文 / 标签 / 添加日期】。
 *
 * - 实时过滤：输入即搜
 * - 命中样式：标题命中高亮；正文命中显示含关键词的上下文片段；标签命中就列出该文章
 * - 添加日期命中（如 "2026-08-23" / "2026/8/23" / 年份）也会列出对应文章
 * - 点结果进入文章详情页
 */
@Composable
fun SearchScreen(navController: NavController) {
    val viewModel: ArticleViewModel = viewModel()
    val articles by viewModel.articles.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // ── 文章标签：标签名进命中维度 + 结果卡展示 chips ──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val chipTagsByArticle = remember(tagData) {
        TagOps.tagsByArticle(tagData).mapValues { (_, list) -> list.toChipUis() }
    }

    val dateFmtDash = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dateFmtSlash = remember { SimpleDateFormat("yyyy/M/d", Locale.getDefault()) }

    val trimmed = query.trim().lowercase(Locale.getDefault())
    // 命中过滤：标题 / 作者 / 正文 / 标签名 / 添加日期（两种日期格式都匹配）
    val results = remember(articles, trimmed, chipTagsByArticle) {
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            articles.filter { art ->
                val authorHit = art.author.contains(trimmed, ignoreCase = true)
                val titleHit = art.title.contains(trimmed, ignoreCase = true)
                val bodyHit = art.content.contains(trimmed, ignoreCase = true)
                val tagHit = chipTagsByArticle[art.id]?.any { it.name.contains(trimmed, ignoreCase = true) } == true
                val dateDash = dateFmtDash.format(Date(art.createdAt)).lowercase()
                val dateSlash = dateFmtSlash.format(Date(art.createdAt)).lowercase()
                val dateHit = dateDash.contains(trimmed) || dateSlash.contains(trimmed)
                titleHit || authorHit || bodyHit || tagHit || dateHit
            }.sortedByDescending { it.updatedAt }
        }
    }
    // 句子级命中：文章结果只回答「在哪篇」，用户搜一句话时真正想找的是那一句。
    // 句子切分只随文章表重算一次；每次输入只做包含判断。
    val articleSentences = remember(articles) {
        articles.map { article ->
            article to SentenceSplitter.splitWithPositions(article.content)
        }
    }
    val sentenceHits = remember(articleSentences, trimmed) {
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            articleSentences.flatMap { (article, sentences) ->
                sentences.mapIndexedNotNull { index, sentence ->
                    if (sentence.text.contains(trimmed, ignoreCase = true)) {
                        SentenceHit(article, index, sentence.text)
                    } else null
                }
            }.take(MAX_SENTENCE_HITS)
        }
    }

    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
    var pendingPracticeArticleId by remember { mutableLongStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    var showModePicker by remember { mutableStateOf(false) }
    var tagTarget by remember { mutableStateOf<Article?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏：返回 + 搜索框 ──
            // 左右 20dp 与其它页对齐；上距 12dp = 全应用顶栏基线。
            // 返回键与搜索框垂直居中：搜索框会随字号/设备变高，钉死 Top 会让返回键越显偏高。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(
                    onClick = { navController.popBackStack() },
                )
                Spacer(Modifier.width(12.dp))
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = { /* 已实时过滤，无需额外动作 */ },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ── 结果区（空态不在此占位，见下方覆盖层） ──
            if (trimmed.isNotEmpty() && (results.isNotEmpty() || sentenceHits.isNotEmpty())) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 6.dp,
                    )
                ) {
                    if (results.isNotEmpty()) {
                        item(key = "article_header") {
                            Text(
                                pluralStringResource(
                                    com.ilyskyo.blancall.R.plurals.search_result_count,
                                    results.size,
                                    results.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                        items(results, key = { "a_${it.id}" }) { article ->
                            // 结果集在每次输入后整体增减：改造前 item 出现/消失都是瞬时的，
                            // 列表像被抖了一下。入场淡入 tween + 换位弹簧 + 退场淡出（词表见 animateListItem）。
                            Box(modifier = animateListItem()) {
                                SearchResultCard(
                                    article = article,
                                    tags = chipTagsByArticle[article.id].orEmpty(),
                                    query = trimmed,
                                    dateFmt = dateFmtDash,
                                    onClick = { navController.navigate("reader/${article.id}") },
                                    onTagClick = { tagId -> navController.navigate("tag/$tagId") },
                                    onPractice = {
                                        // 搜索框还聚焦时弹模式选择，键盘会盖住下半张选项卡
                                        focusManager.clearFocus()
                                        pendingPracticeArticleId = article.id
                                        practiceButtonRect = Rect.Zero
                                        showModePicker = true
                                    },
                                    onAi = if (aiEnabled) {
                                        { navController.navigate("ai?ids=${article.id}") }
                                    } else null,
                                    onTag = {
                                        focusManager.clearFocus()
                                        tagTarget = article
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
                    if (sentenceHits.isNotEmpty()) {
                        item(key = "sentence_header") {
                            Text(
                                stringResource(com.ilyskyo.blancall.R.string.search_sentences),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = if (results.isEmpty()) 4.dp else 12.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(
                            sentenceHits,
                            key = { "s_${it.article.id}_${it.sentenceIndex}" },
                        ) { hit ->
                            Box(modifier = animateListItem()) {
                                SentenceHitCard(
                                    hit = hit,
                                    query = trimmed,
                                    onOpen = { navController.navigate("reader/${hit.article.id}") },
                                    onPractice = {
                                        navController.navigate(
                                            "practice/${hit.article.id}?mode=SENTENCE&focusSentence=${hit.sentenceIndex}"
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 空态覆盖层：图标 + 提示文本整体的视觉中心＝页面中央 ──
        // 提示与「有结果」两副面孔原先由条件直接决定渲不渲染，切换是硬切
        StateSwap(
            targetState = when {
                trimmed.isEmpty() -> 0
                results.isEmpty() && sentenceHits.isEmpty() -> 1
                else -> 2
            },
            label = "searchHintSwap",
        ) {
            when (it) {
                0 -> EmptyHint(stringResource(com.ilyskyo.blancall.R.string.search_hint))
                1 -> {
                    // 长搜索词截断显示，避免空态文案被撑爆
                    val shown = if (trimmed.length > 10) trimmed.take(10) + "…" else trimmed
                    EmptyHint(stringResource(com.ilyskyo.blancall.R.string.search_no_results, shown))
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

    AnimatedOverlayPresence(target = tagTarget) { target ->
        TagPickerSheet(
            targets = listOf(target),
            onDismiss = { tagTarget = null },
            onApplied = { tagTarget = null },
        )
    }
}

/** 顶部可聚焦搜索输入框（磨砂质感） */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val container = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 高度与首页搜索栏一致（46dp）：OutlinedTextField 的最小高度约 56dp，在顶栏里显得「肥」。
    // 自绘容器还顺手解决了「页面底色与卡片同色时输入框整个消失」的问题（有描边）。
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.height(46.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                kind = AppIconKind.SearchHint,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) { innerTextField() }
                }
            )
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .pressClick { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        kind = AppIconKind.Close,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = stringResource(com.ilyskyo.blancall.R.string.clear_search)
                    )
                }
            }
        }
    }
}

/** 结果卡片：标题 + 标签 chips（≤2 + 「+N」）+ 命中片段 + 添加日期 */
@Composable
private fun SearchResultCard(
    article: Article,
    tags: List<TagChipUi> = emptyList(),
    query: String,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit,
    onTagClick: ((Long) -> Unit)? = null,
    onPractice: () -> Unit,
    onAi: (() -> Unit)? = null,
    onTag: () -> Unit,
    practiceModifier: Modifier = Modifier,
) {
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .heroElement("article/${article.id}")
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // 卡片不再整块可点：父级 click 会和「开始练习 / 打标签」抢事件，
            // 真机上表现为点子按钮却打开了文章详情页。让标题与摘要各自承担「打开原文」。
            Text(
                text = snippet(article.title, query, maxLen = 34),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick { onClick() },
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TagChipRow(tags = tags, maxChips = 2, onTagClick = onTagClick)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = snippet(article.content, query, maxLen = 80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick { onClick() },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // stringResource 不能在 buildString 的非组合 lambda 里调，先取好整句
                val addedOnLine = stringResource(com.ilyskyo.blancall.R.string.added_on, dateFmt.format(Date(article.createdAt)))
                Text(
                    text = buildString {
                        if (article.author.isNotBlank()) append(article.author.trim()).append("  ·  ")
                        append(addedOnLine)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
                Text(
                    text = pluralStringResource(com.ilyskyo.blancall.R.plurals.char_count_short, article.content.length, article.content.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPractice,
                    modifier = Modifier.height(30.dp).then(practiceModifier),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.reader_start_practice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                onAi?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.height(30.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                TextButton(
                    onClick = onTag,
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.add_tags),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 句子级命中：用户搜一句话时，直接给出那一句和「练这一句」。 */
private data class SentenceHit(
    val article: Article,
    val sentenceIndex: Int,
    val text: String,
)

private const val MAX_SENTENCE_HITS = 40

@Composable
private fun SentenceHitCard(
    hit: SentenceHit,
    query: String,
    onOpen: () -> Unit,
    onPractice: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "《${hit.article.title.ifBlank { stringResource(com.ilyskyo.blancall.R.string.untitled_article) }}》",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick { onOpen() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = snippet(hit.text, query, maxLen = 110),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClick { onOpen() },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onPractice,
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.search_practice_sentence),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 从文本中截取命中关键词前后的上下文片段 */
private fun snippet(text: String, query: String, maxLen: Int): String {
    // 只压平换行/连续空白，不能像旧版那样把所有空格删掉：
    // 中文没有词间空格看不出问题，英文会变成 "Learningisnot..." 一整串。
    val flat = text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() } ?: return text
    val q = query.takeIf { it.isNotBlank() } ?: return flat.take(maxLen)
    val idx = flat.indexOf(q, ignoreCase = true).let { pos ->
        if (pos >= 0) pos else flat.lowercase(Locale.getDefault()).indexOf(q)
    }
    if (idx < 0) return flat.take(maxLen)
    val start = (idx - maxLen / 3).coerceAtLeast(0)
    val end = (start + maxLen).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return prefix + flat.substring(start, end) + suffix
}

/** 空态提示 */
@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppIcon(
            kind = AppIconKind.SearchHint,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}