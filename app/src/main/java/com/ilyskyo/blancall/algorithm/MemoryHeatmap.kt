// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.PracticeRecord
import androidx.compose.ui.graphics.Color

/**
 * 记忆热力图生成器（F5）。
 *
 * ## 指标（B3-2 起换过一次）
 * 主指标从「错误率」切换为**当前留存率**（句级 FSRS `FsrsEngine.retentionRate`）——
 * 名字叫「记忆」就该回答「哪句我现在快忘了」，而不是「过去错得多不多」。
 * 错误率退为副信息（行内仍展示）：观测正确率是用户练出来的数，不篡改。
 *
 * 颜色的分档（越红 = 越接近遗忘）：
 * - 红/橙：留存走到目标线以下，该复习了
 * - 黄：接近 Anki 默认目标留存率（90%）
 * - 绿：记得牢
 * - 灰色：有练习痕迹但暂无留存结论（句级练习不足 MIN_SAMPLE 次，或旧记录没有 FSRS 状态）
 * - 无色：从未练习过
 *
 * 为什么「高错误率 + 高留存」（历史上错过、但刚练熟）不再显示红：旧指标会把
 * 「历史上错过几次」一直染红，哪怕那句三天前刚练熟 —— 颜色会撒谎。留存率天然
 * 随复习回落，颜色跟着状态走。
 */
object MemoryHeatmap {

    // 留存率→颜色 阈值常量（B3-2 主分档；从低到高，越红越接近遗忘）
    private const val RETENTION_FADING = 0.55f    // <0.55 深红：快忘了
    private const val RETENTION_BLURRY = 0.75f    // <0.75 橙：开始模糊
    private const val RETENTION_OK = 0.90f        // <0.90 黄：接近目标留存线（Anki 默认 90%）
    private const val RETENTION_FIRM = 0.97f      // <0.97 浅绿：熟悉；≥0.97 深绿：牢固

    // 颜色常量
    private val COLOR_DEEP_RED = Color(0xFFE53935)
    private val COLOR_ORANGE = Color(0xFFEF6C00)
    private val COLOR_YELLOW = Color(0xFFF9A825)
    private val COLOR_LIGHT_GREEN = Color(0xFF7CB342)
    private val COLOR_DEEP_GREEN = Color(0xFF43A047)
    /** 样本不足专用灰：既不是红也不是绿。给它一个颜色档位会让用户读成一条结论，而这里没有结论 */
    private val COLOR_INSUFFICIENT = Color(0xFF9E9E9E)

    /** 图例档位标识：稳定 ASCII 键，渲染层按它取多语言文案，数据层不再持有展示文案 */
    const val BAND_WEAK = "weak"
    const val BAND_HARD = "hard"
    const val BAND_MID = "mid"
    const val BAND_FAMILIAR = "familiar"
    const val BAND_FIRM = "firm"
    const val BAND_LOW_SAMPLE = "low_sample"
    const val BAND_UNPRACTICED = "unpracticed"

    /** 单句热力数据 */
    data class SentenceHeat(
        val sentenceIndex: Int,
        val text: String,
        /** 观测错误率 0-1，展示「错 X%」用这个数（B3-2 起退为副信息）—— 用户练的就是这些，不能偷偷改写 */
        val errorRate: Float,
        /** 收缩后的错误率：句级薄弱榜（WeakSentenceRank）的排序依据；不再决定颜色 */
        val adjustedRate: Float,
        /** 该句【实际被作答】的次数（不是整篇的会话数，见下方注释） */
        val practiceCount: Int,
        /** 当前留存率 0-1（句级 FSRS，B3-2 主指标）；-1f = 没有句级状态，算不出 */
        val retention: Float,
        val heatColor: Color,
        /** 有练习痕迹但**颜色不成立**（留存算不出，或句级练习不足 [WeaknessMath.MIN_SAMPLE] 次）：UI 该说「样本不足」 */
        val lowSample: Boolean,
        /** 错题落点是比例估算出来的（旧记录没有逐句索引），UI 需标注「位置为估算」 */
        val estimated: Boolean
    )

    /** 整文热力图数据 */
    data class HeatmapData(
        val sentences: List<SentenceHeat>,
        val overallErrorRate: Float,
        val totalPractices: Int
    )

    /**
     * 图例（色带 + 稳定键）：顺序即由弱到强，末尾两档是「这里没有结论」的两种原因 ——
     * 样本不足（灰）与从未作答（[Color.Unspecified]，渲染层画空心框，不能给它一个颜色）。
     */
    fun legend(): List<Pair<String, Color>> = listOf(
        BAND_WEAK to COLOR_DEEP_RED,
        BAND_HARD to COLOR_ORANGE,
        BAND_MID to COLOR_YELLOW,
        BAND_FAMILIAR to COLOR_LIGHT_GREEN,
        BAND_FIRM to COLOR_DEEP_GREEN,
        BAND_LOW_SAMPLE to COLOR_INSUFFICIENT,
        BAND_UNPRACTICED to Color.Unspecified
    )

