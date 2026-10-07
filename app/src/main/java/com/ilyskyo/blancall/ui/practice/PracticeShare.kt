// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


internal suspend fun exportPdf(
    context: android.content.Context,
    article: com.ilyskyo.blancall.data.model.Article?,
    sentenceCloze: BlancallGenerator.SentenceClozeResult?,
    wordCloze: BlancallGenerator.WordClozeResult?,
    dictationResult: BlancallGenerator.DictationResult?,
    mode: BlancallMode,
    isCrossMode: Boolean,
    crossArticleTitles: List<String>,
    includeAnswer: Boolean
) {
    val art = article ?: return
    val displayText = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.displayText ?: art.content
        BlancallMode.WORD -> wordCloze?.displayText ?: art.content
        // 反向默写导出：展示打乱顺序的句子线索（默写练习题）
        BlancallMode.REVERSE -> dictationResult?.let { buildDictationDisplayText(it) } ?: art.content
    }
    val blanks = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.blanks?.mapIndexed { i, b ->
            PdfExporter.BlankExportInfo(i, b.originalText)
        } ?: emptyList()
        BlancallMode.WORD -> wordCloze?.blanks?.mapIndexed { i, b ->
            PdfExporter.BlankExportInfo(i, b.originalChar)
        } ?: emptyList()
        // 反向默写无按空作答，blanks 为空
        BlancallMode.REVERSE -> emptyList()
    }

    val config = PdfExporter.ExportConfig(
        title = art.title,
        displayText = displayText,
        blanks = blanks,
        includeAnswer = includeAnswer,
        subtitle = if (isCrossMode) "跨文复习 · ${crossArticleTitles.joinToString(" · ")}" else ""
    )

    try {
        val file = withContext(Dispatchers.IO) {
            PdfExporter.export(context, config)
        }
        withContext(Dispatchers.Main) {
            PdfExporter.sharePdf(context, file)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}


internal suspend fun shareNoteImage(
    context: android.content.Context,
    article: com.ilyskyo.blancall.data.model.Article?,
    sentenceCloze: BlancallGenerator.SentenceClozeResult?,
    wordCloze: BlancallGenerator.WordClozeResult?,
    dictationResult: BlancallGenerator.DictationResult?,
    mode: BlancallMode,
    isCrossMode: Boolean,
    crossArticleTitles: List<String>,
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    totalBlanks: Int
) {
    val art = article ?: return
    val displayText = when (mode) {
        BlancallMode.SENTENCE -> sentenceCloze?.displayText ?: art.content
        BlancallMode.WORD -> wordCloze?.displayText ?: art.content
        // 反向默写分享：展示打乱顺序的句子线索
        BlancallMode.REVERSE -> dictationResult?.let { buildDictationDisplayText(it) } ?: art.content
    }
    val stats = if (totalBlanks > 0 && checkResults.isNotEmpty()) {
        val correct = checkResults.values.count { it.result == AnswerChecker.Result.CORRECT }
        "正确率 ${correct * 100 / totalBlanks}%  ·  共 ${totalBlanks} 个空"
    } else ""

    val config = ShareImageGenerator.ShareConfig(
        title = art.title,
        content = displayText,
        subtitle = if (isCrossMode) "跨文复习 · ${crossArticleTitles.joinToString(" · ")}" else "",
        stats = stats
    )

    try {
        val file = withContext(Dispatchers.IO) {
            ShareImageGenerator.generate(context, config)
        }
        withContext(Dispatchers.Main) {
            ShareImageGenerator.shareImage(context, file)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
