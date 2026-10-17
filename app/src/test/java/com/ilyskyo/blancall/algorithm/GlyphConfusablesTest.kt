// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形近字表的守卫与判据测试。
 *
 * 表是人工整理的，所以最容易出的错不是「判据写错」而是**表本身有结构问题**：
 * 一个字被登记进两个组时，后登记的组会静默覆盖先登记的（[GlyphConfusables] 内部是
 * 字 → 组号的 Map），于是某个组的一半成员突然不再互判 —— 界面上只会表现为
 * 「这类错就是抓不到」，没有任何报错。所以这几条断言是把表当数据来校验，
 * 而不是只测几个举例。
 */
class GlyphConfusablesTest {

    // ── 表结构自检 ──

    @Test
    fun `每个组至少两个字`() {
        val bad = GlyphConfusables.groups.filter { it.size < 2 }
        assertTrue("这些组只有一个字，形近无从谈起：$bad", bad.isEmpty())
    }

    @Test
    fun `一个字只能出现在一个组里`() {
        val seen = mutableMapOf<Char, Int>()
        val dup = mutableListOf<String>()
        GlyphConfusables.groups.forEachIndexed { gi, g ->
            g.forEach { c ->
                val prev = seen.put(c, gi)
                if (prev != null) dup += "「$c」同时在第 $prev 组与第 $gi 组"
            }
        }
        assertTrue(
            "重复登记会让先登记的组静默失效（后写覆盖先写）：\n" + dup.joinToString("\n"),
            dup.isEmpty()
        )
    }

    @Test
    fun `表非空且规模合理`() {
        assertTrue("形近组少于 30 个，多半是表被清空了", GlyphConfusables.groups.size >= 30)
    }

    /**
     * 约束 ④：组员必须是「归一化后保持原样」的字符。
     *
     * 判题只拿**归一化后**的串进 [GlyphConfusables]（见 [AnswerChecker.core]），
     * 所以任何在归一化时会被改写的字 —— 大写→小写、中文数字→阿拉伯数字 —— 放进来都永不命中：
     * 表看着完整、守卫全绿，功能却半瘫。
     *
     * 这条断言是补的，因为前三类结构检查都查不出它。真出过两次：
     * ① 拉丁组整体写成大写（'S'/'Z'/'C'/'K'/'P'/'I'/'O'），归一化后全变小写，7 组永不触发；
     * ② 「匕/七」「丸/九」两组 —— 中文数字会被折成 7/9，这两组同样永不触发。
     */
    @Test
    fun `组员必须归一化后保持原样`() {
        val bad = GlyphConfusables.groups.flatten().distinct().filter { c ->
            TextNormalizer.forAnswerCompare(c.toString()) != c.toString()
        }
        assertTrue(
            "这些字在归一化后会被改写，放进表里永远不会命中：\n" +
                bad.joinToString("\n") { c ->
                    "「$c」→ 归一化后是「${TextNormalizer.forAnswerCompare(c.toString())}」"
                },
            bad.isEmpty()
        )
    }

    /**
     * 上一条断言的**自检**：证明它不是「恒真」的空断言。
     *
     * 守卫最怕的不是漏报，而是看起来在跑、实际永远绿。这里拿历史上真出过问题的那几个字符
     * （大写拉丁 + 中文数字）喂进同一条判据，必须能被检出 —— 否则上一条的 `bad.isEmpty()`
     * 只是在陈述「表是空的」，与可达性无关。
     */
    @Test
    fun `可达性判据对已知坏例必须报红`() {
        val knownBad = listOf('S', 'Z', 'C', 'K', 'P', 'I', 'O', '七', '九')
        val missed = knownBad.filter { c ->
            TextNormalizer.forAnswerCompare(c.toString()) == c.toString()
        }
        assertTrue(
            "这些字符在归一化后本应被改写，判据却没检出（说明判据失效）：$missed",
            missed.isEmpty()
        )
    }

    // ── 判据 ──

    @Test
    fun `同组互判为形近`() {
        assertTrue(GlyphConfusables.isGlyphConfused('己', '已'))
        assertTrue(GlyphConfusables.isGlyphConfused('已', '己'))
        assertTrue(GlyphConfusables.isGlyphConfused('未', '末'))
        assertTrue(GlyphConfusables.isGlyphConfused('戍', '戌'))
        assertTrue(GlyphConfusables.isGlyphConfused('l', '1'))
    }

    @Test
    fun `同字不算混淆`() {
        assertFalse(GlyphConfusables.isGlyphConfused('己', '己'))
        assertFalse(GlyphConfusables.isGlyphConfused('a', 'a'))
    }

    @Test
    fun `跨组与表外一律不判形近`() {
        // 跨组：己 在一组、日 在另一组 —— 两个都是「差一笔」类，但不是同一个组
        assertFalse(GlyphConfusables.isGlyphConfused('己', '日'))
        // 表外：完全不相干
        assertFalse(GlyphConfusables.isGlyphConfused('春', '秋'))
        assertFalse(GlyphConfusables.isGlyphConfused('己', 'Q'))
        // 同音但不同形：表里不放同音（判题也不辨读音），必须落「记成他字」
        assertFalse(GlyphConfusables.isGlyphConfused('清', '情'))
    }

