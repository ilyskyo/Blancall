// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.reader.TextContentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 优化版 PDF 预览页：支持无损放大和自适应布局
 * 
 * 主要优化：
 * 1. 集成 PDF 文本提取器，支持矢量文本渲染
 * 2. 无损放大：文字放大时保持清晰度，不再发糊
 * 3. 自适应布局：根据屏幕尺寸自动调整文本布局
 * 4. 智能约束：确保文字不会超出屏幕边界
 * 5. 双模式支持：原始PDF渲染 + 矢量文本渲染
 *
 * @param asset assets 下的 PDF 相对路径，如 "gaokao/p1.pdf"
 * @param title 可选标题（缺省时从配套 .txt 首行读取）
 */
@Composable
fun OptimizedPdfPreviewScreen(
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

    // 配套文字版（如有）：Pair(标题, 正文)
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
    
    // 尝试提取文本
    LaunchedEffect(pdfFile) {
        if (pdfFile != null) {
            textPages = textExtractor.extractText(context, pdfFile)
        }
    }

    // 导入状态
    var showMenu by remember { mutableStateOf(false) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    
    // 渲染模式：true=矢量文本渲染，false=原始PDF渲染
    var useVectorRendering by remember { mutableStateOf(true) }
    
    // 缩放状态
    var isZoomed by remember { mutableStateOf(false) }
    var currentScale by remember { mutableStateOf(1f) }
    var currentOffset by remember { mutableStateOf(Offset.Zero) }

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
            
            // 渲染模式切换按钮
            if (textPages.isNotEmpty()) {
                IconButton(
                    onClick = { useVectorRendering = !useVectorRendering }
                ) {
                    M3Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = if (useVectorRendering) "切换到PDF渲染" else "切换到矢量渲染",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
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
        } else {
            // 根据渲染模式选择不同的显示方式
            if (useVectorRendering && (textLoaded != null || textPages.isNotEmpty())) {
                // 矢量模式：优先用配套纯文字版（排版最干净），否则用 PDF 提取文本
                val content = textLoaded?.second ?: textPages.joinToString("\n\n") { it.text }
                TextContentReader(
                    title = displayTitle,
                    content = content,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 原始PDF渲染模式（带优化）
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isZoomed
                ) {
                    items(renderer.pageCount) { index ->
                        OptimizedZoomablePdfPage(
                            renderer = renderer,
                            index = index,
                            isZoomed = isZoomed,
                            currentScale = currentScale,
                            currentOffset = currentOffset,
                            onZoomChanged = { zoomed -> isZoomed = zoomed },
                            onScaleChanged = { scale -> currentScale = scale },
                            onOffsetChanged = { offset -> currentOffset = offset }
                        )
                    }
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
            // 先捕获值再启动协程：pendingText 随后会被重置，协程延迟执行时读取会拿到空串
            val finalTitle = pendingTitle
            val finalText = pendingText
            if (finalText.isNotBlank()) {
                scope.launch {
                    val articleId = importTextToBlancall(context, finalTitle, finalText)
                    if (articleId > 0) {
                        navController.navigate("practice/${articleId}?mode=${mode.name}")
                    }
                }
            }
            pendingText = ""
        }
    )
}

/**
 * 优化版可缩放PDF页面：支持无损放大和智能约束
 */
@Composable
private fun OptimizedZoomablePdfPage(
    renderer: PdfRenderer,
    index: Int,
    isZoomed: Boolean,
    currentScale: Float,
    currentOffset: Offset,
    onZoomChanged: (Boolean) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit
) {
    val pageCount = renderer.pageCount
    var scale by remember { mutableStateOf(currentScale) }
    var offset by remember { mutableStateOf(currentOffset) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    
    // 使用高分辨率渲染：支持无损放大
    val renderScale = maxOf(1f, scale)
    val bitmap = remember(renderer, index, renderScale) {
        renderPdfPage(renderer, index, pageCount, renderScale)
    }
    
    bitmap?.let { bmp ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { 
                    boxSize = it
                    // 根据容器大小调整偏移量，确保内容不超出屏幕
                    val newOffset = clampOffset(offset, boxSize, scale)
                    if (newOffset != offset) {
                        offset = newOffset
                        onOffsetChanged(newOffset)
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // 双指缩放 + 平移（以双指中心为基准）
                                val c0 = pressed[0]
                                val c1 = pressed[1]
                                val prevDist = (c0.previousPosition - c1.previousPosition).getDistance()
                                val curDist = (c0.position - c1.position).getDistance()
                                val zoom = if (prevDist > 0f) curDist / prevDist else 1f
                                val midPrev = (c0.previousPosition + c1.previousPosition) / 2f
                                val midCur = (c0.position + c1.position) / 2f
                                val newScale = (scale * zoom).coerceIn(0.5f, 6f) // 扩大缩放范围
                                scale = newScale
                                offset = if (newScale <= 1f) Offset.Zero
                                else clampOffset(offset + (midCur - midPrev), boxSize, scale)
                                onScaleChanged(newScale)
                                onZoomChanged(newScale > 1f)
                                pressed.forEach { if (it.positionChanged()) it.consume() }
                            } else if (scale > 1f) {
                                // 放大后单指拖动平移
                                val c = pressed[0]
                                offset = clampOffset(offset + (c.position - c.previousPosition), boxSize, scale)
                                if (c.positionChanged()) c.consume()
                            }
                            // 未放大时单指不消费，交给列表滚动
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 高分辨率PDF页面渲染
 */
private fun renderPdfPage(
    renderer: PdfRenderer,
    index: Int,
    pageCount: Int,
    scale: Float
): Bitmap? {
    return try {
        if (index < pageCount) {
            val page = renderer.openPage(index)
            val w = page.width.toFloat() * scale
            val h = page.height.toFloat() * scale
            val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            val m = Matrix().apply { postScale(scale, scale) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bmp
        } else null
    } catch (_: Exception) { null }
}

/**
 * 智能约束偏移量，确保放大后内容仍在可视范围内、文字不跑到屏幕外
 */
private fun clampOffset(offset: Offset, box: IntSize, scale: Float): Offset {
    if (box == IntSize.Zero) return offset
    
    // 计算最大允许偏移量，确保内容不会超出屏幕边界
    val scaledWidth = box.width * scale
    val scaledHeight = box.height * scale
    
    val maxX = maxOf(0f, (scaledWidth - box.width) / 2f)
    val maxY = maxOf(0f, (scaledHeight - box.height) / 2f)
    
    // 限制偏移量范围，确保内容始终在可视区域内
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

/**
 * 把 assets 里的 PDF 复制到缓存目录，返回文件（失败返回 null）
 */
private fun copyAssetToCache(context: android.content.Context, asset: String): File? {
    return try {
        val name = asset.substringAfterLast("/")
        val out = File(context.cacheDir, "pdfview/$name")
        out.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out
    } catch (_: Exception) { null }
}

/**
 * 读取配套文字版：首行为标题，其余为正文。不存在返回 null
 */
private fun readAssetTxt(context: android.content.Context, asset: String): Pair<String, String>? {
    return try {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        val nl = raw.indexOf('\n')
        val title = if (nl >= 0) raw.substring(0, nl).trim() else raw.trim()
        val body = if (nl >= 0) raw.substring(nl + 1).trim() else ""
        if (body.isBlank()) null else title to body
    } catch (_: Exception) { null }
}

/**
 * 导入文字版为 Article（到背诵列表），返回 Article id
 */
private suspend fun importTextToBlancall(
    context: android.content.Context,
    title: String,
    content: String
): Long = withContext(Dispatchers.IO) {
    try {
        val repo = ArticleRepository.getInstance(
            context.filesDir.resolve("articles.json").absolutePath
        )
        repo.insert(Article(title = title.ifBlank { "未命名" }, content = content))
    } catch (_: Exception) { -1L }
}