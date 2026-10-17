// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import androidx.compose.ui.graphics.Color
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆热力图的落点与分档回归。
 *
 * 分两代：
 * - 落点类（practiceCount 归句、错题落点、估算记号）是「当场显示错数」那一类的修复回归；
 * - 分档类（B3-2 换指标后）验证**颜色 = 当前留存率**：刚练熟的高错误率句子不再被染红、
 *   句级练习不足走灰、无状态走灰/无色、未练无色。
 *
 * 句子锚点一律现算（[SentenceSplitter.splitWithPositions]），不写死下标 —— 切句口径一改，
 * 写死下标的测试会红得没有信息量。`now` 固定注入（不留 `System.currentTimeMillis()`），
 * 否则留存的断言会随运行时刻漂移。
 */
class MemoryHeatmapTest {

    private val content = "床前明月光。疑是地上霜！举头望明月？低头思故乡。"
    private val NOW = 1_800_000_000_000L

    private fun sentenceTexts(): List<String> =
        SentenceSplitter.splitWithPositions(content).map { it.text }

    private fun starts(): List<Int> =
        SentenceSplitter.splitWithPositions(content).map { it.startIndex }

    private fun keyFor(idx: Int): String = SentenceSelector.sentenceKey(7L, sentenceTexts()[idx])

    private fun colorOf(band: String): Color =
        MemoryHeatmap.legend().first { it.first == band }.second

    private fun lowSampleColor(): Color = colorOf(MemoryHeatmap.BAND_LOW_SAMPLE)

    private fun record(
        mistakes: List<MistakeDetail>,
        answeredStarts: List<Int>,
        totalBlanks: Int,
        correctCount: Int
    ) = PracticeRecord(
        articleId = 7L,
        mode = "SENTENCE",
        totalBlanks = totalBlanks,
        correctCount = correctCount,
        mistakes = mistakes,
        timestamp = 1_700_000_000_000L,
        answeredSentenceStarts = answeredStarts
    )

    /** 构造一个「刚复习完」的状态：elapsed=0 → 留存率 1.0 */
    private fun freshState(reviewCount: Int) = FsrsEngine.CardState(
        stability = 30.0, lastReview = NOW, reviewCount = reviewCount
    )

    /** 构造一个「很久没复习」的状态：elapsed 远大于 S → 留存率趋近 0 */
    private fun staleState(reviewCount: Int) = FsrsEngine.CardState(
        stability = 0.5, lastReview = NOW - 60L * 24 * 60 * 60 * 1000, reviewCount = reviewCount
    )

    @Test
    fun `未作答的句子不再有练习次数`() {
        // 只答了第 1 句且全对：其余三句从没被练过，practiceCount 必须是 0。
        // 旧实现这里填的是 records.size（整篇会话数），UI 那句「错 X%」的门控因此形同虚设，
        // 从没答过的句子也显示「错 0%」，用户读成「这句掌握了」。
        val data = MemoryHeatmap.generate(
            content, listOf(record(emptyList(), listOf(starts()[0]), 1, 1)),
            articleId = 7L, now = NOW
        )
        assertEquals(1, data.sentences[0].practiceCount)
        assertTrue(data.sentences.drop(1).all { it.practiceCount == 0 })
        assertTrue(data.sentences.drop(1).all { it.heatColor == Color.Unspecified })
        // 「未作答」与「样本不足」是两种状态：前者的 practiceCount 是 0 且 lowSample 必须为 false，
        // UI 才能把它们分成两支文案（「还未练到此句」/「样本不足」），而不是都读成「没结论」。
        assertTrue(data.sentences.drop(1).all { !it.lowSample })
    }

