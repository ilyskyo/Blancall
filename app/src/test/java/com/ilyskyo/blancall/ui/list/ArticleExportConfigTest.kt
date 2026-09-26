// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildArticleExportConfig] 回归测试：「导出 PDF」两种类型的内容口径 ——
 * - 原文导出不得带挖空（displayText = 完整正文、无空位标记）；
 * - 挖空导出不得还原为完整原文（保留 "[N] ___" 空位呈现、携带空位信息）。
 */
class ArticleExportConfigTest {

    private val content = "山不在高，有仙则名。水不在深，有龙则灵。斯是陋室，惟吾德馨。"

    @Test
    fun `原文导出：完整正文，不含任何挖空空位`() {
        val cfg = buildArticleExportConfig("陋室铭", content, asCloze = false)
        assertEquals(content, cfg.displayText)
        assertTrue("原文导出不得出现空位标记", !cfg.displayText.contains("___"))
        assertTrue("原文导出不携带空位信息", cfg.blanks.isEmpty())
    }

    @Test
    fun `挖空导出：保留空位呈现，不得还原为完整原文`() {
        val cfg = buildArticleExportConfig("陋室铭", content, asCloze = true)
        assertTrue("挖空导出必须保留 [N] ＿… 空位标记", cfg.displayText.contains("＿"))
        assertTrue("挖空导出不得与完整原文一致", cfg.displayText != content)
        assertTrue("挖空导出须携带空位信息", cfg.blanks.isNotEmpty())
    }

    @Test
    fun `挖空导出：空位宽度与被挖字数一致（一字一空宽）`() {
        val cfg = buildArticleExportConfig("陋室铭", content, asCloze = true)
        // 每个 "[N] ＿…" 空位的全角下划线数量 == 第 N 个空被挖字数；固定 "___" 不再出现
        val all = Regex("\\[(\\d+)] (＿+)").findAll(cfg.displayText).toList()
        assertTrue("displayText 应包含按字数生成的空位", all.isNotEmpty())
        assertEquals("空位数量应与 blanks 一致", cfg.blanks.size, all.size)
        all.forEach { m ->
            val blank = cfg.blanks[m.groupValues[1].toInt() - 1]
            assertEquals(
                "空位宽度（全角下划线数）应等于被挖字数",
                blank.correctAnswer.length,
                m.groupValues[2].length
            )
        }
        assertTrue("不应再出现固定 3 下划线占位", !cfg.displayText.contains("___"))
    }

    @Test
    fun `sizeClozeBlanks：按字数逐空替换且顺序一一对应`() {
        // 占位符出现顺序与长度列表一一对应：第 1 空 1 字宽、第 2 空 5 字宽
        val out = sizeClozeBlanks("甲[1] ___乙[2] ___", listOf(1, 5))
        assertEquals("甲[1] ＿乙[2] ＿＿＿＿＿", out)
    }

    @Test
    fun `作者展示：已填作者时两种导出均携带（trim 口径）`() {
        val text = buildArticleExportConfig("陋室铭", content, asCloze = false, author = "  刘禹锡  ")
        assertEquals("作者应去除首尾空白后展示", "刘禹锡", text.author)
        val cloze = buildArticleExportConfig("陋室铭", content, asCloze = true, author = "刘禹锡")
        assertEquals("挖空导出同样携带作者", "刘禹锡", cloze.author)
    }

    @Test
    fun `作者展示：未填或纯空白时不携带（无作者不占行）`() {
        assertEquals("未传作者时为空串", "", buildArticleExportConfig("陋室铭", content, asCloze = false).author)
        assertEquals("纯空白视为未填", "", buildArticleExportConfig("陋室铭", content, asCloze = true, author = "   ").author)
    }
}
