// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

/**
 * `charset.json` 解析器（纯逻辑，不依赖 Android，故可单测）。
 *
 * ## 为什么单独抽出来
 * 这里出过一次**灾难性且静默**的错：早期实现按「扫出所有非 JSON 标点的字符」来读字符表，
 * 而实际文件是 **JSON 对象**（`{"char_to_idx": {...}}`）而非数组。结果扫出 40300 个垃圾字符、
 * 顺序完全错位 —— 模型输出的类别下标会映射到错误的字，识别结果 100% 错，且**不会抛异常**，
 * 表现只是「怎么都认不对」，极难排查。
 *
 * ⇒ 因此把解析独立成纯函数并加单测（见 `HandwritingCharsetTest`），锁死两个不变量：
 * 1. 项数必须恰好 3755；
 * 2. 下标必须恰好覆盖 0..3754（连续、无重复）。
 */
internal object HandwritingCharset {

    /** 模型类别数（GB2312 一级字）。与 `charset.json` 的 `num_classes` 一致。 */
    const val SIZE = 3755

    /**
     * 解析 `char_to_idx`，返回「下标 → 字符」的有序表。
     *
     * 失败（段缺失 / 项数不为 [SIZE] / 下标不连续）时返回 `null`，由调用方决定降级行为
     * —— 宁可整体禁用手写，也不能带着错位的表上阵。
     */
    fun parse(json: String): List<Char>? {
        val section = extractObjectSection(json, "char_to_idx") ?: return null

        val slots = HashMap<Int, Char>(SIZE)
        var i = 0
        val n = section.length
        while (i < n) {
            val keyOpen = section.indexOf('"', i)
            if (keyOpen < 0) break
            val key = readJsonString(section, keyOpen) ?: break
            val afterKey = key.nextIndex

            val colon = section.indexOf(':', afterKey)
            if (colon < 0) break

            var j = colon + 1
            while (j < n && section[j].isWhitespace()) j++
            val numStart = j
            while (j < n && (section[j].isDigit() || section[j] == '-')) j++
            if (j == numStart) {
                i = afterKey
                continue
            }

            val idx = section.substring(numStart, j).toIntOrNull()
            val ch = key.value.singleOrNull()
            if (idx != null && ch != null && idx in 0 until SIZE) {
                slots[idx] = ch
            }
            i = j
        }

        // 不变量 1：项数精确
        if (slots.size != SIZE) return null
        // 不变量 2：下标连续覆盖（防重复/缺号）
        for (k in 0 until SIZE) if (!slots.containsKey(k)) return null

        return List(SIZE) { slots.getValue(it) }
    }

    /** 取出 `"name"` 后那个 `{ ... }` 的内容（不含外层花括号），按花括号配平，跳过字符串内的括号。 */
    internal fun extractObjectSection(text: String, name: String): String? {
        val keyIdx = text.indexOf("\"$name\"")
        if (keyIdx < 0) return null
        val open = text.indexOf('{', keyIdx)
        if (open < 0) return null

        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        return null
    }

    /** 解析 [quoteIndex] 处开始的 JSON 字符串，返回其值与紧随其后的下标。 */
    internal fun readJsonString(text: String, quoteIndex: Int): Parsed? {
        if (quoteIndex >= text.length || text[quoteIndex] != '"') return null
        val sb = StringBuilder(4)
        var i = quoteIndex + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (val e = text[i + 1]) {
                        'u' -> {
                            if (i + 5 < text.length) {
                                text.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 6
                            } else i += 2
                        }
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        else -> { sb.append(e); i += 2 }
                    }
                }
                c == '"' -> return Parsed(sb.toString(), i + 1)
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    internal data class Parsed(val value: String, val nextIndex: Int)
}
