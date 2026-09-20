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

    // ① 估字数：单字宽度 = 字高 × charAspect（汉字近方形，拉丁字母窄）
    val unit = h * charAspect.coerceAtLeast(0.2f)
    var n = Math.round(w / unit)
    if (n < 2) return null

    // ② 列占用表（1px 粒度）。
    // ⚠️ 必须沿**线段**铺列，不能只标点的 x —— 一笔从 x=100 划到 x=250，
    // 中间的列全是有墨的；只标两端点会把字的内部当成「空隙」切得稀碎（单测抓过）。
    // ⚠️ 同样**不要**按笔宽向外膨胀列：两字之间 20px 的中心线间距在真机上已经是
    // 「写得很挤」，一旦膨胀就再也找不到这条缝，连写拆分会退化（单测「写得挤也切」抓过）。
    val cols = BooleanArray(w.toInt() + 2)
    ink.forEach { stroke ->
        if (stroke.isEmpty()) return@forEach
        if (stroke.size == 1) {
            markCols(cols, minX, stroke[0].x, stroke[0].x)
        } else {
            for (i in 1 until stroke.size) {
                markCols(cols, minX, minOf(stroke[i - 1].x, stroke[i].x), maxOf(stroke[i - 1].x, stroke[i].x))
            }
        }
    }

    // ③ 全部够宽的纵向空隙 → 候选切点（取空隙中心）
    val minGap = h * (if (relax) MIN_GAP_RATIO_RELAX else MIN_GAP_RATIO)
    val cuts = ArrayList<Float>()
    var i = 0
    while (i < cols.size) {
        if (!cols[i]) {
            val s = i
            while (i < cols.size && !cols[i]) i++
            if ((i - s).toFloat() >= minGap) cuts.add(minX + s + (i - s) / 2f)
        } else {
            i++
        }
    }
    if (cuts.size < n - 1) n = cuts.size + 1 // 空隙不够就少切几段，不硬凑
    if (n < 2) return null

    // ④ DP：选 n-1 个切点，使各段宽度尽量等于 w / n
    val chosen = chooseCuts(cuts, minX, maxX, n - 1) ?: return null

    val out = ArrayList<List<List<Offset>>>(n)
    var leftEdge = minX
    for (idx in chosen.indices) {
        val rightEdge = chosen[idx]
        out.add(cutRange(ink, leftEdge, rightEdge))
        leftEdge = rightEdge
    }
    out.add(cutRange(ink, leftEdge, Float.MAX_VALUE))

    // ⑤ 护栏：出现「细得不像一个字」的段 ⇒ 这次切法不可信，整体放弃
    out.removeAll { it.isEmpty() }
    if (out.size < 2) return null
    if (out.any { widthOf(it) < h * MIN_SEG_ASPECT }) return null
    return out
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

/**
 * 在候选切点中挑 [k] 个（升序），使「最小段宽」与目标宽度的偏差平方和最小。
 *
 * 标准一维 DP：`dp[i][j]` = 以第 i 个空隙作为第 j 刀时的最小代价，
 * 转移枚举上一刀的位置。空隙数很少（< 20），开销可忽略。
 */
private fun chooseCuts(cuts: List<Float>, minX: Float, maxX: Float, k: Int): List<Float>? {
    val m = cuts.size
    if (k <= 0) return null
    if (m < k) return null
    val target = (maxX - minX) / (k + 1)
    val inf = Float.MAX_VALUE / 4f
    val dp = Array(m) { FloatArray(k) { inf } }
    val prev = Array(m) { IntArray(k) { -1 } }

    for (i in 0 until m) dp[i][0] = sq(cuts[i] - minX - target)
    for (j in 1 until k) {
        for (i in j until m) {
            for (p in j - 1 until i) {
                if (dp[p][j - 1] >= inf) continue
                val c = dp[p][j - 1] + sq(cuts[i] - cuts[p] - target)
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
        val c = dp[i][k - 1] + sq(maxX - cuts[i] - target)
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
        out.add(cuts[i])
        i = prev[i][j]
        j--
        if (i < 0 && j >= 0) return null
    }
    out.reverse()
    return out
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

private fun widthOf(seg: List<List<Offset>>): Float {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    seg.forEach { s -> s.forEach { p -> if (p.x < lo) lo = p.x; if (p.x > hi) hi = p.x } }
    return if (hi >= lo) hi - lo else 0f
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

/** 段宽超过「单字宽度」的多少倍算漏切。 */
private const val OVERWIDE_FACTOR = 1.7f

/** 段宽不足字高的多少倍算「被劈开的半个字」。 */
private const val MIN_SEG_ASPECT = 0.20f
