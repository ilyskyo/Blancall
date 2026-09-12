// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PracticeContentOps] 回归测试。
 *
 * 重点锁定「段落模式下句子锚点必须落在全文正确位置」——这是句子索引三套口径之间唯一的
 * 桥梁，历史上曾出现锚点错标到文章开头的问题，故用真实切分器（而非桩数据）验证。
 */
class PracticeContentOpsTest {

    /** 三段，每段足够长（SectionSplitter 有最小段落字数门槛，太短会被合并成一段）；段落间以空行分隔 */
    private val full = """
        第一段首句说的是甲乙丙丁戊己庚辛壬癸，用来凑够段落字数。第一段第二句说的是子丑寅卯辰巳午未申酉。

        第二段首句说的是天地玄黄宇宙洪荒日月盈昃，同样需要足够的长度。第二段第二句说的是辰宿列张寒来暑往秋收冬藏。

        第三段首句说的是闰余成岁律吕调阳云腾致雨，这一段是测试要选中的目标。第三段第二句说的是露结为霜金生丽水玉出昆冈。
    """.trimIndent()

    private fun sections() = SectionSplitter.split(full)

    @Test
    fun `整篇模式_有效正文即原文`() {
        val secs = sections()
        assertEquals(full, PracticeContentOps.effectiveContent(full, secs, isFullMode = true, selected = emptySet()))
    }

    @Test
    fun `选中全部段落时仍返回原文`() {
        val secs = sections()
        val all = secs.map { it.index }.toSet()
        assertEquals(full, PracticeContentOps.effectiveContent(full, secs, isFullMode = false, selected = all))
    }

    @Test
    fun `整篇模式下锚点等于全文切句位置`() {
        val expected = SentenceSplitter.splitWithPositions(full).map { it.startIndex }
        val anchors = PracticeContentOps.buildSentenceAnchors(
            fullContent = full,
            effectiveContent = full,
            sections = sections(),
            selected = emptySet()
        )
        assertEquals(expected, anchors)
    }

    @Test
    fun `段落模式下锚点必须落在全文对应位置`() {
        val secs = sections()
        assertTrue("样本至少要有 3 段", secs.size >= 3)

        // 只选第三段
        val target = secs[2]
        val selected = setOf(target.index)
        val effective = PracticeContentOps.effectiveContent(full, secs, isFullMode = false, selected = selected)
        assertEquals(target.text, effective)

        val anchors = PracticeContentOps.buildSentenceAnchors(
            fullContent = full,
            effectiveContent = effective,
            sections = secs,
            selected = selected
        )

        val effectiveSentences = SentenceSplitter.splitWithPositions(effective)
        assertEquals(effectiveSentences.size, anchors.size)
        // 每一句的锚点，必须能在全文中找到「该句文本」且位置一致（不允许错标到文章开头）
        effectiveSentences.forEachIndexed { i, s ->
            val pos = anchors[i]
            assertTrue("锚点应在全文范围内", pos in 0 until full.length)
            val slice = full.substring(pos, (pos + s.text.length).coerceAtMost(full.length))
            assertEquals("第 $i 句锚点应对应其自身文本", s.text, slice)
        }
    }

    @Test
    fun `段落模式下多段选择_锚点仍逐句正确`() {
        val secs = sections()
        val selected = setOf(secs[0].index, secs[2].index)
        val effective = PracticeContentOps.effectiveContent(full, secs, isFullMode = false, selected = selected)
        val anchors = PracticeContentOps.buildSentenceAnchors(full, effective, secs, selected)
        val effSents = SentenceSplitter.splitWithPositions(effective)

        assertEquals(effSents.size, anchors.size)
        effSents.forEachIndexed { i, s ->
            val pos = anchors[i]
            assertEquals(s.text, full.substring(pos, (pos + s.text.length).coerceAtMost(full.length)))
        }
    }

    @Test
    fun `重复句子按最近位置消歧`() {
        // 两段都以同一句「重复的句子。」开头 → 真正的跨段重复句；锚点必须指向【选中段内】的那一处，
        // 而不是简单地取全文第一处。段落需够长，否则会被 SectionSplitter 合并成一段。
        val dup = "重复的句子。甲段的补充内容写得很长很长，用来确保这一段不会被合并掉。\n\n" +
            "重复的句子。乙段的补充内容同样写得很长很长，用来确保这一段能够单独成段。"
        val secs = SectionSplitter.split(dup)
        assertTrue("样本应至少分成 2 段，实际 ${secs.size}", secs.size >= 2)

        val target = secs.last()
        val selected = setOf(target.index)
        val effective = PracticeContentOps.effectiveContent(dup, secs, isFullMode = false, selected = selected)
        val anchors = PracticeContentOps.buildSentenceAnchors(dup, effective, secs, selected)
        val effSents = SentenceSplitter.splitWithPositions(effective)

        assertEquals(effSents.size, anchors.size)
        // 每一句的锚点都必须能切出该句自身文本
        effSents.forEachIndexed { i, s ->
            val pos = anchors[i]
            assertEquals(s.text, dup.substring(pos, (pos + s.text.length).coerceAtMost(dup.length)))
        }
        // 关键断言：重复句的锚点应落在【选中段的起始位置之后】，即消歧到了第二处
        assertEquals("重复的句子。", effSents.first().text)
        assertTrue(
            "重复句应锚定到选中段内（实际 ${anchors.first()}，选中段起始 ${target.startChar}）",
            anchors.first() >= target.startChar
        )
    }

    @Test
    fun `空输入安全`() {
        assertEquals(emptyList<Int>(), PracticeContentOps.buildSentenceAnchors("", "", emptyList(), emptySet()))
        val secs = sections()
        assertEquals(emptyList<Int>(), PracticeContentOps.buildSentenceAnchors(full, full, secs, emptySet())
            .filter { false })   // 占位：非空输入返回非空
        assertTrue(PracticeContentOps.buildSentenceAnchors(full, full, secs, emptySet()).isNotEmpty())
    }
}
