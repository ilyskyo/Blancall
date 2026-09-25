// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.ui.theme.Macaron

/**
 * 阅读正文视图（文字流、分节页、遮挡阅读；从 ReadingModeScreen.kt 拆出；纯搬移，行为不变）。
 */

/**
 * 正文段落渲染：按空行分段，段落间留出间距。
 * 不含滚动容器——由调用方决定外层是整篇滚动还是节内滚动。
 */
@Composable
internal fun ReadingTextContent(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily = FontFamily.Default,
    indent: Boolean = true
) {
    val paragraphs = remember(text) { text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 40.dp)
    ) {
        paragraphs.forEachIndexed { index, para ->
            if (index > 0) Spacer(Modifier.height((fontPx * 0.9f).dp))
            Text(
                text = para,
                fontSize = fontPx.sp,
                lineHeight = (fontPx * lineHeight).sp,
                color = textColor,
                fontFamily = fontFamily,
                // 仅对允许缩进的来源统一补两格首行缩进（正文段落已 trim，天然避免重复叠加）；
                // PDF/Word 等来源或全局关闭时不缩进，保持原文
                style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(72.dp)) // 底部留白，避免最后一行被进度胶囊遮挡
    }
}

// ========== 章节单页（翻页模式） ==========


@Composable
internal fun ReadingSectionPage(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    onScrollFraction: (Float) -> Unit,
    indent: Boolean = true,
    maskColor: Color,
    occlusion: OcclusionParams = OcclusionParams(enabled = false),
    customSpansResolver: ((String) -> List<MaskConfigStore.MaskSpan>)? = null,
    header: (@Composable () -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    // 页内滚动比例上报（maxValue 变化时自动重算；无需滚动 → 视为已读完，直接 1f）
    val current by remember { derivedStateOf {
        if (scrollState.maxValue <= 0) 1f else scrollState.value.toFloat() / scrollState.maxValue
    } }
    LaunchedEffect(scrollState) {
        snapshotFlow { current }.collect { onScrollFraction(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        header?.invoke()
        if (occlusion.enabled) {
            OccludedReadingContent(
                text = text,
                fontPx = fontPx,
                lineHeight = lineHeight,
                textColor = textColor,
                fontFamily = fontFamily,
                indent = indent,
                maskColor = maskColor,
                occlusion = occlusion,
                customSpansResolver = customSpansResolver
            )
        } else {
            ReadingTextContent(
                text = text,
                fontPx = fontPx,
                lineHeight = lineHeight,
                textColor = textColor,
                fontFamily = fontFamily,
                indent = indent
            )
        }
    }
}

// ========== 背诵遮挡正文渲染 ==========


/**
 * 背诵遮挡正文渲染：按空行分段，每段以 [OccludedParagraph] 绘制——
 * 底层面板是完整原文（保证换行/缩进与正常阅读一致），上方叠加圆角遮块，
 * 点一下遮块即像揭开挡卡一样露出原文。段内外观与 [ReadingTextContent] 完全对齐。
 *
 * [customSpansResolver] 非空时为自定义遮挡模式：按段落文本查配置的遮块
 * （含每块独立颜色），忽略自动遮挡算法；为 null 时按 [OcclusionParams.mode] 自动生成。
 */
@Composable
internal fun OccludedReadingContent(
    text: String,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    indent: Boolean,
    maskColor: Color,
    occlusion: OcclusionParams,
    customSpansResolver: ((String) -> List<MaskConfigStore.MaskSpan>)? = null
) {
    // 段落口径与遮挡自定义编辑器一致（splitParagraphs），段落索引即配置存储索引
    val paragraphs = remember(text) { ReaderOcclusion.splitParagraphs(text) }
    val mode = occlusion.mode
    // 挡片颜色板：与阅读设置「挡片颜色」同款（自定义遮挡每块按存储索引取色）
    val maskPalette = listOf(
        Macaron.review().fill, Macaron.continueP().fill, Macaron.info().fill,
        Macaron.warn().fill, Macaron.lavender().fill, Macaron.neutral().fill
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 40.dp)
    ) {
        paragraphs.forEachIndexed { index, para ->
            if (index > 0) Spacer(Modifier.height((fontPx * 0.9f).dp))
            if (customSpansResolver != null) {
                // 自定义遮挡：回放该段配置的遮块（每块独立颜色）
                val spans = customSpansResolver(para.text)
                val ranges = spans.map { OcclusionSpan(it.a, it.e) }
                val spanColors = spans.associate { it.a to maskPalette[it.c.coerceIn(0, maskPalette.lastIndex)] }
                OccludedParagraph(
                    text = para.text,
                    hidden = ranges,
                    fontPx = fontPx,
                    lineHeight = lineHeight,
                    textColor = textColor,
                    fontFamily = fontFamily,
                    indent = indent,
                    maskColor = maskColor,
                    onToggleControls = occlusion.onToggleControls,
                    spanColors = spanColors
                )
            } else {
                // 每段的遮挡空：按当前粒度（short=字词 / long=复句 / mixed=逐句随机长或短，均为本地算法）
                val ranges = remember(para.text, occlusion) {
                    ReaderOcclusion.localRangesInPara(para.text, if (mode == "custom") "mixed" else mode)
                }
                OccludedParagraph(
                    text = para.text,
                    hidden = ranges,
                    fontPx = fontPx,
                    lineHeight = lineHeight,
                    textColor = textColor,
                    fontFamily = fontFamily,
                    indent = indent,
                    maskColor = maskColor,
                    onToggleControls = occlusion.onToggleControls
                )
            }
        }
        Spacer(Modifier.height(72.dp)) // 底部留白，避免最后一行被进度胶囊遮挡
    }
}

// ========== 液态玻璃组件 ==========

