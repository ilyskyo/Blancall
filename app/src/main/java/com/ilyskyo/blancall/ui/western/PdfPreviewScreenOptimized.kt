// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.western

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.ilyskyo.blancall.ui.reader.TextContentReader
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 高考 60 篇各篇在完整 PDF（gaokao_full.pdf）中的起始页（0-based，由目录书内页码 +2 生成）。
 * 单篇 PDF 已合并为完整 PDF 以大幅减小 APK 体积，预览时按此表定位到对应篇目。
 */
private val GAOKAO_PAGE_START = intArrayOf(
    3, 5, 6, 7, 9, 11, 13, 15, 17, 20, 22, 24, 25, 28, 29, 31, 34, 36, 38, 40,
    42, 43, 44, 45, 46, 47, 48, 49, 51, 52, 54, 56, 57, 59, 60, 61, 62, 63, 66, 67,
    68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 87, 88
)

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

    // 解析为可读文件：已存在的绝对路径直接用，否则视为 assets 内资源并复制到缓存。
    // 高考 60 篇：单篇 pN.pdf 已合并为完整 PDF，按篇目序号定位起始页
    val pageNum = remember(asset) {
        Regex("p(\\d+)").find(asset)?.groupValues?.get(1)?.toIntOrNull()
    }
    val pdfFile = remember(asset, pageNum) {
        val direct = File(asset)
        if (direct.exists()) direct
        else if (pageNum != null && pageNum in 1..GAOKAO_PAGE_START.size) {
            copyAssetToCache(context, "gaokao/gaokao_full.pdf")
        } else {
            copyAssetToCache(context, asset)
        }
    }
    // 篇目起始页（完整 PDF 模式）；单篇 PDF / 普通 PDF 为 0
    val startPage = pageNum?.takeIf { it in 1..GAOKAO_PAGE_START.size }?.let { GAOKAO_PAGE_START[it - 1] } ?: 0

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
    
    // 提取文本（异步）：仅在无配套纯文字版时才用 PDFBox 从 PDF 提取。
    // 高考 60 篇已全部配置 pN.txt，且 gaokao_full.pdf 共 88 页、PDFBox 全文提取
    // 会耗尽 256MB 堆导致 OOM 闪退——有 txt 时必须直接跳过提取。
    LaunchedEffect(pdfFile) {
        if (pdfFile != null && textLoaded == null) {
            val extracted = withContext(Dispatchers.IO) {
                textExtractor.extractText(context, pdfFile)
            }
            textPages = extracted
        }
    }

    // 导入状态
    var showMenu by remember { mutableStateOf(false) }
    // 缩放状态：放大时禁用列表滚动，双指缩放 / 单指拖动
    var isZoomed by remember { mutableStateOf(false) }
    // 渲染模式：true = 纯文本排版，false = 原 PDF 图片渲染（记忆上次选择，跨篇目保持）
    var useVectorRendering by remember { mutableStateOf(AppPrefs.pdfViewMode() != "image") }

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
                                // 点完即导入：不弹模式选择、不强制进入练习（之后可在背诵列表中自行开始）
                                val finalTitle = textLoaded?.first ?: displayTitle
                                val finalAuthor = textLoaded?.second ?: ""
                                val finalText = textLoaded?.third ?: textPages.joinToString("\n\n") { it.text }
                                if (finalText.isNotBlank()) {
                                    scope.launch {
                                        val articleId = importTextToBlancall(context, finalTitle, finalText, finalAuthor)
                                        Toast.makeText(
                                            context,
                                            if (articleId > 0) "已导入背诵列表" else "导入失败，请重试",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    Toast.makeText(context, "内容为空，无法导入", Toast.LENGTH_SHORT).show()
                                }
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
            val content = textLoaded?.third ?: textPages.joinToString("\n\n") { it.text }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TextContentReader(
                    title = displayTitle,
                    content = content,
                    author = textLoaded?.second ?: "",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // 图片模式：原 PDF 渲染（完整 PDF 时定位到当前篇目起始页）
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
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
}