    @Test
    fun `错题按落点归到那一句而不是平摊`() {
        // 三次练习、每次都在第 3 句错一个空：只有第 3 句计错。
        // 旧实现按 blankIndex/totalBlanks 比例猜句，同一份数据会被摊到别的句子上，
        // 于是「亮红的句子」和「真正写错的那句」不是同一句。
        val records = List(3) { i ->
            record(listOf(MistakeDetail(blankIndex = i, "明月", "明亮", "TYPO", sentenceIndex = 2)),
                listOf(starts()[2]), 1, 0)
        }
        // 配一个「很久没复习」的状态：颜色走留存分档，深红 = 快忘了
        val states = mapOf(keyFor(2) to staleState(reviewCount = 3))
        val data = MemoryHeatmap.generate(content, records, states, articleId = 7L, now = NOW)
        val third = data.sentences[2]
        assertEquals(3, third.practiceCount)
        assertEquals(1f, third.errorRate, 1e-6f)
        assertFalse("落点是量出来的，不该标成估算", third.estimated)
        assertTrue(data.sentences.filterIndexed { i, _ -> i != 2 }.all { it.errorRate == 0f })
        // 状态在、样本够 → 落入留存色档：久未复习应为深红（快忘了）
        assertEquals(colorOf(MemoryHeatmap.BAND_WEAK), third.heatColor)
    }

    @Test
    fun `一句里错三个空不会算出超过百分之百`() {
        // 分子曾是「错空数」、分母是「被作答的会话数」，一句里错 3 个空而只练过 1 次时
        // UI 会显示「错300%」。现在两边都按会话计：一句在一次会话里错多少空都算「这次错了」。
        val mistakes = List(3) { MistakeDetail(blankIndex = it, "霜", "雾", "TYPO", sentenceIndex = 1) }
        val data = MemoryHeatmap.generate(
            content, listOf(record(mistakes, listOf(starts()[1]), 3, 0)),
            articleId = 7L, now = NOW
        )
        val second = data.sentences[1]
        assertEquals(1, second.practiceCount)
        assertTrue("错误率越界：${second.errorRate}", second.errorRate <= 1f)
        // headline 那个整篇错误率仍是空级的（它讲的是这篇的正确率，不是同一件事）
        assertEquals(1f, data.overallErrorRate, 1e-6f)
    }

    @Test
    fun `旧记录没有落点时按估算并打上记号`() {
        // sentenceIndex = -1（字段落盘之前的记录）→ 比例估算，并把该句标成 estimated，
        // UI 才有依据说「位置为估算」，而不是把猜的结果和量出来的用同一副面孔展示。
        val mistakes = listOf(MistakeDetail(blankIndex = 0, "霜", "雾", "TYPO", sentenceIndex = -1))
        val data = MemoryHeatmap.generate(
            content, listOf(record(mistakes, listOf(starts()[1]), 1, 0)),
            articleId = 7L, now = NOW
        )
        val estimated = data.sentences.filter { it.estimated }
        assertTrue("估算路径必须留下记号", estimated.isNotEmpty())
        assertFalse("有精确落点的那句不该被标成估算", data.sentences[1].estimated)
    }

    @Test
    fun `句级练习不足三次不给色，三次起参与留存分档`() {
        // B3-2 后门槛的载体从「会话数」改成「句级 FSRS 的 reviewCount」：
        // 2 次不给结论、3 次必须给 —— 边界两边都要说清（UI 会把 MIN_SAMPLE 写进说明行）。
        val wrong = { record(listOf(MistakeDetail(0, "霜", "雾", "TYPO", 1)), listOf(starts()[1]), 1, 0) }

        val twice = MemoryHeatmap.generate(
            content, listOf(wrong(), wrong()),
            mapOf(keyFor(1) to staleState(reviewCount = 2)), articleId = 7L, now = NOW
        )
        val two = twice.sentences[1]
        assertTrue("句级练习 2 次仍不足以定档", two.lowSample)
        assertEquals(lowSampleColor(), two.heatColor)

        val thrice = MemoryHeatmap.generate(
            content, List(WeaknessMath.MIN_SAMPLE) { wrong() },
            mapOf(keyFor(1) to staleState(reviewCount = WeaknessMath.MIN_SAMPLE)),
            articleId = 7L, now = NOW
        )
        val three = thrice.sentences[1]
        assertEquals(WeaknessMath.MIN_SAMPLE, three.practiceCount)
        assertFalse("满 ${WeaknessMath.MIN_SAMPLE} 次起必须参与分档，不再走灰色样本不足", three.lowSample)
        assertTrue("达标后必须落到某个色档", three.heatColor != lowSampleColor())
        assertTrue(three.heatColor != Color.Unspecified)
    }

