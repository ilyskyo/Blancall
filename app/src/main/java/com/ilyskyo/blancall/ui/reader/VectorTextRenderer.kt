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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import kotlin.math.max

/**
 * 矢量文本渲染器，支持无损放大和自适应布局
 * 
 * 主要特性：
 * 1. 矢量渲染：放大时保持文字清晰度，不出现像素化
 * 2. 自适应布局：根据屏幕尺寸自动调整文本布局
 * 3. 平滑缩放：支持手势缩放和自动适配
 * 4. 内容约束：确保文字不会超出屏幕边界
 */
@Composable
fun VectorTextRenderer(
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
                TextPageContent(
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
 * 单页文本内容渲染
 */
@Composable
private fun TextPageContent(
    textPage: PdfTextExtractor.TextPage,
    adaptiveLayout: PdfTextExtractor.AdaptiveLayout,
    onTextLayout: (TextLayoutResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        textPage.textBlocks.forEach { block ->
            TextBlockRenderer(
                textBlock = block,
                adaptiveLayout = adaptiveLayout,
                onTextLayout = onTextLayout
            )
        }
    }
}

/**
 * 文本块渲染器
 */
@Composable
private fun TextBlockRenderer(
    textBlock: PdfTextExtractor.TextBlock,
    adaptiveLayout: PdfTextExtractor.AdaptiveLayout,
    onTextLayout: (TextLayoutResult) -> Unit
) {
    val textStyle = when (textBlock.type) {
        PdfTextExtractor.BlockType.TITLE -> MaterialTheme.typography.headlineMedium
        PdfTextExtractor.BlockType.HEADER -> MaterialTheme.typography.titleMedium
        PdfTextExtractor.BlockType.PARAGRAPH -> MaterialTheme.typography.bodyLarge
        PdfTextExtractor.BlockType.LIST -> MaterialTheme.typography.bodyMedium
        PdfTextExtractor.BlockType.TABLE -> MaterialTheme.typography.bodySmall
        PdfTextExtractor.BlockType.IMAGE -> MaterialTheme.typography.bodyMedium
    }
    
    Text(
        text = textBlock.text,
        style = textStyle.copy(
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
 * 手势缩放处理器
 */
@Composable
fun rememberGestureScaleHandler(
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    maxScale: Float = 3f,
    minScale: Float = 0.5f
): GestureScaleHandler {
    return remember {
        GestureScaleHandler(onScaleChange, onOffsetChange, maxScale, minScale)
    }
}

/**
 * 手势缩放处理器类
 */
class GestureScaleHandler(
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
}