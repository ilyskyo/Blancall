// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

/**
 * 手写识别的单次结果。
 *
 * [candidates] 按置信度降序，长度为 1..topK。
 * 每个候选的 [Candidate.char] 一定非空（字符表外置，索引必命中）。
 */
data class HandwritingResult(
    val candidates: List<Candidate>,
    /** 本次推理耗时（毫秒），仅用于调试展示 */
    val elapsedMs: Long
) {
    /** 最高置信度候选；无候选时为 null。 */
    val best: Candidate? get() = candidates.firstOrNull()

    /**
     * 是否可用于「自动判对错」。
     *
     * 只有 top-1 置信度足够高、且与 top-2 拉开差距时才自动上屏；
     * 否则交由用户从候选中点选 —— 宁可多一次点选，也不误判。
     *
     * 阈值取 0.80 是保守值：CASIA 测试集 top-1 为 95.47%，
     * 但在真实手写（笔迹潦草、连笔、非常规笔顺）下置信度分布更平，
     * 此时默认不给结论更安全。
     */
    val isConfident: Boolean
        get() {
            val top = candidates.getOrNull(0) ?: return false
            if (top.confidence < CONFIDENCE_THRESHOLD) return false
            val second = candidates.getOrNull(1)
            return second == null || top.confidence - second.confidence >= MARGIN
        }

    /**
     * 与另一文种模型的结果合并候选（跨文种回退展示用）。
     *
     * 规则：**本结果（主文种）的候选保持原顺序在前**；另一结果的候选去重后按原顺序
     * 追加；截断到 [topK]。
     *
     * ⚠️ **刻意不按置信度混排**：不同模型的置信度未经校准，混排会让副文种的
     * 巧合高置信候选（真机实例：在英文空里写潦草字母 'b'，中文模型输出「占」 0.73
     * 高于拉丁模型的 b 0.56）排到用户预期候选之前 —— 用户第一眼看到的候选是错的。
     *
     * ⚠️ 仅用于**候选条展示/点选**：自动上屏仍要求单模型 [isConfident]。
     */
    fun merge(other: HandwritingResult?, topK: Int = Int.MAX_VALUE): HandwritingResult {
        if (other == null) {
            return if (candidates.size <= topK) this
            else copy(candidates = candidates.take(topK))
        }
        val seen = HashSet<Char>()
        val out = ArrayList<Candidate>(minOf(topK, candidates.size + other.candidates.size))
        for (c in candidates) {
            if (out.size >= topK) break
            if (seen.add(c.char)) out.add(c)
        }
        for (c in other.candidates) {
            if (out.size >= topK) break
            if (seen.add(c.char)) out.add(c)
        }
        return HandwritingResult(
            candidates = out,
            elapsedMs = maxOf(elapsedMs, other.elapsedMs)
        )
    }

    data class Candidate(
        /** 字符表索引（0-based，对应 GB2312 一级字顺序） */
        val index: Int,
        /** 识别出的汉字 */
        val char: Char,
        /** 置信度 [0,1]；若模型输出为 logits 则此处为归一化后的相对值 */
        val confidence: Float
    )

    companion object {
        /** 自动上屏所需的最低 top-1 置信度 */
        const val CONFIDENCE_THRESHOLD = 0.80f
        /** top-1 与 top-2 的最小差距，用于排除「两个都像」的模糊输入 */
        const val MARGIN = 0.15f
    }
}
