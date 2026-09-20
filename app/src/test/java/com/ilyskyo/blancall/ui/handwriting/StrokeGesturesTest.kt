// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「划掉重写」手势判定的单测。
 *
 * 这里守的是两条相反的边界，**任何一条被放松都会造成实际伤害**：
 * - 误判（把正常笔画当手势）→ 用户认真写的笔画被吃掉，且他不知道为什么；
 * - 漏判（真划掉不生效）→ 用户仍可点「清空」，只是少了个便利。
 * 所以判据宁可漏判不可错判，下面的拒绝用例比接受用例更重要。
 */
class StrokeGesturesTest {

    private val w = 600f
    private val h = 200f

    /** 生成一条水平方向的折线；reversed=true 表示去而复返回到起点附近。 */
    private fun horizontalRoundTrip(
        from: Float,
        to: Float,
        y: Float = 100f,
        backFraction: Float = 1.0f,
        steps: Int = 12
    ): List<Offset> {
        val pts = ArrayList<Offset>(steps * 2)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            pts.add(Offset(from + (to - from) * t, y))
        }
        // 回程：从 to 走回 from→to 的 backFraction 处，并加一点纵向微抖（真实手写不会笔直）
        val turn = to
        val backTo = turn - (to - from) * backFraction
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            pts.add(Offset(turn + (backTo - turn) * t, y + if (i % 2 == 0) 1.5f else -1.5f))
        }
        return pts
    }

    // ────────────── 必须接受：真正的划掉 ──────────────

    @Test
    fun `右去左回的长划被识别为划掉`() {
        assertTrue(isScratchOut(horizontalRoundTrip(100f, 500f), w, h))
    }

    @Test
    fun `左去右回的长划同样被识别`() {
        assertTrue(isScratchOut(horizontalRoundTrip(500f, 100f), w, h))
    }

    // ────────────── 必须拒绝：正常笔画（这几条才是重点） ──────────────

    @Test
    fun `单向长横不是划掉——否则一二三的首笔会被吃掉`() {
        val one = List(15) { i -> Offset(100f + i * 28f, 100f) }   // 一条「一」
        assertFalse(isScratchOut(one, w, h))
    }

    @Test
    fun `回程只走了一半的单向长横不是划掉`() {
        // 写「一」收笔时的小回锋：端点离起点仍很远
        assertFalse(isScratchOut(horizontalRoundTrip(100f, 500f, backFraction = 0.5f), w, h))
    }

    @Test
    fun `跨度不足的往返不是划掉`() {
        // 只有板宽 20%：太短，可能只是想写一个短横／提
        assertFalse(isScratchOut(horizontalRoundTrip(200f, 300f), w, h))
    }

    @Test
    fun `竖直往返不是划掉`() {
        val pts = ArrayList<Offset>()
        for (i in 0..12) pts.add(Offset(300f, 20f + i * 14f))
        for (i in 1..12) pts.add(Offset(300f, 188f - i * 14f))
        assertFalse(isScratchOut(pts, w, h))
    }

    @Test
    fun `斜向撇捺不是划掉`() {
        // 高宽比超限
        val pts = ArrayList<Offset>()
        for (i in 0..12) pts.add(Offset(100f + i * 25f, 20f + i * 12f))
        for (i in 1..12) pts.add(Offset(400f - i * 25f, 164f - i * 12f))
        assertFalse(isScratchOut(pts, w, h))
    }

    @Test
    fun `点与短划不是划掉`() {
        assertFalse(isScratchOut(List(3) { Offset(300f, 100f) }, w, h))
        assertFalse(isScratchOut(emptyList(), w, h))
        assertFalse(isScratchOut(listOf(Offset(300f, 100f)), w, h))
    }

    // ────────────── 不变性：判定只看相对比例 ──────────────

    @Test
    fun `同形状放大或缩小结论不变`() {
        val stroke = horizontalRoundTrip(100f, 500f)
        val big = stroke.map { Offset(it.x * 3f, it.y * 3f) }
        val small = stroke.map { Offset(it.x * 0.5f, it.y * 0.5f) }
        assertTrue(isScratchOut(stroke, w, h))
        assertTrue(isScratchOut(big, w * 3f, h * 3f))
        assertTrue(isScratchOut(small, w * 0.5f, h * 0.5f))
    }

    @Test
    fun `面板尺寸非法时一律判否`() {
        val stroke = horizontalRoundTrip(100f, 500f)
        assertFalse(isScratchOut(stroke, 0f, h))
        assertFalse(isScratchOut(stroke, w, 0f))
        assertFalse(isScratchOut(stroke, -1f, h))
    }

    @Test
    fun `横向跨度刚好在阈值上下的行为一致且可预期`() {
        // 阈值 = 板宽 × 0.30 = 180px
        val justUnder = horizontalRoundTrip(200f, 370f)   // 跨度 170 < 180
        val justOver = horizontalRoundTrip(200f, 390f)    // 跨度 190 > 180
        assertEquals(false, isScratchOut(justUnder, w, h))
        assertEquals(true, isScratchOut(justOver, w, h))
    }

    // ============ splitInkByXGap：把一板墨迹按纵向空隙切成单字段 ============

    /** 造一个「字」：三笔铺满 [xStart, xEnd]（保证列连续，不留内部空隙） */
    private fun glyph(xStart: Float, xEnd: Float): List<List<Offset>> = listOf(
        listOf(Offset(xStart, 120f), Offset(xEnd, 120f)),
        listOf(Offset(xStart, 300f), Offset(xEnd, 300f)),
        listOf(Offset((xStart + xEnd) / 2, 120f), Offset((xStart + xEnd) / 2, 300f))
    )

    private fun inkOf(vararg glyphs: List<List<Offset>>): List<List<Offset>> = glyphs.toList().flatten()

    @Test
    fun `并排两个字被切成两段`() {
        val ink = inkOf(glyph(100f, 250f), glyph(350f, 500f))   // 总宽 400，中缝 100
        val segs = splitInkByXGap(ink)
        assertEquals(2, segs?.size)
    }

    @Test
    fun `并排三个字被切成三段且按从左到右排序`() {
        val ink = inkOf(glyph(450f, 550f), glyph(50f, 150f), glyph(250f, 350f))
        val segs = splitInkByXGap(ink)
        assertEquals(3, segs?.size)
        // 第一段必须是最左边的字（点的 x 均值最小）—— 上屏顺序不能乱
        val first = segs!!.first().flatten()
        val avgX = first.map { it.x }.average()
        assertTrue("第一段应是 x≈50-150 的字，实际均值 $avgX", avgX in 50.0..150.0)
    }

    @Test
    fun `单个字不切——好这类左右结构字被护栏保护`() {
        // 真实比例的「好」：宽 230 / 高 180 = 1.28 → 单字宽度先验下 round 后仍是 1 个字，
        // 根本不会进入切分（旧实现是「宽高比 < 1.35 不切」，判据换成了字数估计）
        val hao = listOf(
            listOf(Offset(100f, 120f), Offset(210f, 120f)),
            listOf(Offset(100f, 300f), Offset(210f, 300f)),
            listOf(Offset(220f, 140f), Offset(330f, 140f)),
            listOf(Offset(220f, 300f), Offset(330f, 300f))
        )
        assertNull(splitInkByXGap(hao))
    }

    @Test
    fun `横向长笔画没有纵向空隙也不切`() {
        // 「一」：宽 500 / 高 200 = 宽高比 2.5，但列全被占满，无空隙可切
        val yi = listOf(listOf(Offset(50f, 200f), Offset(550f, 200f)))
        assertNull(splitInkByXGap(yi))
    }

    @Test
    fun `写得挤也切——连写优先，识别兜底`() {
        // 中缝 20px（真机上两个字几乎贴着）：仍切，因为单字模型对合并墨迹必然失败，
        // 切开即使认错也给了用户「点候选/删掉重写」的出路
        val ink = inkOf(glyph(100f, 280f), glyph(300f, 480f))
        val segs = splitInkByXGap(ink)
        assertEquals(2, segs?.size)
    }

    @Test
    fun `紧凑连写也切——真机校准（在干嘛场景）`() {
        // 三字连排、字距只有字高的四分之一（45px / 180px）：真机「在干嘛」失败的场景
        val ink = inkOf(glyph(100f, 240f), glyph(285f, 425f), glyph(470f, 610f))
        val segs = splitInkByXGap(ink)
        assertEquals(3, segs?.size)
    }

    @Test
    fun `空墨迹不切`() {
        assertNull(splitInkByXGap(emptyList()))
    }

    // ============ 文种宽度先验：拉丁字母连写 ============

    private fun latinWord(letterCount: Int): List<List<Offset>> {
        val h = 180f                        // 字高（和 glyph 一致：y 120→300）
        val letterW = CHAR_ASPECT_LATIN * h // 单字母宽 ≈ 111.6
        val gap = 0.12f * h                 // 字母间距 ≈ 21.6
        val step = letterW + gap
        return (0 until letterCount)
            .map { i -> glyph(50f + i * step, 50f + i * step + letterW) }
            .flatten()
    }

    @Test
    fun `英文连写按字母先验切成全部字母——用汉字先验会少切一半`() {
        // 「hello」：总宽 = 5×111.6 + 4×21.6 = 644.4。
        // 若沿用汉字先验（width/h = 3.6 → 只切 3~4 段），字母会被并成错误的两字组合；
        // 拉丁先验（0.62）下 n≈6、可切空隙 4 条 → 恰好 5 段。
        val segs = splitInkByXGap(latinWord(5), CHAR_ASPECT_LATIN)
        assertEquals(5, segs?.size)
    }

    @Test
    fun `拉丁先验不会把中文连写切碎`() {
        // 反向护栏：两个汉字（总宽 400 / 高 180）用拉丁先验仍是 2 段，不会过切
        val ink = inkOf(glyph(100f, 250f), glyph(350f, 500f))
        assertEquals(2, splitInkByXGap(ink, CHAR_ASPECT_LATIN)?.size)
    }
}
