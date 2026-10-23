// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判题等价类与错误分型的回归。
 *
 * 要守住的性质有三条，都是「同一次提交算出两个互不相同的判定」那一族的反面：
 * ① 「怎么算等价」全 app 只有一份 —— 页面判错而评级记成 GOOD 会污染记忆状态、把下次排期推后；
 * ② 分型按差异结构判 —— 门限只管「算不算对」，不管「这一处算漏字还是记错」；
 * ③ 口径用例必须从最外层入口进入 —— 形近/等价表都是「归一化后查表」，
 *    绕过 TextNormalizer 的单测只能证明表自洽，证明不了功能成立（这条锁的是口径，不是算法）。
 */
class AnswerEquivalenceTest {

    // ── ① 等价类：两条通道同一结论 ──

    @Test
    fun `繁体作答在两条通道上得到同一个结论`() {
        // 旧实现：字词/句子挖空走 AnswerChecker（不折繁简）判错，
        // 而 FSRS 评级走 TextNormalizer（折表内繁体）把相似度抬高 —— 用户看到红的、系统记成 GOOD
        val correct = "祖国山河"
        val traditional = "祖國山河"
        assertEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check(correct, traditional).result)
        assertEquals(1f, DictationScorer.score(correct, traditional), 1e-3f)
    }

    @Test
    fun `英文大小写不再一分为二`() {
        // 页面忽略大小写、评级通道原先区分：英文篇目与拉丁手写都会踩
        assertEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check("Moon", "MOON").result)
        assertEquals(1f, DictationScorer.score("Moon", "MOON"), 1e-3f)
    }

    @Test
    fun `中文数字与阿拉伯数字等价`() {
        assertEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check("三月", "3月").result)
        assertEquals(1f, DictationScorer.score("三月", "3月"), 1e-3f)
    }

    @Test
    fun `标点只在评级通道里扣分`() {
        // 「算不算对」不看标点，「扣多少分」要看 —— 同一个开关，两条路只差这一处
        assertEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check("明月", "明月。").result)
        val withPunct = DictationScorer.score("明月", "明月。")
        val exact = DictationScorer.score("明月", "明月")
        assertTrue("漏标点应当被扣一点分：$exact vs $withPunct", withPunct < exact)
    }

    @Test
    fun `数字答案的小数点不被当标点删掉`() {
        assertEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check("3.14", "3.14").result)
        // 小数点被当成标点删掉的话，3.14 与 314 就会被判成同一个答案
        assertNotEquals(AnswerChecker.Result.CORRECT, AnswerChecker.check("3.14", "314").result)
    }

    // ── ② 分型：结构而不是距离 ──

    @Test
    fun `单字空不再恒判不正确`() {
        // 旧实现：长度相等→跳过 MISSING/EXTRA；排序不等→非 WRONG_ORDER；
        // ratio = 1/1 = 1.0 > 0.5 → TYPO 门也失败 → 字词挖空恒 INCORRECT
        assertEquals(AnswerChecker.Result.TYPO, AnswerChecker.check("月", "明").result)
    }

    @Test
    fun `长句错三个字终于算记错而不是完全不对`() {
        // 旧实现 editDist=3 > 2 → 恒 INCORRECT，于是「漏字 vs 记错 vs 顺序颠倒」
        // 这个产品上很好用的区分在最主流的作答形态上根本不出现
        val d = AnswerChecker.check("举头望明月", "举头望名月")
        assertEquals(AnswerChecker.Result.TYPO, d.result)
    }

    @Test
    fun `少字与多字按结构判而不按差几个字判`() {
        assertEquals(AnswerChecker.Result.MISSING, AnswerChecker.check("举头望明月", "举头望").result)
        assertEquals(AnswerChecker.Result.EXTRA, AnswerChecker.check("举头望明月", "举头望明月光").result)
        // 旧门限下这两个都是 editDist=2 恰好过线；改成结构判据后，差 3 个字同样归这两类
        assertEquals(AnswerChecker.Result.MISSING, AnswerChecker.check("明月几时有", "月几时").result)
    }

    @Test
    fun `字都写对只是顺序颠倒`() {
        assertEquals(AnswerChecker.Result.WRONG_ORDER, AnswerChecker.check("明月", "月明").result)
        assertEquals(AnswerChecker.Result.WRONG_ORDER, AnswerChecker.check("举头望明月", "望明月举头").result)
    }

    @Test
    fun `没记住的就是没记住`() {
        // 阈值另一侧：内容几乎全不一样时不许被抬成「记错」，否则错题分布会假装问题很轻
        assertEquals(AnswerChecker.Result.INCORRECT, AnswerChecker.check("床前明月光", "东西南北中").result)
    }

    @Test
    fun `换字分两类：形近落字形混淆，不相干落记成他字`() {
        // 同一位置上换字，两类错因的复习动作不同（辨析字形 vs 补记忆），必须分开
        assertEquals(AnswerChecker.Result.GLYPH_CONFUSED, AnswerChecker.check("己所不欲", "已所不欲").result)
        assertEquals(AnswerChecker.Result.GLYPH_CONFUSED, AnswerChecker.check("未雨绸缪", "末雨绸缪").result)
        assertEquals(AnswerChecker.Result.TYPO, AnswerChecker.check("己所不欲", "乙所不欲").result)
        // 一处形近、一处不相干 → 整体是「没记住那一类」，不许被形近那处抬成字形混淆
        assertEquals(AnswerChecker.Result.TYPO, AnswerChecker.check("未春", "末秋").result)
    }

    @Test
    fun `形近表在归一化之后仍然命中（口径用例）`() {
        // 口径用例（**不是**算法用例）：判题拿到的是 TextNormalizer 归一化后的串
        // （末尾 lowercase + 全角折半角），形近表若按归一化前的字形写（大写 S、全角数字），
        // 表自测全绿、线上一次都不命中。这类缺陷只有从最外层 check() 进入才测得出来 ——
        // classifyDiff 吃原始串，会绕过归一化。
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("S5", "55").result
        )
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("S5", "５５").result
        )
        // 反向对照：中文形近不经归一化改写，仍要命中（证明这条口径没把正常路径挤掉）
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("己所不欲", "已所不欲").result
        )
    }

    @Test
    fun `分型结果全部落在错误类型枚举里`() {
        // ErrorType 缺 INCORRECT 那阵子，盘上最普遍的一类错在枚举里查无此值，
        // 汇总与雷达各按自己的口径数 —— 这里把「写方能产出的名字全在枚举里」钉住
        val produced = listOf(
            AnswerChecker.check("明月", "月明").result,
            AnswerChecker.check("明月", "明").result,
            AnswerChecker.check("明月", "明星").result,
            AnswerChecker.check("明月", "阳光").result,
            AnswerChecker.check("明月", "").result,
            AnswerChecker.check("己所不欲", "已所不欲").result
        ).map { it.name }
        produced.forEach { name ->
            assertTrue("$name 不在 ErrorType 枚举里，统计会静默丢数",
                ErrorType.entries.any { it.name == name })
        }
    }
}
