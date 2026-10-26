// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ilyskyo.blancall.algorithm.ContentFingerprint
import com.ilyskyo.blancall.data.contentpack.ContentPackStore
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BatchImportItem
import com.ilyskyo.blancall.ui.common.BatchImportSheet
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 可插拔内容包：导入用户自己的 zip，浏览篇目，一键全部导入为文章。
 *
 * 所有危险操作（路径穿越、超大条目、坏清单）都在 [ContentPackStore] 里拒绝，
 * 本页只负责把失败原因翻译成用户看得懂的一句话 —— 不允许「导入失败」四个字了事。
 */
@Composable
fun ContentPackScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ContentPackStore.getInstance(context.filesDir) }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    var packs by remember { mutableStateOf<List<ContentPackStore.PackMeta>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ContentPackStore.Reason?>(null) }
    // 批量导入面板：当前选中的内容包 / 候选项 / 导入中
    var batchPack by remember { mutableStateOf<ContentPackStore.PackMeta?>(null) }
    var batchItems by remember { mutableStateOf<List<BatchImportItem>>(emptyList()) }
    var batchImporting by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ContentPackStore.PackMeta?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewing by remember { mutableStateOf<Pair<ContentPackStore.PackMeta, Int>?>(null) }
    var viewingText by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val navBarConn = rememberAutoHideNavBarOnScroll()

    fun refresh() {
        scope.launch {
            packs = withContext(Dispatchers.IO) { store.listPacks() }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            val displayName = queryDisplayName(context, uri) ?: "content-pack.zip"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        store.import(it, displayName)
                    } ?: ContentPackStore.ImportResult.Failure(ContentPackStore.Reason.READ_FAILED)
                }.getOrElse {
                    ContentPackStore.ImportResult.Failure(ContentPackStore.Reason.READ_FAILED)
                }
            }
            busy = false
            when (result) {
                is ContentPackStore.ImportResult.Success -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.content_pack_imported, result.pack.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
                is ContentPackStore.ImportResult.Failure -> error = result.reason
            }
        }
    }

    LaunchedEffect(viewing) {
        val target = viewing ?: return@LaunchedEffect
        viewingText = withContext(Dispatchers.IO) {
            store.readEntry(target.first.id, target.second)
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.content_pack_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                GlassButton(
                    onClick = {
                        if (!busy) picker.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        if (busy) stringResource(R.string.content_pack_importing)
                        else stringResource(R.string.content_pack_import),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.content_pack_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.content_pack_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
            error?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Text(
                    contentPackError(reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            if (packs.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(navBarConn),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    items(packs, key = { it.id }) { pack ->
                        val isExpanded = pack.id in expanded
                        val shown = if (isExpanded) pack.entries else pack.entries.take(PREVIEW_COUNT)
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            pack.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            stringResource(
                                                R.string.content_pack_meta,
                                                pack.entries.size,
                                                dateFormat.format(Date(pack.importedAt))
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { deleteTarget = pack }) {
                                        Text(
                                            stringResource(R.string.content_pack_delete),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GlassButton(
                                        onClick = {
                                            scope.launch {
                                                val items = withContext(Dispatchers.IO) {
                                                    buildPackBatchItems(store, articleRepo, pack)
                                                }
                                                batchItems = items
                                                batchPack = pack
                                            }
                                        },
                                        enabled = !batchImporting,
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.library_batch_import),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    GlassButton(
                                        onClick = {
                                            expanded = if (isExpanded) expanded - pack.id else expanded + pack.id
                                        },
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            if (isExpanded) stringResource(R.string.content_pack_collapse)
                                            else stringResource(R.string.content_pack_view_entries),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                shown.forEachIndexed { index, entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pressClick { viewing = pack to index }
                                            .padding(vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${index + 1}.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(26.dp)
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                entry.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.content_pack_entry_meta,
                                                    entry.author.ifBlank { stringResource(R.string.content_pack_no_author) },
                                                    entry.chars
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                if (!isExpanded && pack.entries.size > PREVIEW_COUNT) {
                                    TextButton(onClick = { expanded = expanded + pack.id }) {
                                        Text(
                                            stringResource(
                                                R.string.content_pack_show_all,
                                                pack.entries.size
                                            ),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (packs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.content_pack_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.content_pack_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // 批量导入面板：可全选 / 单独选 / 多选；已导入篇目灰色锁定
    AnimatedOverlayPresence(target = batchPack) { pack ->
        BatchImportSheet(
            title = stringResource(R.string.library_batch_import),
            subtitle = pack.name,
            items = batchItems,
            importing = batchImporting,
            onDismissRequest = { if (!batchImporting) batchPack = null },
            onConfirm = { keys ->
                batchImporting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        importPackSelection(
                            articleRepo,
                            store,
                            pack,
                            keys.mapNotNull { it.toIntOrNull() }.toSet()
                        )
                    }
                    batchImporting = false
                    batchPack = null
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.content_pack_import_result,
                            result.first,
                            result.second
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    deleteTarget?.let { target ->
        BlancallAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.content_pack_delete_title)) },
            text = { Text(stringResource(R.string.content_pack_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deletePack(target.id) }
                        deleteTarget = null
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    viewing?.let { (pack, index) ->
        BlancallAlertDialog(
            onDismissRequest = {
                viewing = null
                viewingText = null
            },
            title = { Text(pack.entries.getOrNull(index)?.title.orEmpty()) },
            text = {
                val text = viewingText
                if (text == null) {
                    Text(stringResource(R.string.content_pack_loading))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewing = null
                    viewingText = null
                }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

private const val PREVIEW_COUNT = 10

/**
 * 构建内容包的批量导入候选项。
 * 已存在相同正文的文章（按 MD5 判定）标记为已导入，面板里显示灰色锁定勾选。
 *
 * internal：素材库首页的用户内容包卡片也复用它（两处入口保持一致）。
 */
internal suspend fun buildPackBatchItems(
    store: ContentPackStore,
    repo: ArticleRepository,
    pack: ContentPackStore.PackMeta,
): List<BatchImportItem> {
    val existingHashes = runCatching {
        repo.awaitLoaded()
        repo.allArticles.first().mapTo(HashSet()) { ContentFingerprint.md5Hex(it.content) }
    }.getOrElse { HashSet() }
    return pack.entries.mapIndexedNotNull { index, meta ->
        val body = store.readEntry(pack.id, index) ?: return@mapIndexedNotNull null
        BatchImportItem(
            key = index.toString(),
            title = meta.title,
            subtitle = meta.author,
            imported = ContentFingerprint.md5Hex(body) in existingHashes,
        )
    }
}

/** 按篇目索引导入所选项；重复内容跳过。返回（导入数, 跳过数）。 */
internal suspend fun importPackSelection(
    repo: ArticleRepository,
    store: ContentPackStore,
    pack: ContentPackStore.PackMeta,
    indices: Set<Int>,
): Pair<Int, Int> {
    val existingHashes = runCatching {
        repo.awaitLoaded()
        repo.allArticles.first().mapTo(HashSet()) { ContentFingerprint.md5Hex(it.content) }
    }.getOrElse { HashSet() }
    var imported = 0
    var skipped = 0
    indices.sorted().forEach { index ->
        val meta = pack.entries.getOrNull(index)
        val body = store.readEntry(pack.id, index)
        if (meta == null || body == null) {
            skipped++
        } else if (!existingHashes.add(ContentFingerprint.md5Hex(body))) {
            skipped++
        } else {
            val ok = runCatching {
                repo.insert(Article(title = meta.title, content = body, author = meta.author))
            }.isSuccess
            if (ok) imported++ else skipped++
        }
    }
    return imported to skipped
}

@Composable
private fun contentPackError(reason: ContentPackStore.Reason): String = stringResource(
    when (reason) {
        ContentPackStore.Reason.EMPTY -> R.string.content_pack_error_empty
        ContentPackStore.Reason.BAD_PATH -> R.string.content_pack_error_path
        ContentPackStore.Reason.TOO_MANY_ENTRIES -> R.string.content_pack_error_count
        ContentPackStore.Reason.TOO_LARGE -> R.string.content_pack_error_size
        ContentPackStore.Reason.BAD_MANIFEST -> R.string.content_pack_error_manifest
        ContentPackStore.Reason.ENCRYPTED -> R.string.content_pack_error_encrypted
        ContentPackStore.Reason.READ_FAILED -> R.string.content_pack_error_read
    }
)

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
