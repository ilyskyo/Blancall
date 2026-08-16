// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 笔记分享图片生成器（F11）
 *
 * 将挖空文本渲染为精美的分享图片，自带品牌标识，低成本传播。
 *
 * 注意：以下函数为 suspend，需在协程中调用（推荐 Dispatchers.IO）。
 */
object ShareImageGenerator {

    private const val TAG = "ShareImageGenerator"

    // 画布参数
    private const val IMAGE_WIDTH = 1080
    private const val PADDING = 64
    private const val BRAND_HEIGHT = 100

    // 配色（书卷古风）
    private const val BG_COLOR = 0xFFFDF8F0.toInt()     // 暖宣纸米白
    private const val TEXT_COLOR = 0xFF2C2416.toInt()    // 墨色
    private const val BRAND_BG = 0xFF1A1108.toInt()      // 深墨底
    private const val BRAND_TEXT = 0xFFD4C5A0.toInt()    // 烫金
    private const val ACCENT = 0xFF8B5E3C.toInt()        // 赭色
    private const val ACCENT_LIGHT = 0xFFD4C5A0.toInt()  // 淡金
    private const val DIVIDER = 0xFFE8DCC8.toInt()       // 浅金分隔
    private const val CARD_BG = 0xFFF5EDE0.toInt()       // 卡片底色

    data class ShareConfig(
        val title: String,
        val content: String,
        val subtitle: String = "",
        val stats: String = ""
    )

