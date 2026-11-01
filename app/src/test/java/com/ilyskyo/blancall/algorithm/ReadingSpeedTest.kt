// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReadingSpeed] 的换算与统计口径测试。
 *
 * 这些数字直接决定底栏"预计还需 X 分钟"可不可信：
 * - 标准字数折算错 → 中英混排的文章时间全歪；
 * - 有效时长门槛/夹取错 → 一次误触就能把个人速度拉成 50 字/分；
 * - 融合权重错 → 读快之后永远回不来。
 */
class ReadingSpeedTest {

    @Test
    fun `汉字按一字一个标准字计`() {
        assertEquals(4f, ReadingSpeed.standardChars("你好世界"), 0.001f)
        // 换行与空格不计
        assertEquals(4f, ReadingSpeed.standardChars("你好\n\n世界 "), 0.001f)
    }

    @Test
    fun `英文整词约等于两个标准字`() {
        // "hello" 与 "world" 各 5 个字母 → 各 2 个标准字，空格不计
        assertEquals(4f, ReadingSpeed.standardChars("hello world"), 0.001f)
        // 一个 9 字母的长词仍只按略高于 2 计（±10% 封顶）
        val long = ReadingSpeed.standardChars("something")
        assertTrue("长词折算应落在 2.0~2.4 之间，实际 $long", long in 2.0f..2.4f)
    }

    @Test
    fun `标点权重远小于正文`() {
        val plain = ReadingSpeed.standardChars("你好世界")
        val withPunct = ReadingSpeed.standardChars("你好，世界。")
        assertTrue("标点不该显著抬高字数：$plain → $withPunct", withPunct - plain < 0.5f)
    }

    @Test
    fun `空文本折算为零`() {
        assertEquals(0f, ReadingSpeed.standardChars(""), 0.0001f)
        assertEquals(0f, ReadingSpeed.standardChars("   \n\n  "), 0.0001f)
    }

    @Test
    fun `样本不足时不给出个人速度`() {
        assertNull(ReadingSpeed.cpmOf(0f, 600))
        assertNull(ReadingSpeed.cpmOf(500f, 89))       // 不足 90 秒
        assertTrue(ReadingSpeed.cpmOf(500f, 90) != null)
    }

    @Test
    fun `速度夹取到可信区间`() {
        // 90 秒读 100 标准字 = 66 字/分 → 低于下限，夹到 MinCpm
        assertEquals(ReadingSpeed.MinCpm, ReadingSpeed.cpmOf(100f, 90)!!, 0.001f)
        // 100 秒读 2000 标准字 = 1200 字/分 → 区间内，原样返回
        assertEquals(1200f, ReadingSpeed.cpmOf(2000f, 100)!!, 0.001f)
        // 100 秒读 3000 标准字 = 1800 字/分 → 高于上限，夹到 MaxCpm
        assertEquals(ReadingSpeed.MaxCpm, ReadingSpeed.cpmOf(3000f, 100)!!, 0.001f)
        // 正常值原样保留（90 秒读 525 标准字 = 350 字/分）
        assertEquals(350f, ReadingSpeed.cpmOf(525f, 90)!!, 0.001f)
    }

    @Test
    fun `读一屏不动手也算在读 但单次封顶五分钟`() {
        // 90 秒读完一屏再翻页：这 90 秒全部计入（旧口径按"触摸后 12 秒"会把 78 秒丢掉，
        // 分母被压小到 1/7，同样 300 字会算出 1500 字/分 → 夹到 1400，剩余时间严重短报）
        assertEquals(90L, ReadingSpeed.effectiveSeconds(90))
        assertEquals(60L, ReadingSpeed.effectiveSeconds(60))
        // 一次进度变化间隔 10 分钟：只认前 5 分钟，其余当人走开了
        assertEquals(ReadingSpeed.SilenceGapSeconds, ReadingSpeed.effectiveSeconds(600))
        // 刚好等于阈值时全额计入（5 分钟无操作会先弹「还在读吗」，确认后才结算到这一段）
        assertEquals(
            ReadingSpeed.SilenceGapSeconds,
            ReadingSpeed.effectiveSeconds(ReadingSpeed.SilenceGapSeconds)
        )
        // 零/负间隔（同一帧内多次回调）不产生时长
        assertEquals(0L, ReadingSpeed.effectiveSeconds(0))
        assertEquals(0L, ReadingSpeed.effectiveSeconds(-5))
    }

    @Test
    fun `没有历史样本时直接采用本次速度`() {
        assertEquals(500f, ReadingSpeed.blend(null, 500f, 120), 0.001f)
        assertEquals(500f, ReadingSpeed.blend(0f, 500f, 120), 0.001f)
    }

    @Test
    fun `短样本对历史速度影响有限 长样本权重更高`() {
        val shortSession = ReadingSpeed.blend(350f, 700f, 30)
        val longSession = ReadingSpeed.blend(350f, 700f, 300)
        assertTrue("短样本不该把速度一把拉满：$shortSession", shortSession < 420f)
        assertTrue("长样本应更接近本次值：$longSession", longSession > shortSession)
        // 上限是 0.5 权重的 EMA：350 + (700-350)*0.5 = 525
        assertEquals(525f, longSession, 0.001f)
    }

    @Test
    fun `融合结果永远夹在可信区间内`() {
        // 单次更新最多挪动一半（EMA 上限权重 0.5）：1300 → 1400 之间只走一半
        val up = ReadingSpeed.blend(1300f, 5000f, 300)
        assertTrue("不该越过上限：$up", up <= ReadingSpeed.MaxCpm)
        assertEquals(1350f, up, 0.001f)
        val down = ReadingSpeed.blend(200f, 1f, 300)
        assertTrue("不该跌破下限：$down", down >= ReadingSpeed.MinCpm)
        assertEquals(155f, down, 0.001f) // 200 + (110-200)*0.5
    }

    @Test
    fun `剩余时间按个人速度折算`() {
        // 350 标准字 ÷ 350 字/分 = 1 分钟
        assertEquals(60L, ReadingSpeed.remainingSeconds(350f, 350f))
        // 只剩 0 字 = 0 秒
        assertEquals(0L, ReadingSpeed.remainingSeconds(0f, 350f))
        // 速度非法时回落到默认值，而不是除零/负数
        assertEquals(60L, ReadingSpeed.remainingSeconds(350f, 0f))
        assertEquals(60L, ReadingSpeed.remainingSeconds(350f, Float.NaN))
    }

    @Test
    fun `分钟精度按区间取整`() {
        assertEquals(0L, ReadingSpeed.displayMinutes(30))
        assertEquals(0L, ReadingSpeed.displayMinutes(59))
        assertEquals(1L, ReadingSpeed.displayMinutes(60))
        assertEquals(2L, ReadingSpeed.displayMinutes(90))     // 1.5 分钟四舍五入
        assertEquals(59L, ReadingSpeed.displayMinutes(3540))  // 59 分钟
        assertEquals(60L, ReadingSpeed.displayMinutes(3600))
        assertEquals(60L, ReadingSpeed.displayMinutes(3720))  // 62 分钟 → 按 5 分钟取整
        assertEquals(65L, ReadingSpeed.displayMinutes(3900))
    }
}