    @Test
    fun `等长替换且全部位置形近才算字形混淆`() {
        // 单字：己 → 已
        assertTrue(GlyphConfusables.isGlyphConfusedSubstitution("己", "已"))
        // 长句里只错一处形近：己 → 巳
        assertTrue(GlyphConfusables.isGlyphConfusedSubstitution("己所不欲勿施于人", "巳所不欲勿施于人"))
        assertTrue(GlyphConfusables.isGlyphConfusedSubstitution("未末不分", "末末不分"))
        // 两处同时错，但都是形近对（未→末、戌→戍）→ 仍算字形混淆
        assertTrue(GlyphConfusables.isGlyphConfusedSubstitution("未戌", "末戍"))
    }

    @Test
    fun `只要有一处不是形近就不算字形混淆`() {
        // 「己」错了（形近），「春」也错了（不相干）→ 整体落「记成他字」
        assertFalse(GlyphConfusables.isGlyphConfusedSubstitution("己春", "已秋"))
    }

    @Test
    fun `长度不同或完全相同都不算替换`() {
        assertFalse(GlyphConfusables.isGlyphConfusedSubstitution("己", "己已"))
        assertFalse(GlyphConfusables.isGlyphConfusedSubstitution("己", "己"))
        assertFalse(GlyphConfusables.isGlyphConfusedSubstitution("", ""))
    }

    @Test
    fun `对称性：任取同组两字两个方向都成立`() {
        GlyphConfusables.groups.forEach { g ->
            val list = g.toList()
            for (i in list.indices) {
                for (j in list.indices) {
                    if (i == j) continue
                    assertTrue(
                        "「${list[i]}」与「${list[j]}」同组却不对称",
                        GlyphConfusables.isGlyphConfused(list[i], list[j]) ==
                            GlyphConfusables.isGlyphConfused(list[j], list[i])
                    )
                    assertTrue(
                        "同组的「${list[i]}」与「${list[j]}」应互判为形近",
                        GlyphConfusables.isGlyphConfused(list[i], list[j])
                    )
                }
            }
        }
    }

    @Test
    fun `组与组之间互不干扰`() {
        val reps = GlyphConfusables.groups.map { it.first() }
        for (i in reps.indices) {
            for (j in reps.indices) {
                if (i == j) continue
                assertFalse(
                    "第 $i 组代表「${reps[i]}」不该与第 $j 组代表「${reps[j]}」互判形近",
                    GlyphConfusables.isGlyphConfused(reps[i], reps[j])
                )
            }
        }
    }

    @Test
    fun `判题接得上：己写成已落字形混淆`() {
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.classifyDiff("己所不欲", "已所不欲")
        )
        assertEquals(
            AnswerChecker.Result.TYPO,
            AnswerChecker.classifyDiff("己所不欲", "乙所不欲")
        )
    }

    /**
     * 口径用例（**不是**算法用例）：形近判定必须**经过归一化**之后仍然成立。
     *
     * 这里刻意走 [AnswerChecker.check] 而不是 [AnswerChecker.classifyDiff]：
     * 后者吃的是原始串，绕过 `TextNormalizer` 的 lowercase()，于是「表里写大写也能过」——
     * 真出过：拉丁组整体写成大写，上面那条 `classifyDiff` 用例全绿，而 app 内一次都不触发。
     * 纯函数测试只能证明「表自洽」，证明不了「功能成立」，这条锁的是后者。
     */
    @Test
    fun `归一化之后形近判定仍然成立`() {
        // 英文大小写：S 会被折成 s，表里必须写小写才命中
        assertEquals(
            "拉丁字母必须经过 lowercase 后仍命中",
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("S5", "55").result
        )
        // 全角数字：归一化里的「全角转半角」也要被覆盖
        assertEquals(
            "全角数字必须经过全角转半角后仍命中",
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("S5", "５５").result
        )
        // 中文形近：己 → 已
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("己所不欲", "已所不欲").result
        )
        // 表外的不相干字仍必须落「记成他字」，不能被形近表误收
        assertEquals(
            AnswerChecker.Result.TYPO,
            AnswerChecker.check("己所不欲", "乙所不欲").result
        )
    }

    /**
     * 已知边界（**刻意钉住，不是待修的缺陷**）：中文数字折成阿拉伯数字后，会与拉丁数字组同形。
     *
     * `TextNormalizer` 把「九」折成 `'9'`（「三」≡「3」是产品既定口径），而 `'9'` 在拉丁组
     * `g/9` 里 —— 于是「答案写 九、用户写 g」会被判成字形混淆。这是「折数字」与「形近表」
     * 两个特性叠加的必然结果，且 9 与 g 本来就是形近的，所以不修。
     *
     * 单独写一条是为了让后人改归一化或改表时**立刻看到这层耦合**：
     * 若将来把中文数字从归一化里拿掉，这条会红，那正是需要重新决策的信号。
     */
    @Test
    fun `中文数字折成阿拉伯数字后与拉丁数字同形是已知边界`() {
        assertEquals(
            AnswerChecker.Result.GLYPH_CONFUSED,
            AnswerChecker.check("九", "g").result
        )
        // 但中文形近组不受影响：九 与 丸 已不再同组（丸 的另一半在归一化后不存在）
        assertEquals(
            AnswerChecker.Result.TYPO,
            AnswerChecker.check("丸", "九").result
        )
    }
}