    /**
     * 生成分享图片到缓存目录。
     * suspend 函数，需在 IO 上下文调用（已内置 withContext(Dispatchers.IO)）。
     */
    suspend fun generate(context: Context, config: ShareConfig): File =
        withContext(Dispatchers.IO) {
            // ── 初始化画笔 ──
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 54f; isFakeBoldText = true; color = TEXT_COLOR
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f; color = ACCENT
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 36f; color = TEXT_COLOR
            }
            val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f; color = ACCENT; isFakeBoldText = true
            }
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 34f; color = BRAND_TEXT; isFakeBoldText = true
            }
            val brandSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 22f; color = alphaColor(BRAND_TEXT, 0.6f)
            }
            val accentBarPaint = Paint().apply {
                color = ACCENT; strokeWidth = 6f; style = Paint.Style.FILL
            }
            val dividerPaint = Paint().apply {
                color = DIVIDER; strokeWidth = 2f
            }

            val maxLineWidth = IMAGE_WIDTH - PADDING * 2

            // ── 标题分行（上限 5 行，超长标题截断避免 Bitmap OOM）──
            val titleLinesRaw = wrapToLines(config.title, titlePaint, maxLineWidth)
            val titleLines = if (titleLinesRaw.size > 5)
                titleLinesRaw.take(4) + listOf("……")
            else titleLinesRaw

            // ── 正文分行（预览最多 25 行）──
            val contentLines = wrapToLines(config.content, bodyPaint, maxLineWidth)
            val previewLines = if (contentLines.size > 25)
                contentLines.take(22) + listOf("……（更多内容请打开 Blancall 查看）")
            else contentLines

            val titleLineH = 64f
            val bodyLineH = 52f
            val titleH = titleLines.size * titleLineH
            val contentH = previewLines.size * bodyLineH
            val subtitleH = if (config.subtitle.isNotBlank()) 48f else 0f
            val statsH = if (config.stats.isNotBlank()) 52f else 0f

            // ── 计算高度 ──
            val totalH = PADDING + titleH + subtitleH + 32f +
                    contentH + 60f + statsH + 48f + BRAND_HEIGHT + 32f
            // 内容较少时，最小高度保证 padding + 品牌栏 + 标题区域，避免大量留白
            val minHeight = (PADDING * 2 + BRAND_HEIGHT + titleH).toInt()
            // 上限 4096px，防止极端情况下分配超大 Bitmap 导致 OOM
            val imageHeight = totalH.toInt().coerceIn(minHeight, 4096)

            // ── 创建位图 ──
            val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, imageHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)

                // 背景
                canvas.drawColor(BG_COLOR)

                // 顶部装饰线
                canvas.drawLine(
                    PADDING.toFloat(), 20f, (IMAGE_WIDTH - PADDING).toFloat(), 20f,
                    Paint().apply { color = ACCENT; strokeWidth = 2f; alpha = 80 }
                )

                var y = PADDING + 20f

                // ── 赭色竖条装饰 ──
                canvas.drawRoundRect(
                    PADDING.toFloat() - 6f, y - 6f, PADDING.toFloat(), y + titleH + 6f,
                    3f, 3f, accentBarPaint
                )

                // ── 标题 ──
                for (line in titleLines) {
                    canvas.drawText(line, PADDING + 20f, y + titlePaint.textSize, titlePaint)
                    y += titleLineH
                }
                y += 8f

                // ── 副标题 ──
                if (config.subtitle.isNotBlank()) {
                    canvas.drawText(config.subtitle, PADDING + 20f, y + subtitlePaint.textSize, subtitlePaint)
                    y += subtitleH
                }

                // ── 分隔线 ──
                y += 16f
                canvas.drawLine(
                    PADDING.toFloat() + 20f, y,
                    (IMAGE_WIDTH - PADDING).toFloat(), y, dividerPaint
                )
                y += 20f

                // ── 正文（卡片区域内）──
                val cardLeft = PADDING.toFloat() + 8f
                val cardRight = (IMAGE_WIDTH - PADDING - 8).toFloat()
                val cardTop = y - 4f
                val cardBottom = y + contentH + 20f
                canvas.drawRoundRect(
                    cardLeft, cardTop, cardRight, cardBottom,
                    12f, 12f,
                    Paint().apply { color = CARD_BG; style = Paint.Style.FILL }
                )
                canvas.drawRoundRect(
                    cardLeft, cardTop, cardRight, cardBottom,
                    12f, 12f,
                    Paint().apply { color = DIVIDER; style = Paint.Style.STROKE; strokeWidth = 1.5f }
                )

                y += 16f
                val contentStartX = PADDING + 28f
                for (line in previewLines) {
                    canvas.drawText(line, contentStartX, y + bodyPaint.textSize, bodyPaint)
                    y += bodyLineH
                }

                // ── 统计 ──
                y += 24f
                if (config.stats.isNotBlank()) {
                    // 注意：emoji（📊）通过 Paint 绘制，部分设备可能显示为方块。
                    // 完整 emoji 支持需引入 androidx.emoji2 依赖，当前暂不引入。
                    canvas.drawText("📊  " + config.stats, PADDING + 20f, y + statsPaint.textSize, statsPaint)
                }

                // ── 品牌底栏 ──
                val brandTop = imageHeight - BRAND_HEIGHT.toFloat()
                val brandGrad = LinearGradient(
                    0f, brandTop, IMAGE_WIDTH.toFloat(), imageHeight.toFloat(),
                    intArrayOf(0xFF2C2416.toInt(), 0xFF1A1108.toInt()),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, brandTop, IMAGE_WIDTH.toFloat(), imageHeight.toFloat(),
                    Paint().apply { shader = brandGrad })

                canvas.drawText(
                    "Blancall",
                    PADDING.toFloat(), brandTop + 48f, brandPaint
                )
                canvas.drawText(
                    "Fill the blank, recall the knowledge.",
                    PADDING.toFloat(), brandTop + 48f + 30f, brandSubPaint
                )

                // 右侧装饰符号
                val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 40f; color = alphaColor(BRAND_TEXT, 0.25f)
                }
                canvas.drawText(
                    "✧",
                    (IMAGE_WIDTH - PADDING - 50).toFloat(), brandTop + 48f, symPaint
                )

                // ── 保存文件 ──
                val file = File(context.cacheDir, "blancall_share.png")
                FileOutputStream(file).use {
                    // PNG 为无损格式，quality 参数对 PNG 无效但保留以备切换 JPEG
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                file
            } finally {
                // 确保 bitmap 被回收，避免内存泄漏
                bitmap.recycle()
            }
        }

    /** 设置颜色的 alpha 通道（0.0~1.0） */
    private fun alphaColor(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 分享图片文件。
     * @return true 表示分享成功；null 表示分享失败（调用方应提示用户）。
     * 不使用 Uri.fromFile 回退，避免 Android 7+ FileUriExposedException 崩溃。
     */
    fun shareImage(context: Context, file: File, authority: String = "${context.packageName}.fileprovider"): Boolean? {
        return try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享笔记"))
            true
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider 分享图片失败，尝试回退", e)
            // 回退仍使用 FileProvider，不使用 Uri.fromFile 避免 Android 7+ 崩溃
            try {
                val fallbackUri = FileProvider.getUriForFile(context, authority, file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, fallbackUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享笔记"))
                true
            } catch (e2: Exception) {
                Log.w(TAG, "回退分享图片仍失败", e2)
                null
            }
        }
    }

    /**
     * 将文本按最大宽度换行。
     * 使用 Paint.breakText 按段一次切分，避免逐字符 measureText 导致的 O(n²)。
     */
    private fun wrapToLines(text: String, paint: Paint, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        val maxW = maxWidth.toFloat()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) { lines.add(""); continue }
            var start = 0
            val len = paragraph.length
            while (start < len) {
                // breakText 测量从 start 开始的子串在 maxWidth 内可容纳的字符数
                val count = paint.breakText(paragraph, start, len, true, maxW, null)
                if (count <= 0) {
                    // 单个字符宽度即超出 maxWidth，强制取出避免死循环
                    lines.add(paragraph.substring(start, start + 1))
                    start += 1
                } else {
                    lines.add(paragraph.substring(start, start + count))
                    start += count
                }
            }
        }
        return lines
    }
}
