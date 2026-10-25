// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.CsvExporter
import com.ilyskyo.blancall.algorithm.DataInterchange
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 数据互换：人类可读 JSON 导出（记录 + 句子状态 + 标签）与 CSV 回灌。
 *
 * 这里刻意**不做 JSON 导入**：整包 ZIP 备份是唯一权威的恢复路径，
 * 再开一条半还原的导入路径，两份结果不一致时用户无法判断信谁。
 */
@Composable
fun DataInterchangeScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val articleRepo = remember { ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath) }
    val fsrsStore = remember { FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath) }
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    var busy by remember { mutableStateOf(false) }
    var importPlan by remember { mutableStateOf<ImportPlan?>(null) }

    fun shareFile(file: File, mime: String, chooser: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, chooser)
        )
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val plan = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@runCatching null
                    val parsed = DataInterchange.parseCsv(text)
                    articleRepo.awaitLoaded()
                    val articles = articleRepo.allArticles.first()
                    recordRepo.awaitLoaded()
                    val records = recordRepo.records.value
                    buildImportPlan(parsed, articles.map { it.id to it.title }, records)
                }.getOrNull()
            }
            busy = false
            if (plan == null) {
                Toast.makeText(context, context.getString(R.string.interchange_import_failed), Toast.LENGTH_LONG).show()
            } else {
                importPlan = plan
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .widthIn(max = 760.dp)
    ) {
        AmbientBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.interchange_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.interchange_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.interchange_export_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.interchange_export_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        GlassButton(
                            onClick = {
                                if (busy) return@GlassButton
                                scope.launch {
                                    busy = true
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            recordRepo.awaitLoaded()
                                            articleRepo.awaitLoaded()
                                            val records = recordRepo.records.value
                                            val articles = articleRepo.allArticles.first()
                                            fsrsStore.awaitLoaded()
                                            val json = DataInterchange.buildExportJson(
                                                records = records,
                                                articleTitles = articles.associate { it.id to it.title },
                                                sentenceStates = fsrsStore.allSentenceStates(),
                                                tags = tagStore.snapshot(),
                                            )
                                            val file = File(
                                                context.cacheDir,
                                                "blancall_export_${System.currentTimeMillis()}.json"
                                            )
                                            file.writeText(json, Charsets.UTF_8)
                                            withContext(Dispatchers.Main) {
                                                shareFile(
                                                    file,
                                                    "application/json",
                                                    context.getString(R.string.interchange_share_json)
                                                )
                                            }
                                            true
                                        }.getOrDefault(false)
                                    }
                                    busy = false
                                    if (!ok) Toast.makeText(
                                        context,
                                        context.getString(R.string.interchange_export_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                stringResource(R.string.interchange_export_json),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        GlassButton(
                            onClick = {
                                if (busy) return@GlassButton
                                scope.launch {
                                    busy = true
                                    recordRepo.awaitLoaded()
                                    val ok = CsvExporter.exportAndShare(context, recordRepo.records.value, articleRepo)
                                    busy = false
                                    if (!ok) Toast.makeText(
                                        context,
                                        context.getString(R.string.interchange_export_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                stringResource(R.string.interchange_export_csv),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.interchange_import_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.interchange_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassButton(
                        onClick = {
                            if (!busy) csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
                        },
                        enabled = !busy,
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(
                            stringResource(R.string.interchange_import_csv),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    importPlan?.let { plan ->
        BlancallAlertDialog(
            onDismissRequest = { importPlan = null },
            title = { Text(stringResource(R.string.interchange_preview_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.interchange_preview_ready, plan.ready.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (plan.noArticle > 0) {
                        Text(
                            stringResource(R.string.interchange_preview_no_article, plan.noArticle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (plan.duplicate > 0) {
                        Text(
                            stringResource(R.string.interchange_preview_duplicate, plan.duplicate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (plan.badRows > 0) {
                        Text(
                            stringResource(R.string.interchange_preview_bad, plan.badRows),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.interchange_preview_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toInsert = plan.ready
                        importPlan = null
                        scope.launch {
                            busy = true
                            val inserted = withContext(Dispatchers.IO) {
                                var n = 0
                                toInsert.forEach { r ->
                                    val ok = runCatching {
                                        recordRepo.insert(
                                            PracticeRecord(
                                                articleId = r.articleId,
                                                mode = r.mode,
                                                totalBlanks = r.totalBlanks,
                                                correctCount = r.correctCount,
                                                mistakes = r.mistakes,
                                                timestamp = r.timestamp,
                                                duration = r.durationMs,
                                            )
                                        )
                                    }.isSuccess
                                    if (ok) n++
                                }
                                n
                            }
                            busy = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.interchange_import_done, inserted),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = plan.ready.isNotEmpty() && !busy
                ) {
                    Text(stringResource(R.string.interchange_preview_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { importPlan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private data class ImportPlan(
    val ready: List<DataInterchange.ImportedRecord>,
    val noArticle: Int,
    val duplicate: Int,
    val badRows: Int,
)

private fun buildImportPlan(
    parsed: DataInterchange.CsvParse,
    articles: List<Pair<Long, String>>,
    existingRecords: List<PracticeRecord>,
): ImportPlan {
    val byId = articles.toMap()
    val byTitle = articles.groupBy { it.second }
    val existingKeys = existingRecords.map { existingKey(it) }.toHashSet()
    val ready = ArrayList<DataInterchange.ImportedRecord>()
    var noArticle = 0
    var duplicate = 0
    parsed.records.forEach { r ->
        val articleId = when {
            byId.containsKey(r.articleId) -> r.articleId
            else -> byTitle[r.articleTitle]?.singleOrNull()?.first
        }
        if (articleId == null) {
            noArticle++
            return@forEach
        }
        val candidate = r.copy(articleId = articleId)
        val key = "${candidate.articleId}|${candidate.timestamp}|${candidate.mode}|${candidate.totalBlanks}|${candidate.correctCount}"
        if (!existingKeys.add(key)) {
            duplicate++
            return@forEach
        }
        ready += candidate
    }
    return ImportPlan(ready, noArticle, duplicate, parsed.badRows)
}

private fun existingKey(r: PracticeRecord): String =
    "${r.articleId}|${r.timestamp}|${r.mode}|${r.totalBlanks}|${r.correctCount}"
