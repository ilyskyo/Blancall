// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 阅读速度模型：把正文折算成「标准字」，再把标准字折算成时间。
 *
 * ## 为什么不能只数长度
 * 旧实现用 `content.length / 350f` 估算剩余时间，三处偏差会叠加成"明显不准"：
 * 1. **中英混排**：一个英文单词 6~10 个字符，阅读时间却只相当于 2 个汉字，
 *    按字符数算会把英文段落的时间放大 3 倍以上；
 * 2. **标点与空白**：它们占字符数、几乎不占阅读时间；
 * 3. **个人差异**：350 字/分是"普通成年人默读"的经验中位数，但同一个人读学术文
 *    与读散文的速度能差一倍，读自己的笔记又比读陌生材料快。
 *
 * ## 这个模型怎么算
 * - [standardChars]：汉字 1 个标准字；英文/数字词整词约 2 个标准字（200 wpm ≈ 400 标准字/分）；
 *   标点、空白、换行只占极小权重。于是"标准字/分钟"对中英文都可比。
 * - [cpmOf]：用**真实阅读过的标准字**除以**有效阅读秒数**，得到用户自己的速度。
 *   有效秒数由 UI 侧按「进度前进所花的时间」结算（见 [SilenceGapSeconds] 的说明），
 *   不是按「最后一次触摸之后若干秒」—— 后者会把"读一屏不动手"的时间当成走开。
 * - [blend]：历史值与本次样本按样本时长做指数加权（长样本权重高、短样本权重低，
 *   上下限 [MinSampleWeight]/[MaxSampleWeight]）。既不会被一次误触带偏，
 *   也能跟上"最近读得更快了"这种变化。
 */
object ReadingSpeed {

    /** 默认速度（标准字/分钟）：普通成年人中文默读的经验中位数 */
    const val DefaultCpm = 350f

    /** 个人速度的可信区间：低于/高于此值多半是统计出错（误触、挂机），夹住而不是采信 */
    const val MinCpm = 110f
    const val MaxCpm = 1400f

    /**
     * 单次结算的时间上限（秒）：超过它就按「人离开了」处理，不当成在读。
     *
     * 为什么不按「距上次触摸 N 秒」判：读一屏文字本来就可能一两分钟不碰屏幕，
     * 触摸法会把分母压到十几秒，个人速度被抬到 1000+ 字/分（实测过 12 秒阈值
     * 下滚 2 分钟就能算出 261，真实读者会的更高）。改成「进度前进的时间」后，
     * 不动手但也不会翻页的那段时间自然不会被计进分母。
     * 五分钟是「一屏最长能看多久」的上限；到点 UI 会问一句还在读吗，点了确认就按五分钟计入。
     */
    const val SilenceGapSeconds = 300L

    /**
     * 一次"进度变化"能归属多少有效阅读秒。
     *
     * UI 侧在进度变化时用「本次变化距上次变化的间隔」当参数调用它：
     * 读一屏不动手也照算，超过 [SilenceGapSeconds] 的部分当走开。
     * 抽成纯函数是为了让这条口径能被单测钉住 —— 它直接决定分母。
     */
    fun effectiveSeconds(gapSeconds: Long): Long =
        if (gapSeconds <= 0L) 0L else gapSeconds.coerceAtMost(SilenceGapSeconds)

    /** 累计有效阅读不足这个秒数时，不采信个人速度，仍用 [DefaultCpm] */
    const val MinTrustedSeconds = 90L

    /** EMA 权重上下限：单次会话对历史值的影响 */
    private const val MinSampleWeight = 0.15f
    private const val MaxSampleWeight = 0.5f

    /** 一次会话达到这个秒数时权重取满（约等于"读了五分钟"才算一个可靠样本） */
    private const val FullWeightSeconds = 300f

    /** 汉字（含扩展 A 区）判定：与 ReaderOcclusion 的中文判定保持同一口径 */
    fun isCjk(ch: Char): Boolean =
        ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf'

