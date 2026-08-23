// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 高考必背篇目专用文本渲染器
 * 
 * 专门针对高考必背篇目的特点进行优化：
 * 1. 智能识别文言文、古诗、现代文等不同文体
 * 2. 优化排版，支持重点标注和注释显示
 * 3. 无损放大，文字始终保持清晰
 * 4. 自适应布局，根据屏幕尺寸智能调整
 * 5. 支持手势缩放和双指拖动
 * 6. 智能约束，确保文字不会超出屏幕
 */
@Composable
fun GaokaoTextRenderer(
    textPages: List<PdfTextExtractor.TextPage>,
    modifier: Modifier = Modifier,
    initialScale: Float = 1f,
    maxScale: Float = 5f,
    minScale: Float = 0.3f,
    onScaleChanged: (Float) -> Unit = {}
) {
    val density = LocalDensity.current
    var currentScale by remember { mutableStateOf(initialScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isZooming by remember { mutableStateOf(false) }
    
    // 智能布局计算器
    val layoutCalculator = remember { GaokaoLayoutCalculator() }
    val adaptiveLayout = remember(textPages, containerSize, currentScale) {
        layoutCalculator.calculateLayout(
            textPages = textPages,
            containerSize = containerSize,
            scale = currentScale
        )
    }
    
    // 监听缩放变化
    LaunchedEffect(currentScale) {
        onScaleChanged(currentScale)
    }
    
    // 手势状态
    val gestureState = remember { GaokaoGestureState() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
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
                                // 双指缩放
                                val c0 = pressed[0]
                                val c1 = pressed[1]
                                val prevDist = (c0.previousPosition - c1.previousPosition).getDistance()
                                val curDist = (c0.position - c1.position).getDistance()
                                val zoom = if (prevDist > 0f) curDist / prevDist else 1f
                                
                                val newScale = (currentScale * zoom).coerceIn(minScale, maxScale)
                                if (newScale != currentScale) {
                                    currentScale = newScale
                                    isZooming = true
                                }
                                
                                // 以双指中心为基准进行平移
                                val midPrev = (c0.previousPosition + c1.previousPosition) / 2f
                                val midCur = (c0.position + c1.position) / 2f
                                val delta = midCur - midPrev
                                
                                offset = calculateSmartOffset(
                                    currentOffset = offset,
                                    delta = delta,
                                    containerSize = containerSize,
                                    scale = currentScale,
                                    layout = adaptiveLayout
                                )
                                
                                pressed.forEach { if (it.positionChanged()) it.consume() }
                            }
                            1 -> {
                                // 单指拖动（仅在缩放时可用）
                                if (currentScale > 1f) {
                                    val c = pressed[0]
                                    val delta = c.position - c.previousPosition
                                    
                                    offset = calculateSmartOffset(
                                        currentOffset = offset,
                                        delta = delta,
                                        containerSize = containerSize,
                                        scale = currentScale,
                                        layout = adaptiveLayout
                                    )
                                    
                                    if (c.positionChanged()) c.consume()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            items(textPages) { page ->
                GaokaoPageRenderer(
                    textPage = page,
                    adaptiveLayout = adaptiveLayout,
                    isZooming = isZooming
                )
            }
        }
    }
}

/**
 * 高考必背篇目页面渲染器
 */
@Composable
private fun GaokaoPageRenderer(
    textPage: PdfTextExtractor.TextPage,
    adaptiveLayout: GaokaoLayoutCalculator.AdaptiveLayout,
    isZooming: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        textPage.textBlocks.forEach { block ->
            GaokaoTextBlock(
                textBlock = block,
                adaptiveLayout = adaptiveLayout,
                isZooming = isZooming
            )
        }
    }
}

/**
 * 高考文本块渲染器
 */
@Composable
private fun GaokaoTextBlock(
    textBlock: PdfTextExtractor.TextBlock,
    adaptiveLayout: GaokaoLayoutCalculator.AdaptiveLayout,
    isZooming: Boolean
) {
    val textStyle = when (textBlock.type) {
        PdfTextExtractor.BlockType.TITLE -> MaterialTheme.typography.headlineMedium
        PdfTextExtractor.BlockType.HEADER -> MaterialTheme.typography.titleMedium
        PdfTextExtractor.BlockType.PARAGRAPH -> MaterialTheme.typography.bodyLarge
        PdfTextExtractor.BlockType.LIST -> MaterialTheme.typography.bodyMedium
        PdfTextExtractor.BlockType.TABLE -> MaterialTheme.typography.bodySmall
        PdfTextExtractor.BlockType.IMAGE -> MaterialTheme.typography.bodyMedium
    }
    
    // 根据高考内容类型进行特殊处理
    when {
        isClassicalText(textBlock.text) -> {
            // 文言文处理
            ClassicalTextBlock(
                text = textBlock.text,
                textStyle = textStyle,
                adaptiveLayout = adaptiveLayout,
                fontSize = textBlock.fontSize
            )
        }
        isPoetryText(textBlock.text) -> {
            // 诗词处理
            PoetryTextBlock(
                text = textBlock.text,
                textStyle = textStyle,
                adaptiveLayout = adaptiveLayout,
                fontSize = textBlock.fontSize
            )
        }
        else -> {
            // 现代文处理
            ModernTextBlock(
                text = textBlock.text,
                textStyle = textStyle,
                adaptiveLayout = adaptiveLayout,
                fontSize = textBlock.fontSize,
                isBold = textBlock.isBold
            )
        }
    }
}

/**
 * 文言文文本块
 */
@Composable
private fun ClassicalTextBlock(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    adaptiveLayout: GaokaoLayoutCalculator.AdaptiveLayout,
    fontSize: Float
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = textStyle.copy(
                fontSize = (fontSize * adaptiveLayout.scale).sp,
                fontWeight = FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        
        // 添加注释提示（如果有）
        if (text.contains("曰") || text.contains("云") || text.contains("谓")) {
            Text(
                text = "• 点击查看注释",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp)
                    .alpha(if (adaptiveLayout.scale > 1.5f) 1f else 0.7f)
            )
        }
    }
}

