// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `左右结构宽字连写不会被劈开——段数自校正`() {
        // 真机案例「你好」：两字都是左右结构（宽≈1.33 倍字高），字内有部件间隙。
        // 旧实现 n=round(560/180)=3 → 把「好」的「女/子」部件间隙当字间缝劈开，
        // 半字段识别出 (、8 这类乱候选。逐级回退应按段宽均匀性拒绝 3 段、采纳 2 段。
        fun wideGlyph(x0: Float, w: Float): List<List<Offset>> {
            val partW = (w - 30f) / 2f   // 左右两部件，部件间隙 30px
            return listOf(
                listOf(Offset(x0, 120f), Offset(x0 + partW, 120f)),
                listOf(Offset(x0, 300f), Offset(x0 + partW, 300f)),
                listOf(Offset(x0 + partW + 30f, 120f), Offset(x0 + w, 120f)),
                listOf(Offset(x0 + partW + 30f, 300f), Offset(x0 + w, 300f))
            )
        }
        // 两字各宽 240（=1.33×180），字间距 80px；总宽 560 → 旧 n=3
        val ink = wideGlyph(100f, 240f) + wideGlyph(420f, 240f)
        val segs = splitInkByXGap(ink)
        assertEquals(2, segs?.size)
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

    @Test
    fun `英文紧排连写也能切开——字母间距只有汉字的一半`() {
        // 真机场景：写「hello」时字母几乎挨着，间距仅字高的 5%（h=180 → 9px）。
        // 若沿用汉字的可切空隙阈值（10% 字高 = 18px），会判成「根本没有空隙可切」
        // → 段数掉回 1 → 整段回落整板识别 → 五个字母喂进单字符模型必然乱码。
        val h = 180f
        val letterW = CHAR_ASPECT_LATIN * h   // 111.6
        val gap = 0.05f * h                   // 9px
        val step = letterW + gap
        val ink = (0 until 5)
            .map { i -> glyph(50f + i * step, 50f + i * step + letterW) }
            .flatten()
        assertEquals(5, splitInkByXGap(ink, CHAR_ASPECT_LATIN)?.size)
    }

    // ============ 拉丁连笔：弱切点（墨量谷）切分 ============

    /**
     * 造一个「连笔单词」：每个字母由 3 条横线构成主体（列墨量高），
     * 字母之间用一条底部细桥连接（列墨量低 = 谷）。整行**无真空隙**（强切点为空）
     * —— 这正是真机「连笔 hello → hdW」的形态。
     */
    private fun cursiveWord(letterCount: Int): List<List<Offset>> {
        val y0 = 140f
        val letterW = 90f
        val bridge = 20f
        val pitch = letterW + bridge
        val strokes = ArrayList<List<Offset>>()
        var x = 100f
        repeat(letterCount) {
            // 字母主体：3 条横线（模拟笔画来回，列墨量 3）
            strokes += listOf(Offset(x, y0), Offset(x + letterW, y0))
            strokes += listOf(Offset(x, y0 + 40f), Offset(x + letterW, y0 + 40f))
            strokes += listOf(Offset(x, y0 + 80f), Offset(x + letterW, y0 + 80f))
            // 连笔桥：到下一字母左端（列墨量 1，位于最底部）
            if (it < letterCount - 1) {
                strokes += listOf(Offset(x + letterW, y0 + 160f), Offset(x + pitch, y0 + 160f))
            }
            x += pitch
        }
        return strokes
    }

    @Test
    fun `连笔单词（无真空隙）靠墨量谷切成每个字母`() {
        // 真机场景：连笔写「hello」，字母相连无空隙，强切点为 0，旧实现段数塌缩成 3（hdW）。
        // 新实现：五个字母三条横线 + 四段连笔桥 ⇒ 4 个墨量谷 ⇒ 谷间距校准单字宽
        // （110px；字高法先验 0.62×160≈99px 在含升部的连写作上会高估导致 n 偏小）
        // ⇒ 切成 5 段。
        val ink = cursiveWord(5)
        val segs = splitInkByXGap(ink, CHAR_ASPECT_LATIN)
        assertEquals(5, segs?.size)
    }

    @Test
    fun `连笔切点落在字母之间——不劈开字母`() {
        val ink = cursiveWord(5)
        val segs = splitInkByXGap(ink, CHAR_ASPECT_LATIN) ?: return
        // 每段应只包含一个字母（宽度在一个字母量级，远小于 2 个字母）
        val widths = segs.map { seg ->
            val xs = seg.flatten().map { it.x }
            xs.max() - xs.min()
        }
        assertTrue("每段宽应在一个字母量级，实际=$widths", widths.all { it <= 130f })
    }

    // ============ splitInkSmart：几何优先 + 时间停顿补充 ============

    /** 造一个带时间戳的「字」：三笔铺满 [xStart, xEnd]，时间窗从 [startMs] 起。 */
    private fun timedGlyph(
        xStart: Float,
        xEnd: Float,
        startMs: Long,
        strokeStepMs: Long = 120L
    ): List<StrokeSnapshot> = listOf(
        StrokeSnapshot(listOf(Offset(xStart, 120f), Offset(xEnd, 120f)), startMs, startMs + 30),
        StrokeSnapshot(
            listOf(Offset(xStart, 300f), Offset(xEnd, 300f)),
            startMs + strokeStepMs, startMs + strokeStepMs + 30
        ),
        StrokeSnapshot(
            listOf(Offset((xStart + xEnd) / 2, 120f), Offset((xStart + xEnd) / 2, 300f)),
            startMs + 2 * strokeStepMs, startMs + 2 * strokeStepMs + 30
        )
    )

    @Test
    fun `几何切不开但字间有明显停顿——按停顿切成两段`() {
        // 两字间隙仅 8px（< 汉字阈值 8%×180=14.4px）⇒ 几何无切点；
        // 但字间停顿 530ms（> 300ms）且边界存留微小空隙、簇宽合理 ⇒ 时间切分生效。
        // 这正是「多字被识别成一个字」的主场景（紧凑连写）。
        val ink = timedGlyph(100f, 250f, 0L) + timedGlyph(258f, 408f, 800L)
        val segs = splitInkSmart(ink)
        assertEquals(2, segs?.size)
        // 段内必须是原笔画的**引用**（removeStrokes 依赖引用匹配逐段清墨）
        assertSame(ink[0].pts, segs!!.first().first())
        assertSame(ink[3].pts, segs[1].first())
    }

    @Test
    fun `字内长停顿但上下 x 投影重叠——不得误切`() {
        // 「赢」式上下结构：上半与下半 x 投影重叠，中间虽停 450ms 也不能切
        val top = listOf(
            StrokeSnapshot(listOf(Offset(100f, 120f), Offset(250f, 120f)), 0L, 30L),
            StrokeSnapshot(listOf(Offset(100f, 160f), Offset(250f, 160f)), 120L, 150L)
        )
        val bottom = listOf(
            StrokeSnapshot(listOf(Offset(120f, 240f), Offset(270f, 240f)), 600L, 630L),
            StrokeSnapshot(listOf(Offset(120f, 300f), Offset(270f, 300f)), 720L, 750L)
        )
        assertNull(splitInkSmart(top + bottom))
    }

    @Test
    fun `时间簇太窄——判为半个字，拒绝时间切分`() {
        // 第一簇只占 30px（< 0.25×unit=45px）：即使边界有空隙且停顿够长，也不能切
        val a = listOf(
            StrokeSnapshot(listOf(Offset(100f, 120f), Offset(130f, 120f)), 0L, 30L)
        )
        val b = listOf(
            StrokeSnapshot(listOf(Offset(135f, 120f), Offset(315f, 120f)), 800L, 830L),
            StrokeSnapshot(listOf(Offset(135f, 300f), Offset(315f, 300f)), 900L, 930L)
        )
        assertNull(splitInkSmart(a + b))
    }

    @Test
    fun `几何能切时优先几何——结果与纯几何一致`() {
        val ink = timedGlyph(100f, 250f, 0L) + timedGlyph(350f, 500f, 800L)
        val geo = splitInkByXGap(ink.map { it.pts })
        val smart = splitInkSmart(ink)
        assertEquals(2, geo?.size)
        assertEquals(geo?.size, smart?.size)
    }

    // ============ needsMulticharGuard：多字形态守卫 ============

    @Test
    fun `「你好」式连写命中守卫——禁止自动上屏`() {
        val ink = inkOf(glyph(100f, 250f), glyph(350f, 500f))   // 总宽 400、h=180
        assertTrue(needsMulticharGuard(ink, panelHeight = 200f))
    }

    @Test
    fun `单个「好」不命中守卫`() {
        val hao = listOf(
            listOf(Offset(100f, 120f), Offset(210f, 120f)),
            listOf(Offset(100f, 300f), Offset(210f, 300f)),
            listOf(Offset(220f, 140f), Offset(330f, 140f)),
            listOf(Offset(220f, 300f), Offset(330f, 300f))
        )
        assertFalse(needsMulticharGuard(hao, panelHeight = 200f))
    }

    @Test
    fun `「一」类长横字被高度前置条件豁免`() {
        // 「一」的 h≈笔宽：宽高比虽大，但高度远不到板高的 45%，不应触发守卫
        val yi = listOf(listOf(Offset(50f, 200f), Offset(550f, 200f)))
        assertFalse(needsMulticharGuard(yi, panelHeight = 200f))
    }

    @Test
    fun `拉丁多字母连写命中守卫、单字母不命中`() {
        val hello = latinWord(5)   // 总宽 644.4、h=180
        assertTrue(needsMulticharGuard(hello, panelHeight = 200f, charAspect = CHAR_ASPECT_LATIN))
        val oneLetter = glyph(100f, 100f + CHAR_ASPECT_LATIN * 180f)   // 宽 111.6
        assertFalse(needsMulticharGuard(oneLetter, panelHeight = 200f, charAspect = CHAR_ASPECT_LATIN))
    }
}
