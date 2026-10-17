// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 形近字表：判「这一处错是**字形混淆**还是**记成他字**」的唯一依据。
 *
 * 为什么需要它：判题原先只有 TYPO 一个「换了别的字」的桶，展示名还叫「错别字」——
 * 于是「你把『己』写成了『已』」这类事实被包装成「笔误」，而实际可能是没记清字形。
 * 「写成形近字」（看得见、手上错）与「写成不相干的字」（想不起来、记错）是两种错因，
 * 复习策略也不同（前者练辨析、后者补记忆），所以要把它们分开。
 *
 * 判据是**表**而不是算法：字形是否相近没有可计算的度量（笔画数、部件、编码距离都不成立），
 * 只能拿一份人工整理的常见形近组来查。表只收「真会写混」的组，不追全 ——
 * 表外的一律落「记成他字」，这正是保守的那一侧：宁可把形近说成记错，不可把记错说成形近。
 *
 * 表里**只放形近，不放同音**：判题不辨读音，混进同音字会让「字形混淆」这个名字说假话。
 *
 * 维护约束（[GlyphConfusablesTest] 会逐条钉住）：
 * ① 每个组至少两个字；② **一个字只能出现在一个组里**（重复会让先登记的组失效，
 * 而失效是静默的）；③ 组内不含同字；
 * ④ **组员必须是「归一化后保持原样」的字符** —— 判题只拿归一化后的串进来，
 * 若某个字在归一化时会被改写（大写→小写、中文数字→阿拉伯数字），这一项就永远不可能命中。
 * 约束 ④ 是后补的：前三条都查不出「表里写着、实际一次都不触发」这一类，
 * 而它恰恰是最难发现的（守卫全绿、功能半瘫）。
 */
object GlyphConfusables {