/**
 * 诗词文本块
 */
@Composable
private fun PoetryTextBlock(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    adaptiveLayout: GaokaoLayoutCalculator.AdaptiveLayout,
    fontSize: Float
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 按行分割诗词
        val lines = text.split(Regex("[。！？；，]"))
        
        lines.forEach { line ->
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = textStyle.copy(
                        fontSize = (fontSize * adaptiveLayout.scale * 1.1f).sp, // 诗词稍大
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 现代文文本块
 */
@Composable
private fun ModernTextBlock(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    adaptiveLayout: GaokaoLayoutCalculator.AdaptiveLayout,
    fontSize: Float,
    isBold: Boolean
) {
    Text(
        text = text,
        style = textStyle.copy(
            fontSize = (fontSize * adaptiveLayout.scale).sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/**
 * 智能偏移量计算
 */
private fun calculateSmartOffset(
    currentOffset: Offset,
    delta: Offset,
    containerSize: IntSize,
    scale: Float,
    layout: GaokaoLayoutCalculator.AdaptiveLayout
): Offset {
    if (containerSize == IntSize.Zero) return currentOffset
    
    // 计算内容总尺寸
    val contentWidth = containerSize.width * scale
    val contentHeight = containerSize.height * scale
    
    // 计算最大偏移量
    val maxOffsetX = maxOf(0f, (contentWidth - containerSize.width) / 2f)
    val maxOffsetY = maxOf(0f, (contentHeight - containerSize.height) / 2f)
    
    // 应用偏移量，并限制在允许范围内
    val newOffsetX = (currentOffset.x + delta.x).coerceIn(-maxOffsetX, maxOffsetX)
    val newOffsetY = (currentOffset.y + delta.y).coerceIn(-maxOffsetY, maxOffsetY)
    
    return Offset(newOffsetX, newOffsetY)
}

/**
 * 判断是否为文言文
 */
private fun isClassicalText(text: String): Boolean {
    val classicalIndicators = listOf("曰", "云", "谓", "之", "乎", "者", "也", "矣", "焉", "哉")
    return classicalIndicators.any { text.contains(it) } || text.matches(Regex("^[第][一二三四五六七八九十]+[篇章节].*$"))
}

/**
 * 判断是否为诗词
 */
private fun isPoetryText(text: String): Boolean {
    val poetryIndicators = listOf("诗", "词", "歌", "赋", "吟", "咏", "曲")
    val lineBreaks = text.count { it == '\n' }
    return poetryIndicators.any { text.contains(it) } || lineBreaks > 2
}

/**
 * 高考布局计算器
 */
class GaokaoLayoutCalculator {
    
    /**
     * 自适应布局信息
     */
    data class AdaptiveLayout(
        val screenWidth: Float,
        val screenHeight: Float,
        val scale: Float,
        val textOffsetY: Float = 0f,
        val padding: Float = 16f
    )
    
    /**
     * 计算自适应布局
     */
    fun calculateLayout(
        textPages: List<PdfTextExtractor.TextPage>,
        containerSize: IntSize,
        scale: Float
    ): AdaptiveLayout {
        if (containerSize == IntSize.Zero || textPages.isEmpty()) {
            return AdaptiveLayout(
                containerSize.width.toFloat(),
                containerSize.height.toFloat(),
                scale
            )
        }
        
        // 计算文本总高度
        val totalTextHeight = textPages.fold(0f) { acc, page ->
            acc + page.textBlocks.fold(0f) { innerAcc, block ->
                innerAcc + block.bounds.height() * page.pageSize.height
            }
        }
        
        // 根据高考内容特点调整布局
        val adjustedScale = adjustScaleForGaokao(totalTextHeight, containerSize.height.toFloat(), scale)
        
        return AdaptiveLayout(
            screenWidth = containerSize.width.toFloat(),
            screenHeight = containerSize.height.toFloat(),
            scale = adjustedScale,
            textOffsetY = calculateTextOffset(textPages, containerSize.height.toFloat(), adjustedScale),
            padding = calculatePadding(containerSize.width.toFloat(), adjustedScale)
        )
    }
    
    /**
     * 根据高考内容特点调整缩放比例
     */
    private fun adjustScaleForGaokao(
        textHeight: Float,
        screenHeight: Float,
        targetScale: Float
    ): Float {
        val baseScale = screenHeight / textHeight
        
        // 高考内容通常需要更大的字体，适当增加缩放比例
        val gaokaoScaleMultiplier = when {
            isClassicalContent(textHeight) -> 1.2f // 文言文需要更大字体
            isPoetryContent(textHeight) -> 1.15f // 诗词需要适当放大
            else -> 1.0f
        }
        
        return (baseScale * targetScale * gaokaoScaleMultiplier).coerceIn(0.5f, 4f)
    }
    
    /**
     * 计算文本偏移量
     */
    private fun calculateTextOffset(
        textPages: List<PdfTextExtractor.TextPage>,
        screenHeight: Float,
        scale: Float
    ): Float {
        // 高考内容通常需要居中显示
        return 0f
    }
    
    /**
     * 计算边距
     */
    private fun calculatePadding(screenWidth: Float, scale: Float): Float {
        // 根据屏幕宽度和缩放比例调整边距
        return when {
            screenWidth < 600f && scale > 1.5f -> 8f // 小屏幕大缩放时减少边距
            else -> 16f
        }
    }
    
    /**
     * 判断是否为文言文内容
     */
    private fun isClassicalContent(textHeight: Float): Boolean {
        // 简化的判断逻辑，实际可以根据文本内容分析
        return textHeight < 1000f // 假设文言文文本较短
    }
    
    /**
     * 判断是否为诗词内容
     */
    private fun isPoetryContent(textHeight: Float): Boolean {
        // 简化的判断逻辑
        return textHeight < 800f // 假设诗词文本较短
    }
}

/**
 * 高考手势状态
 */
class GaokaoGestureState {
    var isZooming: Boolean = false
    var lastScale: Float = 1f
    var lastOffset: Offset = Offset.Zero
    
    fun reset() {
        isZooming = false
        lastScale = 1f
        lastOffset = Offset.Zero
    }
}