// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.import

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享 → 确认页。
 *
 * 为什么绝不静默建文章：分享来源的文本经常夹着导航栏、广告、评论区和乱码。
 * 直接入库会让文章列表变成垃圾场，而用户根本不知道是哪一次分享造成的。
 * 这里先给出可编辑的标题与正文、标出句数与长度，确认后才写入。
 * 超长文本在**确认时**截断并告知（不偷偷截断，也不让一次分享撑爆文章库）。
 */
@Composable
fun ShareImportScreen(
    navController: NavController,
    initialText: String?,
    onInitialConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeInitial = initialText.orEmpty()
    var title by rememberSaveable { mutableStateOf(defaultTitle(safeInitial)) }
    var content by rememberSaveable { mutableStateOf(safeInitial) }
    var truncatedCount by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(initialText) {
        if (!initialText.isNullOrBlank() && initialText != content) {
            title = defaultTitle(initialText)
            content = initialText
            truncatedCount = 0
        }
        onInitialConsumed()
    }
    val sentenceCount = remember(content) {
        if (content.isBlank()) 0 else SentenceSplitter.split(content).size
    }
    val tooLong = content.length > MAX_CHARS

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.share_import_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.share_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.field_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            truncatedCount = 0
                        },
                        label = { Text(stringResource(R.string.share_import_content_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        maxLines = 24,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.share_import_stats, content.length, sentenceCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (tooLong) {
                            Text(
                                stringResource(R.string.share_import_too_long, MAX_CHARS),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (truncatedCount > 0) {
                        Text(
                            stringResource(R.string.share_import_truncated, truncatedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    GlassButton(
                        onClick = {
                            if (content.isBlank() || saving) return@GlassButton
                            val overflow = (content.length - MAX_CHARS).coerceAtLeast(0)
                            val finalContent = if (overflow > 0) content.take(MAX_CHARS) else content
                            val finalTitle = title.trim().ifBlank {
                                context.getString(R.string.share_import_untitled)
                            }
                            saving = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ArticleRepository.getInstance(
                                            context.filesDir.resolve("articles.json").absolutePath
                                        ).insert(
                                            Article(title = finalTitle, content = finalContent)
                                        )
                                    }.isSuccess
                                }
                                saving = false
                                if (ok) {
                                    Toast.makeText(
                                        context,
                                        if (overflow > 0) {
                                            context.getString(R.string.share_import_truncated, overflow)
                                        } else {
                                            context.getString(R.string.share_import_saved)
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    navController.popBackStack()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.share_import_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(
                            stringResource(R.string.share_import_confirm),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val MAX_CHARS = 200_000

private fun defaultTitle(text: String): String {
    val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return if (firstLine.length <= 30) firstLine else firstLine.take(30)
}
