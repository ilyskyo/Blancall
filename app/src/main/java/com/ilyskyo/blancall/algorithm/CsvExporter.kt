// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.repository.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 练习记录 CSV 导出器。
 *
 * 字段：ID, 文章ID, 文章标题, 模式, 总空数, 正确数, 正确率, 用时(秒),
 *       练习时间, 错误数, 错误明细
 *
 * 字段含逗号/引号/换行时按 RFC 4180 转义：整体加双引号，内部双引号转义为两个。
 */
object CsvExporter {

    private const val TAG = "CsvExporter"

    /** CSV 表头（随界面语言） */
    private fun header(context: Context): List<String> = listOf(
        context.getString(R.string.csv_header_id),
        context.getString(R.string.csv_header_article_id),
        context.getString(R.string.csv_header_article_title),
        context.getString(R.string.csv_header_mode),
        context.getString(R.string.csv_header_total_blanks),
        context.getString(R.string.csv_header_correct),
        context.getString(R.string.csv_header_accuracy),
        context.getString(R.string.csv_header_duration_sec),
        context.getString(R.string.csv_header_time),
        context.getString(R.string.csv_header_error_count),
        context.getString(R.string.csv_header_error_detail),
        // 第 12 列：稳定契约（不随界面语言变），供本程序重新导入时恢复逐空错因
        "mistakes_json",
    )

    /**
     * 导出全部练习记录为 CSV 文件并调起系统分享。
     *
     * @return 成功返回 true；失败返回 false
     */
    suspend fun exportAndShare(
        context: Context,
        records: List<PracticeRecord>,
        articleRepo: ArticleRepository
    ): Boolean = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext false
        try {
            // 文章标题预取（IO 线程，避免主线程磁盘读取）
            val titleMap = records.map { it.articleId }.distinct().associateWith { id ->
                runCatching {
                    articleRepo.getArticleById(id)?.title
                        ?: context.getString(R.string.ov_article_fallback, id)
                }.getOrElse { context.getString(R.string.ov_article_fallback, id) }
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb = StringBuilder()

            // UTF-8 BOM：保证 Excel 正确识别中文编码
            sb.append('\uFEFF')
            sb.append(header(context).joinToString(",") { escape(it) })
            sb.append("\r\n")

            for (r in records) {
                val accuracy = if (r.totalBlanks > 0)
                    "${(r.correctCount * 100 / r.totalBlanks)}%" else "—"
                val durationSec = r.duration / 1000L
                val mistakesStr = if (r.mistakes.isEmpty()) {
                    ""
                } else {
                    r.mistakes.joinToString("; ") { m ->
                        val type = context.getString(
                            when (m.errorType) {
                                "TYPO" -> R.string.mistake_bar_typo
                                "GLYPH_CONFUSED" -> R.string.mistake_bar_glyph
                                "MISSING" -> R.string.mistake_bar_missing
                                "EXTRA" -> R.string.mistake_bar_extra
                                "WRONG_ORDER" -> R.string.mistake_bar_order
                                else -> R.string.mistake_bar_incorrect
                            }
                        )
                        context.getString(R.string.csv_mistake_expected, type, m.correctAnswer) +
                            if (m.userAnswer.isNotBlank()) {
                                " · " + context.getString(R.string.csv_mistake_user, m.userAnswer)
                            } else ""
                    }
                }

                val row = listOf(
                    r.id.toString(),
                    r.articleId.toString(),
                    titleMap[r.articleId] ?: context.getString(R.string.ov_article_fallback, r.articleId),
                    // 模式写枚举名而不是本地化标签：导入端要按同一契约解析，
                    // 本地化标签会随界面语言变，换语言后旧 CSV 就再也导不回来。
                    r.mode,
                    r.totalBlanks.toString(),
                    r.correctCount.toString(),
                    accuracy,
                    durationSec.toString(),
                    dateFormat.format(Date(r.timestamp)),
                    r.mistakes.size.toString(),
                    mistakesStr,
                    DataInterchange.mistakesToCsvJson(r.mistakes),
                )
                sb.append(row.joinToString(",") { escape(it) })
                sb.append("\r\n")
            }

            // 写入缓存目录（FileProvider cache-path 已配置）
            val csvFile = File(context.cacheDir, "blancall_records_${System.currentTimeMillis()}.csv")
            csvFile.writeText(sb.toString())

            shareFile(context, csvFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出 CSV 失败", e)
            false
        }
    }

    /** 调起系统分享面板 */
    private fun shareFile(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.csv_export_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, context.getString(R.string.csv_share_title))
        )
    }

    /** CSV 字段转义：含逗号/引号/换行/CN 字符时用双引号包裹，内部引号转义为两个 */
    private fun escape(field: String): String {
        return if (field.contains(',') || field.contains('"') ||
            field.contains('\n') || field.contains('\r')
        ) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

}
