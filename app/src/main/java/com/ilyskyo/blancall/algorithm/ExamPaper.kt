// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 出卷模式：多篇文章 → 一份挖空卷（答案页分离）。
 *
 * ## 为什么不单开「出卷」入口
 * 现有「导出 PDF」已经具备单篇练习纸 + 答案页的完整能力；再开一个页签等于把
 * 「导出」拆成两个语义相同的入口。这里只把「多选文章 + 篇均题量」接到同一个
 * 导出对话框上：选一篇就是原来的单篇导出，选多篇就是组卷。
 *
 * ## 明确不做难度控制
 * 盘上没有「题本身难度」的标定（FSRS 的 difficulty 是「你掌握它的难度」），
 * 硬做只会生成简单题扎堆的废卷。题量上限按分句数量均匀取，不猜难度。
 */
object ExamPaper {

    data class Source(
        val title: String,
        val author: String = "",
        val content: String,
    )

    data class Section(
        val title: String,
        val author: String,
        val displayText: String,
        val blanks: List<Blank>,
    )

    data class Blank(val index: Int, val answer: String)

    /**
     * @param asCloze true = 挖空卷；false = 原文卷
     * @param maxBlanksPerArticle 0 = 用生成器的默认密度；>0 = 每篇最多要几个空
     */
    fun build(
        sources: List<Source>,
        asCloze: Boolean,
        maxBlanksPerArticle: Int = 0,
    ): List<Section> = sources.map { source ->
        if (!asCloze) {
            Section(
                title = source.title,
                author = source.author.trim(),
                displayText = source.content,
                blanks = emptyList(),
            )
        } else {
            val cloze = BlancallGenerator.generateSentenceCloze(
                content = source.content,
                count = maxBlanksPerArticle.coerceAtLeast(0),
                strategy = BlancallGenerator.Strategy.FULL_COVERAGE,
            )
            Section(
                title = source.title,
                author = source.author.trim(),
                displayText = sizeClozePlaceholders(
                    cloze.displayText,
                    cloze.blanks.map { it.originalText.length }
                ),
                blanks = cloze.blanks.map { Blank(it.index, it.originalText) },
            )
        }
    }

    /**
     * 把 "[N] ___" 的占位符按被挖字数换成全角下划线（一字一空宽）。
     * 与单篇导出的 [com.ilyskyo.blancall.ui.list.sizeClozeBlanks] 同一口径：
     * 出卷与单篇导出不能出现两种空位宽度。
     */
    fun sizeClozePlaceholders(displayText: String, blankLengths: List<Int>): String {
        var i = 0
        return Regex("_{3,}").replace(displayText) { _ ->
            "＿".repeat(blankLengths.getOrElse(i++) { 3 }.coerceAtLeast(1))
        }
    }
}
