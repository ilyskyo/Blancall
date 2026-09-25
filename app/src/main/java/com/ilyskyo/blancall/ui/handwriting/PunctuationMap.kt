// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import com.ilyskyo.blancall.data.handwriting.HandwritingScript

/**
 * 手写标点的文种适配（半角 → 全角）。
 *
 * ## 背景（2026-09 模型升级后的事实）
 * 中文手写模型是用户自训的 HWDB 全集 **7356 类**（7185 汉字 + 171 符号），
 * 其中包含 14 个标点类：`! " ( ) , . : ; ? … 、 。 《 》`；
 * 但**不含**全角 `，？！：；（）`（HWDB 符号集里就没有这些全角类）。
 * 中文语境下用户「写逗号」期望得到「，」，因此**候选展示与提交统一走一次映射**：
 * 模型认出的半角标点 → 中文全角标点，保证「看到即所得」。
 *
 * ## 边界（宁少映射，不多映射）
 * - 只对**中文字种**生效；拉丁（英文）空保持原样（英文标点本就该是半角）。
 * - `。、…《》` 本身已是正确的中文标点类，保持原样。
 * - `.` 与 `"` 暂不映射：`。` 有独立类（写圆圈即得中文句号）；`.` 在数字 /
 *   版本号等语境仍需原形。真机探针（各标点识别率）出来后可在下表一键调整。
 */
internal fun localizePunct(script: HandwritingScript, ch: Char): Char {
    if (script != HandwritingScript.Chinese) return ch
    return when (ch) {
        ',' -> '，'
        '?' -> '？'
        '!' -> '！'
        ':' -> '：'
        ';' -> '；'
        '(' -> '（'
        ')' -> '）'
        else -> ch
    }
}

/** 批量映射（提交入口用；非中文文种原样返回）。 */
internal fun localizePunct(script: HandwritingScript, chars: List<Char>): List<Char> =
    if (script == HandwritingScript.Chinese) {
        if (chars.none { isMappedPunct(it) }) chars else chars.map { localizePunct(script, it) }
    } else {
        chars
    }

/** 该字符是否受映射影响（供批量路径短路，避免无谓的 map 分配）。 */
private fun isMappedPunct(ch: Char): Boolean = when (ch) {
    ',', '?', '!', ':', ';', '(', ')' -> true
    else -> false
}
