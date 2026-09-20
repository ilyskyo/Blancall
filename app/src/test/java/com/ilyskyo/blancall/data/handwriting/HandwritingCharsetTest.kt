// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字符表解析回归测试。
 *
 * ## 为什么必须存在
 * `charset.json` 是 **JSON 对象**（`char_to_idx` 映射），早期实现误按数组扫描，
 * 得到 40300 个垃圾字符、下标 100% 错位，且**静默不报错** —— 手写只是「怎么都认不对」。
 * 这类错误只能靠单测锁死，代码 review 很难看出来。
 */
class HandwritingCharsetTest {

    /** 用 `char_to_idx` 形状构造一份最小 JSON（非 3755 项，仅验证解析路径） */
    private fun smallJson(entries: List<Pair<String, Int>>): String {
        val body = entries.joinToString(",") { (c, i) -> "\"$c\":$i" }
        return """{"version":1,"num_classes":${entries.size},"char_to_idx":{$body},"freq":{"角":240}}"""
    }

    @Test
    fun `parses object form mapping char to index`() {
        // 故意乱序书写，验证按 index 回填而非按出现顺序
        val json = smallJson(listOf("任" to 1, "亢" to 0, "坤" to 2))
        // 注意：SIZE 是 3755，小样本必然不满足不变量 → 应返回 null（保守降级）
        assertNull("不足 3755 项必须拒绝，避免半张表上阵", HandwritingCharset.parse(json))
    }

    @Test
    fun `extractObjectSection balances braces and skips braces inside strings`() {
        val text = """{"a":1,"char_to_idx":{"甲":0,"含{b}字":1},"z":2}"""
        val section = HandwritingCharset.extractObjectSection(text, "char_to_idx")
        assertNotNull(section)
        // 期望去掉外层花括号后的内容，且字符串里的 { } 不参与配平
        assertEquals("\"甲\":0,\"含{b}字\":1", section)
    }

    @Test
    fun `extractObjectSection returns null when key absent`() {
        assertNull(HandwritingCharset.extractObjectSection("{\"x\":1}", "char_to_idx"))
    }

    @Test
    fun `readJsonString handles unicode escape and literal`() {
        val literal = HandwritingCharset.readJsonString("\"亢\":0", 0)
        assertEquals("亢", literal?.value)
        assertEquals(3, literal?.nextIndex)

        val escaped = HandwritingCharset.readJsonString("\"\\u4ea2\":0", 0)
        assertEquals("亢", escaped?.value)
        assertEquals(8, escaped?.nextIndex)
    }

    @Test
    fun `readJsonString returns null on unterminated string`() {
        assertNull(HandwritingCharset.readJsonString("\"亢", 0))
    }

    @Test
    fun `size constant matches model num_classes`() {
        assertEquals(3755, HandwritingCharset.SIZE)
    }

    /**
     * 端到端：解析 APK 里真实的 charset.json（若测试 classpath 可读到 assets 便校验；
     * 读不到时跳过，不误报失败）。真实文件的强校验放在构建期脚本里。
     */
    @Test
    fun `parse rejects json array form (the historical bug)`() {
        // 历史 bug 的输入形状：数组。必须被拒绝，而不是被扫成一堆垃圾字符
        val arrayForm = """["亢","任","坤"]"""
        assertNull(HandwritingCharset.parse(arrayForm))
    }

    @Test
    fun `parse returns null when index duplicated`() {
        // 构造 3755 项但含重复下标：应被不变量 2 拒绝
        val entries = ArrayList<Pair<String, Int>>(3755)
        for (i in 0 until 3755) entries += ('\u4e00'.code + i).toChar().toString() to i
        // 把最后一个改成重复 0（于是 3754 缺失）
        entries[3754] = entries[3754].first to 0
        val json = smallJson(entries)
        assertNull(HandwritingCharset.parse(json))
    }

    @Test
    fun `parse accepts a full 3755 table with contiguous indices`() {
        // 正样本：3755 项、下标连续、顺序被打乱
        val entries = ArrayList<Pair<String, Int>>(3755)
        for (i in 0 until 3755) entries += ('\u4e00'.code + i).toChar().toString() to i
        entries.reverse()   // 打乱书写顺序，验证按 index 回填
        val json = smallJson(entries)

        val parsed = HandwritingCharset.parse(json)
        assertNotNull("完整且下标连续的表应被接受", parsed)
        assertTrue(parsed!!.size == 3755)
        // 回填后下标 i 必须对应构造时 index=i 的那个字
        assertEquals('\u4e00', parsed[0])
        assertEquals(('\u4e00'.code + 3754).toChar(), parsed[3754])
    }
}
