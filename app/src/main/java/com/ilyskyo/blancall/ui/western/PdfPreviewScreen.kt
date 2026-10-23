// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.western

import com.ilyskyo.blancall.ui.common.rememberEnteredOnFirstFrame
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.core.animateFloatAsState
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** PdfRenderer 非线程安全（同一时间只允许一个 openPage），所有页面渲染经此串行化 */
private val pdfPageMutex = Mutex()

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
    /** 总页数：由父级一次性读入传入 —— 组合/重组期绝不直接访问 renderer，避免文档关闭后访问抛异常（闪退） */
    pageCount: Int,
    isZoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 渲染级别：未放大 1x，放大到 >=2 时用 2 倍分辨率重渲染（文字仍清晰，不再发糊）
    val renderLevel = maxOf(1, kotlin.math.ceil(scale).toInt()).coerceAtMost(2)
    // 位图渲染切 IO 线程（2 倍分辨率渲染 + 大位图分配耗几十 ms，同步执行会卡滚动）
    var bitmap by remember(renderer, index, renderLevel) { mutableStateOf<Bitmap?>(null) }
    // 被更高分辨率替换下来的旧图：等新图至少画过两帧后再回收，避免「仍在绘制却被回收」。
    // 该状态不随 renderLevel 重建：否则切换分辨率的瞬间取消等待，上一张旧图会失去回收者。
    var retiredBitmap by remember(renderer, index) { mutableStateOf<Bitmap?>(null) }
    // DisposableEffect 只随页面/渲染器创建一次；用 rememberUpdatedState 让 onDispose
    // 总能读到最新的 bitmap/retired（否则只会回收首次组合时那张，后续分辨率位图泄漏）。
    val latestBitmap by rememberUpdatedState(bitmap)
    val latestRetired by rememberUpdatedState(retiredBitmap)
    LaunchedEffect(renderer, index, renderLevel) {
        val rendered = pdfPageMutex.withLock {
            withContext(Dispatchers.IO) {
                renderPdfPage(renderer, index, pageCount, 2f * renderLevel)
            }
        }
        val old = bitmap
        val olderRetired = retiredBitmap
        bitmap = rendered
        retiredBitmap = old
        // 上一轮已等待过的旧图直接回收（它早已不参与绘制）
        if (olderRetired != null && olderRetired !== old && !olderRetired.isRecycled) {
            olderRetired.recycle()
        }
        // 状态换新后至少让两帧过去，旧图肯定不再参与绘制
        withFrameNanos { }
        withFrameNanos { }
        if (retiredBitmap === old) {
            retiredBitmap = null
            if (old != null && !old.isRecycled) old.recycle()
        }
    }
    // 离开组合（列表滚出屏幕 / 退出预览页）时显式回收位图：
    // 2 倍分辨率下单页位图可达数 MB，仅靠 GC 回收会让 88 页 PDF 滚动时峰值内存偏高。
    DisposableEffect(renderer, index) {
        onDispose {
            latestRetired?.takeIf { !it.isRecycled }?.recycle()
            latestBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }
    // 离开组合（列表滚出屏幕 / 退出预览页）时显式回收位图：
    // 2 倍分辨率下单页位图可达数 MB，仅靠 GC 回收会让 88 页 PDF 滚动时峰值内存偏高。
    DisposableEffect(renderer, index) {
        onDispose {
            latestRetired?.recycle()
            latestBitmap?.recycle()
        }
    }
    bitmap?.let { bmp ->
        // 位图到位时淡入，而不是凭空出现。刻意不用 Crossfade / StateSwap：那会让新旧两页
        // 位图同时组合，2 倍分辨率下单页就有几 MB，88 页滚动时峰值内存直接翻倍；
        // 而下面 onDispose 里「只在离屏时回收」的纪律，正是为了不让仍在绘制的位图被回收。
        val entered = rememberEnteredOnFirstFrame()
        val pageAlpha by animateFloatAsState(
            if (entered) 1f else 0f,
            MotionFade.alpha(MotionFade.itemEnter),
            label = "pdfPageIn"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = pageAlpha }
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
            val bmp = createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
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
        val stamp = File(out.parentFile, "$name.stamp")
        // 素材随 APK 一起发版，同一 versionCode 下缓存副本必然等同源文件；
        // 此前每次打开预览都重拷一遍（gaokao_full.pdf 实测 10.25 MB，全在首帧前同步 IO 里）。
        // 用 versionCode 而不是文件长度：PDF 走的是压缩资源，openFd 拿不到长度。
        val ver = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        ).toString()
        if (out.exists() && runCatching { stamp.readText() == ver }.getOrDefault(false)) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        // 章写失败不影响已复制好的文件，下次大不了重拷
        runCatching { stamp.writeText(ver) }
        out
    } catch (_: Exception) { null }
}

/**
 * 读取配套文字版：Triple(标题, 作者, 正文)。不存在或正文为空返回 null。
 *
 * 素材库文本版排版固定为「第 1 行标题 / 第 2 行作者（可缺）/ 其后正文」。
 * 旧实现只取首行当标题、其余全当正文，导致作者行被吞进正文
 * （如《师说》的"韩愈"进了正文），故改按行拆分并用 [isAuthorLine] 判定作者行。
 */
internal fun readAssetTxt(context: android.content.Context, asset: String): Triple<String, String, String>? {
    return try {
        val raw = context.assets.open(asset).bufferedReader().use { it.readText() }
        val (title, author, body) = splitArticleText(raw)
        if (body.isBlank()) null else Triple(title, author, body)
    } catch (_: Exception) { null }
}

/**
 * 按「首行标题 / 次行作者（可缺）/ 其余正文」拆分整篇文本。
 * 只有第 2 行参与作者判定，正文不会被误伤。
 */
internal fun splitArticleText(raw: String): Triple<String, String, String> {
    val lines = raw.split('\n')
    val title = lines.getOrNull(0)?.trim().orEmpty()
    val second = lines.getOrNull(1)?.trim().orEmpty()
    val author = if (isAuthorLine(second)) second else ""
    val body = lines.drop(if (author.isEmpty()) 1 else 2)
        .joinToString("\n")
        .trim()
    return Triple(title, author, body)
}

/**
 * 作者行特征：非空、不超过 20 字、且不含句读标点。
 * 素材库里的形态如「韩愈」「《荀子》」「[唐]王维」「《古诗十九首》」；
 * 正文句子必然更长且带标点，不会被判成作者。
 */
private fun isAuthorLine(line: String): Boolean {
    if (line.isBlank() || line.length > 20) return false
    return line.none { it in "。！？；，、：,.!?;:" }
}

/** 导入文字版为 Article（到背诵列表），返回 Article id */
internal suspend fun importTextToBlancall(
    context: android.content.Context,
    title: String,
    content: String,
    author: String = ""
): Long = withContext(Dispatchers.IO) {
    try {
        val repo = ArticleRepository.getInstance(
            context.filesDir.resolve("articles.json").absolutePath
        )
        android.util.Log.d("BlancallImport", "导入 title=$title content长度=${content.length}")
        repo.insert(
            Article(
                title = title.ifBlank { context.getString(com.ilyskyo.blancall.R.string.untitled_article) },
                content = content,
                author = author
            )
        )
    } catch (e: Exception) {
        android.util.Log.e("BlancallImport", "import failed", e)
        -1L
    }
}