    /**
     * 从练习记录生成整篇文章的热力图数据。
     *
     * 错题落点优先用记录里的【精确句子索引】（`MistakeDetail.sentenceIndex`，与这里同一套全文切句），
     * 只有旧记录没有该字段时才回退到按 blankIndex 比例估算，并把该句标成 [SentenceHeat.estimated] ——
     * 猜出来的位置不能长得和量出来的一样。
     *
     * @param sentenceStates 句级 FSRS 状态快照（键由 [SentenceSelector.sentenceKey] 生成，
     *   与练习提交的写入侧同一函数）；为空或缺 key 的句子按「暂无留存」灰度处理
     * @param articleId 文章 id（生成句键用；≤0 时跳过状态查询）
     * @param now 留存率基准时刻（可注入以便单测固定）
     */
    fun generate(
        content: String,
        records: List<PracticeRecord>,
        sentenceStates: Map<String, FsrsEngine.CardState> = emptyMap(),
        articleId: Long = 0L,
        now: Long = System.currentTimeMillis(),
    ): HeatmapData {
        // 用带位置的切句作为唯一锚点：字符起始位置 → 句索引。
        // 练习记录存的是"句子在全文中的字符起始位置"（见 PracticeRecord.answeredSentenceStarts），
        // 这样不受"全文切句 vs 各段分别切句"口径差异影响（标题行/段落拼接都不会错位）。
        val positioned = SentenceSplitter.splitWithPositions(content)
        val allSentences = positioned.map { it.text }
        if (allSentences.isEmpty()) {
            return HeatmapData(emptyList(), 0f, 0)
        }
        val startToIndex = positioned.withIndex().associate { it.value.startIndex to it.index }

        // sentenceErrors[sIdx] = 该句【出错的会话数】；sentenceTotal[sIdx] = 该句【被作答的会话数】
        // 两边单位必须一致：旧实现分子数的是「错空数」、分母数的是「会话数」，一句里错 3 个空
        // 而只练过 1 次时会算出 300% 的错误率（UI 上就是「错300%」）。
        val sentenceErrors = mutableMapOf<Int, Int>()
        val sentenceTotal = mutableMapOf<Int, Int>()
        val sentenceEstimated = mutableSetOf<Int>()
        // 旧记录（无 answeredSentenceStarts）要往每一句上摊的份数之和，循环结束后一次落表
        var legacyBlanks = 0

        for (record in records) {
            val errorSentences = mutableSetOf<Int>()
            for (mistake in record.mistakes) {
                val exact = mistake.sentenceIndex
                // 反向默写的 blankIndex 是【分句序号】，与这里的全空位序号不同源：
                // 按比例猜句会把默写的错算到别的句子上。默写只认精确落点，认不到就不计这一句。
                if (exact !in allSentences.indices && record.mode == "REVERSE") continue
                val estimated = exact !in allSentences.indices
                val sentIdx = if (estimated)
                    estimateSentenceIndex(mistake.blankIndex, allSentences.size, record.totalBlanks)
                else exact
                if (estimated) sentenceEstimated += sentIdx
                errorSentences += sentIdx
            }
            errorSentences.forEach { sIdx ->
                sentenceErrors[sIdx] = (sentenceErrors[sIdx] ?: 0) + 1
            }
            // 本次实际作答的句子：新记录按字符位置锚定（未完成提交被跳过的空不计入，
            // 避免未作答题被当成"答对/0错"）；旧记录无该字段 → 回退整篇都练到。
            if (record.answeredSentenceStarts.isNotEmpty()) {
                for (start in record.answeredSentenceStarts) {
                    val sIdx = startToIndex[start] ?: continue
                    sentenceTotal[sIdx] = (sentenceTotal[sIdx] ?: 0) + 1
                }
            } else {
                // 旧记录没有逐句索引，只能按比例猜，并且要「整篇都练到」地铺到每一句上。
                // 原实现把这次铺表放在记录循环里，复杂度是 O(记录数 × 句数)；这里只累加
                // 「每句要加的份数」，循环结束后一次落表（见下方 legacyBlanks），降到 O(记录 + 句)。
                // 语义等价：只要存在一条旧记录，sentenceEstimated 本来就是全集（每句都标估算），
                // 所以铺表提前还是延后，对最终结论没有影响。
                legacyBlanks += maxOf(1, record.totalBlanks / allSentences.size)
            }
            // 落点与作答集本该同源（同一个 blankSentenceIndex 算出来的）。真不同源就是异常数据：
            // 宁可退回「样本不足、位置为估算」，也不给一个说不出口的比率。
            errorSentences.forEach { sIdx -> if ((sentenceTotal[sIdx] ?: 0) == 0) sentenceEstimated += sIdx }
        }

        // 旧记录按比例摊到每一句上（见循环内注释）：一次落表，避免 O(记录 × 句) 的重复写。
        if (legacyBlanks > 0) {
            for (sIdx in allSentences.indices) {
                sentenceTotal[sIdx] = (sentenceTotal[sIdx] ?: 0) + legacyBlanks
                sentenceEstimated += sIdx
            }
        }

        val totalPractices = records.size

        // 整篇平均错误率当收缩先验：单句样本不足时先回答「和这篇差不多」。
        // 注意用的是【会话级】的整篇均值 —— 与上面同一单位，不能拿空级的 headline 当先验。
        val answeredSessions = sentenceTotal.values.sum()
        val errorSessions = sentenceErrors.values.sum()
        val sentencePrior = if (answeredSessions > 0)
            errorSessions.toFloat() / answeredSessions else 0f
        val overallError = if (records.isNotEmpty()) {
            val totalMistakes = records.sumOf { it.mistakes.size }
            val totalBlanks = records.sumOf { it.totalBlanks }
            if (totalBlanks > 0) totalMistakes.toFloat() / totalBlanks else 0f
        } else 0f

        val sentences = allSentences.mapIndexed { idx, text ->
            val errors = sentenceErrors[idx] ?: 0
            val total = sentenceTotal[idx] ?: 0
            // 未作答句（从未被练到）→ 未练习：无色。
            // 旧实现在这里把 practiceCount 填成整篇会话数（records.size），于是只要这篇练过一次，
            // 从没答过的句子也带着「错 0%」出场 —— 色条没画、文字照给，用户读成「这句掌握了」。
            val rate = if (total > 0) errors.toFloat() / total.coerceAtLeast(1) else 0f
            val adjusted = WeaknessMath.adjustedErrorRate(errors, total, sentencePrior)
            // 留存（B3-2 主指标）：与写入侧同一函数生成句键（内部 trim），查不到状态就算不出
            val state = if (sentenceStates.isNotEmpty()) {
                sentenceStates[SentenceSelector.sentenceKey(articleId, text)]
            } else null
            val retention = if (state != null) FsrsEngine.retentionRate(state, now).toFloat() else -1f
            // 分档可用性：有状态且句级练习次数达标 —— 沿用「别把噪声当结论」的门槛（MIN_SAMPLE）
            val retentionUsable = retention >= 0f && (state?.reviewCount ?: 0) >= WeaknessMath.MIN_SAMPLE
            val lowSample = (total > 0 || state != null) && !retentionUsable
            val heatColor = when {
                total <= 0 && state == null -> Color.Unspecified
                !retentionUsable -> COLOR_INSUFFICIENT
                else -> retentionToColor(retention)
            }
            SentenceHeat(
                sentenceIndex = idx,
                text = text,
                errorRate = rate,
                adjustedRate = adjusted,
                practiceCount = total,
                retention = retention,
                heatColor = heatColor,
                lowSample = lowSample,
                estimated = idx in sentenceEstimated
            )
        }

        return HeatmapData(sentences, overallError, totalPractices)
    }