    /**
     * 文本折算成标准字数（≈ 汉字个数）。
     *
     * 权重来源：
     * - 汉字 1.0
     * - 拉丁/数字词 2.0（整词计；词内字符不再单独计）
     * - 其它可见字符（标点、符号）0.12 —— 有短暂的视线停留，但不构成阅读量
     * - 空白 / 换行 0
     */
    fun standardChars(text: String): Float {
        if (text.isEmpty()) return 0f
        var sum = 0f
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                isCjk(c) -> { sum += 1f; i++ }
                // 拉丁字母 / 数字连成词：整词 2 个标准字
                c.isLetterOrDigit() && !isCjk(c) -> {
                    var j = i + 1
                    while (j < n) {
                        val d = text[j]
                        if (d.isLetterOrDigit() && !isCjk(d)) j++ else break
                    }
                    val len = j - i
                    // 极短词（a / is / of）比长词读得更快，但差距远小于字符数差距：
                    // 统一 2.0 已经足够，长度只做轻微修正（±10% 封顶）
                    sum += 2f * (1f + ((len - 5).coerceIn(-3, 6) * 0.02f))
                    i = j
                }
                c.isWhitespace() -> i++
                else -> { sum += 0.12f; i++ }
            }
        }
        return sum
    }

    /**
     * 由累计标准字数与累计有效秒数算出速度（标准字/分钟）。
     * 样本不足（秒数 < [MinTrustedSeconds]、字数非正）时返回 null —— 让调用方明确落到默认值，
     * 而不是拿一个看起来像"你的速度"的噪声数字去糊用户。
     */
    fun cpmOf(chars: Float, seconds: Long): Float? {
        if (chars <= 0f || seconds < MinTrustedSeconds) return null
        val cpm = chars / seconds.toFloat() * 60f
        if (!cpm.isFinite() || cpm <= 0f) return null
        return cpm.coerceIn(MinCpm, MaxCpm)
    }

    /**
     * 融合新样本。
     *
     * @param previous 历史速度（null / 非正 = 还没有可信历史）
     * @param sampleCpm 本次会话速度（应当已经过 [cpmOf] 的区间夹取）
     * @param sampleSeconds 本次会话的有效阅读秒数
     */
    fun blend(previous: Float?, sampleCpm: Float, sampleSeconds: Long): Float {
        val sample = sampleCpm.coerceIn(MinCpm, MaxCpm)
        if (previous == null || !previous.isFinite() || previous <= 0f) return sample
        val weight = (sampleSeconds / FullWeightSeconds).coerceIn(MinSampleWeight, MaxSampleWeight)
        return (previous + (sample - previous) * weight).coerceIn(MinCpm, MaxCpm)
    }

    /** 剩余时间的显示值（秒）。[remainChars] 为剩余标准字数。 */
    fun remainingSeconds(remainChars: Float, cpm: Float): Long {
        val speed = if (cpm.isFinite() && cpm > 0f) cpm.coerceIn(MinCpm, MaxCpm) else DefaultCpm
        if (remainChars <= 0f) return 0L
        return (remainChars / speed * 60f).toLong().coerceAtLeast(0L)
    }

    /**
     * 分钟精度：剩余时间越长，分钟数的"抖动"越无所谓；越短越要精确。
     * - < 1 分钟：0（调用方显示"不足 1 分钟"）
     * - < 10 分钟：四舍五入到 1 分钟
     * - < 60 分钟：四舍五入到 1 分钟（整体趋势清楚，不额外粗糙化）
     * - ≥ 60 分钟：按 5 分钟取整（长文里每秒跳一个数字纯属噪声）
     */
    fun displayMinutes(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        val minutes = seconds / 60.0
        return when {
            seconds < 60L -> 0L
            minutes < 60.0 -> Math.round(minutes).coerceAtLeast(1L)
            else -> (Math.round(minutes / 5.0) * 5).coerceAtLeast(60L)
        }
    }
}
