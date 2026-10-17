// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 「句子卡片」主动回忆用的挖空提示（Anki cloze 的轻量版）。
 *
 * 卡片正面不再直接把整句交给用户 —— 那是"认读"，不是"回忆"。这里按**分句首字提示法**
 * 生成挖空：每个分句保留首字与标点，其余字符替换为占位符；用户凭首字把整句默想出来，
 * 再揭晓核对（揭晓动作在界面层，见 SentenceCardScreen）。
 *
 * 为什么用「首字提示」而不是随机挖词：背整句时人的提取线索天然是**每分句的起头**，
 * 首字给的是"从这里往下走"的索引；随机挖词会把句子切成互不相干的碎片，
 * 反而制造新的记忆负担（把"背句子"降级成"背碎片"）。
 *
 * 纯函数、无 Android 依赖，供 JVM 单测直接覆盖。
 */
object SentenceCloze {

    /** 占位符（每个被隐藏的字符一个；宽度接近汉字，同时保留"还差几个字"的长度线索） */
    const val PLACEHOLDER = '□'

    /**
     * 生成挖空提示文本：
     * - 按 [ClozeTokens] 分句级（level 1）切块，标点跟随前一块；
     * - 每块保留**首个实义字符**，其余实义字符 → [PLACEHOLDER]，标点原样保留；
     * - 空串 / 纯标点块等边界原样返回（绝不产生"整句全空"的卡片）。
     */
    fun cue(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (token in ClozeTokens.tokensFor(text, 1)) {
            sb.append(cueToken(token.text))
        }
        return sb.toString()
    }

    /**
     * 挖空后是否真的藏了东西。界面据此决定要不要设「显示答案」这道门 ——
     * 藏不出东西（极端短句）还拦着用户点，就是"点了没反应"。
     *
     * 内部会再算一次 [cue]。**刻意不合并**成「一次返回 (文本, 是否藏了)」：两个消费者不在同一层 ——
     * 状态层（SentenceCardScreen）要的是布尔，渲染层（SentenceCardViews）要的是文本，
     * 让状态层拿着渲染文本传下去，等于把渲染细节漏进状态机。
     * 代价只是一次 O(句长) 的 StringBuilder 拼接，不值得为此换掉分层。
     */
    fun hidesAnything(text: String): Boolean = cue(text) != text

    /**
     * 单个分句块的提示：把「实义部分」的首字留下，其余实义字符变占位，尾部标点照旧。
     * 全标点块（如句子以标点起头）原样返回 —— 标点本来就不是要背的内容。
     */
    private fun cueToken(token: String): String {
        if (token.isEmpty()) return token
        // 尾部连续标点不参与挖空：标点不是记忆内容，留着还能维持句子结构（朗读停顿仍在）
        var coreEnd = token.length
        while (coreEnd > 0 && isPunctuation(token[coreEnd - 1])) coreEnd--
        if (coreEnd == 0) return token
        val sb = StringBuilder(token.length)
        sb.append(token[0])
        for (i in 1 until coreEnd) sb.append(PLACEHOLDER)
        if (coreEnd < token.length) sb.append(token, coreEnd, token.length)
        return sb.toString()
    }

    /** 标点 = 既非汉字也非字母（与 [ClozeTokens] 的分句口径一致） */
    private fun isPunctuation(c: Char): Boolean =
        !(c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf') && !c.isLetter()
}