    /**
     * 形近组：同组内任意两字互判为「字形混淆」。组内顺序无意义。
     *
     * 组织方式：先「只差一笔/一笔长短」的基本形，再「同形旁不同声旁」的常用字，
     * 最后拉丁字母/数字的抄写型混淆。跨组冲突由测试拦下，不靠人工记。
     */
    internal val groups: List<Set<Char>> = listOf(
        // ── 只差一笔 / 一笔长短 ──
        setOf('己', '已', '巳'),
        setOf('未', '末'),
        setOf('日', '曰'),
        setOf('人', '入'),
        setOf('免', '兔'),
        setOf('戊', '戌', '戍', '戎', '戒'),
        setOf('天', '夭'),
        setOf('千', '干', '于'),
        setOf('午', '牛'),
        setOf('刀', '力'),
        // 刻意**不收**「匕/七」「丸/九」：这两个组在判题时不可能成立 ——
        // TextNormalizer 会把中文数字折成阿拉伯数字（七→7、九→9），而判题只拿归一化后的串比对，
        // 「七」这一侧永远以 '7' 的形式出现，组内配对无从发生。留着它们等于给表刷虚假规模。
        setOf('几', '儿'),
        setOf('大', '太', '犬'),
        setOf('王', '玉', '主'),
        setOf('士', '土', '工'),
        setOf('亳', '毫'),
        setOf('予', '矛'),
        setOf('毋', '母'),
        setOf('弋', '戈'),
        setOf('兀', '元'),
        setOf('冗', '亢'),
        setOf('白', '自'),
        setOf('艮', '良'),
        setOf('吊', '帛'),

        // ── 同形旁 / 形近声旁的常用字 ──
        setOf('赢', '羸', '嬴'),
        setOf('辨', '辩', '辫', '瓣'),
        setOf('侯', '候'),
        setOf('茶', '荼'),
        setOf('竟', '竞'),
        setOf('辰', '晨'),
        setOf('宵', '霄'),
        setOf('弛', '驰'),
        setOf('簿', '薄'),
        setOf('蔓', '漫'),
        setOf('稍', '梢', '捎'),
        setOf('藉', '籍'),
        setOf('涣', '焕'),
        setOf('僵', '疆'),
        setOf('潮', '嘲'),
        setOf('搏', '博', '膊'),
        setOf('概', '慨'),
        setOf('蜡', '腊'),
        setOf('消', '销'),
        setOf('爆', '暴'),
        setOf('蔗', '庶'),
        setOf('玷', '沾'),
        setOf('兑', '衮'),
        setOf('汨', '汩'),
        setOf('炙', '灸'),
        setOf('祟', '崇'),
        setOf('赡', '瞻'),
        setOf('杳', '沓'),
        setOf('舐', '舔'),
        setOf('雎', '睢'),
        setOf('挠', '绕', '饶'),
        setOf('摩', '磨', '摹'),
        setOf('涓', '捐', '娟'),
        setOf('芸', '耘'),
        setOf('绘', '荟'),
        setOf('莠', '秀'),
        setOf('拆', '折'),
        setOf('菅', '管'),
        setOf('蓬', '篷'),
        setOf('粹', '萃', '悴', '瘁'),
        setOf('篮', '蓝'),
        setOf('缜', '慎'),
        setOf('篡', '纂'),
        setOf('诛', '株'),
        setOf('铄', '烁'),
        setOf('鸠', '鸩'),
        setOf('赝', '膺'),
        setOf('篙', '蒿'),
        setOf('饬', '饰'),
        setOf('攥', '攒'),
        setOf('篓', '缕'),
        setOf('姣', '皎'),
        setOf('谙', '暗'),
        setOf('蜷', '倦'),
        setOf('拈', '粘'),

        // ── 拉丁字母 / 数字：抄写型混淆（英文文章模式）──
        //
        // 全部**必须写成归一化之后的样子**（小写）：判题拿到的串已经过 TextNormalizer.normalize()，
        // 而它最后一步是 lowercase()。早先这里写成大写（'S'/'Z'/'C'/'K'/'P'/'I'/'O'），
        // 那些项在 app 内一次都不会命中 —— 表上看着 11 组、实际只活 4 组，
        // 而单测直接调 isGlyphConfused('S','5') 绕过了归一化，所以一直是绿的。
        // 约束 ④（组员归一化后必须保持原样）现在由 [GlyphConfusablesTest] 钉死。
        setOf('l', '1'),      // 竖线型：l / 1（不含 i —— i 是英文高频字母，收进来会把正常拼写判成形近）
        setOf('o', '0'),      // 圆型：o / 0（不含大写 O：归一化后 O→o，与 o 本来就等价）
        setOf('b', '6'),
        setOf('g', '9'),
        setOf('s', '5'),
        setOf('z', '2'),
        setOf('m', 'n'),
        setOf('u', 'v')
        // 刻意不收：'c'/'g'（g 已在前一组，一个字只能属一组）、'k'/'r'、'p'/'f'。
        // 大写 K/R、P/F 看着像，小写 k/r、p/f 并不像 —— 收进来会把「记成了另一个字」误报成
        // 「字形混淆」，而本表的取舍一贯是「宁可把形近说成记错，不可把记错说成形近」。
    )

    /** 字 → 组号。查不到即「不在表里」，一律不判形近。 */
    private val groupOf: Map<Char, Int> =
        buildMap { groups.forEachIndexed { i, g -> g.forEach { put(it, i) } } }

    /** 两字是否形近（同组；同一个字不算混淆） */
    fun isGlyphConfused(a: Char, b: Char): Boolean {
        if (a == b) return false
        val ga = groupOf[a] ?: return false
        return groupOf[b] == ga
    }

    /**
     * 等长串「在同一位置上换了字」，且**每个换字位置**都是形近字对 → true。
     *
     * 为什么要求全部位置都形近：只要有一个位置换成了不相干的字，这一处错的本质就是
     * 「没记住」，把它算成字形混淆会把真正的记忆漏洞藏起来。混合情形落「记成他字」。
     * 长度不同、或完全没有差异位置时返回 false（那两种情形由别的方法分型）。
     */
    fun isGlyphConfusedSubstitution(expected: String, actual: String): Boolean {
        if (expected.length != actual.length || expected.isEmpty()) return false
        var diffs = 0
        for (i in expected.indices) {
            if (expected[i] == actual[i]) continue
            if (!isGlyphConfused(expected[i], actual[i])) return false
            diffs++
        }
        return diffs > 0
    }
}
