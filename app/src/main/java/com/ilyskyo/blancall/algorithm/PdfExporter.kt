// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * PDF 试卷导出器
 *
 * 基于 Android 原生 PdfDocument + Canvas 渲染，零第三方字体依赖。
 * Typeface.DEFAULT 在所有 Android 设备上原生支持 CJK 字符，无需字体文件。
 * 若 assets/fonts/ 下有 .ttf/.otf 则优先使用，保证跨设备排版一致。
 *
 * 注意：以下函数为 suspend，需在协程中调用（推荐 Dispatchers.IO）。
 */
object PdfExporter {

    data class ExportConfig(
        val title: String,
        val displayText: String,
        val blanks: List<BlankExportInfo>,
        val includeAnswer: Boolean = false,
        val subtitle: String = ""
    )

    data class BlankExportInfo(val index: Int, val correctAnswer: String)

    // A4 尺寸（PostScript pt = 1/72 inch）
    private const val PW = 595; private const val PH = 842
    private const val MG = 50f
    private const val FS_TITLE = 20f; private const val FS_SUB = 11f; private const val FS_BODY = 13f
    private const val LG_TITLE = 28f; private const val LG_SUB = 20f; private const val LG_BODY = 22f
    private val CLOZE_COLOR = 0xFF4A90D9.toInt()
    private const val TAG = "PdfExporter"

    /** 填空标记正则（下划线 3 个及以上），提取为常量避免重复编译 */
    private val CLOZE_REGEX = Regex("_{3,}")

    /** 字体缓存，避免每次导出都遍历 assets 并 createFromAsset */
    @Volatile
    private var cachedTypeface: Typeface? = null

    /**
     * 持有当前 Page 的可变引用。PdfDocument 不提供"当前页"查询，
     * 只能用 startPage() 的返回值追踪。
     * finish() 设计为幂等，防止重复 finish 导致异常。
     */
    private class PageCtx(val doc: PdfDocument) {
        var page: PdfDocument.Page = doc.startPage(pageInfo(0))
        var num = 0
        private set
        private var finished = false

        fun newPage() {
            finish()
            page = doc.startPage(pageInfo(++num))
            finished = false
        }

        /** 幂等 finish，确保即使重复调用也不会崩溃 */
        fun finish() {
            if (!finished) {
                doc.finishPage(page)
                finished = true
            }
        }

        val canvas: Canvas get() = page.canvas

        private fun pageInfo(n: Int) = PdfDocument.PageInfo.Builder(PW, PH, n + 1).create()
    }

