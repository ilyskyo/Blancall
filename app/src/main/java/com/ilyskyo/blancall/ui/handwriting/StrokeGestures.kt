// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * 手写板里的**手势**（与「写字」相对）判定。
 *
 * ## 为什么需要「划掉重写」
 * 「划掉即删除」是三个平台各自独立收敛到的同一手势：
 * - Microsoft 手写设计指南：纠错手段里明确列出 **scratch-out gesture**（划掉识别错的墨迹再重写）；
 * - Apple Scribble 官方手册：**Scratch — 划掉字母/词以删除**；
 * - 同类产品不背单词：**写错可划三下重写**。
 *
 * 而 Blancall 的手写板原先只有一枚「清空」按钮：写坏一个字要抬手离开书写区去点按钮，
 * 书写节奏被打断。本文件提供纯函数判定，供 `HandwritingPanel` 在**抬笔时**识别该手势。
 *
 * ## 判据为什么必须是「去而复返」
 * 单纯「一条长横」绝对不能用 —— 「一」「二」「三」「王」的首笔都是长横，
 * 一旦误判就会把用户正常写的笔画当成删除手势。
 * 因此必须要求**往返**：起笔出去、又回到起点附近。
 * 判据取严：宁可漏判（用户仍可点「清空」），也绝不错判（那会吃掉正常笔画）。
 */

/** 手势判定所需的最少采样点数：点、短划、抖动都不该被当成手势。 */
private const val MIN_POINTS = 5

/** 横向跨度至少占板宽的这个比例才算「一条划」。 */
private const val MIN_WIDTH_RATIO = 0.30f

/** 纵向跨度上限：占板高比例。超过就不是横划（撇、捺、竖直往返都会超）。 */
private const val MAX_HEIGHT_RATIO = 0.22f

/** 纵向跨度还要相对自身横向跨度够扁，防止在窄板里被板高比例放水。 */
private const val MAX_ASPECT = 0.45f

/** 端点必须回到起点附近：|xEnd - xStart| ≤ 横向跨度 × 该比例。单向长横的该值约为 1.0。 */
private const val MAX_ENDPOINT_GAP = 0.40f

/**
 * 判断一条笔画是否为「划掉重写」手势（**去而复返的长横划**）。
 *
 * 判定全部基于**相对比例**（板宽/板高），因此与屏幕密度、书写区像素尺寸无关。
 *
 * @param stroke 该笔画的采样点序列（按时间顺序）
 * @param panelWidth 书写区宽度（像素）
 * @param panelHeight 书写区高度（像素）
 * @return 命中返回 true —— 调用方应把它当作手势，**不要**计入笔画、也不要触发识别
 */
fun isScratchOut(
    stroke: List<Offset>,
    panelWidth: Float,
    panelHeight: Float
): Boolean {
    if (stroke.size < MIN_POINTS) return false
    if (panelWidth <= 0f || panelHeight <= 0f) return false

    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in stroke) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    val w = maxX - minX
    val h = maxY - minY

    // ① 够长：横跨幅度要够大
    if (w < panelWidth * MIN_WIDTH_RATIO) return false
    // ② 够扁：纵向不许跑远（挡掉撇捺与竖直往返）
    if (h > panelHeight * MAX_HEIGHT_RATIO) return false
    if (h > w * MAX_ASPECT) return false
    // ③ 去而复返：端点必须回到起点附近 —— 这是与「一」的横画的关键区别
    val endpointDx = abs(stroke.last().x - stroke.first().x)
    if (endpointDx > w * MAX_ENDPOINT_GAP) return false

    return true
}

