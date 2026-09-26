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
        assertTrue("挖空导出必须保留 [N] ___ 空位标记", cfg.displayText.contains("___"))
        assertTrue("挖空导出不得与完整原文一致", cfg.displayText != content)
        assertTrue("挖空导出须携带空位信息", cfg.blanks.isNotEmpty())
    }
}
