// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.AnsweredBlank
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.model.TagData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataInterchangeTest {

    private val mistakes = listOf(
        MistakeDetail(1, "名", "各", "TYPO", 0, 0.4f, "山不在高，有仙则名。")
    )

    @Test
    fun `CSV 解析按列位且兼容 11 列旧文件`() {
        val header = "ID,文章ID,标题,模式,总空,正确,正确率,用时,时间,错误数,错误明细,mistakes_json"
        val row = "5,42,陋室铭,SENTENCE,4,3,75%,61,2026-10-24 20:00:00,1,明细,"
        val parsed = DataInterchange.parseCsv(header + "\r\n" + row + "\r\n")
        assertEquals(1, parsed.records.size)
        assertEquals(0, parsed.badRows)
        val r = parsed.records.single()
        assertEquals(42L, r.articleId)
        assertEquals("SENTENCE", r.mode)
        assertEquals(4, r.totalBlanks)
        assertEquals(3, r.correctCount)
        assertEquals(61_000L, r.durationMs)
        assertTrue(r.mistakes.isEmpty())
    }

    @Test
    fun `CSV 错题 JSON 列可恢复逐空错因`() {
        val header = "ID,文章ID,标题,模式,总空,正确,正确率,用时,时间,错误数,错误明细,mistakes_json"
        val json = DataInterchange.mistakesToCsvJson(mistakes).replace("\"", "\"\"")
        val row = "5,42,陋室铭,SENTENCE,4,3,75%,61,2026-10-24 20:00:00,1,明细,\"$json\""
        val r = DataInterchange.parseCsv(header + "\n" + row + "\n").records.single()
        assertEquals(1, r.mistakes.size)
        assertEquals("名", r.mistakes.single().correctAnswer)
        assertEquals("山不在高，有仙则名。", r.mistakes.single().sentenceText)
    }

    @Test
    fun `CSV 字段内逗号与引号按 RFC4180 解析`() {
        val rows = DataInterchange.parseCsvRows("a,\"b,c\",\"d\"\"e\"\n1,2,3")
        assertEquals(listOf("a", "b,c", "d\"e"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun `坏行被计数而不是让整包失败`() {
        val text = "h\n" +
            "1,42,t,SENTENCE,4,3,75%,1,2026-10-24 20:00:00,0,\n" +
            "2,not-a-number,t,SENTENCE,4,3,75%,1,2026-10-24 20:00:00,0,\n"
        val parsed = DataInterchange.parseCsv(text)
        assertEquals(1, parsed.records.size)
        assertEquals(1, parsed.badRows)
    }

    @Test
    fun `JSON 导出包含记录 句子状态 标签与关联`() {
        val record = PracticeRecord(
            id = 7,
            articleId = 42,
            mode = "SENTENCE",
            totalBlanks = 4,
            correctCount = 3,
            mistakes = mistakes,
            timestamp = 1000,
            answeredBlanks = listOf(
                AnsweredBlank(1, "名", "各", "TYPO", 0, "山不在高，有仙则名。", 0.4f)
            ),
        )
        val state = FsrsEngine.CardState(
            difficulty = 5.0, stability = 3.0, due = 2000, lastReview = 1000,
            reviewCount = 2, lapses = 1, lastRating = "AGAIN"
        )
        val json = DataInterchange.buildExportJson(
            records = listOf(record),
            articleTitles = mapOf(42L to "陋室铭"),
            sentenceStates = mapOf("s:42:abcd" to state),
            tags = TagData(
                tags = listOf(Tag(1, "古文", 0x112233)),
                links = mapOf(42L to setOf(1L)),
            ),
            exportedAt = 1234,
        )
        val root = JSONObject(json)
        assertEquals("blancall-export", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("陋室铭", root.getJSONArray("records").getJSONObject(0).getString("articleTitle"))
        assertEquals(
            1,
            root.getJSONArray("records").getJSONObject(0).getJSONArray("answeredBlanks").length()
        )
        assertEquals(
            "s:42:abcd",
            root.getJSONArray("sentenceStates").getJSONObject(0).getString("key")
        )
        assertEquals("古文", root.getJSONArray("tags").getJSONObject(0).getString("name"))
        assertEquals(1, root.getJSONArray("tagLinks").getJSONObject(0).getJSONArray("tagIds").length())
    }
}
