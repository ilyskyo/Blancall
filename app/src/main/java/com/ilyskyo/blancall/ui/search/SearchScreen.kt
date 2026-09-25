// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.TagChipRow
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
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
 * - 点结果进入阅读页
 */
@Composable
fun SearchScreen(navController: NavController) {
    val viewModel: ArticleViewModel = viewModel()
    val articles by viewModel.articles.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏：返回 + 搜索框 ──
            // 左右 20dp 与其它页对齐；上距 12dp：本行高由 56dp 输入框决定，
            // 使 40dp 返回圆的圆心落到与其它页一致的 40dp 基线（20..60）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
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
            if (trimmed.isNotEmpty() && results.isNotEmpty()) {
                Text(
                    "共 ${results.size} 篇",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    items(results, key = { it.id }) { article ->
                        SearchResultCard(
                            article = article,
                            tags = chipTagsByArticle[article.id].orEmpty(),
                            query = trimmed,
                            dateFmt = dateFmtDash,
                            onClick = { navController.navigate("reader/${article.id}") }
                        )
                    }
                }
            }
        }

        // ── 空态覆盖层：图标 + 提示文本整体的视觉中心＝页面中央 ──
        if (trimmed.isEmpty()) {
            EmptyHint("搜索标题、作者、正文、标签或添加日期")
        } else if (results.isEmpty()) {
            // 长搜索词截断显示，避免空态文案被撑爆
            val shown = if (trimmed.length > 10) trimmed.take(10) + "…" else trimmed
            EmptyHint("没有找到与「$shown」相关的内容")
        }
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

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .focusRequester(focusRequester),
        singleLine = true,
        leadingIcon = {
            AppIcon(
                kind = AppIconKind.SearchHint,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        kind = AppIconKind.Close,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "清除搜索"
                    )
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() })
    )
}

/** 结果卡片：标题 + 标签 chips（≤2 + 「+N」）+ 命中片段 + 添加日期 */
@Composable
private fun SearchResultCard(
    article: Article,
    tags: List<TagChipUi> = emptyList(),
    query: String,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit
) {
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .background(bgColor)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = snippet(article.title, query, maxLen = 34),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TagChipRow(tags = tags, maxChips = 2)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = snippet(article.content, query, maxLen = 80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        if (article.author.isNotBlank()) append(article.author.trim()).append("  ·  ")
                        append("添加于 ").append(dateFmt.format(Date(article.createdAt)))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
                Text(
                    text = "${article.content.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
    }
}

/** 从文本中截取命中关键词前后的上下文片段 */
private fun snippet(text: String, query: String, maxLen: Int): String {
    val flat = text.replace("\n", " ").replace(" ", "").takeIf { it.isNotBlank() } ?: return text
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