/**
 * 把一板墨迹按**纵向空隙**切成单字段（支持任意字数的连写）。
 *
 * ## 算法：先估字数，再做最优切分
 * 1. **估字数**：汉字/字母的单字宽度是稳定的先验（[charAspect] 倍字高），
 *    所以由 `n = round(总宽 / (字高 × charAspect))` 得到「这一板写了几个字」；
 * 2. **列占用表**：沿**线段**铺列（不能只标端点，见下），得到全部「墨迹之间的纵向空隙」；
 * 3. **最优切分**：在空隙里选 `n-1` 个下刀位置，使各段宽度尽量接近 `总宽 / n`
 *    —— 用动态规划最小化各段宽度的方差。DP 很便宜（空隙数通常 < 20）。
 *
 * ## 为什么不是「递归取最宽空隙」
 * 旧实现每次都在**全局最宽**的空隙下刀，一旦这个空隙其实是某个字内部的结构缝隙
 * （「好」的左右、「川」的三竖之间），就会把一个字劈成两个错字；而真正该下刀的字间
 * 空隙反而被留着。改为「以字数为目标 + 段宽均匀」后，切点由**宽度先验**约束，
 * 不再由「谁最宽」决定。这是真机反馈「拆分有时不准」的直接原因。
 *
 * ## 护栏
 * - 估算字数 < 2 直接不切（写得挤的两字若总宽不到 1.5 倍字高，宁可不切）；
 * - 可切空隙的**长度**至少占字高的 [MIN_GAP_RATIO]（放宽时为 [MIN_GAP_RATIO_RELAX]），
 *   滤掉笔画自身的小缺口（如「乙」的钩）；
 * - 切完若出现「宽度不足字高 0.2 倍」的段，说明把字劈开了 ⇒ 整体放弃切分；
 * - 切完仍有过宽的段（> [charAspect]×2.4 倍字高）⇒ 在段内用放宽阈值再切一刀。
 *
 * 触发时机（调用方保证）：只在墨迹明显偏宽时才走这里。
 *
 * @param strokes 板上全部笔画（各笔为点序列）
 * @param charAspect 单字宽度先验（宽/高）：汉字 [CHAR_ASPECT_CJK]，拉丁字母 [CHAR_ASPECT_LATIN]
 * @return 切出的段（每段为「该段内的笔画点」，从左到右排序）；切不出 ≥2 段时返回 null。
 */
fun splitInkByXGap(
    strokes: List<List<Offset>>,
    charAspect: Float = CHAR_ASPECT_CJK
): List<List<List<Offset>>>? {
    val segments = splitOptimal(strokes, charAspect, relax = false) ?: return null

    // 细化：DP 只在「够宽的空隙」里下刀，若某个字写得太挤（空隙 < 阈值）会漏切，
    // 表现为某段明显过宽。对这一类段用放宽阈值再切一刀。
    val h = heightOf(strokes)
    if (h <= 0f) return segments
    val overWide = h * charAspect * OVERWIDE_FACTOR
    val refined = ArrayList<List<List<Offset>>>()
    segments.forEach { seg ->
        if (widthOf(seg) > overWide) {
            refined.addAll(splitOptimal(seg, charAspect, relax = true) ?: listOf(seg))
        } else {
            refined.add(seg)
        }
    }
    return if (refined.size >= 2) refined else null
}