    @Test
    fun `刚练熟的高错误率句子不再被染红`() {
        // B3-2 的核心升级断言：历史错得多（errorRate=1）不再等于红 ——
        // 那句如果刚刚复习过（留存≈1），它就是牢固的绿；只有久未复习（留存≈0）才是红。
        val records = List(3) { i ->
            record(listOf(MistakeDetail(blankIndex = i, "明月", "明亮", "TYPO", sentenceIndex = 2)),
                listOf(starts()[2]), 1, 0)
        }
        val fresh = MemoryHeatmap.generate(
            content, records, mapOf(keyFor(2) to freshState(reviewCount = 5)),
            articleId = 7L, now = NOW
        )
        val stale = MemoryHeatmap.generate(
            content, records, mapOf(keyFor(2) to staleState(reviewCount = 5)),
            articleId = 7L, now = NOW
        )
        assertEquals(1f, fresh.sentences[2].errorRate, 1e-6f)
        assertEquals(colorOf(MemoryHeatmap.BAND_FIRM), fresh.sentences[2].heatColor)
        assertEquals(colorOf(MemoryHeatmap.BAND_WEAK), stale.sentences[2].heatColor)
    }

    @Test
    fun `没有句级状态但练过的句子走灰`() {
        // 旧记录（字段落盘前）练过但没有 FSRS 状态：留存算不出，颜色不可下结论 ——
        // retention 标记为 -1、lowSample 为 true（UI 才会说「暂无留存评估」而不是「差几次」）。
        val wrong = record(listOf(MistakeDetail(0, "霜", "雾", "TYPO", 1)), listOf(starts()[1]), 1, 0)
        val data = MemoryHeatmap.generate(content, List(3) { wrong }, articleId = 7L, now = NOW)
        val second = data.sentences[1]
        assertEquals(3, second.practiceCount)
        assertEquals(-1f, second.retention, 1e-6f)
        assertTrue(second.lowSample)
        assertEquals(lowSampleColor(), second.heatColor)
    }

    @Test
    fun `留存率分档阈值`() {
        // 阈值即产品语义：<55% 快忘了、<75% 模糊、<90% 接近目标线、<97% 熟悉、之上牢固。
        assertEquals(colorOf(MemoryHeatmap.BAND_WEAK), MemoryHeatmap.retentionToColor(0.5f))
        assertEquals(colorOf(MemoryHeatmap.BAND_HARD), MemoryHeatmap.retentionToColor(0.6f))
        assertEquals(colorOf(MemoryHeatmap.BAND_MID), MemoryHeatmap.retentionToColor(0.8f))
        assertEquals(colorOf(MemoryHeatmap.BAND_FAMILIAR), MemoryHeatmap.retentionToColor(0.95f))
        assertEquals(colorOf(MemoryHeatmap.BAND_FIRM), MemoryHeatmap.retentionToColor(0.99f))
    }

    @Test
    fun `图例覆盖全部档位且不携带展示文案`() {
        // 图例给的是稳定 ASCII 键：数据层一旦持有中文，六语用户的统计页上就会出现简体，
        // 而 res 目录那套多语言守卫看不见 .kt 里的字面量（本项目踩过，徽章就是那样漏出去的）
        val bands = MemoryHeatmap.legend()
        assertEquals(7, bands.size)
        assertTrue(bands.all { it.first.none { c -> c in '一'..'鿿' } })
        // 「未练」是唯一没有颜色的一档：未作答的句子在界面上就是无色条，
        // 给它任何颜色都会被读成一条结论 —— 与「样本不足」用灰而不用红/绿同一个理由。
        assertEquals(
            Color.Unspecified,
            bands.first { it.first == MemoryHeatmap.BAND_UNPRACTICED }.second
        )
        assertTrue(
            bands.filter { it.first != MemoryHeatmap.BAND_UNPRACTICED }
                .all { it.second != Color.Unspecified }
        )
    }
}
