// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

/**
 * 高考必背篇目文本清理器
 * 
 * 专门用于处理高考必背篇目PDF中的文本提取和清理：
 * 1. 从PDF中提取纯文本内容
 * 2. 自动识别和去除注释、解析说明等辅助内容
 * 3. 保留原文内容，确保用于背诵挖空的纯净文本
 * 4. 支持文言文、诗词、现代文等不同文体
 */
class GaokaoTextCleaner {

    /**
     * 高考文本类型枚举
     */
    enum class GaokaoTextType {
        CLASSICAL,    // 文言文
        POETRY,       // 诗词
        MODERN,       // 现代文
        MIXED         // 混合类型
    }

    /**
     * 文本清理结果
     */
    data class CleaningResult(
        val originalText: String,      // 原始文本（带注释）
        val cleanedText: String,       // 清理后的文本（无注释）
        val textType: GaokaoTextType,  // 文本类型
        val removedAnnotations: List<String>, // 被移除的注释
        val confidence: Float           // 清理置信度 (0-1)
    )

    /**
     * 从PDF文件中提取并清理文本内容
     */
    suspend fun extractAndCleanText(context: Context, pdfFile: File): CleaningResult = withContext(Dispatchers.IO) {
        try {
            // 1. 从PDF提取原始文本
            val originalText = extractTextFromPdf(pdfFile)
            
            // 2. 分析文本类型
            val textType = analyzeTextType(originalText)
            
            // 3. 清理文本（去除注释）
            val cleaningResult = cleanText(originalText, textType)
            
            cleaningResult
        } catch (e: Exception) {
            // 如果处理失败，返回原始文本
            CleaningResult(
                originalText = "",
                cleanedText = "",
                textType = GaokaoTextType.MODERN,
                removedAnnotations = emptyList(),
                confidence = 0f
            )
        }
    }

    /**
     * 从PDF文件中提取原始文本
     */
    private fun extractTextFromPdf(pdfFile: File): String {
        return try {
            PDDocument.load(FileInputStream(pdfFile)).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, outputStream: List<com.tom_roush.pdfbox.text.TextPosition>?) {
                        // 过滤控制字符，保留文本内容
                        val cleanText = text.replace(Regex("[\u0000-\u001F\u007F-\u009F]"), "")
                        super.writeString(cleanText, outputStream)
                    }
                }
                stripper.getText(document)
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 分析文本类型
     */
    private fun analyzeTextType(text: String): GaokaoTextType {
        val classicalIndicators = listOf("曰", "云", "谓", "之", "乎", "者", "也", "矣", "焉", "哉", "夫", "盖")
        val poetryIndicators = listOf("诗", "词", "歌", "赋", "吟", "咏", "曲", "七言", "五言", "律诗", "绝句")
        val modernIndicators = listOf("的", "了", "是", "在", "有", "和", "就", "不", "人", "都", "一", "个")
        
        val classicalCount = classicalIndicators.count { text.contains(it) }
        val poetryCount = poetryIndicators.count { text.contains(it) }
        val modernCount = modernIndicators.count { text.contains(it) }
        
        return when {
            classicalCount > poetryCount && classicalCount > modernCount -> GaokaoTextType.CLASSICAL
            poetryCount > classicalCount && poetryCount > modernCount -> GaokaoTextType.POETRY
            modernCount > classicalCount && modernCount > poetryCount -> GaokaoTextType.MODERN
            else -> GaokaoTextType.MIXED
        }
    }

    /**
     * 清理文本内容（去除注释）
     */
    private fun cleanText(text: String, textType: GaokaoTextType): CleaningResult {
        val removedAnnotations = mutableListOf<String>()
        var cleanedText = text

        // 根据文本类型使用不同的清理策略
        when (textType) {
            GaokaoTextType.CLASSICAL -> {
                cleanedText = cleanClassicalText(text, removedAnnotations)
            }
            GaokaoTextType.POETRY -> {
                cleanedText = cleanPoetryText(text, removedAnnotations)
            }
            GaokaoTextType.MODERN -> {
                cleanedText = cleanModernText(text, removedAnnotations)
            }
            GaokaoTextType.MIXED -> {
                cleanedText = cleanMixedText(text, removedAnnotations)
            }
        }

        // 后处理：清理多余空白和格式
        cleanedText = postProcessText(cleanedText)

        // 计算清理置信度
        val confidence = calculateConfidence(text, cleanedText, removedAnnotations.size)

        return CleaningResult(
            originalText = text,
            cleanedText = cleanedText,
            textType = textType,
            removedAnnotations = removedAnnotations,
            confidence = confidence
        )
    }

    /**
     * 清理文言文文本
     */
    private fun cleanClassicalText(text: String, removedAnnotations: MutableList<String>): String {
        var cleaned = text
        
        // 移除常见的文言文注释模式
        val annotationPatterns = listOf(
            "\\([^)]*\\)",           // 圆括号注释
            "［[^］]*］",           // 方括号注释
            "【[^】]*】",           // 方头括号注释
            "注[^：]*：[^；]*；?",   // 注释标记
            "解[^：]*：[^；]*;?",   // 解说标记
            "译[^：]*：[^；]*;?",   // 翻译标记
        )
        
        annotationPatterns.forEach { pattern ->
            val regex = Pattern.compile(pattern)
            val matcher = regex.matcher(cleaned)
            while (matcher.find()) {
                val annotation = matcher.group()
                removedAnnotations.add(annotation)
                cleaned = cleaned.replace(annotation, "")
            }
        }
        
        // 移除页码标记
        cleaned = cleaned.replace(Regex("\\s*[第]?\\d+\\s*[页]"), "")
        
        // 移除标题中的注释
        cleaned = cleaned.replace(Regex("《[^》]*》\\s*\\([^)]*\\)"), { matchResult ->
            val title = matchResult.value.replace(Regex("\\s*\\([^)]*\\)"), "")
            title
        })
        
        return cleaned
    }