/** 一次最优切分：目标段数由宽度先验给出；切不出 ≥2 段返回 null。 */
private fun splitOptimal(
    ink: List<List<Offset>>,
    charAspect: Float,
    relax: Boolean
): List<List<List<Offset>>>? {
    val pts = ink.flatten()
    if (pts.size < 2) return null

    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    pts.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    val w = maxX - minX
    val h = maxY - minY
    if (w <= 0f || h <= 0f) return null

    // ② 列占用表（1px 粒度）。
    // ⚠️ 必须沿**线段**铺列，不能只标点的 x —— 一笔从 x=100 划到 x=250，
    // 中间的列全是有墨的；只标两端点会把字的内部当成「空隙」切得稀碎（单测抓过）。
    // ⚠️ 同样**不要**按笔宽向外膨胀列：两字之间 20px 的中心线间距在真机上已经是
    // 「写得很挤」，一旦膨胀就再也找不到这条缝，连写拆分会退化（单测「写得挤也切」抓过）。
    // 拉丁时额外统计「列墨量」（每列被笔画经过的次数）：连笔字母之间的连接笔画
    // 通常只有单次经过（墨量谷），字母主体的笔画多为多次经过 —— 弱切点就找这些谷。
    val latin = charAspect < 0.8f
    val density: IntArray? = if (latin) IntArray(w.toInt() + 2) else null
    val cols = BooleanArray(w.toInt() + 2)
    ink.forEach { stroke ->
        if (stroke.isEmpty()) return@forEach
        if (stroke.size == 1) {
            markCols(cols, minX, stroke[0].x, stroke[0].x)
            markColDensity(density, minX, stroke[0].x, stroke[0].x)
        } else {
            for (i in 1 until stroke.size) {
                val a = minOf(stroke[i - 1].x, stroke[i].x)
                val b = maxOf(stroke[i - 1].x, stroke[i].x)
                markCols(cols, minX, a, b)
                markColDensity(density, minX, a, b)
            }
        }
    }

    // ③ 全部够宽的纵向空隙 → 候选切点（取空隙中心）
    //
    // ⚠️ 可切空隙阈值必须**按文种分开**：英文字母本来就窄（0.62×字高），连写时字母
    // 之间的间距常常只有几像素。沿用汉字阈值（10% 字高）会把「hello」判成
    // 「根本没有空隙可切」→ 段数掉回 1 → 整段回落整板识别 → 五个字母喂进单字符模型，
    // 必然输出乱码（真机现象就是「英文怎么写都出不来」）。
    val minGap = if (latin) h * MIN_GAP_RATIO_LATIN
    else h * (if (relax) MIN_GAP_RATIO_RELAX else MIN_GAP_RATIO)
    val cuts = ArrayList<CutPoint>()
    var i = 0
    while (i < cols.size) {
        if (!cols[i]) {
            val s = i
            while (i < cols.size && !cols[i]) i++
            if ((i - s).toFloat() >= minGap) {
                cuts.add(CutPoint(minX + s + (i - s) / 2f, weak = false))
            }
        } else {
            i++
        }
    }

    // ③.5 拉丁专用：连笔时没有真空隙 ⇒ 用「墨量谷」做弱切点，并用谷间距校准单字宽。
    //
    // 背景（真机）：用户连笔写「hello」，字母间墨迹相连（列无空隙）→ 强切点为 0
    // ⇒ 段数塌缩（“hdW” 3 段把 5 个字母吞成 3 个乱字）。但连笔的连接笔画在 x 方向上
    // 是「单次经过」的细桥，列墨量会形成规则的谷 —— 谷间距即字母间距（周期），
    // 用它既能校准单字宽（字高法在含升部的连笔下会高估、导致 n 偏小），又能给出弱切点。
    //
    // ⚠️ 只对拉丁生效：汉字内部结构复杂（左右结构字的天然间隙也会形成谷），
    // 引入弱切点会放大把单字劈开的风险，中文继续只用真空隙。
    var unit = h * charAspect.coerceAtLeast(0.2f)
    if (density != null) {
        val minima = findWeakCuts(density, minX, unit * WEAK_MIN_SPACING_RATIO)
        calibrateLatinUnit(minima, unit)?.let { unit = it }
        minima.forEach { pos ->
            if (cuts.none { abs(it.pos - pos) < unit * 0.25f }) {
                cuts.add(CutPoint(pos, weak = true))
            }
        }
        cuts.sortBy { it.pos }
    }

    // ④ 估字数：单字宽度 = 字高 × charAspect（连笔时已用墨量谷间距校准）
    var n = Math.round(w / unit)
    if (n < 2) return null
    if (cuts.size < n - 1) n = cuts.size + 1 // 候选切点不够就少切几段，不硬凑
    if (n < 2) return null

    // ⑤ 从 n 起往下逐级尝试 DP 切分（选 k = nn-1 个切点），
    // 第一个「每段都像一个字」的方案即采纳。
    //
    // ⚠️ 为什么必须逐级回退（真机教训）：字宽先验是**平均值**，而左右结构的宽字
    // （「你」「好」）实际宽可达 1.3 倍字高 —— 两个这样的字连写时 n = round(w/unit)
    // 会被高估成 3，于是把「好」的「女/子」部件间隙当成字间缝劈开，
    // 半字段识别出 `(`、`8` 这类乱候选（真机复现：写「你好」→ 候选 ( / 尕 / 8）。
    // 而「一段至少要有半个多字宽、且各段宽度不应悬殊」比平均字宽稳定得多 ——
    // 用它们对 n 自校正，误切率大幅下降。
    val minSegW = unit * (if (latin) MIN_SEG_UNITS_LATIN else MIN_SEG_UNITS_CJK)
    for (nn in n downTo 2) {
        val k = nn - 1
        if (cuts.size < k) continue
        val chosen = chooseCuts(cuts, minX, maxX, k) ?: continue

        val out = ArrayList<List<List<Offset>>>(nn)
        var leftEdge = minX
        for (idx in chosen.indices) {
            val rightEdge = chosen[idx]
            out.add(cutRange(ink, leftEdge, rightEdge))
            leftEdge = rightEdge
        }
        out.add(cutRange(ink, leftEdge, Float.MAX_VALUE))

        // ⑥ 护栏：出现「细得不像一个字」或「宽度悬殊」的段 ⇒ 这一档不可信，降一档重试
        out.removeAll { it.isEmpty() }
        if (out.size < 2) continue
        if (out.any { widthOf(it) < minSegW }) continue
        if (out.any { widthOf(it) < h * MIN_SEG_ASPECT }) continue
        val widths = out.map { widthOf(it) }
        if (widths.max() > widths.min() * MAX_SEG_WIDTH_RATIO) continue
        return out
    }
    return null
}

