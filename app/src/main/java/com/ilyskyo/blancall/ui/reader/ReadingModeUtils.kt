// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ilyskyo.blancall.ui.common.pinchZoomByTouch

/**
 * 阅读模式工具（分节、权重标签、双指缩放；从 ReadingModeScreen.kt 拆出；纯搬移，行为不变）。
 */

/**
 * 一「节」：翻页模式下的一页。
 *
 * [firstPara] / [paraCount] 是这一节覆盖的**全局段落区间**（[ReaderOcclusion.splitParagraphs] 口径）。
 * 划线/批注按全局段落索引存储，而每页渲染的是"节的文本"，没有这两个字段就无法把
 * 页内段落映射回全文段落 —— 翻页模式下的划线会画错段，或者干脆画不出来。
 */
internal data class ReadingSection(
    val text: String,
    val firstPara: Int,
    val paraCount: Int
)

/**
 * 将正文按段落聚合为若干「节」：
 * - 先按空行拆段（口径与遮挡/划线一致：`splitParagraphs`）
 * - 超长段落再按单行拆小（拆出的行仍记在原段落名下）
 * - 小块按目标长度聚合，形成适合翻页的章节
 */
internal fun buildReadingSections(content: String): List<ReadingSection> {
    val normalized = content.trim().replace("\r\n", "\n")
    if (normalized.isEmpty()) return emptyList()
    val paras = ReaderOcclusion.splitParagraphs(normalized)
    if (paras.isEmpty()) return listOf(ReadingSection(content.trim(), 0, 0))

    // 每个 block 记住自己来自哪一段：超长段落被按行拆开时，拆出来的每一行都指回原段落
    val blocks = mutableListOf<Pair<String, Int>>()
    paras.forEachIndexed { idx, p ->
        if (p.text.length > 500) {
            p.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { line -> blocks += line to idx }
        } else {
            blocks += p.text to idx
        }
    }
    if (blocks.isEmpty()) return listOf(ReadingSection(content.trim(), 0, 0))

    val sections = mutableListOf<ReadingSection>()
    val current = StringBuilder()
    var firstPara = -1
    var lastPara = -1
    for ((b, paraIdx) in blocks) {
        if (current.isNotEmpty() && current.length + b.length > ReadingSectionTarget) {
            sections += ReadingSection(current.toString().trim(), firstPara, lastPara - firstPara + 1)
            current.setLength(0)
            firstPara = -1
            lastPara = -1
        }
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(b)
        if (firstPara < 0) firstPara = paraIdx
        lastPara = paraIdx
    }
    if (current.isNotBlank()) {
        sections += ReadingSection(current.toString().trim(), firstPara, lastPara - firstPara + 1)
    }
    return sections.ifEmpty { listOf(ReadingSection(content.trim(), 0, 0)) }
}


/** 字重档位的本地化名称（霞鹜文楷：300 细 / 400 常规 / 500 中等；其余字体：400 常规 / 700 加粗） */
@Composable
internal fun weightLabel(weight: Int): String = when (weight) {
    300 -> stringResource(com.ilyskyo.blancall.R.string.weight_thin)
    500 -> stringResource(com.ilyskyo.blancall.R.string.weight_medium)
    700 -> stringResource(com.ilyskyo.blancall.R.string.weight_bold)
    else -> stringResource(com.ilyskyo.blancall.R.string.weight_regular)
}


/**
 * 双指捏合缩放（**仅手指**）。实现统一在 [com.ilyskyo.blancall.ui.common.pinchZoomByTouch]。
 *
 * 修复要点：旧实现 `pressed.size >= 2` 未区分 PointerType，平板上
 * 「扶屏的手指 + 书写的手写笔」会被误判成双指捏合，导致正文字号乱跳。
 * 现在只有手指参与捏合；手写笔的落笔走书写通道（见 HandwritingInput）。
 */
internal fun Modifier.pinchZoom(onZoomChange: (Float) -> Unit): Modifier =
    this.pinchZoomByTouch(onZoomChange = onZoomChange)

// ========== 正文渲染（整篇滚动 / 章节翻页共用） ==========

