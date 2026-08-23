// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 高性能 PDF 文本提取器，支持无损放大和自适应布局
 * 
 * 主要功能：
 * 1. 提取 PDF 文本层，保持原始文字内容
 * 2. 计算文本布局，支持自适应屏幕尺寸
 * 3. 生成矢量文本渲染，放大时保持清晰度
 * 4. 支持分页文本提取和布局优化
 */
class PdfTextExtractor {

    /**
     * PDF 文本页面信息
     */
    data class TextPage(
        val pageIndex: Int,
        val text: String,
        val textBlocks: List<TextBlock>,
        val pageSize: Size,
        val textSize: Float = 16f // 基础字体大小
    )

    /**
     * 文本块信息（段落、标题等）
     */
    data class TextBlock(
        val text: String,
        val bounds: RectF, // 在 PDF 页面中的相对位置 (0-1)
        val type: BlockType,
        val fontSize: Float,
        val isBold: Boolean = false
    )

    enum class BlockType {
        TITLE, HEADER, PARAGRAPH, LIST, TABLE, IMAGE
    }

    /**
     * 提取 PDF 文本内容
     */
    suspend fun extractText(context: Context, pdfFile: File): List<TextPage> = withContext(Dispatchers.IO) {
        // 防御性初始化：确保 pdfbox 能从 assets 加载 glyphlist 等字体映射资源
        try {
            PDFBoxResourceLoader.init(context)
        } catch (_: Exception) { }
        try {
            PDDocument.load(FileInputStream(pdfFile)).use { document ->
                val pages = mutableListOf<TextPage>()
                
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val textStripper = object : PDFTextStripper() {
                        override fun writeString(text: String, outputStream: List<com.tom_roush.pdfbox.text.TextPosition>?) {
                            // 提取纯文本，过滤控制字符
                            val cleanText = text.replace(Regex("[\u0000-\u001F\u007F-\u009F]"), "")
                            super.writeString(cleanText, outputStream)
                        }
                    }
                    
                    textStripper.startPage = pageIndex + 1
                    textStripper.endPage = pageIndex + 1
                    val text = textStripper.getText(document)
                    val textBlocks = extractTextBlocks(page, text)
                    val pageSize = Size(
                        page.mediaBox.width.toFloat(),
                        page.mediaBox.height.toFloat()
                    )
                    
                    pages.add(TextPage(pageIndex, text, textBlocks, pageSize))
                }
                
                pages
            }
        } catch (e: Exception) {
            // 如果文本提取失败，返回空列表，使用原始 PDF 渲染
            emptyList()
        }
    }

    /**
     * 提取文本块信息
     */
    private fun extractTextBlocks(page: PDPage, text: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        
        // 简单的文本块分割（可根据需要优化）
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.isNotBlank()) {
                // 根据文本特征判断块类型
                val type = when {
                    paragraph.matches(Regex("^[第][一二三四五六七八九十]+[篇章节].*$")) -> BlockType.TITLE
                    paragraph.matches(Regex("^[一二三四五六七八九十]+[、．．].*$")) -> BlockType.LIST
                    paragraph.length > 100 -> BlockType.PARAGRAPH
                    else -> BlockType.PARAGRAPH
                }
                
                // 估算文本位置（简化处理，实际需要更复杂的布局分析）
                val yPosition = (index * 0.1f).coerceIn(0f, 0.9f)
                val bounds = RectF(0.05f, yPosition, 0.95f, yPosition + 0.08f)
                
                blocks.add(TextBlock(
                    text = paragraph.trim(),
                    bounds = bounds,
                    type = type,
                    fontSize = when (type) {
                        BlockType.TITLE -> 20f
                        BlockType.HEADER -> 18f
                        else -> 16f
                    }
                ))
            }
        }
        
        return blocks
    }

    /**
     * 计算自适应布局参数
     */
    fun calculateAdaptiveLayout(
        textPages: List<TextPage>,
        screenWidth: Float,
        screenHeight: Float,
        targetScale: Float = 1f
    ): AdaptiveLayout {
        if (textPages.isEmpty()) {
            return AdaptiveLayout(screenWidth, screenHeight, targetScale)
        }

        // 计算文本总高度和需要的缩放比例
        val totalTextHeight = textPages.fold(0f) { acc, page ->
            acc + page.textBlocks.fold(0f) { innerAcc, block ->
                innerAcc + block.bounds.height() * page.pageSize.height
            }
        }

        // 根据屏幕高度计算合适的缩放比例
        val scale = calculateOptimalScale(totalTextHeight, screenHeight, targetScale)
        
        return AdaptiveLayout(screenWidth, screenHeight, scale)
    }

    /**
     * 计算最优缩放比例
     */
    private fun calculateOptimalScale(
        textHeight: Float,
        screenHeight: Float,
        targetScale: Float
    ): Float {
        val baseScale = screenHeight / textHeight
        return (baseScale * targetScale).coerceIn(0.5f, 3f) // 限制缩放范围
    }

    /**
     * 自适应布局信息
     */
    data class AdaptiveLayout(
        val screenWidth: Float,
        val screenHeight: Float,
        val scale: Float,
        val textOffsetY: Float = 0f
    )
}