/**
 * 把拉丁（英语）墨迹按「词间大空隙」聚成词组（词内不再切分）。
 *
 * ## 为什么需要
 * 英语不只是单个单词：还有词组/短语（"give up"）。用户一口气写完两组词时，
 * 词与词之间的空隙明显大于字母间距（字母 3–8% 字高，词间通常 ≥ 0.3 字高）。
 * 先按词分组、再逐词跑现有切段逻辑有两个直接收益：
 * 1. 词界宽度不参与「估字数」—— 整体切段时 `n = round(总宽/单字宽)` 会把
 *    词间空地也算成一个字，段数高估后可能把宽字母劈开；
 * 2. 提交流程可以在词间插入空格，得到 "give up" 而不是 "giveup"。
 *
 * ## 判据
 * 把笔画按起点 x 排序后扫一遍：若下一笔的起点与「当前词已覆盖的最大 x」之间
 * 出现 > [WORD_GAP_UNIT_RATIO]×单字宽的空白，则断开为新词。排序只用于分组
 * 扫描（兼容乱序书写）；分组只是**归属划分**，组内笔画仍是原列表引用。
 *
 * 护栏：切不出 ≥2 个词时返回 null（调用方走原有单组路径，零行为变化）。
 *
 * @param strokes 板上全部笔画（各笔为点序列）
 * @param charAspect 单字宽度先验（拉丁 [CHAR_ASPECT_LATIN]；本函数只用于拉丁）
 * @return 词组列表（每组为「该词的笔画」，组间按 x 从左到右）；不足 2 组时返回 null
 */
fun splitInkIntoWords(
    strokes: List<List<Offset>>,
    charAspect: Float = CHAR_ASPECT_LATIN
): List<List<List<Offset>>>? {
    if (strokes.size < 2) return null
    val h = heightOf(strokes)
    if (h <= 0f) return null
    val gapThreshold = h * charAspect.coerceAtLeast(0.2f) * WORD_GAP_UNIT_RATIO

    val ordered = strokes.sortedBy { s -> s.minOfOrNull { it.x } ?: Float.MAX_VALUE }
    val words = ArrayList<List<List<Offset>>>()
    var cur = ArrayList<List<Offset>>()
    var curMaxX = -Float.MAX_VALUE
    for (s in ordered) {
        val lo = s.minOfOrNull { it.x } ?: continue
        val hi = s.maxOfOrNull { it.x } ?: continue
        if (cur.isNotEmpty() && lo - curMaxX > gapThreshold) {
            words.add(cur)
            cur = ArrayList()
            curMaxX = -Float.MAX_VALUE
        }
        cur.add(s)
        if (hi > curMaxX) curMaxX = hi
    }
    if (cur.isNotEmpty()) words.add(cur)
    return if (words.size >= 2) words else null
}

/**
 * 「几何 + 停顿」联合切分：几何优先，失败时用书写停顿补充。
 *
 * ## 为什么需要时间信号
 * 纯几何切分（[splitInkByXGap]）依赖字间存在**纵向空隙**。用户写得紧凑或连笔时
 * 空隙消失 ⇒ 切分返回 null ⇒ 整板多字被喂进**单字模型**，输出一个高置信乱码
 * —— 真机现象就是「连写多个字只出来一个字」。
 *
 * 而「字间停顿」是比几何空隙更自然的分字信号：一个字内部的笔画间停顿通常
 * 更短（脑内还在写同一个字的下一笔），字与字之间往往有明显停顿。因此：
 * 1. 几何能切就切（行为与改造前逐位一致，零回归风险）；
 * 2. 几何切不开时，按笔间停顿 > [TIME_GAP_CJK_MS]/[TIME_GAP_LATIN_MS] 分簇，
 *    但必须通过几何护栏才采纳（见 [splitByTimeGap]）。
 *
 * 返回段内为原笔画点集的**引用**，调用方可继续用 [InkBoardView.removeStrokes] 逐段清理。
 *
 * @param strokes 带时间的墨迹快照（书写顺序）
 * @param charAspect 单字宽度先验（宽/高）：汉字 [CHAR_ASPECT_CJK]，拉丁 [CHAR_ASPECT_LATIN]
 * @return 切出的段；两侧都切不出 ≥2 段时返回 null（由调用方走整板识别 + 守卫）
 */
