// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小样本比率的置信收缩：分档与排序共用的那份数学 */
class WeaknessMathTest {

    @Test
    fun `一次错一次不得算成薄弱`() {
        // 旧行为：1÷1 = 100% 直接越过 0.7 的「薄弱」线，新用户第一篇文章整屏铺满深红
        val adjusted = WeaknessMath.adjustedErrorRate(errors = 1, total = 1, prior = 0.2f)
        assertTrue("收缩后仍达 $adjusted，深红档会把它标成薄弱", adjusted < 0.7f)
    }

    @Test
    fun `样本够时观测值占主导`() {
        // 同样的先验，练 40 次错 20 次就该基本承认 50%
        val adjusted = WeaknessMath.adjustedErrorRate(errors = 20, total = 40, prior = 0.2f)
        assertEquals(0.5f, adjusted, 0.05f)
    }

    @Test
    fun `收缩只会把小样本推向整篇均值`() {
        // 收缩的方向性：观测 100% 被压下来、观测 0% 被抬上去，结果一律落在观测值与先验之间。
        // 「谁更该练」的排序靠的是下面那条够样本门槛，不是靠把一次作答排到三十次之后 ——
        // 那样等于假装一次数据里有信息，正好是这套数学要反对的事。
        val highObserved = WeaknessMath.adjustedErrorRate(errors = 1, total = 1, prior = 0.3f)
        assertTrue("收缩后应落在 (0.3, 1) 区间：$highObserved", highObserved > 0.3f && highObserved < 1f)
        val lowObserved = WeaknessMath.adjustedErrorRate(errors = 0, total = 1, prior = 0.3f)
        assertTrue("收缩后应落在 (0, 0.3) 区间：$lowObserved", lowObserved > 0f && lowObserved < 0.3f)
    }

    @Test
    fun `够不够样本按作答次数判而不按比率判`() {
        // 1 次全错与 30 次错 9 次比率都不小，只有次数能把前者挡在分档与榜单之外
        assertFalse(WeaknessMath.enoughSample(WeaknessMath.MIN_SAMPLE - 1))
        assertTrue(WeaknessMath.enoughSample(WeaknessMath.MIN_SAMPLE))
    }

    @Test
    fun `先验按整篇均值走`() {
        // prior 高（这篇本来就老出错）时，同样的小样本被抬起来；prior=0 时被压下去
        assertTrue(
            WeaknessMath.adjustedErrorRate(0, 2, 0.6f) >
                WeaknessMath.adjustedErrorRate(0, 2, 0.05f)
        )
    }

    @Test
    fun `没有作答就没有结论`() {
        assertEquals(0f, WeaknessMath.adjustedErrorRate(0, 0, 0.5f), 0f)
        assertFalse(WeaknessMath.enoughSample(0))
        assertTrue(WeaknessMath.enoughSample(WeaknessMath.MIN_SAMPLE))
    }

    @Test
    fun `练对的句子会自己退出薄弱池`() {
        // 这是 E3 要买的东西：旧口径按「历史上错过几次」的相对排名选段，
        // 错过一次就永远在池子里；新口径的分母是【被作答的会话数】，所以练熟了会自己往下走。
        val content = "床前明月光。疑是地上霜！举头望明月？低头思故乡。"
        val starts = SentenceSplitter.splitWithPositions(content).map { it.startIndex }
        val wrong = record(starts = listOf(starts[0]), mistakes = listOf(0))
        val clean = record(starts = listOf(starts[0]), mistakes = emptyList())

        val before = WeaknessMath.sentenceWeakness(List(3) { wrong }, content).getValue(0)
        assertTrue("三次都错应被判弱：$before", before >= WeaknessMath.WEAK_RATE_THRESHOLD)

        // 同一句后来连续练对九次：弱度应跌到门槛以下，自动退出集训池
        val after = WeaknessMath.sentenceWeakness(List(3) { wrong } + List(9) { clean }, content)
            .getValue(0)
        assertTrue("连续练对九次后仍被判弱：$after", after < WeaknessMath.WEAK_RATE_THRESHOLD)
    }

    @Test
    fun `样本不足的句子不进薄弱池`() {
        val content = "床前明月光。疑是地上霜！举头望明月？低头思故乡。"
        val starts = SentenceSplitter.splitWithPositions(content).map { it.startIndex }
        val once = WeaknessMath.sentenceWeakness(
            listOf(record(starts = listOf(starts[2]), mistakes = listOf(2))), content
        )
        assertTrue("只练过一次就够格算薄弱，那是噪声：$once", once.isEmpty())
    }

    @Test
    fun `选段只挑过门槛的段落`() {
        val ranked = listOf(0 to 0.9f, 1 to 0.4f, 2 to 0.2f, 3 to 0f)
        assertEquals(setOf(0, 1), WeaknessMath.weakSectionIndexes(ranked, WeaknessMath.WEAK_RATE_THRESHOLD))
        // 一个都没过门槛 → 空集，由调用方回退全篇，而不是"有点错就算弱"
        assertTrue(WeaknessMath.weakSectionIndexes(listOf(0 to 0.1f), 0.3f).isEmpty())
    }

    private fun record(starts: List<Int>, mistakes: List<Int>) =
        com.ilyskyo.blancall.data.model.PracticeRecord(
            articleId = 3L,
            mode = "SENTENCE",
            totalBlanks = starts.size,
            correctCount = starts.size - mistakes.size,
            timestamp = 1_700_000_000_000L,
            answeredSentenceStarts = starts,
            mistakeSentenceIndices = mistakes
        )
}
