// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ilyskyo.blancall.ui.common.pinchZoomByTouch

/**
 * 阅读模式工具（分节、权重标签、双指缩放；从 ReadingModeScreen.kt 拆出；纯搬移，行为不变）。
 */

/**
 * 将正文按段落聚合为若干「节」：
 * - 先按空行拆段；超长段落再按单行拆小
 * - 小块按目标长度聚合，形成适合翻页的章节
 */
internal fun buildReadingSections(content: String): List<String> {
    val normalized = content.trim().replace("\r\n", "\n")
    if (normalized.isEmpty()) return emptyList()
    val paras = normalized.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paras.isEmpty()) return listOf(content.trim())

    val blocks = mutableListOf<String>()
    for (p in paras) {
        if (p.length > 500) {
            blocks += p.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            blocks += p
        }
    }
    if (blocks.isEmpty()) return listOf(content.trim())

    val sections = mutableListOf<String>()
    val current = StringBuilder()
    for (b in blocks) {
        if (current.isNotEmpty() && current.length + b.length > ReadingSectionTarget) {
            sections += current.toString().trim()
            current.setLength(0)
        }
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(b)
    }
    if (current.isNotBlank()) sections += current.toString().trim()
    return sections.ifEmpty { listOf(content.trim()) }
}


/** 字重档位的中文名（霞鹜文楷：300 细 / 400 常规 / 500 中等；其余字体：400 常规 / 700 加粗） */
internal fun weightLabel(weight: Int): String = when (weight) {
    300 -> "细"
    500 -> "中等"
    700 -> "加粗"
    else -> "常规"
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

