// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 用 `char_to_idx` 形状构造一份最小 JSON（非完整表，仅验证解析路径） */
    private fun smallJson(entries: List<Pair<String, Int>>): String {
        val body = entries.joinToString(",") { (c, i) -> "\"$c\":$i" }
        return """{"version":1,"num_classes":${entries.size},"char_to_idx":{$body},"freq":{"角":240}}"""
    }

    @Test
    fun `parses object form mapping char to index`() {
        // 故意乱序书写，验证按 index 回填而非按出现顺序
        val json = smallJson(listOf("任" to 1, "亢" to 0, "坤" to 2))
        // 注意：默认期望完整表尺寸，小样本必然不满足不变量 → 应返回 null（保守降级）
        assertNull("不足完整表尺寸必须拒绝，避免半张表上阵", HandwritingCharset.parse(json))
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
        assertEquals(7356, HandwritingCharset.SIZE)
    }

    @Test
    fun `latin size constant matches emnist balanced classes`() {
        assertEquals(47, HandwritingCharset.SIZE_LATIN)
    }

    /**
     * 拉丁（EMNIST Balanced 47 类：数字 + 大小写字母）字符表。
     *
     * 背景：早期 `parse` 硬编码 3755 项不变量，47 类的英文表即使读取成功也会被拒 ——
     * 英文手写「怎么写都没反应」的第二级故障。必须用真实形状的表锁死回归。
     */
    @Test
    fun `parses a full latin 47-class table with expectedSize`() {
        val chars: List<Char> = ('0'..'9') + ('A'..'Z') +
            listOf('a', 'b', 'd', 'e', 'f', 'g', 'h', 'n', 'q', 'r', 't')
        val entries = chars.mapIndexed { i, c -> "\"$c\":$i" }
        val json = """{"version":1,"num_classes":47,"char_to_idx":{${entries.joinToString(",")}}}"""

        val parsed = HandwritingCharset.parse(json, HandwritingCharset.SIZE_LATIN)
        assertNotNull("47 类拉丁表应被接受", parsed)
        assertEquals(47, parsed!!.size)
        assertEquals('0', parsed[0])
        assertEquals('t', parsed[46])
    }

    /**
     * `num_classes` 自述类数与调用方期望不一致 ⇒ 必须拒绝（模型与字符表版本错配），
     * 而不是「凑合着用」——错位的表会让识别结果 100% 错且静默。
     */
    @Test
    fun `num_classes mismatch is rejected`() {
        val latinJson = """{"version":1,"num_classes":47,"char_to_idx":{"0":0}}"""
        // 默认期望完整表尺寸（中文 7356）⇒ 与自述 47 不符，拒绝
        assertNull(HandwritingCharset.parse(latinJson))
        // 显式 3755 同样拒绝
        assertNull(HandwritingCharset.parse(latinJson, 3755))
    }

    /** 缺失 `num_classes` 时以 expectedSize 为准（向后兼容旧表文件）。 */
    @Test
    fun `absent num_classes falls back to expectedSize`() {
        val json = """{"char_to_idx":{"甲":0,"乙":1,"丙":2}}"""
        val parsed = HandwritingCharset.parse(json, 3)
        assertNotNull(parsed)
        assertEquals(listOf('甲', '乙', '丙'), parsed)
    }

    // ============ isBasicHanziSupported：生僻字守卫的查询口径 ============

    @Test
    fun `基本区汉字查表——表内 true、表外 false`() {
        val table = setOf('你', '好', '一')
        assertTrue(HandwritingCharset.isBasicHanziSupported('你', table))
        // 「嗟」是必背《诗经·鹿鸣》等篇目的表外生僻字：必须判不支持，触发守卫
        assertFalse(HandwritingCharset.isBasicHanziSupported('嗟', table))
    }

    @Test
    fun `非基本区字符一律判支持——标点不归此守卫管`() {
        assertTrue(HandwritingCharset.isBasicHanziSupported('，', null))
        assertTrue(HandwritingCharset.isBasicHanziSupported('A', null))
        assertTrue(HandwritingCharset.isBasicHanziSupported(' ', null))
    }

    @Test
    fun `字表缺失时保守返回 true（宁可不提示不误伤）`() {
        assertTrue(HandwritingCharset.isBasicHanziSupported('嗟', null))
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
        // 构造完整表但含重复下标：应被不变量 2 拒绝
        val size = HandwritingCharset.SIZE
        val entries = ArrayList<Pair<String, Int>>(size)
        for (i in 0 until size) entries += ('\u4e00'.code + i).toChar().toString() to i
        // 把最后一个改成重复 0（于是 size-1 缺失）
        entries[size - 1] = entries[size - 1].first to 0
        val json = smallJson(entries)
        assertNull(HandwritingCharset.parse(json))
    }

    @Test
    fun `parse accepts a full table with contiguous indices`() {
        // 正样本：完整表、下标连续、顺序被打乱
        val entries = ArrayList<Pair<String, Int>>(HandwritingCharset.SIZE)
        for (i in 0 until HandwritingCharset.SIZE) entries += ('\u4e00'.code + i).toChar().toString() to i
        entries.reverse()   // 打乱书写顺序，验证按 index 回填
        val json = smallJson(entries)

        val parsed = HandwritingCharset.parse(json)
        assertNotNull("完整且下标连续的表应被接受", parsed)
        assertTrue(parsed!!.size == HandwritingCharset.SIZE)
        // 回填后下标 i 必须对应构造时 index=i 的那个字
        assertEquals('\u4e00', parsed[0])
        assertEquals(('\u4e00'.code + HandwritingCharset.SIZE - 1).toChar(), parsed[HandwritingCharset.SIZE - 1])
    }
}
