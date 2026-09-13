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
import com.ilyskyo.blancall.util.FileTextExtractor
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
    // 仅 PDF 走原版渲染（PdfRenderer）；其余可预览格式（DOCX / DOC / TXT / EPUB / HTML / RTF / MD）
    // 统一提取全文作文本预览——本地无版式渲染能力，内容完整可读
    val isPdf = remember(asset) { asset.lowercase().endsWith(".pdf") }
    // 异步加载：assets 复制（完整 PDF 可达数 MB）+ txt 读取 + PdfRenderer 打开全部切 IO 线程，
    // 避免进屏首帧在主线程同步做磁盘 IO 导致卡顿。加载中显示占位空白，失败才显示错误文案。
    var pdfFile by remember(asset) { mutableStateOf<File?>(null) }
    var textLoaded by remember(asset) { mutableStateOf<Triple<String, String, String>?>(null) }
    var renderer by remember(asset) { mutableStateOf<PdfRenderer?>(null) }
    // 总页数：打开成功后一次性读入。组合/绘制期不再直接访问 renderer ——
    // 此前在 items(renderer.pageCount) 里组合期读取，渲染器被关闭后重组即抛
    // IllegalStateException: Document already closed（用户反馈「预览 PDF 闪退」）
    var pageCount by remember(asset) { mutableStateOf(0) }
    // PDF 文件描述符：PdfRenderer.close() 不负责关闭它，随渲染器一并回收，避免句柄泄漏
    var pdfPfd by remember(asset) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var loadFailed by remember(asset) { mutableStateOf(false) }

    // 篇目起始页（完整 PDF 模式）；单篇 PDF / 普通 PDF 为 0
    val startPage = pageNum?.takeIf { it in 1..GAOKAO_PAGE_START.size }?.let { GAOKAO_PAGE_START[it - 1] } ?: 0
    val displayTitle = title?.takeIf { it.isNotBlank() }
        ?: textLoaded?.first
        ?: asset.substringAfterLast("/")

    LaunchedEffect(asset, pageNum) {
        if (!isPdf) {
            // 非 PDF（Word / EPUB / HTML / RTF / MD / TXT）：提取全文作文本预览
            val (f, extracted) = withContext(Dispatchers.IO) {
                val direct = File(asset)
                val file = if (direct.exists()) direct else copyAssetToCache(context, asset)
                val text = file?.let {
                    runCatching {
                        // 传文件名提示：file:// URI 查不到 DISPLAY_NAME，不传会把 docx/doc 错判成纯文本
                        FileTextExtractor.extractTextWithInfo(context, Uri.fromFile(it), it.name).text
                    }.getOrNull()
                }
                file to text
            }
            pdfFile = f
            textLoaded = extracted?.takeIf { it.isNotBlank() }
                ?.let { Triple(f?.nameWithoutExtension ?: "", "", it) }
            renderer = null
            pageCount = 0
            loadFailed = f == null || textLoaded == null
            return@LaunchedEffect
        }
        val (file, txt) = withContext(Dispatchers.IO) {
            val direct = File(asset)
            val f = if (direct.exists()) direct
            else if (pageNum != null && pageNum in 1..GAOKAO_PAGE_START.size) {
                copyAssetToCache(context, "gaokao/gaokao_full.pdf")
            } else {
                copyAssetToCache(context, asset)
            }
            val t = readAssetTxt(context, asset.removeSuffix(".pdf") + ".txt")
            f to t
        }
        textLoaded = txt
        pdfFile = file
        val opened: Pair<ParcelFileDescriptor?, PdfRenderer?> = file?.let {
            withContext(Dispatchers.IO) {
                try {
                    val p = ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = try {
                        PdfRenderer(p)
                    } catch (_: Exception) {
                        p.close()
                        null
                    }
                    if (r == null) null to null else p to r
                } catch (_: Exception) {
                    null to null
                }
            }
        } ?: (null to null)
        pdfPfd = opened.first
        renderer = opened.second
        // 刚打开时一次性读取页数（此后组合/绘制期不再触碰 renderer）
        pageCount = opened.second?.let { r ->
            withContext(Dispatchers.IO) { runCatching { r.pageCount }.getOrDefault(0) }
        } ?: 0
        loadFailed = file == null || renderer == null
    }
    // **防闪退**：onDispose 里读 renderer（state 委托）得到的是「最新值」而非本 effect 创建时的值 ——
    // renderer 从 null 变为实例时 key 变化，旧 effect 的 onDispose 会把刚打开的实例 close 掉，
    // 随后的组合访问（pageCount/items）抛 IllegalStateException: Document already closed。
    // 先取快照，保证只关闭「本 effect 对应的那一个」实例；PFD 一并回收（PdfRenderer.close 不负责关它）。
    DisposableEffect(renderer) {
        val r = renderer
        val p = pdfPfd
        onDispose {
            r?.close()
            p?.close()
        }
    }

    // 文本提取器
    val textExtractor = remember { PdfTextExtractor() }
    var textPages by remember { mutableStateOf<List<PdfTextExtractor.TextPage>>(emptyList()) }
    
    // 提取文本（异步）：仅在无配套纯文字版时才用 PDFBox 从 PDF 提取。
    // 高考 60 篇已全部配置 pN.txt，且 gaokao_full.pdf 共 88 页、PDFBox 全文提取
    // 会耗尽 256MB 堆导致 OOM 闪退——有 txt 时必须直接跳过提取。
    LaunchedEffect(pdfFile) {
        val pf = pdfFile
        if (pf != null && textLoaded == null) {
            val extracted = withContext(Dispatchers.IO) {
                textExtractor.extractText(context, pf)
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
                        // 视图模式切换（记忆选择，跨篇目保持）：仅 PDF 有图片/文本两种模式
                        if (isPdf && (textLoaded != null || textPages.isNotEmpty())) {
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
                                scope.launch {
                                    val finalTitle = textLoaded?.first ?: displayTitle
                                    val finalAuthor = textLoaded?.second ?: ""
                                    // 无配套文字版时从提取文本拼接（可能很大），切 IO 线程
                                    val finalText = textLoaded?.third
                                        ?: withContext(Dispatchers.IO) { textPages.joinToString("\n\n") { it.text } }
                                    if (finalText.isNotBlank()) {
                                        val articleId = importTextToBlancall(context, finalTitle, finalText, finalAuthor)
                                        Toast.makeText(
                                            context,
                                            if (articleId > 0) "已导入背诵列表" else "导入失败，请重试",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(context, "内容为空，无法导入", Toast.LENGTH_SHORT).show()
                                    }
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

        if (loadFailed) {
            Text(
                text = if (isPdf) "无法打开该 PDF" else "无法预览该文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(40.dp)
            )
        } else if ((textLoaded != null || textPages.isNotEmpty()) && (useVectorRendering || !isPdf)) {
            // 文本模式：PDF 按用户选择的模式；其余格式（Word/EPUB/HTML/RTF/MD/TXT）恒为文本预览
            val content = textLoaded?.third ?: textPages.joinToString("\n\n") { it.text }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TextContentReader(
                    title = displayTitle,
                    content = content,
                    author = textLoaded?.second ?: "",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (renderer == null || pdfFile == null) {
            // 加载中：占位空白，避免错误提示闪烁、底部按钮跳位
            Box(Modifier.weight(1f).fillMaxWidth())
        } else {
            // 图片模式：原 PDF 渲染（完整 PDF 时定位到当前篇目起始页）
            // 此分支 renderer 必非 null（上方条件已挡），取局部 val 以通过编译器 smart cast
            val r = renderer
            if (r != null && pageCount > 0) {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !isZoomed
                    ) {
                        items(pageCount) { index ->
                            ZoomablePdfPage(r, index, pageCount, isZoomed, onZoomChanged = { isZoomed = it })
                        }
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
