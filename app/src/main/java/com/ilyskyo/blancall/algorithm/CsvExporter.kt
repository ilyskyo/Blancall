// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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

    /** CSV 表头 */
    private val HEADER = listOf(
        "ID", "文章ID", "文章标题", "模式", "总空数", "正确数", "正确率",
        "用时(秒)", "练习时间", "错误数", "错误明细"
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
                runCatching { articleRepo.getArticleById(id)?.title ?: "文章#$id" }
                    .getOrDefault("文章#$id")
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb = StringBuilder()

            // UTF-8 BOM：保证 Excel 正确识别中文编码
            sb.append('\uFEFF')
            sb.append(HEADER.joinToString(",") { escape(it) })
            sb.append("\r\n")

            for (r in records) {
                val accuracy = if (r.totalBlanks > 0)
                    "${(r.correctCount * 100 / r.totalBlanks)}%" else "—"
                val durationSec = r.duration / 1000L
                val mistakesStr = if (r.mistakes.isEmpty()) {
                    ""
                } else {
                    r.mistakes.joinToString("; ") { m ->
                        val type = when (m.errorType) {
                            "TYPO" -> "错字"
                            "MISSING" -> "漏填"
                            "EXTRA" -> "多填"
                            "WRONG_ORDER" -> "乱序"
                            else -> "错误"
                        }
                        "[$type] 应填「${m.correctAnswer}」" +
                            if (m.userAnswer.isNotBlank()) "·你填「${m.userAnswer}」" else ""
                    }
                }

                val row = listOf(
                    r.id.toString(),
                    r.articleId.toString(),
                    titleMap[r.articleId] ?: "文章#${r.articleId}",
                    modeLabel(r.mode),
                    r.totalBlanks.toString(),
                    r.correctCount.toString(),
                    accuracy,
                    durationSec.toString(),
                    dateFormat.format(Date(r.timestamp)),
                    r.mistakes.size.toString(),
                    mistakesStr
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
            putExtra(Intent.EXTRA_TITLE, "Blancall 练习记录")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "导出练习记录")
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

    private fun modeLabel(mode: String): String = when (mode) {
        "SENTENCE" -> "句子挖空"
        "WORD" -> "字词挖空"
        "REVERSE" -> "反向默写"
        else -> mode
    }
}