fun splitInkSmart(
    strokes: List<StrokeSnapshot>,
    charAspect: Float = CHAR_ASPECT_CJK
): List<List<List<Offset>>>? {
    // ① 几何优先：与改造前行为完全一致
    val geometry = splitInkByXGap(strokes.map { it.pts }, charAspect)
    if (geometry != null) return geometry
    // ② 几何失败 → 时间停顿补充
    return splitByTimeGap(strokes, charAspect)
}

/**
 * 按笔间停顿分簇切字（[splitInkSmart] 的第二级）。
 *
 * 采纳条件（全部满足才返回，否则 null 回落几何/整板）：
 * 1. 至少切成 2 簇；
 * 2. **每个边界处仍有微小几何空隙**（≥ [TIME_EDGE_MIN_GAP_RATIO]×字高）
 *    —— 防「赢」类上下结构汉字内部的长停顿被误当成字间边界
 *    （这种字的上下部分在 x 投影上必然重叠，边界不成立）；
 * 3. 每簇宽度 ∈ [TIME_SEG_MIN_UNITS, TIME_SEG_MAX_UNITS] × 单字宽
 *    —— 太窄说明把半个字切了；太宽说明簇内还挤着多字，时间信号没切干净。
 */
private fun splitByTimeGap(
    strokes: List<StrokeSnapshot>,
    charAspect: Float
): List<List<List<Offset>>>? {
    if (strokes.size < 2) return null
    val gapMs = if (charAspect < 0.8f) TIME_GAP_LATIN_MS else TIME_GAP_CJK_MS

    // 1) 按停顿分簇（strokes 顺序即书写时序）
    val clusters = ArrayList<MutableList<StrokeSnapshot>>()
    var cluster = ArrayList<StrokeSnapshot>()
    cluster.add(strokes[0])
    for (i in 1 until strokes.size) {
        val gap = strokes[i].startMs - strokes[i - 1].endMs
        if (gap > gapMs) {
            clusters.add(cluster)
            cluster = ArrayList()
        }
        cluster.add(strokes[i])
    }
    clusters.add(cluster)
    if (clusters.size < 2) return null

    // 2) 几何护栏
    val allPts = strokes.map { it.pts }
    val h = heightOf(allPts)
    if (h <= 0f) return null
    val unit = h * charAspect.coerceAtLeast(0.2f)
    val minEdgeGap = h * TIME_EDGE_MIN_GAP_RATIO

    for (k in 0 until clusters.size - 1) {
        val leftMax = maxXOf(clusters[k].map { it.pts })
        val rightMin = minXOf(clusters[k + 1].map { it.pts })
        if (leftMax == null || rightMin == null) return null
        if (rightMin - leftMax < minEdgeGap) return null
    }
    for (c in clusters) {
        val w = widthOf(c.map { it.pts })
        if (w < unit * TIME_SEG_MIN_UNITS || w > unit * TIME_SEG_MAX_UNITS) return null
    }

    return clusters.map { c -> c.map { it.pts } }
}

/**
 * 「多字形态」守卫：切分失败、墨迹又明显偏宽时，禁止自动上屏。
 *
 * ## 为什么需要
 * 切分失败（几何切不出、时间也不成立）时调用方会走**整板识别**。若用户实际写的是
 * 多个字，单字模型会输出一个高置信乱码字并自动上屏 —— 用户看到的答案是「一个字」，
 * 而实际写的是三个字，且错字已静默写进答案（伤害最大的一类体验）。
 * 此守卫把这种情况降级为「展示候选 + 提示分开写」，宁多一次点选，不错写。
 *
 * ## 为什么不会被「一/二/三」误伤
 * 前置条件是字高 h 达到板高 [GUARD_MIN_HEIGHT_RATIO]（按格写）。长横类单字
 * （一/二/三/十）的 h 极小（≈笔宽），天然不满足；而「你好」这类真实两字连写的
 * h 接近满格，且总宽超过单字宽上限，命中。
 *
 * @param strokes 板上墨迹
 * @param panelHeight 书写板高度（px）
 * @param charAspect 单字宽度先验（汉字 [CHAR_ASPECT_CJK]，拉丁 [CHAR_ASPECT_LATIN]）
 */
