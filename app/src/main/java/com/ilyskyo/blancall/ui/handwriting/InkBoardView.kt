// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import com.ilyskyo.blancall.ui.common.StylusActivity

/**
 * 超低延迟书写板（**原生 View 自绘**）。
 *
 * ## 为什么不能继续用 Compose 画墨
 * 用 Compose 画「正在写的那一笔」，每个输入批次都要走
 * **状态写入 → 重组 → 布局 → 绘制 → 合成** 五段流水线；
 * 而且 `pointerInput` 收到的事件是**按帧批处理**的（等 vsync 才派发一批）。
 * 两者叠加，笔尖到墨迹的延迟通常在 2–4 帧（60Hz 下 33–66ms），
 * 真机主观感受就是「笔迹糊在后面」。
 *
 * 这里做两件事把这段延迟压掉：
 * 1. **自绘**：墨迹、田字格、笔尖指示、提示文字全部由这个 View 的 `onDraw` 画，
 *    书写过程中只调 `invalidate()` —— 只重录这一个 View 的显示列表，**完全不碰组合与布局**；
 * 2. **`requestUnbufferedDispatch`**：书写时要求输入系统**不做帧批处理**，
 *    有事件立刻派发（API 30+）。作用域是「派发给本 View 的事件」，
 *    因此必须是独立 View —— 直接开在 ComposeView 上会波及整屏的手势判定。
 *
 * ## 墨迹所有权
 * **墨迹由本 View 独占持有**（[snapshotStrokes] 供识别取快照）。
 * Compose 侧不再保存一份点集，避免两处状态不同步：
 * - 一笔写完 → [onStrokeEnd] 同步回调，返回值决定这一笔**留还是丢**
 *   （丢 = 判定为「划掉」手写手势）；
 * - 清空/识别成功 → 上层调 [clearInk]。
 *
 * ## 事件分流（与改造前完全一致）
 * - **只有手写笔**落笔才进入书写；手指一律不写（[PointerType] 过滤的原生等价物）。
 * - 手指落在板上：**书写中吞掉**（防掌托穿透到正文/按钮），**空闲时放行**
 *   （让外层页面正常滚动 —— 返回 false 即可，事件回到 Compose 手势系统）。
 * - 抬笔只认「笔自己报告抬起」：掌压（手指）持续到达不代表笔离屏。
 */
class InkBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ───────────────────────── 外观（由 Compose 侧 update 注入） ─────────────────────────

    var strokeColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                inkPaint.color = value
                dotPaint.color = value
                tipPaint.color = (value and 0x00FFFFFF) or (0x73 shl 24) // 45% alpha
                invalidate()
            }
        }

    var paperColor: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                applyPaper()
                invalidate()
            }
        }

    /** 田字格颜色（已含基础 alpha）。悬停时会被提亮，见 [GRID_ALPHA_HOVER]/[GRID_ALPHA_IDLE]。 */
    var gridColor: Int = Color.LTGRAY
        set(value) {
            if (field != value) {
                field = value
                gridPaint.color = value
                invalidate()
            }
        }

    var hintText: CharSequence = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var hintColor: Int = Color.GRAY
        set(value) {
            if (field != value) {
                field = value
                hintPaint.color = value
                invalidate()
            }
        }

    var hintTextSizePx: Float = 42f
        set(value) {
            if (field != value) {
                field = value
                hintPaint.textSize = value
                invalidate()
            }
        }

    /** 圆角半径（px）：纸面自带圆角并裁剪自身绘制，避免墨迹溢出圆角外。 */
    var cornerRadiusPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                applyPaper()
                invalidate()
            }
        }

    /** 识别中：右上角画一个细弧，替代原先 Compose 的 CircularProgressIndicator。 */
    var isRecognizing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 是否接受书写。false 时**完全不拦截任何事件**（笔与手指都穿透到下层），
     * 与改造前 `if (!enabled) return@pointerInput` 的行为一致。
     */
    var writingEnabled: Boolean = true

    /** 起笔（笔落下）时回调：上层借此取消「尚未触发的识别」并隐藏上一次的候选。 */
    var onStrokeStart: (() -> Unit)? = null

    /**
     * 一笔结束时回调，参数为「这一笔的点集 + 当时板面尺寸（px）」。
     * **返回值 = 是否保留这一笔的墨迹**：false 表示这是「划掉」手势，整笔丢弃、不上屏。
     *
     * 注意这个回调是**在 `onTouchEvent` 里同步调用**的（主线程），
     * 上层实现里不要做耗时工作（识别必须回到协程里异步做）。
     */
    var onStrokeEnd: ((List<Offset>, Int, Int) -> Boolean)? = null

    // ───────────────────────── 绘制资源 ─────────────────────────

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = INK_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textSize = hintTextSizePx
        textAlign = Paint.Align.CENTER
    }

    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (strokeColor and 0x00FFFFFF) or (0x73 shl 24)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val spinnerRect = RectF()

    // ───────────────────────── 墨迹状态 ─────────────────────────

    /**
     * 一笔（已完成）。Path 在这里一次性建好，绘制时不再重建 ——
     * 书写中每帧只需重建「正在写的那一笔」的 path。
     *
     * [pressures] 与 [pts] **逐点同序**（含 historical 采样），只服务于压感显示，
     * 不参与识别 —— 送给模型的位图始终是等宽墨迹。
     */
    private class StrokeRec(val pts: List<Offset>, val pressures: List<Float>) {
        val path = Path()
        val single = pts.size == 1

        /** 压感分段子路径；仅在压感可用时构建，否则保持 null（走等宽 [path]）。 */
        var ribbon: List<InkBand>? = null

        init {
            if (!single) buildInkPath(pts, path)
        }

        fun pressureAt(i: Int): Float = pressures.getOrElse(i) { 1f }
    }

    /** 压感墨迹的一段：用 [width] 的笔宽绘制 [path]。 */
    private class InkBand(val path: Path, val width: Float)

    private val strokes = ArrayList<StrokeRec>()

    /** 正在写的这一笔。用 ArrayList 直接攒点，**不产生任何 Compose 状态写入**。 */
    private val current = ArrayList<Offset>(256)

    /** 与 [current] **逐点同序**的压力采样。 */
    private val currentPress = ArrayList<Float>(256)

    private val livePath = Path()
    private var liveDirty = false

    // ───────────────────────── 压感（自适应） ─────────────────────────
    //
    // ⚠️ 压感**只用于屏上显示**：`HandwritingPanel.renderInk()` 仍然按固定笔宽渲染位图，
    // 因为识别模型是在等宽墨迹上训练的，喂带粗细的位图会掉识别率。
    //
    // ⚠️ 必须自适应：无压感的电容笔 / 部分设备会把 pressure 恒定报成同一个值，
    // 此时若照着缩放笔宽，全部笔画会变成同一个最粗档 —— 比等宽更难看。
    // 因此只在观测到「压力确实有变化」之后才切到压感渲染，否则永远是等宽。
    private var pressLo = Float.MAX_VALUE
    private var pressHi = -Float.MAX_VALUE
    private var pressSamples = 0
    private var pressureActive = false

    /** 当前正被追踪的笔指针 id；-1 表示没有笔在写。 */
    private var penPointerId = -1

    private var hovering = false
    private var tipX = 0f
    private var tipY = 0f

    // ───────────────────────── 延迟统计（仅 DEBUG 输出） ─────────────────────────
    // 用 ApplicationInfo 标志判断，不用 BuildConfig —— AGP 8 默认不生成 BuildConfig
    // （与 BlancallApp 里的判定方式保持一致）。
    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var latLastEventMs = 0L
    private var latSamples = 0
    private var latSumMs = 0L
    private var latMaxMs = 0L

    init {
        isFocusable = false
        applyPaper()
    }

    /** 纸面：自带圆角 + 裁剪自身绘制。 */
    private fun applyPaper() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(paperColor)
            cornerRadius = cornerRadiusPx
        }
        clipToOutline = cornerRadiusPx > 0f
        invalidateOutline()
    }

    // ───────────────────────── 供上层使用 ─────────────────────────

    /** 取当前墨迹快照（识别用）。返回的 List 是拷贝，调用方可安全跨线程使用。 */
    fun snapshotStrokes(): List<List<Offset>> = strokes.map { it.pts }

    /** 板上是否有已完成的笔画。 */
    fun hasInk(): Boolean = strokes.isNotEmpty()

    /** 清空全部墨迹（「清空」按钮、识别成功后、划掉重写都走这里）。 */
    fun clearInk() {
        strokes.clear()
        current.clear()
        currentPress.clear()
        liveDirty = false
        invalidate()
    }

    // ───────────────────────── 输入 ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!writingEnabled) return false

        when (event.actionMasked) {
            // ⚠️ ACTION_POINTER_DOWN 必须一起处理：扶屏的手指可能**先于笔**落在板上，
            // 此时笔的落下是「第二个指针按下」。旧 Compose 实现是在事件流里找
            // 「当前有没有按下的笔触点」，天然覆盖这种情况；原生实现若只认 ACTION_DOWN，
            // 「一手扶屏 + 一手落笔」就永远开不了笔。
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                if (!isPenPointer(event, idx)) {
                    // 手指/掌托落在板上。书写中一律吞掉（否则会穿透到正文与按钮），
                    // 空闲时放行 —— 返回 false，事件回到 Compose，页面照常滚动。
                    return StylusActivity.isWriting
                }
                if (penPointerId >= 0) return true // 理论上不会有第二支笔，保守只吞掉
                penPointerId = event.getPointerId(idx)
                requestUnbufferedInput()
                StylusActivity.isWriting = true
                latSamples = 0
                latSumMs = 0L
                latMaxMs = 0L
                onStrokeStart?.invoke()
                current.clear()
                appendSamples(event, idx)
                liveDirty = true
                latLastEventMs = event.eventTime
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (penPointerId < 0) return false // 没在写：手指拖动交给上层滚动
                val idx = event.findPointerIndex(penPointerId)
                if (idx >= 0) {
                    appendSamples(event, idx)
                    liveDirty = true
                    latLastEventMs = event.eventTime
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (penPointerId < 0) return false
                val idx = event.findPointerIndex(penPointerId)
                if (idx >= 0) appendSamples(event, idx)
                finishStroke(drop = false)
                return true
            }

            // ⚠️ 扶屏的手指先抬起来 ≠ 这一笔写完了，**只有笔自己的指针抬起才算收笔**。
            // 漏掉这一条会出事：手指+笔同时在屏时收笔走的是 POINTER_UP，
            // penPointerId 永远清不掉、StylusActivity.isWriting 永久停在 true ——
            // 表现为「写一次之后全屏手指点按都失灵」，且没有任何报错。
            // 反过来，抬起的是手指（不是笔）时什么也不做：绝不能打断这一笔。
            MotionEvent.ACTION_POINTER_UP -> {
                if (penPointerId >= 0 && event.getPointerId(event.actionIndex) == penPointerId) {
                    appendSamples(event, event.actionIndex)
                    finishStroke(drop = false)
                    return true
                }
                return penPointerId >= 0
            }

            MotionEvent.ACTION_CANCEL -> {
                if (penPointerId < 0) return false
                finishStroke(drop = true)
                return true
            }

            else -> return penPointerId >= 0
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (isPenPointer(event, 0)) {
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) requestUnbufferedInput()
                    tipX = event.x
                    tipY = event.y
                    hovering = true
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hovering) {
                    hovering = false
                    invalidate()
                }
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun onDetachedFromWindow() {
        // 与旧实现的 try/finally 等价：View 被移除时若还挂着「正在书写」标记，
        // 会让全屏手指点按永久失灵（且没有任何报错）。
        super.onDetachedFromWindow()
        penPointerId = -1
        StylusActivity.isWriting = false
    }

    /** 仅手写笔（含笔尾橡皮）算「笔」。 */
    private fun isPenPointer(event: MotionEvent, index: Int): Boolean {
        if (index >= event.pointerCount) return false
        return when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            else -> false
        }
    }

    /**
     * 把这一批移动采样全部吃进来。
     *
     * ⚠️ `historical` 必须吃：`MotionEvent` 默认按帧批处理，一帧里常含多个历史采样。
     * 只取帧端点会让快写明显棱角化、视觉断续。开启 [requestUnbufferedInput] 后
     * 批处理的批量会变小，但历史采样依旧存在，不能省。
     *
     * ⚠️ 压力必须与坐标**同序**取（同一个 index、同一段 historical 循环）：
     * 分开遍历会让粗细与位置错位，墨迹会「前粗后细」地拧着。
     */
    private fun appendSamples(event: MotionEvent, index: Int) {
        val hist = event.historySize
        for (h in 0 until hist) {
            current.add(Offset(event.getHistoricalX(index, h), event.getHistoricalY(index, h)))
            val p = event.getHistoricalPressure(index, h)
            currentPress.add(p)
            observePressure(p)
        }
        current.add(Offset(event.getX(index), event.getY(index)))
        val p = event.getPressure(index)
        currentPress.add(p)
        observePressure(p)
    }

    /** 观测压力范围；一旦确认「压力确实在变化」就切到压感渲染，并重绘已有墨迹。 */
    private fun observePressure(p: Float) {
        if (pressureActive) return
        if (p <= 0f || p > PRESSURE_ABSURD) return
        pressSamples++
        if (p < pressLo) pressLo = p
        if (p > pressHi) pressHi = p
        if (pressSamples >= PRESSURE_MIN_SAMPLES && pressHi - pressLo >= PRESSURE_MIN_RANGE) {
            pressureActive = true
            // 之前按等宽画好的笔画改为按压力重绘（缓存置空，下次 onDraw 重建）
            strokes.forEach { it.ribbon = null }
            invalidate()
        }
    }

    /** 单点笔宽：无压感时恒为 [INK_WIDTH_PX]；有压感时按归一化压力映射到 [PRESS_MIN_SCALE]–[PRESS_MAX_SCALE]。 */
    private fun widthFor(pressure: Float): Float {
        if (!pressureActive) return INK_WIDTH_PX
        val span = (pressHi - pressLo).coerceAtLeast(0.02f)
        val t = ((pressure - pressLo) / span).coerceIn(0f, 1f)
        return INK_WIDTH_PX * (PRESS_MIN_SCALE + (PRESS_MAX_SCALE - PRESS_MIN_SCALE) * t)
    }

    /** 量化笔宽，避免压力抖动把一笔切成几十段（性能与观感的折中）。 */
    private fun quantize(w: Float): Float = Math.round(w / WIDTH_QUANT) * WIDTH_QUANT

    /**
     * 把点串按笔宽变化切成若干等宽子段（压感渲染）。
     *
     * 相邻子段**共用边界点**（前一段多含一个点），配合圆头笔帽在接缝处自然融合，
     * 不会出现台阶或断口。
     */
    private fun buildRibbon(pts: List<Offset>, pressures: List<Float>): List<InkBand> {
        val bands = ArrayList<InkBand>(8)
        if (pts.isEmpty()) return bands
        if (pts.size == 1) {
            bands.add(
                InkBand(
                    singlePointPath(pts[0]),
                    quantize(widthFor(pressures.getOrElse(0) { 1f }))
                )
            )
            return bands
        }
        var segStart = 0
        var segW = quantize(widthFor(pressures.getOrElse(0) { 1f }))
        for (i in 1 until pts.size) {
            val w = quantize(widthFor(pressures.getOrElse(i) { 1f }))
            if (Math.abs(w - segW) >= 0.01f) {
                // i+1（而不是 i）作为右端：与下一段共用这个点，接缝处圆头重叠
                bands.add(InkBand(segmentPath(pts, segStart, i + 1), segW))
                segStart = i
                segW = w
            }
        }
        bands.add(InkBand(segmentPath(pts, segStart, pts.size), segW))
        return bands
    }

    /** 用各段自己的笔宽画压感墨迹；画完把 [inkPaint] 复位，避免污染等宽路径。 */
    private fun drawBands(canvas: Canvas, bands: List<InkBand>) {
        for (b in bands) {
            inkPaint.strokeWidth = b.width
            canvas.drawPath(b.path, inkPaint)
        }
        inkPaint.strokeWidth = INK_WIDTH_PX
    }

    /** 要求输入系统对本 View 的**笔事件**不做帧批处理（API 30+）。 */
    private fun requestUnbufferedInput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)
        }
    }

    private fun finishStroke(drop: Boolean) {
        StylusActivity.isWriting = false
        penPointerId = -1

        if (drop || current.isEmpty()) {
            current.clear()
            currentPress.clear()
            liveDirty = false
            invalidate()
            return
        }

        val pts = ArrayList(current)
        val pressures = ArrayList(currentPress)
        current.clear()
        currentPress.clear()
        liveDirty = false

        // 同步交给上层判定：是字还是「划掉」手势。压力只留给显示，不进这个回调。
        val keep = onStrokeEnd?.invoke(pts, width, height) ?: false
        if (keep) strokes.add(StrokeRec(pts, pressures))

        if (isDebuggable && latSamples > 0) {
            // 一起把刷新率打出来：屏幕刷新率决定墨迹延迟的**下限**（60Hz ⇒ 每帧 16.7ms）。
            // 若这里报 60Hz 而设备本可以更高，那剩下的一半延迟在屏幕侧，不在代码侧。
            @Suppress("DEPRECATION")
            val hz = display?.refreshRate ?: 0f
            Log.d(
                TAG,
                "ink latency avg=${latSumMs / latSamples}ms max=${latMaxMs}ms " +
                    "frames=$latSamples pts=${pts.size} hz=$hz api=${Build.VERSION.SDK_INT}"
            )
        }
        invalidate()
    }

    // ───────────────────────── 绘制 ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ── 田字格：给出书写基准。笔悬停时略微加深 = 「笔已被识别」的即时反馈 ──
        gridPaint.alpha = if (hovering) GRID_ALPHA_HOVER else GRID_ALPHA_IDLE
        val midX = w / 2f
        val midY = h / 2f
        canvas.drawLine(midX, 0f, midX, h, gridPaint)
        canvas.drawLine(0f, midY, w, midY, gridPaint)

        // ── 已完成的笔画 ──
        for (s in strokes) {
            if (s.single) {
                canvas.drawCircle(s.pts[0].x, s.pts[0].y, widthFor(s.pressureAt(0)) / 2f, dotPaint)
            } else if (pressureActive) {
                val ribbon = s.ribbon ?: buildRibbon(s.pts, s.pressures).also { s.ribbon = it }
                drawBands(canvas, ribbon)
            } else {
                canvas.drawPath(s.path, inkPaint)
            }
        }

        // ── 正在写的这一笔（每帧重建 path，只重建这一条）──
        if (current.isNotEmpty()) {
            if (current.size == 1) {
                canvas.drawCircle(
                    current[0].x,
                    current[0].y,
                    widthFor(currentPress.firstOrNull() ?: 1f) / 2f,
                    dotPaint
                )
            } else if (pressureActive) {
                // 每帧重建分段路径：点的量级只有几十到几百，与「每帧重建 livePath」同价，
                // 但换来笔尖粗细跟手（抬笔后才变粗细会明显突兀）。
                drawBands(canvas, buildRibbon(current, currentPress))
            } else {
                if (liveDirty) {
                    buildInkPath(current, livePath)
                    liveDirty = false
                }
                canvas.drawPath(livePath, inkPaint)
            }
        }

        // ── 笔尖位置指示：细十字 + 圆环。只在未落笔时显示，落笔后交给真实墨迹 ──
        if (hovering && current.isEmpty()) {
            canvas.drawCircle(tipX, tipY, 7f, tipPaint)
            canvas.drawLine(tipX - 12f, tipY, tipX - 3f, tipY, tipPaint)
            canvas.drawLine(tipX + 3f, tipY, tipX + 12f, tipY, tipPaint)
            canvas.drawLine(tipX, tipY - 12f, tipX, tipY - 3f, tipPaint)
            canvas.drawLine(tipX, tipY + 3f, tipX, tipY + 12f, tipPaint)
        }

        // ── 空板提示 ──
        if (strokes.isEmpty() && current.isEmpty() && hintText.isNotEmpty()) {
            val fm = hintPaint.fontMetrics
            canvas.drawText(hintText, 0, hintText.length, midX, midY - (fm.ascent + fm.descent) / 2f, hintPaint)
        }

        // ── 识别中：右上角细弧（替代原 Compose 的 CircularProgressIndicator）──
        if (isRecognizing) {
            val r = 8f
            val cx = w - 14f
            val cy = 14f
            spinnerRect.set(cx - r, cy - r, cx + r, cy + r)
            spinnerPaint.color = strokeColor
            canvas.drawArc(spinnerRect, -90f, 260f, false, spinnerPaint)
        }

        measureLatency()
    }

    /**
     * 量「事件时间戳 → 本帧绘制」的间隔。
     *
     * 这正是本次改造要压掉的那一段（输入批处理 + 组合/布局/绘制流水线）。
     * 它**不包含**屏幕刷新与像素响应（那部分应用侧测不到），所以真实主观延迟会略大于此值；
     * 但这个数能明确回答「输入到上屏」这一段还剩多少，避免靠感觉调参。
     */
    private fun measureLatency() {
        if (penPointerId < 0 || latLastEventMs <= 0L) return
        val d = SystemClock.uptimeMillis() - latLastEventMs
        if (d < 0L || d > 500L) return
        latSamples++
        latSumMs += d
        if (d > latMaxMs) latMaxMs = d
    }

    companion object {
        private const val TAG = "BlancallInk"
        private const val INK_WIDTH_PX = 6f

        /**
         * 田字格 alpha：空闲淡、悬停深（「笔已被识别」的即时反馈）。
         *
         * ⚠️ 这两个值是**最终值**，上层必须传不透明的格子色。
         * 改造前 Compose 侧是 `outlineVariant.copy(alpha = 0.5f)` 再乘 0.55/1.0，
         * 即最终 0.275/0.5；这里取 70/255=0.275、128/255=0.5，视觉与改造前一致。
         * 若上层传了带 alpha 的颜色、这里又设 alpha，会叠加两次而变淡。
         */
        private const val GRID_ALPHA_IDLE = 70
        private const val GRID_ALPHA_HOVER = 128

        /** 笔宽量化步进（px）：避免压力抖动把一笔切成几十段。 */
        private const val WIDTH_QUANT = 0.6f

        /** 压感映射区间：最轻 0.62 倍标准笔宽，最重 1.45 倍（差异明显但不夸张）。 */
        private const val PRESS_MIN_SCALE = 0.62f
        private const val PRESS_MAX_SCALE = 1.45f

        /** 判定「本机确实有压感」所需的最少采样点数与压力变化幅度。 */
        private const val PRESSURE_MIN_SAMPLES = 12
        private const val PRESSURE_MIN_RANGE = 0.06f

        /**
         * 压力合理上限。超过说明设备没做归一化（老设备可能给 0–255 的原值）——
         * 这种情况直接按住不切压感渲染，回退等宽，比按错比例画出满屏粗线好。
         */
        private const val PRESSURE_ABSURD = 4f
    }
}

