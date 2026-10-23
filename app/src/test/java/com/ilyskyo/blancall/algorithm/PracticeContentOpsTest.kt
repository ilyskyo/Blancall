// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PracticeContentOps] 回归测试。
 *
 * 重点锁定「段落模式下句子锚点必须落在全文正确位置」——这是句子索引三套口径之间唯一的
 * 桥梁，历史上曾出现锚点错标到文章开头的问题，故用真实切分器（而非桩数据）验证。
 * 另锁定句级聚焦窗口（[PracticeContentOps.focusWindow]）：它决定「点一句去练」只练多大一块，
 * 历史上曾因按「该句所在段落」聚焦，在没有空行的文章里退化成「重做整篇」。
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

    // ── 句级聚焦窗口：点一句去练只练那一块 ──

    @Test
    fun `聚焦窗口_只覆盖目标句前后各N句`() {
        val w = PracticeContentOps.focusWindow(full, focusSentence = 2, radius = 1)!!
        val sents = SentenceSplitter.splitWithPositions(full)

        assertEquals("窗口首句 = 目标句前 1 句", 1, w.firstSentenceIndex)
        assertEquals("窗口共 3 句", 3, w.sentenceCount)
        assertEquals("目标句索引原样带出（落盘「继续练习」要用）", 2, w.focusSentence)
        assertEquals(sents[1].startIndex, w.section.startChar)
        assertEquals(sents[3].endIndex, w.section.endChar)
        // 切片重新切句后句数必须一致（句界不能在切片里丢掉）
        assertEquals(3, SentenceSplitter.splitWithPositions(w.section.text).size)
    }

    @Test
    fun `聚焦窗口_逗号结尾的换行分句不粘连`() {
        // 「每句一行、行尾是逗号」的文章（高考必背源文件风格）：切片必须带上句末那个换行，
        // 否则窗口文本里相邻两句会粘成一句，挖空就跑到别的句子上去了
        val byLine = "甲乙丙丁，戊己庚辛\n壬癸子丑，寅卯辰巳\n午未申酉，戌亥甲乙"
        val sents = SentenceSplitter.splitWithPositions(byLine)

        val one = PracticeContentOps.focusWindow(byLine, focusSentence = 1, radius = 0)!!
        assertEquals(sents[1].text, one.section.text.trim())

        val three = PracticeContentOps.focusWindow(byLine, focusSentence = 1, radius = 1)!!
        assertEquals(3, three.sentenceCount)
        assertEquals(
            "窗口文本重新切句后仍是那 3 句，不能粘连",
            sents.map { it.text },
            SentenceSplitter.splitWithPositions(three.section.text).map { it.text }
        )
    }

    @Test
    fun `聚焦窗口_锚点落在全文对应位置`() {
        val w = PracticeContentOps.focusWindow(full, focusSentence = 2, radius = 1)!!
        val anchors = PracticeContentOps.buildSentenceAnchors(
            fullContent = full,
            effectiveContent = w.section.text,
            sections = listOf(w.section),
            selected = setOf(w.section.index)
        )
        val inWindow = SentenceSplitter.splitWithPositions(full)
            .subList(w.firstSentenceIndex, w.firstSentenceIndex + w.sentenceCount)
            .map { it.startIndex }
        assertEquals(inWindow, anchors)
    }

    @Test
    fun `聚焦窗口_首末句不越界_非法输入返回null`() {
        val sents = SentenceSplitter.splitWithPositions(full)

        val first = PracticeContentOps.focusWindow(full, focusSentence = 0, radius = 2)!!
        assertEquals(0, first.firstSentenceIndex)
        assertEquals(3, first.sentenceCount)

        val last = PracticeContentOps.focusWindow(full, focusSentence = sents.size - 1, radius = 2)!!
        assertEquals(sents.size, last.firstSentenceIndex + last.sentenceCount)

        assertNull(PracticeContentOps.focusWindow(full, -1))
        assertNull(PracticeContentOps.focusWindow(full, sents.size))
        assertNull(PracticeContentOps.focusWindow("", 0))
    }

    @Test
    fun `聚焦窗口_错误率表按窗口句序平移`() {
        val shifted = PracticeContentOps.shiftSentenceErrorRates(
            rates = mapOf(1 to 0.5f, 3 to 0.9f, 5 to 0.2f),
            delta = -1,
            limit = 3
        )
        // 全文第 3 句 = 窗口第 2 句；全文第 5 句在窗口（共 3 句）之外，丢弃
        assertEquals(mapOf(0 to 0.5f, 2 to 0.9f), shifted)
        assertTrue(PracticeContentOps.shiftSentenceErrorRates(emptyMap(), -1, 3).isEmpty())
    }
}