fun needsMulticharGuard(
    strokes: List<List<Offset>>,
    panelHeight: Float,
    charAspect: Float = CHAR_ASPECT_CJK
): Boolean {
    if (panelHeight <= 0f || strokes.isEmpty()) return false
    val h = heightOf(strokes)
    if (h < panelHeight * GUARD_MIN_HEIGHT_RATIO) return false
    val unit = h * charAspect.coerceAtLeast(0.2f)
    if (unit <= 0f) return false
    val threshold = if (charAspect < 0.8f) GUARD_UNITS_LATIN else GUARD_UNITS_CJK
    return widthOf(strokes) >= unit * threshold
}

/** 把 x ∈ [left, right) 的墨迹切出来（按笔画整体归属，一笔不会被劈成两半）。 */
private fun cutRange(
    ink: List<List<Offset>>,
    left: Float,
    right: Float
): List<List<Offset>> =
    ink.mapNotNull { s ->
        val mid = s.sumOf { it.x.toDouble() } / s.size
        if (mid >= left && mid < right) s else null
    }

/** 候选切点：[weak]=true 表示「墨量谷」弱切点（连笔场景），DP 中带代价、优先真空隙。 */
private class CutPoint(val pos: Float, val weak: Boolean)

/**
 * 在候选切点中挑 [k] 个（升序），使各段宽度与目标宽度的偏差平方和最小。
 *
 * 标准一维 DP：`dp[i][j]` = 以第 i 个切点作为第 j 刀时的最小代价，
 * 转移枚举上一刀的位置。弱切点额外计入 [WEAK_CUT_COST]（归一化量纲）——
 * 只有「用弱切点能明显改善段宽均匀性」时才值得用，否则优先用真空隙。
 * 空隙数很少（< 40），开销可忽略。
 */
private fun chooseCuts(cuts: List<CutPoint>, minX: Float, maxX: Float, k: Int): List<Float>? {
    val m = cuts.size
    if (k <= 0) return null
    if (m < k) return null
    val target = (maxX - minX) / (k + 1)
    val weakPenalty = WEAK_CUT_COST * target * target
    val inf = Float.MAX_VALUE / 4f
    val dp = Array(m) { FloatArray(k) { inf } }
    val prev = Array(m) { IntArray(k) { -1 } }

    fun cutCost(i: Int): Float = if (cuts[i].weak) weakPenalty else 0f

    for (i in 0 until m) dp[i][0] = sq(cuts[i].pos - minX - target) + cutCost(i)
    for (j in 1 until k) {
        for (i in j until m) {
            for (p in j - 1 until i) {
                if (dp[p][j - 1] >= inf) continue
                val c = dp[p][j - 1] + sq(cuts[i].pos - cuts[p].pos - target) + cutCost(i)
                if (c < dp[i][j]) {
                    dp[i][j] = c
                    prev[i][j] = p
                }
            }
        }
    }
    var bestI = -1
    var bestC = inf
    for (i in 0 until m) {
        if (dp[i][k - 1] >= inf) continue
        val c = dp[i][k - 1] + sq(maxX - cuts[i].pos - target)
        if (c < bestC) {
            bestC = c
            bestI = i
        }
    }
    if (bestI < 0) return null

    val out = ArrayList<Float>(k)
    var i = bestI
    var j = k - 1
    while (j >= 0) {
        out.add(cuts[i].pos)
        i = prev[i][j]
        j--
        if (i < 0 && j >= 0) return null
    }
    out.reverse()
    return out
}

/**
 * 拉丁专用：列墨量的「谷」列表（弱切点候选）。
 *
 * 先标记「谷列」：墨量低于左右两像素窗口最大值的一半（谷要够深，防止平坦
 * 区域的噪声）；再把相邻谷列聚成连通区间，取区间中点 —— 连笔的连接笔画是
 * 一条细桥，其谷是一个**平坦区间**而非单列 V 形谷，取中点才是字母边界。
 * 同[minSpacing]内的过密谷去密（保留先出现者）。
 */