    /**
     * 导出 PDF 试卷到缓存目录。
     * suspend 函数，需在 IO 上下文调用（已内置 withContext(Dispatchers.IO)）。
     * Bitmap/Canvas 在 Dispatchers.IO 上创建，Android 10+ 支持。
     */
    suspend fun export(context: Context, config: ExportConfig, fileName: String = "blancall_paper.pdf"): File =
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, fileName)
            if (config.displayText.isBlank()) throw IOException("导出内容为空")

            val document = PdfDocument()
            try {
                val tf = loadTypeface(context)
                val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = FS_BODY }
                val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = FS_TITLE; isFakeBoldText = true }
                val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = FS_SUB; color = 0xFF666666.toInt() }
                val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f }
                val blancallP = Paint(bodyP).apply { color = CLOZE_COLOR; isFakeBoldText = true }
                val mw = (PW - 2 * MG).toFloat()

                val ctx = PageCtx(document)

                // ═══ 试题 ═══（try-finally 确保每页 finish）
                try {
                    var y = drawHeader(ctx.canvas, config, titleP, subP, lineP)
                    y = drawBody(ctx, y, config.displayText, bodyP, blancallP, mw)
                } finally {
                    ctx.finish()
                }

                // ═══ 答案 ═══
                if (config.includeAnswer && config.blanks.isNotEmpty()) {
                    ctx.newPage()
                    try {
                        var y = drawAnswerTitle(ctx.canvas, titleP, lineP)
                        y = drawAnswerBody(ctx, y, config.blanks, bodyP, mw)
                    } finally {
                        ctx.finish()
                    }
                }

                FileOutputStream(file).use { document.writeTo(it) }
            } finally {
                document.close()
            }
            file
        }

    // ── 字体 ──

    private fun loadTypeface(context: Context): Typeface {
        // 双重检查锁，命中缓存直接返回
        cachedTypeface?.let { return it }
        for (path in listOf(
            "fonts/NotoSansSC-Regular.otf", "fonts/NotoSansSC-Regular.ttf",
            "fonts/DroidSansFallback.ttf", "fonts/NotoSansCJK-Regular.ttc"
        )) {
            try {
                val tf = Typeface.createFromAsset(context.assets, path)
                val tp = Paint().apply { typeface = tf; textSize = 40f }
                if (tp.measureText("\u4E2D\u9879") > 0f) {
                    Log.i(TAG, "内置字体: $path")
                    cachedTypeface = tf
                    return tf
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载字体失败: $path", e)
            }
        }
        Log.i(TAG, "系统 Typeface.DEFAULT")
        cachedTypeface = Typeface.DEFAULT
        return Typeface.DEFAULT
    }

    // ── 绘制 ──
    // 注意：Android PdfDocument.Page.canvas 原点在左上角，y 向下为正。
    // 所有 y 坐标从顶部 margin 开始，向下递增。

    private fun drawHeader(
        c: Canvas, cfg: ExportConfig, titleP: Paint, subP: Paint, lineP: Paint
    ): Float {
        // y 从顶部 margin + 标题字号开始（标题基线位置）
        var y = MG + FS_TITLE
        for (l in wrap(titleP, cfg.title)) { c.drawText(l, MG, y, titleP); y += LG_TITLE }
        if (cfg.subtitle.isNotBlank())
            for (l in wrap(subP, cfg.subtitle)) { c.drawText(l, MG, y, subP); y += LG_SUB }
        // 分隔线绘制在当前 y 下方 6pt
        val ly = y + 6f
        c.drawLine(MG, ly, PW - MG, ly, lineP)
        // 返回正文起始 y（分隔线下方一个行高）
        return ly + LG_BODY
    }

    private fun drawBody(ctx: PageCtx, startY: Float, text: String, bodyP: Paint, blancallP: Paint, mw: Float): Float {
        var y = startY
        for (line in wrap(bodyP, text)) {
            if (line.isEmpty()) { y += LG_BODY * 0.6f; continue }
            // y 向下递增，超过底部 margin 时翻页
            if (y > PH - MG - LG_BODY) { ctx.finish(); ctx.newPage(); y = MG + FS_BODY }
            drawRichLine(ctx.canvas, line, bodyP, blancallP, MG, y)
            y += LG_BODY
        }
        return y
    }

    private fun drawRichLine(c: Canvas, line: String, bodyP: Paint, blancallP: Paint, x: Float, y: Float) {
        val parts = line.split(CLOZE_REGEX)
        val marks = CLOZE_REGEX.findAll(line).map { it.value }.toList()
        var cx = x
        for (i in parts.indices) {
            if (parts[i].isNotEmpty()) { c.drawText(parts[i], cx, y, bodyP); cx += bodyP.measureText(parts[i]) }
            if (i < marks.size) { c.drawText(marks[i], cx, y, blancallP); cx += bodyP.measureText(marks[i]) }
        }
    }

    private fun drawAnswerTitle(c: Canvas, titleP: Paint, lineP: Paint): Float {
        // y 从顶部 margin + 标题字号开始
        var y = MG + FS_TITLE
        c.drawText("参考答案", MG, y, titleP); y += LG_TITLE
        val ly = y + 6f
        c.drawLine(MG, ly, PW - MG, ly, lineP)
        return ly + LG_BODY
    }

    private fun drawAnswerBody(ctx: PageCtx, startY: Float, blanks: List<BlankExportInfo>, p: Paint, mw: Float): Float {
        var y = startY
        for (b in blanks)
            for (al in wrap(p, "[${b.index + 1}] ${b.correctAnswer}")) {
                // y 向下递增，超过底部 margin 时翻页
                if (y > PH - MG - LG_BODY) { ctx.finish(); ctx.newPage(); y = MG + FS_BODY }
                ctx.canvas.drawText(al, MG, y, p)
                y += LG_BODY
            }
        return y
    }

    // ── 换行 ──

    private fun wrap(paint: Paint, text: String): List<String> {
        val w = (PW - 2 * MG).toFloat()
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) { out.add(""); continue }
            var r = para
            while (r.isNotEmpty()) {
                val n = paint.breakText(r, true, w, null)
                if (n <= 0) { out.add(r.first().toString()); r = r.substring(1) }
                else { out.add(r.substring(0, n)); r = r.substring(n) }
            }
        }
        return out
    }

    // ── 分享 ──

    /**
     * 分享 PDF 文件。
     * @return true 表示分享成功；null 表示分享失败（调用方应提示用户）。
     * 不使用 Uri.fromFile 回退，避免 Android 7+ FileUriExposedException 崩溃。
     */
    fun sharePdf(context: Context, file: File, authority: String = "${context.packageName}.fileprovider"): Boolean? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享试卷"))
            true
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider 分享 PDF 失败，尝试回退", e)
            // 回退仍使用 FileProvider（可能 authority 配置不同），不使用 Uri.fromFile
            try {
                val fallbackUri = FileProvider.getUriForFile(context, authority, file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, fallbackUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享试卷"))
                true
            } catch (e2: Exception) {
                Log.w(TAG, "回退分享 PDF 仍失败", e2)
                null
            }
        }
    }
}
