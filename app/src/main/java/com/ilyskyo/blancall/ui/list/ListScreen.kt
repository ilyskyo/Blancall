// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.list

import androidx.activity.compose.PredictiveBackHandler

import android.widget.Toast
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.EbbinghausScheduler
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.PdfExporter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.DeleteConfirmDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ListScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val articleViewModel: ArticleViewModel = viewModel()
    // AI 功能开关（关闭时隐藏 AI 入口）
    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
    val articles by articleViewModel.articles.collectAsState()
    val context = LocalContext.current
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val allRecords by recordRepo.records.collectAsState()
    // 预建 文章ID→练习记录 映射，避免每个 ArticleCard 内重复 O(N×M) 过滤
    val recordsByArticle = remember(allRecords) {
        allRecords.groupBy { it.articleId }
    }
    // FSRS 记忆状态（自适应调度；无状态文章回退模板间隔）
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val fsrsStates = remember { fsrsStore.allStates() }
    var deleteTarget by remember { mutableStateOf<Article?>(null) }
    var deleteTargets by remember { mutableStateOf<List<Article>>(emptyList()) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 跨文复习多选模式（F7）
    var crossSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    // 模式选择弹窗
    var showModePicker by remember { mutableStateOf(false) }
    var pendingPracticeArticleId by remember { mutableStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    var showExportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 退出多选模式时清空选择
    fun exitCrossSelect() {
        crossSelectMode = false
        selectedIds = emptySet()
    }

    // 多选模式下拦截返回手势（侧滑/系统返回）：先退出多选回到文章列表，
    // 而非直接返回上一页；返回手势就是取消多选的唯一方式
    PredictiveBackHandler(enabled = crossSelectMode) { progress ->
        try {
            progress.collect { }
            // 手势完成 → 退出多选
            exitCrossSelect()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 手势取消 → 保持多选状态
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 底部导航模式：三页面并列，无需返回键（返回首页按底部导航即可）
            val bottomNavEnabled by AppPrefs.bottomNavEnabledFlow.collectAsState()
            if (!bottomNavEnabled) {
                // 多选模式下返回按钮退出多选；否则返回上一页
                // （触点展开方式进入时由 onBack 收起，导航方式进入时 popBackStack）
                BackButton(onClick = {
                    if (crossSelectMode) exitCrossSelect()
                    else onBack?.invoke() ?: navController.popBackStack()
                })
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = if (crossSelectMode) "已选 ${selectedIds.size} 篇" else "我的文章",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 多选模式下右上角显示"全选/取消全选"
                if (crossSelectMode) {
                    OutlinedButton(
                        onClick = {
                            selectedIds = if (selectedIds.size == articles.size) emptySet()
                            else articles.map { it.id }.toSet()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(if (selectedIds.size == articles.size) "取消全选" else "全选")
                    }
                }
                if (!crossSelectMode) {
                    // AI 历史记录入口（启用 AI 且开启「保存与 AI 的对话」时显示）
                    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
                    val aiHistoryEnabled by AppPrefs.aiHistoryEnabledFlow.collectAsState()
                    if (aiEnabled && aiHistoryEnabled) {
                        GlassButton(
                            onClick = { navController.navigate("ai_history") },
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("历史记录", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    // 与首页右上角同款磨砂玻璃风格
                    GlassButton(
                        onClick = { navController.navigate("import") },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("导入", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassButton(
                        onClick = {
                            if (articles.isEmpty()) {
                                Toast.makeText(context, "暂无文章可导出", Toast.LENGTH_SHORT).show()
                            } else {
                                showExportDialog = true
                            }
                        },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("导出", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (articles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(
                        kind = AppIconKind.Inbox,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "暂无文章",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { navController.navigate("import") },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("导入第一篇文章")
                    }
                }
            }
        } else {
            val isWide = LocalConfiguration.current.screenWidthDp >= 600
            if (isWide) {
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    gridItems(articles, key = { it.id }) { article ->
                        val records = recordsByArticle[article.id] ?: emptyList()
                        ArticleCard(
                            article = article,
                            dateFormat = dateFormat,
                            reviewStatus = EbbinghausScheduler.getReviewStatus(fsrsStates[article.id], records),
                            onClick = {
                                if (crossSelectMode) {
                                    selectedIds = if (article.id in selectedIds)
                                        selectedIds - article.id
                                    else selectedIds + article.id
                                } else {
                                    navController.navigate("reader/${article.id}")
                                }
                            },
                            onLongClick = {
                                if (!crossSelectMode) {
                                    crossSelectMode = true
                                    selectedIds = setOf(article.id)
                                } else {
                                    selectedIds = if (article.id in selectedIds)
                                        selectedIds - article.id
                                    else selectedIds + article.id
                                }
                            },
                            onPractice = {
                                    pendingPracticeArticleId = article.id
                                    showModePicker = true
                                },
                            showCheckbox = crossSelectMode,
                            isSelected = article.id in selectedIds,
                            practiceModifier = if (article.id == pendingPracticeArticleId) Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                practiceButtonRect = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                            } else Modifier
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(articles, key = { it.id }) { article ->
                        val records = recordsByArticle[article.id] ?: emptyList()
                        ArticleCard(
                            article = article,
                            dateFormat = dateFormat,
                            reviewStatus = EbbinghausScheduler.getReviewStatus(fsrsStates[article.id], records),
                            onClick = {
                                if (crossSelectMode) {
                                    selectedIds = if (article.id in selectedIds)
                                        selectedIds - article.id
                                    else selectedIds + article.id
                                } else {
                                    navController.navigate("reader/${article.id}")
                                }
                            },
                            onLongClick = {
                                if (!crossSelectMode) {
                                    crossSelectMode = true
                                    selectedIds = setOf(article.id)
                                } else {
                                    selectedIds = if (article.id in selectedIds)
                                        selectedIds - article.id
                                    else selectedIds + article.id
                                }
                            },
                            onPractice = {
                                    pendingPracticeArticleId = article.id
                                    showModePicker = true
                                },
                            showCheckbox = crossSelectMode,
                            isSelected = article.id in selectedIds,
                            practiceModifier = if (article.id == pendingPracticeArticleId) Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                practiceButtonRect = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                            } else Modifier
                        )
                    }
                }
            }
        }

        // 多选模式底部操作栏：删除选中 + 跨文复习（≥2篇）+ 取消
        if (crossSelectMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 删除选中
                OutlinedButton(
                    onClick = {
                        // 批量删除：收集选中文章后弹出确认
                        deleteTargets = articles.filter { it.id in selectedIds }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    AdaptiveButtonLabel("删除选中（${selectedIds.size}）")
                }
                // 发给 AI（启用 AI 功能时显示，≥1 篇即可）
                if (aiEnabled && selectedIds.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val ids = selectedIds.joinToString(",")
                            exitCrossSelect()
                            navController.navigate("ai?ids=$ids")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        AdaptiveButtonLabel("发给 AI")
                    }
                }
                // 跨文复习（≥2篇时显示）
                if (selectedIds.size >= 2) {
                    Button(
                        onClick = {
                            val ids = selectedIds.joinToString(",")
                            exitCrossSelect()
                            navController.navigate("cross/$ids")
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        AdaptiveButtonLabel("🔗 跨文复习")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 删除确认对话框（引用公共组件）
    deleteTarget?.let { target ->
        DeleteConfirmDialog(
            title = "确认删除",
            message = "确定要删除「${target.title}」吗？\n删除后无法恢复。",
            onConfirm = {
                articleViewModel.deleteArticle(target)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // 批量删除确认对话框
    if (deleteTargets.isNotEmpty()) {
        BlancallAlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text("确认删除") },
            text = {
                Text("确定要删除选中的 ${deleteTargets.size} 篇文章吗？\n删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargets.forEach { articleViewModel.deleteArticle(it) }
                    deleteTargets = emptyList()
                    exitCrossSelect()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptyList() }) { Text("取消") }
            }
        )
    }

    // 导出 PDF 对话框
    if (showExportDialog) {
        var selectedArticle by remember { mutableStateOf<Article?>(null) }
        // 排序结果缓存，避免每次重组新建列表
        val sortedArticles = remember(articles) { articles.sortedByDescending { it.updatedAt } }
        BlancallAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出 PDF") },
            text = {
                Column {
                    Text(
                        "选择要导出为 PDF 的文章：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        sortedArticles.forEach { article ->
                            val isSelected = selectedArticle?.id == article.id
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedArticle = article },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedArticle = article }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            article.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${article.content.length} 字符",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val article = selectedArticle
                        if (article != null) {
                            showExportDialog = false
                            scope.launch {
                                exportArticlePdf(context, article)
                            }
                        }
                    },
                    enabled = selectedArticle != null
                ) {
                    Text("导出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect,
        onDismiss = { showModePicker = false },
        onModeSelected = { mode ->
            showModePicker = false
            if (pendingPracticeArticleId > 0) {
                navController.navigate("practice/${pendingPracticeArticleId}?mode=${mode.name}")
            }
        }
    )
    }
}

@Composable
private fun ArticleCard(
    article: Article,
    dateFormat: SimpleDateFormat,
    reviewStatus: EbbinghausScheduler.ReviewStatus = EbbinghausScheduler.ReviewStatus.NOT_STARTED,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPractice: () -> Unit,
    showCheckbox: Boolean = false,
    isSelected: Boolean = false,
    practiceModifier: Modifier = Modifier
) {
    val statusText = when (val s = reviewStatus) {
        is EbbinghausScheduler.ReviewStatus.NOT_STARTED -> null
        is EbbinghausScheduler.ReviewStatus.DUE -> "待复习" to MaterialTheme.colorScheme.error
        is EbbinghausScheduler.ReviewStatus.PENDING -> "${s.daysLeft}天后复习" to MaterialTheme.colorScheme.outline
        is EbbinghausScheduler.ReviewStatus.COMPLETED -> "已掌握" to MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.dp else 0.5.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCheckbox) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (statusText != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusText.component2().copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusText.component1(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusText.component2(),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${article.content.length} 字符  ·  ${dateFormat.format(Date(article.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPractice,
                    modifier = practiceModifier.height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text("开始练习", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ========== PDF 导出逻辑 ==========

private suspend fun exportArticlePdf(context: android.content.Context, article: Article) {
    try {
        val blancall = withContext(Dispatchers.Default) {
            BlancallGenerator.generateSentenceCloze(article.content)
        }
        val config = PdfExporter.ExportConfig(
            title = article.title,
            displayText = blancall.displayText,
            blanks = blancall.blanks.map {
                PdfExporter.BlankExportInfo(it.index, it.originalText)
            },
            includeAnswer = false
        )
        val file = withContext(Dispatchers.IO) {
            PdfExporter.export(context, config, "${article.title}_试卷.pdf")
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

/**
 * 自适应单行按钮文字：空间不足时逐步缩小字号（下限 12sp，上限为默认 labelLarge），
 * 保证单行不换行且保持可读性。用于按钮宽度受限的场景，避免文字换行导致按钮大小不一。
 */
@Composable
private fun AdaptiveButtonLabel(text: String) {
    val style = MaterialTheme.typography.labelLarge
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = style.copy(fontSize = fontSize),
        onTextLayout = { result ->
            // 溢出时逐步缩小字号，但不低于 12sp，保持可读性且不超过默认 labelLarge
            if (result.hasVisualOverflow && fontSize.value > 12f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}