/**
 * 把点串连成平滑笔画（二次贝塞尔取中点过渡），与改造前 Compose 版本**逐点一致**，
 * 否则同一份墨迹在识别前渲染出的位图会与改动前不同。
 *
 * 放在**文件级**（而不是 companion）是刻意的：[InkBoardView.StrokeRec] 是嵌套类，
 * 嵌套类访问外层 companion 成员需要限定名，文件级私有函数任何地方都能直接调，
 * 少一处容易写错的地方。
 */
private fun buildInkPath(pts: List<Offset>, path: Path) =
    buildInkPathRange(pts, 0, pts.size, path)

/**
 * 把 `[from, to)` 的点串连成平滑笔画。
 * 压感渲染按段调用它，**逐点算法与等宽路径完全一致**，同一份墨迹不会出现两种手感。
 */
private fun buildInkPathRange(pts: List<Offset>, from: Int, to: Int, path: Path) {
    path.rewind()
    if (to - from <= 0) return
    path.moveTo(pts[from].x, pts[from].y)
    if (to - from == 1) {
        // 单点段：极短线段 + 圆头笔帽 = 一个直径等于笔宽的圆点
        path.lineTo(pts[from].x + 0.01f, pts[from].y)
        return
    }
    for (i in from + 1 until to) {
        val prev = pts[i - 1]
        val cur = pts[i]
        val mx = (prev.x + cur.x) / 2f
        val my = (prev.y + cur.y) / 2f
        path.quadTo(prev.x, prev.y, mx, my)
    }
    path.lineTo(pts[to - 1].x, pts[to - 1].y)
}

/** 取一段点串的路径（压感分段绘制用）。 */
private fun segmentPath(pts: List<Offset>, from: Int, to: Int): Path {
    val p = Path()
    buildInkPathRange(pts, from, to, p)
    return p
}

/** 单点路径（压感分段绘制用）。 */
private fun singlePointPath(p: Offset): Path {
    val path = Path()
    path.moveTo(p.x, p.y)
    path.lineTo(p.x + 0.01f, p.y)
    return path
}
