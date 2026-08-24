// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.western

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.reader.TextContentReader
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 优化的 PDF 预览页：支持矢量文本渲染和无损放大
 * 
 * 主要改进：
 * 1. 文本提取：从 PDF 中提取文本层，支持无损放大
 * 2. 矢量渲染：放大时保持文字清晰度，不出现像素化
 * 3. 自适应布局：根据屏幕尺寸自动调整文本布局
 * 4. 智能缩放：支持手势缩放和自动适配
 * 5. 双模式切换：可在 PDF 渲染和文本渲染间切换
 * 
 * @param asset assets 下的 PDF 相对路径，如 "gaokao/p1.pdf"
 * @param title 可选标题（缺省时从配套 .txt 首行读取）
 */
@Composable
fun PdfPreviewScreenOptimized(
    navController: NavController,
    asset: String,
    title: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 解析为可读文件：已存在的绝对路径直接用，否则视为 assets 内资源并复制到缓存
    val pdfFile = remember(asset) {
        val direct = File(asset)
        if (direct.exists()) direct else copyAssetToCache(context, asset)
    }

    // 配套文字版（如有）：Pair(标题, 正文）
    val textLoaded = remember(asset) {
        readAssetTxt(context, asset.removeSuffix(".pdf") + ".txt")
    }
    val displayTitle = title?.takeIf { it.isNotBlank() }
        ?: textLoaded?.first
        ?: asset.substringAfterLast("/")

    // 渲染器
    val renderer = remember(pdfFile) {
        pdfFile?.let {
            PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }

    // 文本提取器
    val textExtractor = remember { PdfTextExtractor() }
    var textPages by remember { mutableStateOf<List<PdfTextExtractor.TextPage>>(emptyList()) }
    
    // 提取文本（异步）
    LaunchedEffect(pdfFile) {
        if (pdfFile != null) {
            val extracted = withContext(Dispatchers.IO) {
                textExtractor.extractText(context, pdfFile)
            }
            textPages = extracted
        }
    }

    // 导入状态
    var showMenu by remember { mutableStateOf(false) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    // 缩放状态：放大时禁用列表滚动，双指缩放 / 单指拖动
    var isZoomed by remember { mutableStateOf(false) }
    // 渲染模式：true = 纯文本排版，false = 原 PDF 图片渲染（记忆上次选择，跨篇目保持）
    var useVectorRendering by remember { mutableStateOf(AppPrefs.pdfViewMode() != "image") }

    // 从 asset 解析篇目序号（如 gaokao/p5.pdf 或缓存绝对路径中的 p5）
    val pageNum = remember(asset) {
        Regex("p(\\d+)").find(asset)?.groupValues?.get(1)?.toIntOrNull()
    }
    // 上一篇 / 下一篇的 asset 路径（保持与当前相同的路径格式）
    val prevAsset = pageNum?.takeIf { it > 1 }?.let { asset.replace(Regex("p\\d+"), "p${it - 1}") }
    val nextAsset = pageNum?.takeIf { it < 60 }?.let { asset.replace(Regex("p\\d+"), "p${it + 1}") }
    // 打开相邻篇目（替换当前预览页，避免返回栈堆积）
    fun openSiblingPdf(assetPath: String) {
        val currentId = navController.currentBackStackEntry?.destination?.id
        navController.navigate("pdf_preview?asset=${Uri.encode(assetPath)}") {
            if (currentId != null) popUpTo(currentId) { inclusive = true }
            launchSingleTop = true
        }
    }

    BackHandler(onBack = { navController.popBackStack() })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = { navController.popBackStack() })
            Spacer(Modifier.width(12.dp))
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            if (textLoaded != null || textPages.isNotEmpty()) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        M3Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GlassDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        // 视图模式切换（记忆选择，跨篇目保持）
                        if (textLoaded != null || textPages.isNotEmpty()) {
                            GlassMenuItem(
                                leadingIcon = {
                                    AppIcon(
                                        kind = if (useVectorRendering) AppIconKind.Pdf else AppIconKind.ViewAgenda,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                label = { Text(if (useVectorRendering) "查看图片模式" else "查看文本模式", fontWeight = FontWeight.Medium) },
                                onClick = {
                                    showMenu = false
                                    useVectorRendering = !useVectorRendering
                                    AppPrefs.setPdfViewMode(if (useVectorRendering) "text" else "image")
                                }
                            )
                            GlassMenuDivider()
                        }
                        GlassMenuItem(
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.Check,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("导入到背诵挖空", fontWeight = FontWeight.Medium) },
                            onClick = {
                                showMenu = false
                                // 优先使用配套文字版；没有则用 PDF 提取的文本
                                pendingTitle = textLoaded?.first ?: displayTitle
                                pendingText = textLoaded?.second ?: textPages.joinToString("\n\n") { it.text }
                                showPicker = true
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        if (renderer == null || pdfFile == null) {
            Text(
                text = "无法打开该 PDF",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(40.dp)
            )
        } else if (useVectorRendering && (textLoaded != null || textPages.isNotEmpty())) {
            // 文本模式：优先用配套纯文字版（排版最干净），否则用 PDF 提取文本
            val content = textLoaded?.second ?: textPages.joinToString("\n\n") { it.text }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TextContentReader(
                    title = displayTitle,
                    content = content,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // 图片模式：原 PDF 渲染
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isZoomed
                ) {
                    items(renderer.pageCount) { index ->
                        ZoomablePdfPage(renderer, index, isZoomed, onZoomChanged = { isZoomed = it })
                    }
                }
            }
        }

        // 底部：上一篇 / 下一篇（仅素材库篇目 pN 格式时显示）
        if (pageNum != null) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { prevAsset?.let { openSiblingPdf(it) } },
                    enabled = prevAsset != null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ) {
                    Text("‹ 上一篇")
                }
                OutlinedButton(
                    onClick = { nextAsset?.let { openSiblingPdf(it) } },
                    enabled = nextAsset != null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ) {
                    Text("下一篇 ›")
                }
            }
        }
    }

    // 导入：先选模式，选定后写入文章并进入练习
    AdaptiveModePicker(
        visible = showPicker,
        anchorRect = null,
        onDismiss = { showPicker = false },
        onModeSelected = { mode ->
            showPicker = false
            if (pendingText.isNotBlank()) {
                scope.launch {
                    val articleId = importTextToBlancall(context, pendingTitle, pendingText)
                    if (articleId > 0) {
                        navController.navigate("practice/${articleId}?mode=${mode.name}")
                    }
                }
            }
            pendingText = ""
        }
    )
}