    /**
     * 估算错题所属句子索引（回退方案）。
     * 由于空白索引是全局的，缺乏真实句子索引时按比例映射到句子。
     */
    private fun estimateSentenceIndex(
        blankIndex: Int,
        totalSentences: Int,
        totalBlanks: Int
    ): Int {
        if (totalSentences <= 0 || totalBlanks <= 0) return 0
        // 按比例映射
        val ratio = blankIndex.toFloat() / totalBlanks.coerceAtLeast(1)
        return (ratio * totalSentences).toInt().coerceIn(0, totalSentences - 1)
    }

    /**
     * 留存率 → 颜色映射（B3-2 主分档；阈值见上方常量）。
     *
     * 只在「留存可用」时才该被调用（有句级状态且练习次数达标），
     * 无结论的句子走 [COLOR_INSUFFICIENT] 灰或无色 —— 分档只对可信的数说话。
     */
    fun retentionToColor(retention: Float): Color = when {
        retention < RETENTION_FADING -> COLOR_DEEP_RED      // 快忘了
        retention < RETENTION_BLURRY -> COLOR_ORANGE        // 开始模糊
        retention < RETENTION_OK -> COLOR_YELLOW            // 接近目标留存线
        retention < RETENTION_FIRM -> COLOR_LIGHT_GREEN     // 熟悉
        else -> COLOR_DEEP_GREEN                            // 牢固
    }
}
