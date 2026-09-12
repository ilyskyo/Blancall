// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.MarkdownText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.ShareImageGenerator
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_MENU_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.ilyskyo.blancall.ui.viewmodel.BlankCountWarning
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.PracticeViewModel
import com.ilyskyo.blancall.ui.viewmodel.SectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
