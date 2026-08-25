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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
    PdfPreviewScreenOptimized(navController, asset, title)
}

/** 单页渲染 + 双指缩放/单指拖动：放大到跨过整数倍时按更高分辨率重渲染页面，保证文字清晰（矢量重绘） */
@Composable
internal fun ZoomablePdfPage(
    renderer: PdfRenderer,
    index: Int,
    isZoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    val pageCount = renderer.pageCount
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 渲染级别：未放大 1x，放大到 >=2 时用 2 倍分辨率重渲染（文字仍清晰，不再发糊）
    val renderLevel = maxOf(1, kotlin.math.ceil(scale).toInt()).coerceAtMost(2)
    val bitmap = remember(renderer, index, renderLevel) {
        renderPdfPage(renderer, index, pageCount, 2f * renderLevel)
    }
    bitmap?.let { bmp ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { boxSize = it }
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
                                val newScale = (scale * zoom).coerceIn(1f, 4f)
                                scale = newScale
                                offset = if (newScale <= 1f) Offset.Zero
                                else clampOffset(offset + (midCur - midPrev), boxSize, scale)
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

/** 按给定比例矢量重渲染 PDF 页面到 Bitmap（比例越大文字越清晰） */
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

/** 夹紧平移量，确保放大后内容仍在可视范围内、文字不跑到屏幕外 */
private fun clampOffset(offset: Offset, box: IntSize, scale: Float): Offset {
    if (box == IntSize.Zero) return offset
    val maxX = (scale - 1f) * box.width / 2f
    val maxY = (scale - 1f) * box.height / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 把 assets 里的 PDF 复制到缓存目录，返回文件（失败返回 null） */
internal fun copyAssetToCache(context: android.content.Context, asset: String): File? {
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
internal fun readAssetTxt(context: android.content.Context, asset: String): Pair<String, String>? {
    return try {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        val nl = raw.indexOf('\n')
        val title = if (nl >= 0) raw.substring(0, nl).trim() else raw.trim()
        val body = if (nl >= 0) raw.substring(nl + 1).trim() else ""
        if (body.isBlank()) null else title to body
    } catch (_: Exception) { null }
}

/** 导入文字版为 Article（到背诵列表），返回 Article id */
internal suspend fun importTextToBlancall(
    context: android.content.Context,
    title: String,
    content: String
): Long = withContext(Dispatchers.IO) {
    try {
        val repo = ArticleRepository.getInstance(
            context.filesDir.resolve("articles.json").absolutePath
        )
        android.util.Log.d("BlancallImport", "导入 title=$title content长度=${content.length}")
        repo.insert(Article(title = title.ifBlank { "未命名" }, content = content))
    } catch (e: Exception) {
        android.util.Log.e("BlancallImport", "导入失败", e)
        -1L
    }
}