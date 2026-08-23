// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import kotlin.math.max

/**
 * 高考必背篇目专用文本渲染器
 * 
 * 针对高考必背篇目的特殊优化：
 * 1. 智能分段：自动识别标题、段落、列表等结构
 * 2. 重点突出：标题和重点内容突出显示
 * 3. 自适应排版：根据屏幕尺寸和缩放级别自动调整
 * 4. 流畅缩放：支持手势缩放，放大时保持文字清晰
 * 5. 内容约束：确保文字不会超出屏幕边界
 */
@Composable
fun GaokaoTextRenderer(
    textPages: List<PdfTextExtractor.TextPage>,
    modifier: Modifier = Modifier,
    initialScale: Float = 1f,
    maxScale: Float = 3f,
    minScale: Float = 0.5f,
    onScaleChanged: (Float) -> Unit = {}
) {
    val density = LocalDensity.current
    var currentScale by remember { mutableStateOf(initialScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    
    // 计算自适应布局
    val adaptiveLayout = remember(textPages, containerSize) {
        if (containerSize != IntSize.Zero && textPages.isNotEmpty()) {
            PdfTextExtractor().calculateAdaptiveLayout(
                textPages = textPages,
                screenWidth = containerSize.width.toFloat(),
                screenHeight = containerSize.height.toFloat(),
                targetScale = currentScale
            )
        } else {
            PdfTextExtractor.AdaptiveLayout(
                containerSize.width.toFloat(),
                containerSize.height.toFloat(),
                currentScale
            )
        }
    }
    
    // 监听缩放变化
    LaunchedEffect(currentScale) {
        onScaleChanged(currentScale)
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            textPages.forEach { page ->
                GaokaoTextPageContent(
                    textPage = page,
                    adaptiveLayout = adaptiveLayout,
                    onTextLayout = { textLayoutResult ->
                        // 根据文本布局结果调整偏移量，确保内容不超出屏幕
                        adjustOffsetIfNeeded(textLayoutResult, containerSize, currentScale, offset)
                    }
                )
            }
        }
    }
}

/**
 * 高考必背篇目单页文本内容渲染
 */
@Composable
private fun GaokaoTextPageContent(
    textPage: PdfTextExtractor.TextPage,
    adaptiveLayout: PdfTextExtractor.AdaptiveLayout,
    onTextLayout: (TextLayoutResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        textPage.textBlocks.forEach { block ->
            GaokaoTextBlockRenderer(
                textBlock = block,
                adaptiveLayout = adaptiveLayout,
                onTextLayout = onTextLayout
            )
        }
    }
}

/**
 * 高考必背篇目文本块渲染器
 */
@Composable
private fun GaokaoTextBlockRenderer(
    textBlock: PdfTextExtractor.TextBlock,
    adaptiveLayout: PdfTextExtractor.AdaptiveLayout,
    onTextLayout: (TextLayoutResult) -> Unit
) {
    when (textBlock.type) {
        PdfTextExtractor.BlockType.TITLE -> {
            // 标题：大号字体，加粗，突出显示
            Text(
                text = textBlock.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = (textBlock.fontSize * adaptiveLayout.scale * 1.2f).sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                onTextLayout = onTextLayout
            )
        }
        PdfTextExtractor.BlockType.HEADER -> {
            // 小标题：中等字体，加粗
            Text(
                text = textBlock.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (textBlock.fontSize * adaptiveLayout.scale * 1.1f).sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                onTextLayout = onTextLayout
            )
        }
        PdfTextExtractor.BlockType.PARAGRAPH -> {
            // 段落：正常字体，适当行间距
            Text(
                text = textBlock.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (textBlock.fontSize * adaptiveLayout.scale).sp,
                    fontWeight = if (textBlock.isBold) FontWeight.Bold else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onTextLayout = onTextLayout
            )
        }
        PdfTextExtractor.BlockType.LIST -> {
            // 列表项：缩进显示，项目符号
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = "• ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (textBlock.fontSize * adaptiveLayout.scale).sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = textBlock.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (textBlock.fontSize * adaptiveLayout.scale).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onTextLayout = onTextLayout
                )
            }
        }
        PdfTextExtractor.BlockType.TABLE -> {
            // 表格内容：等宽字体，紧凑布局
            Text(
                text = textBlock.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (textBlock.fontSize * adaptiveLayout.scale * 0.9f).sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                onTextLayout = onTextLayout
            )
        }
        PdfTextExtractor.BlockType.IMAGE -> {
            // 图片说明：小号字体，斜体
            Text(
                text = textBlock.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (textBlock.fontSize * adaptiveLayout.scale * 0.85f).sp,
                    fontWeight = FontWeight.Light,
                    fontStyle = FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onTextLayout = onTextLayout
            )
        }
    }
}

/**
 * 调整偏移量，确保内容不超出屏幕边界
 */
private fun adjustOffsetIfNeeded(
    textLayoutResult: TextLayoutResult,
    containerSize: IntSize,
    scale: Float,
    currentOffset: Offset
): Offset {
    if (containerSize == IntSize.Zero) return currentOffset
    
    // 计算文本布局的实际尺寸
    val textWidth = textLayoutResult.size.width * scale
    val textHeight = textLayoutResult.size.height * scale
    
    // 计算最大允许偏移量
    val maxOffsetX = max(0f, (textWidth - containerSize.width) / 2)
    val maxOffsetY = max(0f, (textHeight - containerSize.height) / 2)
    
    // 限制偏移量范围
    return Offset(
        x = currentOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = currentOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
    )
}

/**
 * 高考必背篇目专用手势处理器
 */
@Composable
fun rememberGaokaoGestureHandler(
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    maxScale: Float = 3f,
    minScale: Float = 0.5f
): GaokaoGestureHandler {
    return remember {
        GaokaoGestureHandler(onScaleChange, onOffsetChange, maxScale, minScale)
    }
}

/**
 * 高考必背篇目专用手势处理器类
 */
class GaokaoGestureHandler(
    private val onScaleChange: (Float) -> Unit,
    private val onOffsetChange: (Offset) -> Unit,
    private val maxScale: Float,
    private val minScale: Float
) {
    private var currentScale = 1f
    private var currentOffset = Offset.Zero
    
    fun onGestureZoom(scaleDelta: Float, center: Offset) {
        val newScale = (currentScale * scaleDelta).coerceIn(minScale, maxScale)
        if (newScale != currentScale) {
            currentScale = newScale
            onScaleChange(currentScale)
        }
    }
    
    fun onGestureDrag(delta: Offset) {
        currentOffset += delta
        onOffsetChange(currentOffset)
    }
    
    fun reset() {
        currentScale = 1f
        currentOffset = Offset.Zero
        onScaleChange(1f)
        onOffsetChange(Offset.Zero)
    }
    
    fun getScale(): Float = currentScale
    fun getOffset(): Offset = currentOffset
}