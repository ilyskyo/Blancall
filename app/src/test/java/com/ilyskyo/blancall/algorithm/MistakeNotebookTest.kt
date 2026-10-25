// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.AnsweredBlank
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeNotebookTest {

    private val articleId = 42L

    private fun record(
        id: Long,
        at: Long,
        answered: List<AnsweredBlank> = emptyList(),
        mistakes: List<MistakeDetail> = emptyList(),
    ) = PracticeRecord(
        id = id,
        articleId = articleId,
        mode = "SENTENCE",
        totalBlanks = answered.size,
        correctCount = answered.count { it.correct },
        mistakes = mistakes,
        timestamp = at,
        answeredBlanks = answered,
    )

    private fun wrong(blank: Int, answer: String, user: String, sentence: String = "山不在高，有仙则名。") =
        AnsweredBlank(blank, answer, user, "TYPO", 0, sentence, 0.4f)

    private fun right(blank: Int, answer: String, sentence: String = "山不在高，有仙则名。") =
        AnsweredBlank(blank, answer, answer, "", 0, sentence, 1f)

    @Test
    fun `后来写对过的条目算已攻克`() {
        val records = listOf(
            record(1, 1000, answered = listOf(wrong(0, "名", "各"))),
            record(2, 2000, answered = listOf(right(0, "名"))),
        )
        val entries = MistakeNotebook.entries(records)
        assertEquals(1, entries.size)
        assertTrue(entries.single().resolved)
        assertEquals(2, entries.single().attempts)
        assertEquals(1, entries.single().wrongCount)
        assertEquals("各", entries.single().lastUserAnswer)
        assertTrue(MistakeNotebook.entries(records, onlyUnresolved = true).isEmpty())
    }

    @Test
    fun `旧记录无法判断是否攻克且不会被静默过滤`() {
        val old = record(
            1, 1000,
            mistakes = listOf(MistakeDetail(0, "名", "各", "TYPO", 0, 0.4f, "山不在高，有仙则名。"))
        )
        val entry = MistakeNotebook.entries(listOf(old)).single()
        assertFalse(entry.exact)
        assertFalse(entry.resolved)
        assertEquals(1, MistakeNotebook.entries(listOf(old), onlyUnresolved = true).size)
    }

    @Test
    fun `同一答案在不同句子不合并`() {
        val records = listOf(
            record(1, 1000, answered = listOf(wrong(0, "之", "知", "学而时习之。"))),
            record(2, 2000, answered = listOf(wrong(0, "之", "知", "温故而知新。"))),
        )
        assertEquals(2, MistakeNotebook.entries(records).size)
    }

    @Test
    fun `未攻克按最近错误排序`() {
        val records = listOf(
            record(1, 1000, answered = listOf(wrong(0, "名", "各"))),
            record(2, 2000, answered = listOf(wrong(0, "灵", "临", "水不在深，有龙则灵。"))),
        )
        val entries = MistakeNotebook.entries(records)
        assertEquals("灵", entries.first().correctAnswer)
    }

    @Test
    fun `形近对照只认同长度且表内字`() {
        assertEquals('己' to '已', MistakeNotebook.glyphPair("己", "已"))
        assertNull(MistakeNotebook.glyphPair("已经", "已"))
        assertNull(MistakeNotebook.glyphPair("天", "地"))
    }

    @Test
    fun `只练这一批：按句子文字重新定位并生成区间`() {
        val content = "山不在高，有仙则名。水不在深，有龙则灵。"
        val entries = MistakeNotebook.entries(
            listOf(
                record(
                    1, 1000,
                    answered = listOf(
                        wrong(0, "名", "各", "山不在高，有仙则名。"),
                        wrong(1, "灵", "临", "水不在深，有龙则灵。"),
                    )
                )
            )
        )
        val drill = MistakeNotebook.buildDrill(content, articleId, entries)
        assertEquals(2, drill.blanks.size)
        assertEquals(0, drill.unresolved)
        val sentences = SentenceSplitter.split(content)
        for (b in drill.blanks) {
            assertEquals(
                b.answer,
                sentences[b.sentenceIndex].substring(b.start, b.endExclusive)
            )
        }
    }

    @Test
    fun `只练这一批：正文已改找不到的条目如实计数不猜位置`() {
        val entries = MistakeNotebook.entries(
            listOf(record(1, 1000, answered = listOf(wrong(0, "名", "各", "山不在高，有仙则名。"))))
        )
        val drill = MistakeNotebook.buildDrill("完全不同的正文。", articleId, entries)
        assertTrue(drill.blanks.isEmpty())
        assertEquals(1, drill.unresolved)
    }
}
