// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import kotlin.math.max

/**
 * 纯文本阅读器：以古诗文常规排版展示篇目（标题居中 → 作者居中 → 正文），
 * 用于 PDF 预览的矢量模式，替代复杂的自适应缩放渲染，保证阅读体验正常。
 *
 * @param title 篇目标题
 * @param content 正文（首行若为作者则自动居中展示，如“魏征”）
 */
@Composable
fun TextContentReader(
    title: String,
    content: String,
    modifier: Modifier = Modifier
) {
    val trimmed = content.trim()
    val firstLine = trimmed.substringBefore("\n")
    val (author, body) = if (
        firstLine.length <= 12 &&
        !firstLine.contains(Regex("[，。！？；：、]")) &&
        trimmed.contains("\n")
    ) {
        firstLine to trimmed.removePrefix(firstLine).trimStart('\n').trim()
    } else {
        null to trimmed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 标题：居中、加粗、醒目
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (author != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        // 正文：按空行分段落，每段首行缩进两个汉字（与原文段落一致）
        val paragraphs = body.split(Regex("\\n\\s*\\n"))
        paragraphs.forEachIndexed { index, para ->
            if (para.isNotBlank()) {
                Text(
                    text = para,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        lineHeight = 30.sp,
                        textIndent = TextIndent(firstLine = 2.em)
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                if (index != paragraphs.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

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
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        when (pressed.size) {
                            2 -> {
                                // 双指缩放：以双指中心为基准无损放大，并同步平移
                                val c0 = pressed[0]
                                val c1 = pressed[1]
                                val prevDist = (c0.previousPosition - c1.previousPosition).getDistance()
                                val curDist = (c0.position - c1.position).getDistance()
                                val zoom = if (prevDist > 0f) curDist / prevDist else 1f
                                currentScale = (currentScale * zoom).coerceIn(minScale, maxScale)
                                val midPrev = (c0.previousPosition + c1.previousPosition) / 2f
                                val midCur = (c0.position + c1.position) / 2f
                                offset = clampOffset(offset + (midCur - midPrev), containerSize, currentScale)
                                pressed.forEach { if (it.positionChanged()) it.consume() }
                            }
                            1 -> {
                                // 单指拖动（放大状态下平移内容，未放大时交给列表滚动）
                                if (currentScale > 1f) {
                                    val c = pressed[0]
                                    offset = clampOffset(
                                        offset + (c.position - c.previousPosition),
                                        containerSize,
                                        currentScale
                                    )
                                    if (c.positionChanged()) c.consume()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = currentScale <= 1f)
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
                        offset = adjustOffsetIfNeeded(textLayoutResult, containerSize, currentScale, offset)
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
 * 智能约束偏移量，确保放大后内容仍在可视区域内
 */
private fun clampOffset(currentOffset: Offset, containerSize: IntSize, scale: Float): Offset {
    if (containerSize == IntSize.Zero) return currentOffset
    
    // 计算缩放后内容尺寸与容器的差，限制平移范围
    val scaledWidth = containerSize.width * scale
    val scaledHeight = containerSize.height * scale
    val maxX = maxOf(0f, (scaledWidth - containerSize.width) / 2f)
    val maxY = maxOf(0f, (scaledHeight - containerSize.height) / 2f)
    
    return Offset(
        x = currentOffset.x.coerceIn(-maxX, maxX),
        y = currentOffset.y.coerceIn(-maxY, maxY)
    )
}