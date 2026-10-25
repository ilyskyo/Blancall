// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamPaperTest {

    private val a = ExamPaper.Source(
        "陋室铭",
        "刘禹锡",
        "山不在高，有仙则名。水不在深，有龙则灵。斯是陋室，惟吾德馨。"
    )
    private val b = ExamPaper.Source(
        "爱莲说",
        "周敦颐",
        "予独爱莲之出淤泥而不染，濯清涟而不妖。"
    )

    @Test
    fun `多篇组卷保留每篇标题与作者`() {
        val sections = ExamPaper.build(listOf(a, b), asCloze = true)
        assertEquals(2, sections.size)
        assertEquals("陋室铭", sections[0].title)
        assertEquals("刘禹锡", sections[0].author)
        assertEquals("爱莲说", sections[1].title)
    }

    @Test
    fun `每篇的答案编号从 1 开始不串篇`() {
        val sections = ExamPaper.build(listOf(a, b), asCloze = true)
        for (section in sections) {
            assertTrue("每篇至少一个空", section.blanks.isNotEmpty())
            assertEquals(0, section.blanks.first().index)
            assertEquals(
                section.blanks.indices.toList(),
                section.blanks.map { it.index }
            )
            assertTrue("空位必须保留在全角下划线中", section.displayText.contains("＿"))
            assertTrue("编号必须带方括号", section.displayText.contains("[1]"))
        }
    }

    @Test
    fun `篇均题量上限生效且不超实际分句数`() {
        val sections = ExamPaper.build(listOf(a), asCloze = true, maxBlanksPerArticle = 1)
        assertTrue("限制 1 空时不应超过 1 个空", sections.single().blanks.size <= 1)
    }

    @Test
    fun `原文卷不再生成空位`() {
        val sections = ExamPaper.build(listOf(a, b), asCloze = false)
        assertTrue(sections.all { it.blanks.isEmpty() })
        assertEquals(a.content, sections[0].displayText)
    }

    @Test
    fun `空位宽度等于被挖字数`() {
        val sections = ExamPaper.build(listOf(a), asCloze = true)
        val section = sections.single()
        val marks = Regex("\\[(\\d+)] (＿+)").findAll(section.displayText).toList()
        assertEquals(section.blanks.size, marks.size)
        marks.forEach { m ->
            val blank = section.blanks[m.groupValues[1].toInt() - 1]
            assertEquals(blank.answer.length, m.groupValues[2].length)
        }
    }
}
