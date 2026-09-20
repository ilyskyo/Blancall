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
 * 1. 项数必须恰好 expectedSize（当前中文模型 7356）；
 * 2. 下标必须恰好覆盖 0..(expectedSize-1)（连续、无重复）。
 */
internal object HandwritingCharset {

    /** 中文模型类别数（HWDB 全集：7185 汉字 + 171 字母数字符号）。与 `charset.json` 的 `num_classes` 一致。 */
    const val SIZE = 7356

    /** 拉丁（EMNIST Balanced）类别数：数字 + 大小写字母 = 47。 */
    const val SIZE_LATIN = 47

    /**
     * 解析 `char_to_idx`，返回「下标 → 字符」的有序表。
     *
     * @param expectedSize 期望类别数（中文 [SIZE] / 拉丁 [SIZE_LATIN]）。
     *   JSON 里若带 `num_classes` 且与它不一致 ⇒ 直接拒绝（防「拿错表上阵」）。
     *
     * 失败（段缺失 / 项数不为 expectedSize / 下标不连续）时返回 `null`，
     * 由调用方决定降级行为 —— 宁可整体禁用手写，也不能带着错位的表上阵。
     */
    fun parse(json: String, expectedSize: Int = SIZE): List<Char>? {
        // 文件自述的类数若与调用方期望不一致，说明模型与字符表版本错配，拒绝
        val declared = readIntField(json, "num_classes")
        if (declared != null && declared != expectedSize) return null

        val section = extractObjectSection(json, "char_to_idx") ?: return null

        val slots = HashMap<Int, Char>(expectedSize)
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
            if (idx != null && ch != null && idx in 0 until expectedSize) {
                slots[idx] = ch
            }
            i = j
        }

        // 不变量 1：项数精确
        if (slots.size != expectedSize) return null
        // 不变量 2：下标连续覆盖（防重复/缺号）
        for (k in 0 until expectedSize) if (!slots.containsKey(k)) return null

        return List(expectedSize) { slots.getValue(it) }
    }

    /**
     * 快速判定：CJK 基本区汉字 [ch] 是否在给定识别字表内。
     *
     * 供「生僻字守卫」使用：答案的下一个字符是基本区汉字但不在表内时，手写必然认不出
     * （模型只能输出表内类别），应引导用户改用键盘输入。
     *
     * 以下情况一律返回 true（不触发守卫，宁可不提示也不误伤）：
     * - 非 CJK 基本区（U+4E00–U+9FFF）的字符：标点/字母/空格等的「不支持」是另一套
     *   已有机制（标点快捷栏 / 文种切换），不归此守卫管；
     * - 字表缺失（assets 读取失败）。
     */
    fun isBasicHanziSupported(ch: Char, table: Set<Char>?): Boolean {
        if (ch.code < 0x4E00 || ch.code > 0x9FFF) return true
        return table?.contains(ch) ?: true
    }

    /** 读取顶层 `"name": 数字` 字段；不存在或非数字返回 null。 */
    private fun readIntField(text: String, name: String): Int? {
        val keyIdx = text.indexOf("\"$name\"")
        if (keyIdx < 0) return null
        val colon = text.indexOf(':', keyIdx)
        if (colon < 0) return null
        var j = colon + 1
        while (j < text.length && text[j].isWhitespace()) j++
        val start = j
        while (j < text.length && (text[j].isDigit() || text[j] == '-')) j++
        if (j == start) return null
        return text.substring(start, j).toIntOrNull()
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