private fun findWeakCuts(
    density: IntArray,
    minX: Float,
    minSpacing: Float
): List<Float> {
    val n = density.size
    // 窗口取间距先验的 1.5 倍：足够跨到谷区外的「字母主体」，又不会把远处别的谷拉进来
    val win = (minSpacing * 1.5f).toInt().coerceAtLeast(4)
    val isValley = BooleanArray(n)
    for (x in win until n - win) {
        val d = density[x]
        if (d <= 0) continue                          // 真空隙已有强切点覆盖
        var maxInWin = 0
        for (j in x - win..x + win) {
            if (density[j] > maxInWin) maxInWin = density[j]
        }
        // 谷要够深：低于窗口最大值的一半。用「窗口最大值」而非「邻列值」是刻意的——
        // 连笔桥的谷是一个**平坦区间**（宽度可达十几像素），用邻列比较会在谷内部失效。
        if (d * 2 <= maxInWin) isValley[x] = true
    }
    val out = ArrayList<Float>()
    var i = win
    var lastAccepted = -Float.MAX_VALUE
    while (i < n - win) {
        if (isValley[i]) {
            val s = i
            while (i < n - win && isValley[i]) i++
            val pos = minX + (s + i - 1) / 2
            if (pos - lastAccepted >= minSpacing) {
                out.add(pos)
                lastAccepted = pos
            }
        } else {
            i++
        }
    }
    return out
}

/**
 * 拉丁专用：用墨量谷的间距（周期）校准单字宽。
 *
 * 连笔单词的字母宽 ≈ 谷间距，而「字高×0.62」在含升部的连写作上会高估（导致
 * n 偏小、段数塌缩）。校准条件（全满足才采纳，否则回退字高先验）：
 * - 至少 4 个谷（≥4 个谷才承认周期存在——5 字母单词有 4 个桥）；
 * - 中位间距落在先验的 0.30–1.15 倍内（防离谱）；
 * - 间距变异系数 < 0.6（周期要规整）。
 */
private fun calibrateLatinUnit(minima: List<Float>, unitPrior: Float): Float? {
    if (minima.size < 4) return null
    val gaps = minima.zipWithNext { a, b -> b - a }
    val median = gaps.sorted()[gaps.size / 2]
    if (median <= 0f) return null
    if (median < unitPrior * 0.30f || median > unitPrior * 1.15f) return null
    val mean = gaps.average()
    if (mean <= 0.0) return null
    val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
    val cv = Math.sqrt(variance) / mean
    if (cv > 0.6) return null
    return median
}

private fun sq(v: Float): Float = v * v

/** 标记 [from, to] 覆盖的列（含两端，越界自动裁剪）。 */
private fun markCols(cols: BooleanArray, originX: Float, from: Float, to: Float) {
    var c = (from - originX).toInt()
    val end = (to - originX).toInt()
    while (c <= end) {
        if (c in cols.indices) cols[c] = true
        c++
    }
}

/** 累计 [from, to] 覆盖到的列墨量（线段经过次数；[density] 为 null 时跳过）。 */
private fun markColDensity(density: IntArray?, originX: Float, from: Float, to: Float) {
    if (density == null) return
    var c = (from - originX).toInt()
    val end = (to - originX).toInt()
    while (c <= end) {
        if (c in density.indices) density[c]++
        c++
    }
}

private fun widthOf(seg: List<List<Offset>>): Float {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    seg.forEach { s -> s.forEach { p -> if (p.x < lo) lo = p.x; if (p.x > hi) hi = p.x } }
    return if (hi >= lo) hi - lo else 0f
}

private fun maxXOf(ink: List<List<Offset>>): Float? {
    var hi = -Float.MAX_VALUE
    ink.forEach { s -> s.forEach { p -> if (p.x > hi) hi = p.x } }
    return if (hi > -Float.MAX_VALUE) hi else null
}

private fun minXOf(ink: List<List<Offset>>): Float? {
    var lo = Float.MAX_VALUE
    ink.forEach { s -> s.forEach { p -> if (p.x < lo) lo = p.x } }
    return if (lo < Float.MAX_VALUE) lo else null
}

private fun heightOf(ink: List<List<Offset>>): Float {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    ink.forEach { s -> s.forEach { p -> if (p.y < lo) lo = p.y; if (p.y > hi) hi = p.y } }
    return if (hi >= lo) hi - lo else 0f
}

/** 单字宽度先验：汉字 （宽/高）。 */
const val CHAR_ASPECT_CJK = 1.0f

/** 单字宽度先验：拉丁小写字母连写（宽/高）。 */
const val CHAR_ASPECT_LATIN = 0.62f

/** 可切分的最小纵向空隙（占字高比例）：滤掉笔画自身的小缺口。 */
private const val MIN_GAP_RATIO = 0.08f

/** 放宽后的最小纵向空隙（占字高比例）：用于二次细化「漏切」的过宽段。 */
private const val MIN_GAP_RATIO_RELAX = 0.05f

