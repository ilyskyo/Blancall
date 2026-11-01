// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossTextReviewClauseTest {

    @Test
    fun `默写分句按顺序映射回混合句`() {
        val sentences = listOf(
            "Learning is not memorizing facts, but connecting ideas.",
            "A second sentence follows.",
        )
        val clauses = listOf(
            "Learning is not memorizing facts,",
            "but connecting ideas.",
            "A second sentence follows.",
        )
        assertEquals(
            listOf(0, 0, 1),
            CrossTextReview.clauseSentenceIndices(clauses, sentences),
        )
    }

    @Test
    fun `中文标点切出的分句也能归到同一句`() {
        val sentences = listOf("山不在高，有仙则名。", "水不在深，有龙则灵。")
        val clauses = listOf("山不在高，", "有仙则名。", "水不在深，", "有龙则灵。")
        assertEquals(
            listOf(0, 0, 1, 1),
            CrossTextReview.clauseSentenceIndices(clauses, sentences),
        )
    }

    @Test
    fun `空白或找不到的分句返回负一`() {
        val sentences = listOf("第一句。")
        assertEquals(
            listOf(-1, -1),
            CrossTextReview.clauseSentenceIndices(listOf("  ", "不存在的分句"), sentences),
        )
    }
}
