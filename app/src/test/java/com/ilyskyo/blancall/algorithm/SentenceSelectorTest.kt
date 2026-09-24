// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [SentenceSelector] 回归测试（纯函数，无 Android 依赖）。
 *
 * 覆盖：句子键稳定性/唯一性、合格句过滤边界、候选去重、到期优先选取、
 * 新句抽取（避重复 / 文章轮转 / 兜底 / 空集），行为直接决定每日抽句结果是否可复现。
 */
class SentenceSelectorTest {

    private fun article(id: Long, content: String) = Article(id = id, title = "文章$id", content = content)

    private fun state(
        due: Long,
        lastReview: Long,
        reviewCount: Int = 1,
    ) = FsrsEngine.CardState(
        difficulty = 5.0,
        stability = 3.0,
        due = due,
        lastReview = lastReview,
        reviewCount = reviewCount,
        lapses = 0,
    )

    // ------------------------------------------------------------------
    // 句子键
    // ------------------------------------------------------------------

    @Test
    fun `句子键同文本同文章稳定且不同文章隔离`() {
        val k1 = SentenceSelector.sentenceKey(12, "春眠不觉晓，处处闻啼鸟。")
        val k2 = SentenceSelector.sentenceKey(12, "春眠不觉晓，处处闻啼鸟。")
        val k3 = SentenceSelector.sentenceKey(13, "春眠不觉晓，处处闻啼鸟。")
        assertEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertTrue("键应带命名空间前缀: $k1", k1.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX))
        assertTrue("键应含文章 id: $k1", k1.startsWith("s:12:"))
        // trim 后同文本 → 同键（含首尾空白不影响身份）
        assertEquals(k1, SentenceSelector.sentenceKey(12, "  春眠不觉晓，处处闻啼鸟。  "))
    }

    @Test
    fun `句子键解析文章 id 且脏键返回 null`() {
        val k = SentenceSelector.sentenceKey(42, "白日依山尽，黄河入海流。")
        assertEquals(42L, SentenceSelector.articleIdOf(k))
        assertNull(SentenceSelector.articleIdOf("s:abc:def"))
        assertNull(SentenceSelector.articleIdOf("12"))
        assertNull(SentenceSelector.articleIdOf("s:12:"))
    }

    // ------------------------------------------------------------------
    // 合格句过滤
    // ------------------------------------------------------------------

    @Test
    fun `合格句长度与内容过滤边界`() {
        assertTrue(SentenceSelector.isEligible("春眠不觉晓处处闻啼鸟"))
        assertTrue(SentenceSelector.isEligible("The quick brown fox jumps"))
        // 过短（trim 后 5 字）→ 不合格
        assertTrue(!SentenceSelector.isEligible("春眠不觉晓"))
        // 过长（>80 字）→ 不合格
        assertTrue(!SentenceSelector.isEligible("字".repeat(81)))
        // 纯标点 / 纯数字 / 单字母 → 不合格
        assertTrue(!SentenceSelector.isEligible("。。。。。。"))
        assertTrue(!SentenceSelector.isEligible("12345678"))
        assertTrue(!SentenceSelector.isEligible("a"))
    }

    @Test
    fun `候选切句按 hash 去重且保留首处位置`() {
        val content = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。春眠不觉晓，处处闻啼鸟。"
        val cands = SentenceSelector.candidates(article(1, content))
        assertEquals(2, cands.size)
        assertEquals("春眠不觉晓，处处闻啼鸟。", cands[0].text)
        assertEquals("夜来风雨声，花落知多少。", cands[1].text)
        assertEquals(1, cands[0].articleId.toInt())
        assertEquals(0, cands[0].start)
        assertEquals(12, cands[0].end)
        assertEquals(12, cands[1].start)
        assertEquals(24, cands[1].end)
    }

    // ------------------------------------------------------------------
    // 到期优先
    // ------------------------------------------------------------------

    @Test
    fun `pickDue 取最逾期句且忽略未到期与文章键`() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val kOld = SentenceSelector.sentenceKey(1, "最逾期的句子内容")
        val kNew = SentenceSelector.sentenceKey(2, "刚到期不久的句子")
        val states = mapOf(
            kOld to state(due = now - 3 * day, lastReview = now - 10 * day),
            kNew to state(due = now - 1 * day, lastReview = now - 2 * day),
            // 未到期：不得选中
            SentenceSelector.sentenceKey(3, "还没到期的句子") to state(due = now + 5 * day, lastReview = now),
            // 文章级状态混入（真实调用方传的是句子状态表，这里验证前缀过滤兜底）
            "12" to state(due = now - 9 * day, lastReview = now - 20 * day),
        )
        assertEquals(kOld, SentenceSelector.pickDue(states, now))
        assertNull(SentenceSelector.pickDue(emptyMap(), now))
    }

    // ------------------------------------------------------------------
    // 新句抽取
    // ------------------------------------------------------------------

    @Test
    fun `pickNew 回避已抽过的句子`() {
        val a = article(1, "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。")
        val seen = SentenceSelector.sentenceKey(1, "春眠不觉晓，处处闻啼鸟。")
        val picked = SentenceSelector.pickNew(
            listOf(a),
            mapOf(seen to state(due = 1L, lastReview = 1L)),
            Random(1),
        )
        assertTrue(picked != null)
        assertNotEquals(seen, picked!!.key)
        assertEquals("夜来风雨声，花落知多少。", picked.text)
    }

    @Test
    fun `pickNew 全部抽过时兜底取最久未复习的句子`() {
        val a = article(1, "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。")
        val kRecent = SentenceSelector.sentenceKey(1, "春眠不觉晓，处处闻啼鸟。")
        val kOld = SentenceSelector.sentenceKey(1, "夜来风雨声，花落知多少。")
        val picked = SentenceSelector.pickNew(
            listOf(a),
            mapOf(
                kRecent to state(due = 9L, lastReview = 500L),
                kOld to state(due = 9L, lastReview = 100L),
            ),
            Random(1),
        )
        assertEquals(kOld, picked?.key)
    }

    @Test
    fun `pickNew 文章轮转优先最久未抽过句子的文章`() {
        // 文章 2 的句子最近复习过；文章 1 从未抽过 → 轮转候选头位应给文章 1
        val a1 = article(1, "山重水复疑无路，柳暗花明又一村。")
        val a2 = article(2, "会当凌绝顶，一览众山小。")
        val seenInA2 = SentenceSelector.sentenceKey(2, "会当凌绝顶，一览众山小。")
        val picked = SentenceSelector.pickNew(
            listOf(a2, a1),
            mapOf(seenInA2 to state(due = 9L, lastReview = 9_999_999L)),
            Random(7),
        )
        assertEquals(1L, picked?.articleId)
    }

    @Test
    fun `pickNew 无文章与无合格句均返回 null`() {
        assertNull(SentenceSelector.pickNew(emptyList(), emptyMap(), Random(1)))
        assertNull(
            SentenceSelector.pickNew(
                listOf(article(1, "。！？"), article(2, "1234567890")),
                emptyMap(),
                Random(1),
            )
        )
    }
}
