// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨文种候选合并（[HandwritingResult.merge]）回归测试。
 *
 * 背景：跨文种回退（中文空里写英文的救援）在主模型低置信时把两套模型的候选合并
 * 展示，用户从候选条点选。合并规则必须稳定：**主文种候选保持原顺序在前**、副文种
 * 去重追加、截断 topK。
 *
 * ⚠️ 真机教训（必须锁死）：**不能按置信度混排**。在英文空里写潦草字母 'b' 时，
 * 拉丁模型给 b:0.56、中文模型巧合给「占:0.73」——若全局按置信排序，「占」会排到
 * b 前面，用户第一眼看到的候选就是错的。
 */
class HandwritingResultMergeTest {

    private fun c(ch: Char, conf: Float, idx: Int = 0) =
        HandwritingResult.Candidate(index = idx, char = ch, confidence = conf)

    private fun r(vararg cs: HandwritingResult.Candidate, elapsed: Long = 1L) =
        HandwritingResult(candidates = cs.toList(), elapsedMs = elapsed)

    @Test
    fun `主文种候选保持原顺序在前——即使副文种置信度更高也不抢占`() {
        // 真机现场：primary=b:0.56（拉丁）、alt=占:0.73（中文），b 必须排第一
        val primary = r(c('b', 0.56f), c('h', 0.20f))
        val alt = r(c('占', 0.73f), c('口', 0.23f))
        val merged = primary.merge(alt, topK = 4)
        assertEquals(listOf('b', 'h', '占', '口'), merged.candidates.map { it.char })
    }

    @Test
    fun `合并后截断到 topK`() {
        val a = r(c('甲', 0.55f), c('乙', 0.50f), c('丙', 0.40f))
        val b = r(c('A', 0.70f), c('B', 0.30f))
        val merged = a.merge(b, topK = 3)
        assertEquals(3, merged.candidates.size)
        assertEquals(listOf('甲', '乙', '丙'), merged.candidates.map { it.char })
    }

    @Test
    fun `同一字符以主文种候选为准，副文种不重复追加`() {
        val a = r(c('O', 0.40f))
        val b = r(c('O', 0.90f), c('Q', 0.50f))
        val merged = a.merge(b)
        assertEquals(listOf('O', 'Q'), merged.candidates.map { it.char })
        // 保留的是主文种的 O（0.40），而不是副文种更高的 0.90
        assertEquals(0.40f, merged.best!!.confidence, 1e-6f)
    }

    @Test
    fun `对方为 null 时返回自身并按 topK 截断`() {
        val a = r(c('甲', 0.9f), c('乙', 0.5f), c('丙', 0.3f))
        val merged = a.merge(null, topK = 2)
        assertEquals(2, merged.candidates.size)
        assertEquals(listOf('甲', '乙'), merged.candidates.map { it.char })
        // 不超限时原样返回（引用级复用，不做无谓拷贝）
        assertTrue(a.merge(null, topK = 5) === a)
    }

    @Test
    fun `耗时取两者最大值`() {
        val merged = r(c('甲', 0.9f), elapsed = 3L).merge(r(c('A', 0.8f), elapsed = 7L))
        assertEquals(7L, merged.elapsedMs)
    }
}
