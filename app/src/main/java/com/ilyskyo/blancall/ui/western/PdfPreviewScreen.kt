// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.western

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置 PDF 预览页：用系统 [PdfRenderer] 逐页渲染，支持在 app 内直接阅览 PDF
 * （素材库单篇 PDF、以及导入流程中的 PDF 均可复用），不再跳到外部查看器。
 *
 * 若存在配套的文字版（asset 同名 .txt：第一行为标题、其余为正文，不带注释），
 * 右上角 ⋮ 菜单提供「导入到背诵挖空」——导入干净的文字版（不含注释）。
 *
 * @param asset assets 下的 PDF 相对路径，如 "gaokao/p1.pdf"
 * @param title 可选标题（缺省时从配套 .txt 首行读取）
 */
@Composable
fun PdfPreviewScreen(
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

    // 导入状态
    var showMenu by remember { mutableStateOf(false) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    // 缩放状态：放大时禁用列表滚动，双指缩放 / 单指拖动
    var isZoomed by remember { mutableStateOf(false) }

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
            if (textLoaded != null) {
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
                                pendingTitle = textLoaded.first
                                pendingText = textLoaded.second
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

/** 单页渲染 + 双指缩放/单指拖动 */
@Composable
private fun ZoomablePdfPage(
    renderer: PdfRenderer,
    index: Int,
    isZoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    val pageCount = renderer.pageCount
    val bitmap = remember(renderer, index) {
        var bmp: Bitmap? = null
        try {
            if (index < pageCount) {
                val page = renderer.openPage(index)
                val scale = 2f
                val w = page.width.toFloat() * scale
                val h = page.height.toFloat() * scale
                bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
                val m = Matrix().apply { postScale(scale, scale) }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
            }
        } catch (_: Exception) { }
        bmp
    }
    bitmap?.let { bmp -> ZoomablePdfImage(bmp, isZoomed, onZoomChanged) }
}

/** 可缩放图片：双指捏合缩放(1~6 倍)，放大后单指拖动平移；未放大时不拦截，列表可滚动 */
@Composable
private fun ZoomablePdfImage(
    bitmap: Bitmap,
    isZoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            if (newScale > 1f) {
                                offset += (midCur - midPrev)
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            onZoomChanged(scale > 1f)
                            pressed.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1f) {
                            // 放大后单指拖动平移
                            val c = pressed[0]
                            offset += (c.position - c.previousPosition)
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
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
    }
}

/** 把 assets 里的 PDF 复制到缓存目录，返回文件（失败返回 null） */
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

/** 读取配套文字版：首行为标题，其余为正文。不存在返回 null */
private fun readAssetTxt(context: android.content.Context, asset: String): Pair<String, String>? {
    return try {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        val nl = raw.indexOf('\n')
        val title = if (nl >= 0) raw.substring(0, nl).trim() else raw.trim()
        val body = if (nl >= 0) raw.substring(nl + 1).trim() else ""
        if (body.isBlank()) null else title to body
    } catch (_: Exception) { null }
}

/** 导入文字版为 Article（到背诵列表），返回 Article id */
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