    /**
     * 清理诗词文本
     */
    private fun cleanPoetryText(text: String, removedAnnotations: MutableList<String>): String {
        var cleaned = text
        
        // 移除诗词注释
        val annotationPatterns = listOf(
            "\\([^)]*\\)",           // 圆括号注释
            "［[^］］*",           // 方括号注释
            "【[^】]*】",           // 方头括号注释
            "注[^：]*：[^；]*;?",   // 注释标记
        )
        
        annotationPatterns.forEach { pattern ->
            val regex = Pattern.compile(pattern)
            val matcher = regex.matcher(cleaned)
            while (matcher.find()) {
                val annotation = matcher.group()
                removedAnnotations.add(annotation)
                cleaned = cleaned.replace(annotation, "")
            }
        }
        
        // 移除作者和朝代信息（保留诗词正文）
        cleaned = cleaned.replace(Regex("[^\\n]*\\s*[作者|朝代|简介][^\\n]*\\n"), "")
        
        // 移除页码
        cleaned = cleaned.replace(Regex("\\s*[第]?\\d+\\s*[页]"), "")
        
        return cleaned
    }

    /**
     * 清理现代文文本
     */
    private fun cleanModernText(text: String, removedAnnotations: MutableList<String>): String {
        var cleaned = text
        
        // 移除现代文注释
        val annotationPatterns = listOf(
            "\\([^)]*\\)",           // 圆括号注释
            "［[^］]*］",           // 方括号注释
            "【[^】]*】",           // 方头括号注释
            "\\[[^\\]]*\\]",        // 方括号注释
            "注[^：]*：[^；]*;?",   // 注释标记
            "说明[^：]*：[^；]*;?", // 说明标记
        )
        
        annotationPatterns.forEach { pattern ->
            val regex = Pattern.compile(pattern)
            val matcher = regex.matcher(cleaned)
            while (matcher.find()) {
                val annotation = matcher.group()
                removedAnnotations.add(annotation)
                cleaned = cleaned.replace(annotation, "")
            }
        }
        
        // 移除页码
        cleaned = cleaned.replace(Regex("\\s*-\\s*\\d+\\s*-"), "")
        cleaned = cleaned.replace(Regex("\\s*[第]?\\d+\\s*[页]"), "")
        
        return cleaned
    }

    /**
     * 清理混合文本
     */
    private fun cleanMixedText(text: String, removedAnnotations: MutableList<String>): String {
        // 使用较保守的清理策略，保留更多内容
        return cleanModernText(text, removedAnnotations)
    }

    /**
     * 后处理文本
     */
    private fun postProcessText(text: String): String {
        var processed = text
        
        // 清理多余空白
        processed = processed.replace(Regex("\\s+"), " ")
        
        // 清理空行
        processed = processed.replace(Regex("\\n\\s*\\n"), "\n")
        
        // 移除首尾空白
        processed = processed.trim()
        
        // 确保段落之间有适当间隔
        processed = processed.replace(Regex("\\n([\\s\\S]*?)\\n"), "\n$1\n")
        
        return processed
    }

    /**
     * 计算清理置信度
     */
    private fun calculateConfidence(originalText: String, cleanedText: String, removedCount: Int): Float {
        val originalLength = originalText.length
        val cleanedLength = cleanedText.length
        val removedRatio = if (originalLength > 0) removedCount.toFloat() / originalLength else 0f
        
        // 基于多个因素计算置信度
        val lengthRatio = if (cleanedLength > 0) cleanedLength.toFloat() / originalLength else 0f
        val annotationFactor = if (removedCount > 0) 0.8f else 0.5f
        
        return (lengthRatio * 0.6f + annotationFactor * 0.4f).coerceIn(0f, 1f)
    }

    /**
     * 判断是否为高考必背篇目
     */
    fun isGaokaoText(text: String): Boolean {
        val gaokaoKeywords = listOf(
            "论语", "孟子", "庄子", "荀子", "韩非子", // 诸子百家
            "岳阳楼记", "醉翁亭记", "桃花源记", "小石潭记", // 古文
            "静夜思", "春望", "登高", "蜀相", // 诗词
            "沁园春", "念奴娇", "水调歌头", // 词牌
            "劝学", "师说", "阿房宫赋", // 经典篇目
            "赤壁赋", "前赤壁赋", "后赤壁赋", // 赋文
        )
        
        return gaokaoKeywords.any { text.contains(it) }
    }

    /**
     * 提取文本标题（用于生成文章标题）
     */
    fun extractTitle(text: String): String {
        // 尝试从文本中提取标题
        val titlePatterns = listOf(
            Regex("《([^》]+)》"),           // 书名号标题
            Regex("第[一二三四五六七八九十]+[篇章节][^\\n]*"),  // 章节标题
            Regex("[^\\n]+"),               // 第一行作为标题
        )
        
        for (pattern in titlePatterns) {
            val matcher = pattern.toPattern().matcher(text)
            if (matcher.find()) {
                val title = matcher.group(1) ?: matcher.group()
                return title.trim().take(50) // 限制标题长度
            }
        }
        
        return "高考必背篇目"
    }
}