/**
 * 拉丁文（英文）的最小可切空隙（占字高比例），比汉字小得多。
 *
 * 依据：字母宽只有字高的 0.62 倍，连写时字母间距实测常在 0.03–0.08 字高区间；
 * 用汉字的 0.10 会把几乎所有英文连写判成「切不开」。
 */
private const val MIN_GAP_RATIO_LATIN = 0.035f

/**
 * 拉丁词间空隙阈值（× 单字宽先验）：超过它才把墨迹断成两个词（[splitInkIntoWords]）。
 *
 * 字母间距实测 3–8% 字高（≈0.05–0.13 字母宽），词间空隙通常 ≥ 0.5 字母宽；
 * 0.55 字母宽（≈0.34 字高）是保守分界 —— 宁可漏空格（整组按单组路径提交），
 * 也不误分（把一个词拆成两个假词、提交出错误空格）。真机数据出来后随日志校准。
 */
private const val WORD_GAP_UNIT_RATIO = 0.55f

/** 段宽超过「单字宽度」的多少倍算漏切。 */
private const val OVERWIDE_FACTOR = 1.7f

/** 段宽不足字高的多少倍算「被劈开的半个字」（兜底护栏）。 */
private const val MIN_SEG_ASPECT = 0.20f

// ============ 段数自校正（splitOptimal 的逐级回退判据） ============

/**
 * 段宽下限（× 单字宽先验，汉字）：低于它认为「这是半个字」，降一档段数重试。
 * 取 0.55：真机「你好」误切三段时存在 ~0.58 倍字宽的假段，配合均匀性判据共同拦截；
 * 同时不动「并排三字」类等宽窄段的合法切分（单测兼容）。
 */
private const val MIN_SEG_UNITS_CJK = 0.55f

/** 段宽下限（拉丁）：窄字母（i/l/1）只有 0.3~0.5 单元宽，取 0.35 防误伤。 */
private const val MIN_SEG_UNITS_LATIN = 0.35f

/**
 * 各段最大宽 / 最小宽 的上限：超过说明切法把字劈得宽窄悬殊（误切特征）。
 * 真机「你好」误切三段时该比值约 2.2；正常等宽连写约 1.0~1.3。
 */
private const val MAX_SEG_WIDTH_RATIO = 1.6f

// ============ 拉丁连笔弱切点（splitOptimal 的拉丁分支专用） ============

/**
 * 弱切点代价（×目标段宽²，归一化量纲）：越大越保守——只有明显改善段宽均匀性
 * 时才用弱切点。真机连笔标定值。
 */
private const val WEAK_CUT_COST = 0.20f

/** 弱切点相对先验单字宽的最小间距比例（去密）。 */
private const val WEAK_MIN_SPACING_RATIO = 0.35f

// ============ 时间停顿切分（splitInkSmart 第二级） ============

/** 字与字之间的最小停顿（汉字，毫秒）：笔间隔超过它才可能是换字。 */
private const val TIME_GAP_CJK_MS = 300L

/** 字与字之间的最小停顿（拉丁字母，毫秒）：字母连写节奏更快。 */
private const val TIME_GAP_LATIN_MS = 250L

/**
 * 时间簇边界处仍须存在的微小几何空隙（占字高比例）。
 * 取 0.02：只要两簇的 x 投影真正分开（哪怕只差几个像素）即成立；
 * 「赢」类上下结构字内部上下部分的 x 投影重叠 ⇒ 空隙为负 ⇒ 拒绝，防误切。
 */
private const val TIME_EDGE_MIN_GAP_RATIO = 0.02f

/** 时间簇宽度下限（×单字宽）：低于它说明把半个字切了。 */
private const val TIME_SEG_MIN_UNITS = 0.25f

/** 时间簇宽度上限（×单字宽）：高于它说明簇内还挤着多字，切分不可信。 */
private const val TIME_SEG_MAX_UNITS = 2.2f

// ============ 多字形态守卫（needsMulticharGuard） ============

/** 守卫前置：墨迹高度须达到板高的这个比例（按格写）——豁免「一/二/三」类长横字。 */
private const val GUARD_MIN_HEIGHT_RATIO = 0.45f

/** 守卫阈值（汉字）：总宽 ≥ 单字宽的 1.6 倍视为多字形态。 */
private const val GUARD_UNITS_CJK = 1.6f

/** 守卫阈值（拉丁）：字母窄，两字母连写即可达 1.3~1.6 倍，取 2.4 避免误伤宽字母。 */
private const val GUARD_UNITS_LATIN = 2.4f
