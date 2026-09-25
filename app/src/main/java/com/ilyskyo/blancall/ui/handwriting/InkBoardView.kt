// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import android.animation.Animator
import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor
import com.ilyskyo.blancall.algorithm.InkWidthSim
import com.ilyskyo.blancall.ui.common.StylusActivity
import com.ilyskyo.blancall.ui.common.StylusPresence

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
 * ## 事件分流（双模式：手写笔 / 触屏）
 * - **有笔设备**（[StylusPresence]）：只有手写笔落笔进入书写；手指一律不写
 *   （保留系统掌托拒识的前提）。
 * - **无笔设备**：手指落笔也进入书写 —— 「手写输入」模式，笔宽由轨迹速度仿造
 *   （见 [InkWidthSim]），不读压感。
 * - 模式在**落笔时刻**判定一次：书写过程中插入/拔出笔不打断当前笔画，下一笔生效。
 * - 非书写工具的手指落在板上：**书写中吞掉**（防掌托穿透到正文/按钮），
 *   **空闲时放行**（让外层页面正常滚动 —— 返回 false 即可，事件回到 Compose 手势系统）。
 * - 抬笔只认书写指针自己报告抬起：掌压（手指）持续到达不代表笔离屏。
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
     * [pressures] 与 [pts] **逐点同序**（含 historical 采样），只服务于笔宽显示：
     * - 手写笔模式：真实压感（自适应，见 [pressureActive]）；
     * - 触屏仿造模式（[sim]）：速度生成的伪压感（见 [InkWidthSim]）。
     * 不参与识别 —— 送给模型的位图始终是等宽墨迹。
     *
     * [startMs]/[endMs] 为 `event.eventTime` 口径（uptimeMillis），供
     * 「按书写停顿逐字切分」使用：字间停顿是比几何空隙更自然的分字信号。
     */
    private class StrokeRec(
        val pts: List<Offset>,
        val pressures: List<Float>,
        val startMs: Long,
        val endMs: Long,
        /** true = 触屏仿造（因子由速度生成，走 [simWidth] 映射） */
        val sim: Boolean = false
    ) {
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

    /** 正在写的这一笔的起笔时间（`event.eventTime` 口径），见 [StrokeRec.startMs]。 */
    private var strokeStartMs = 0L

    /** 与 [current] **逐点同序**的笔宽因子（真实压感 / 触屏仿造的伪压感）。 */
    private val currentPress = ArrayList<Float>(256)

    /** 与 [current] **逐点同序**的采样时间（触屏仿造的速度计算用）。 */
    private val currentTimes = ArrayList<Long>(256)

    /** 正在写的这一笔是否为触屏仿造（无笔设备的手指书写）。 */
    private var strokeSim = false

    /** 触屏仿造压感：由轨迹速度生成宽度因子（见 [InkWidthSim]）。 */
    private val widthSim = InkWidthSim()

    /** 屏幕密度（px/dp）：速度按 dp 口径归一，跨设备观感一致。 */
    private val density: Float = resources.displayMetrics.density

    private val livePath = Path()
    private var liveDirty = false

    // ───────────────────────── 退场动画（自动上屏的墨迹「收拢→淡出」） ─────────────────────────
    //
    // 只服务「自动上屏」这一个时刻：判定高置信/整词通过、字已提交的那批墨迹不再瞬时消失，
    // 而是围绕**各自包围盒重心**收拢、上飘、淡出（约 200ms），与目标字落到内容区在
    // 同一动画窗口内衔接 —— 让用户看懂「刚才写的这团墨迹 → 就是现在出现的这个字」。
    // 低置信点选、标点、清空、划掉等路径**不经过这里**（仍走 removeStrokes/clearInk 瞬时清除）。
    //
    // 三条正确性约束：
    // ① 退场笔画先从 strokes 摘出、放进独立列表 ⇒ snapshot* 与 hasInk() 都看不到它们，
    //    绝不会被二次识别、二次上屏，也不改变「划掉撤回」的判定；
    // ② 动画只重绘本 View（invalidate），不写任何 Compose 状态 ⇒ 不阻塞书写与下一次识别；
    // ③ 新落的笔（current/strokes）与退场互不影响 ⇒ 动画期间继续书写不会被吞掉。

    /** 一组正在退场的笔画：[recs] + 该组包围盒重心（收拢的缩放轴心，动画开始时算好）。 */
    private class ExitGroup(val recs: List<StrokeRec>, val cx: Float, val cy: Float)

    private val exitGroups = ArrayList<ExitGroup>()
    private var exitProgress = 0f
    private var exitAnimator: ValueAnimator? = null

    // ───────────────────────── 笔宽（真实压感 / 触屏仿造） ─────────────────────────
    //
    // ⚠️ 笔宽**只用于屏上显示**：`HandwritingPanel.renderInk()` 仍然按固定笔宽渲染位图，
    // 因为识别模型是在等宽墨迹上训练的，喂带粗细的位图会掉识别率。
    //
    // 两种笔宽来源、同一套宽段渲染（[buildRibbon]）：
    // - 手写笔模式：真实压感，且必须自适应 —— 无压感的电容笔 / 部分设备会把 pressure
    //   恒定报成同一个值，照着缩放会让全部笔画变成同一个最粗档（比等宽更难看）。
    //   因此只在观测到「压力确实有变化」之后才切到压感渲染，否则永远是等宽；
    // - 触屏仿造模式（[strokeSim]，无笔设备的手指书写）：**不读压感**，
    //   宽度因子由轨迹速度生成（[InkWidthSim]）。
    private var pressLo = Float.MAX_VALUE
    private var pressHi = -Float.MAX_VALUE
    private var pressSamples = 0
    private var pressureActive = false

    /** 当前正被追踪的书写指针 id（手写笔，或触屏仿造模式下的手指）；-1 表示没有在写。 */
    private var writePointerId = -1

    /**
     * 运动预测器（显示层补间，见 [PREDICT_ENABLED]）。
     * 库在 API 19+ 自带内置预测实现；初始化失败（极端情况）时置 null，功能整体降级。
     */
    private val predictor: MotionEventPredictor? =
        runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()

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

    /**
     * 带时间的墨迹快照（识别用）：除点集外还携带每笔的起止时间。
     *
     * ⚠️ [StrokeSnapshot.pts] 就是内部持有的 List 本身（非拷贝），
     * 与 [snapshotStrokes] 一样可直接用于 [removeStrokes] 的引用匹配。
     */
    fun snapshotWithTiming(): List<StrokeSnapshot> =
        strokes.map { StrokeSnapshot(it.pts, it.startMs, it.endMs) }

    /** 板上是否有已完成的笔画。 */
    fun hasInk(): Boolean = strokes.isNotEmpty()

    /**
     * 移除指定笔画（按**引用**匹配 —— [snapshotStrokes] 交出去的正是内部这些 list 本身）。
     *
     * 连写逐段确认靠它：已经上屏的那几段墨迹要从板上消失，
     * 而「停在这一段等用户点候选」的那一段、以及它后面的段必须**留着** ——
     * 用户要能看着自己写的字挑候选。
     */
    fun removeStrokes(exact: List<List<Offset>>) {
        if (exact.isEmpty()) return
        strokes.removeAll { rec -> exact.any { it === rec.pts } }
        invalidate()
    }

    /**
     * 把指定笔画从板上「收拢淡出」后移除（自动上屏的退场动画）。匹配规则与 [removeStrokes]
     * 完全一致（按引用），区别只有多一段约 200ms 的视觉退场（见「退场动画」状态区注释）。
     *
     * [groups]：一次上屏的墨迹分组（连写/整词按「一个字/一段」分组；单字上屏为一组）。
     * 各组围绕自身包围盒重心收拢，**同一动画窗口内一起播放**：批量提交的落字是同时的，
     * 不做错峰，既避免与落字时序脱节，也天然不会互相覆盖/闪烁。
     *
     * ⚠️ 必须在主线程、与上屏写入（commitChars）同帧调用；动画只负责「墨迹消失」
     * 这一半，绝不延迟上屏。系统「移除动画」（Animator 时长缩放 = 0）时安全降级为
     * 瞬时移除（[removeStrokes]），与改造前逐帧一致。
     */
    fun animateStrokesOut(groups: List<List<List<Offset>>>) {
        val refs = groups.flatten()
        if (refs.isEmpty()) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // 系统「移除动画」/ 动画器被禁用：直接瞬时清除
            removeStrokes(refs)
            return
        }
        // 按引用摘出匹配的笔画（与 removeStrokes 同规则）；找不到（已被移除）就收工
        val matched = strokes.filter { rec -> refs.any { it === rec.pts } }
        if (matched.isEmpty()) return
        // 上一批退场还没播完又来了新一批（正常节奏下两次提交间隔 > 550ms，到不了这里；
        // 这里需要 <200ms 的连击）。两批共用一个进度值，先即时了结旧的 —— 否则旧笔画
        // 会「回光返照」跳到新进度上。
        clearExitNow()
        strokes.removeAll(matched)
        groups.forEach { g ->
            val recs = g.mapNotNull { ptsRef -> matched.firstOrNull { it.pts === ptsRef } }
            if (recs.isEmpty()) return@forEach
            // 包围盒重心：收拢缩放的轴心（动画期间不变，先算好）
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            recs.forEach { rec ->
                rec.pts.forEach { p ->
                    if (p.x < minX) minX = p.x
                    if (p.x > maxX) maxX = p.x
                    if (p.y < minY) minY = p.y
                    if (p.y > maxY) maxY = p.y
                }
            }
            exitGroups.add(ExitGroup(recs, (minX + maxX) / 2f, (minY + maxY) / 2f))
        }
        exitProgress = 0f
        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INK_EXIT_DURATION_MS
            // Material standard 缓动（FastOutSlowIn，与 Compose tween 默认一致）
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { va ->
                exitProgress = (va.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    // 正常播完（或 cancel）后：退场笔画到此真正消失
                    exitGroups.clear()
                    exitAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    /** 立即终止退场动画（清空退场笔画并置空动画器）。 */
    private fun clearExitNow() {
        exitAnimator?.let { animator ->
            exitAnimator = null
            animator.cancel()
        }
        exitGroups.clear()
        exitProgress = 0f
    }

    /**
     * 清空全部墨迹（「清空」按钮、划掉重写走这里；自动上屏的墨迹退场见 [animateStrokesOut]）。
     * 正在播的退场动画一并终止。
     */
    fun clearInk() {
        clearExitNow()
        strokes.clear()
        current.clear()
        currentPress.clear()
        currentTimes.clear()
        strokeSim = false
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
                val stylusDown = isPenPointer(event, idx)
                // 「手写输入」（触屏）模式：**无笔设备**时手指也是书写工具。
                // 有笔设备保持纯笔书写 —— 手指留给滚动/点按（系统掌托拒识的前提）。
                val fingerWriting = !stylusDown &&
                    event.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER &&
                    !StylusPresence.isPresent
                // 诊断（仅 debug 包）：确认笔/手指事件**到底有没有到达书写板**。
                // 出现「完全没有墨迹」时，日志里有没有这一行可以立刻二分定位：
                //   有这一行 ⇒ 事件已到达，问题在绘制/识别链路；
                //   没有     ⇒ 事件被上层吃掉了（Compose pointerInput 消费 / 父级拦截 / 根本没命中本 View）。
                if (isDebuggable) {
                    Log.d(
                        TAG,
                        "touch down idx=$idx tool=${event.getToolType(idx)} stylus=$stylusDown " +
                            "fingerWrite=$fingerWriting present=${StylusPresence.isPresent} " +
                            "enabled=$writingEnabled"
                    )
                }
                if (!stylusDown && !fingerWriting) {
                    // 手指/掌托落在板上且不处于触屏书写模式。书写中一律吞掉（否则会穿透到
                    // 正文与按钮），空闲时放行 —— 返回 false，事件回到 Compose，页面照常滚动。
                    // ⚠️ 不能只看本 View 的 writePointerId：笔可能正写在**另一块**板上，
                    // 全局标记 StylusActivity.isWriting 表达的就是这一情形。
                    return writePointerId >= 0 || StylusActivity.isWriting
                }
                if (writePointerId >= 0) return true // 已在写（笔或手指）：再加的触点保守吞掉
                writePointerId = event.getPointerId(idx)
                strokeSim = !stylusDown
                // 起笔即告知父容器别再拦截：AndroidView 嵌在可滚动列表里时，父级的滚动
                // 手势会在划到一半时把事件抢走（真机现象「写着写着页面跟着滚」）。
                parent?.requestDisallowInterceptTouchEvent(true)
                requestUnbufferedInput(stylusDown)
                // 全局「笔在写」标记**只对笔置位**：它是掌托守卫（tapGesturesPenAware /
                // suppressAsPalmMisTouch）的判定依据；手指书写没有掌托语义，置位会误伤
                // 「另一只手点按其它控件」的合法操作。
                StylusActivity.isWriting = stylusDown
                latSamples = 0
                latSumMs = 0L
                latMaxMs = 0L
                onStrokeStart?.invoke()
                current.clear()
                currentPress.clear()
                currentTimes.clear()
                widthSim.reset()
                strokeStartMs = event.eventTime
                appendSamples(event, idx)
                predictor?.record(event)
                liveDirty = true
                latLastEventMs = event.eventTime
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (writePointerId < 0) return false // 没在写：手指拖动交给上层滚动
                val idx = event.findPointerIndex(writePointerId)
                if (idx >= 0) {
                    appendSamples(event, idx)
                    predictor?.record(event)
                    liveDirty = true
                    latLastEventMs = event.eventTime
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (writePointerId < 0) return false
                val idx = event.findPointerIndex(writePointerId)
                if (idx >= 0) appendSamples(event, idx)
                finishStroke(drop = false, endMs = event.eventTime)
                return true
            }

            // ⚠️ 扶屏的手指先抬起来 ≠ 这一笔写完了，**只有书写指针自己抬起才算收笔**。
            // 漏掉这一条会出事：手指+笔同时在屏时收笔走的是 POINTER_UP，
            // writePointerId 永远清不掉、StylusActivity.isWriting 永久停在 true ——
            // 表现为「写一次之后全屏手指点按都失灵」，且没有任何报错。
            // 反过来，抬起的是别的触点（不是书写指针）时什么也不做：绝不能打断这一笔。
            MotionEvent.ACTION_POINTER_UP -> {
                if (writePointerId >= 0 && event.getPointerId(event.actionIndex) == writePointerId) {
                    appendSamples(event, event.actionIndex)
                    finishStroke(drop = false, endMs = event.eventTime)
                    return true
                }
                return writePointerId >= 0
            }

            MotionEvent.ACTION_CANCEL -> {
                if (writePointerId < 0) return false
                finishStroke(drop = true, endMs = event.eventTime)
                return true
            }

            else -> return writePointerId >= 0
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (isPenPointer(event, 0)) {
                    if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) requestUnbufferedInput(stylus = true)
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
        writePointerId = -1
        strokeSim = false
        StylusActivity.isWriting = false
        // 退场动画不能跟着视图一起留下（ValueAnimator 还持有监听、会继续 invalidate）
        clearExitNow()
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
     * ⚠️ 压力/时间必须与坐标**同序**取（同一个 index、同一段 historical 循环）：
     * 分开遍历会让粗细与位置错位，墨迹会「前粗后细」地拧着。
     *
     * 触屏仿造模式（[strokeSim]）下**完全不读压感**：只取坐标与时间，
     * 宽度因子由 [InkWidthSim] 按速度生成。
     */
    private fun appendSamples(event: MotionEvent, index: Int) {
        val hist = event.historySize
        for (h in 0 until hist) {
            val x = event.getHistoricalX(index, h)
            val y = event.getHistoricalY(index, h)
            val t = event.getHistoricalEventTime(h)
            if (strokeSim) {
                pushSimSample(x, y, t)
            } else {
                current.add(Offset(x, y))
                currentTimes.add(t)
                val p = event.getHistoricalPressure(index, h)
                currentPress.add(p)
                observePressure(p)
            }
        }
        val x = event.getX(index)
        val y = event.getY(index)
        val t = event.eventTime
        if (strokeSim) {
            pushSimSample(x, y, t)
        } else {
            current.add(Offset(x, y))
            currentTimes.add(t)
            val p = event.getPressure(index)
            currentPress.add(p)
            observePressure(p)
        }
    }

    /**
     * 触屏仿造采样：落一个点，宽度因子由与上一采样的速度生成。
     * 因子的语义与真实压感的归一化值一致 —— 绘制侧共用同一套宽段渲染（[simWidth]）。
     */
    private fun pushSimSample(x: Float, y: Float, tMs: Long) {
        val prev = current.lastOrNull()
        val factor = if (prev == null) {
            widthSim.onSample(0f, 0f, 0L, density)
        } else {
            widthSim.onSample(
                x - prev.x,
                y - prev.y,
                tMs - (currentTimes.lastOrNull() ?: tMs),
                density
            )
        }
        current.add(Offset(x, y))
        currentTimes.add(tMs)
        currentPress.add(factor)
    }

    /** 观测压力范围（**仅手写笔模式调用**）；一旦确认「压力确实在变化」就切到压感渲染，并重绘已有墨迹。 */
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

    /**
     * 触屏仿造的宽度映射：因子直接按归一化压感处理（与真实压感共用同一映射区间），
     * 保证两种模式的笔宽范围与观感一致。
     */
    private fun simWidth(factor: Float): Float = INK_WIDTH_PX *
        (PRESS_MIN_SCALE + (PRESS_MAX_SCALE - PRESS_MIN_SCALE) * factor.coerceIn(0f, 1f))

    /** 量化笔宽，避免压力抖动把一笔切成几十段（性能与观感的折中）。 */
    private fun quantize(w: Float): Float = Math.round(w / WIDTH_QUANT) * WIDTH_QUANT

    /**
     * 把点串按笔宽变化切成若干等宽子段（真实压感 / 触屏仿造共用）。
     *
     * [widthOf] 给出第 i 个点的显示笔宽（手写笔走自适应压感映射，触屏仿造走速度因子映射）。
     * 相邻子段**共用边界点**（前一段多含一个点），配合圆头笔帽在接缝处自然融合，
     * 不会出现台阶或断口。
     */
    private fun buildRibbon(pts: List<Offset>, widthOf: (Int) -> Float): List<InkBand> {
        val bands = ArrayList<InkBand>(8)
        if (pts.isEmpty()) return bands
        if (pts.size == 1) {
            bands.add(InkBand(singlePointPath(pts[0]), quantize(widthOf(0))))
            return bands
        }
        var segStart = 0
        var segW = quantize(widthOf(0))
        for (i in 1 until pts.size) {
            val w = quantize(widthOf(i))
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

    /**
     * 要求输入系统对本 View 的事件不做帧批处理（API 30+）。
     * 手写笔走 [InputDevice.SOURCE_STYLUS]、触屏仿造走 [InputDevice.SOURCE_TOUCHSCREEN] ——
     * 两类来源各自开低延迟派发，与书写模式对应。
     */
    private fun requestUnbufferedInput(stylus: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestUnbufferedDispatch(
                if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
            )
        }
    }

    private fun finishStroke(drop: Boolean, endMs: Long) {
        StylusActivity.isWriting = false
        writePointerId = -1
        // 收笔后把拦截权还给父容器，否则列表再也滚不动
        parent?.requestDisallowInterceptTouchEvent(false)

        if (drop || current.isEmpty()) {
            current.clear()
            currentPress.clear()
            currentTimes.clear()
            strokeSim = false
            liveDirty = false
            invalidate()
            return
        }

        val pts = ArrayList(current)
        val pressures = ArrayList(currentPress)
        val startMs = strokeStartMs
        val sim = strokeSim
        // 触屏仿造：收笔时把末段因子收细（抬笔提锋，只影响显示；实时预览不做，
        // 因为抬笔前不知道哪是末端）。
        if (sim) InkWidthSim.taperEnd(pressures)
        current.clear()
        currentPress.clear()
        currentTimes.clear()
        strokeSim = false
        liveDirty = false

        // 同步交给上层判定：是字还是「划掉」手势。压力只留给显示，不进这个回调。
        val keep = onStrokeEnd?.invoke(pts, width, height) ?: false
        if (keep) strokes.add(StrokeRec(pts, pressures, startMs, endMs, sim))

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

    /** 画一条已完成笔画。live 与退场墨迹共用同一套绘制分支，保证两种墨迹逐点一致。 */
    private fun drawStrokeRec(canvas: Canvas, s: StrokeRec) {
        if (s.single) {
            canvas.drawCircle(s.pts[0].x, s.pts[0].y, strokeWidthOf(s, 0) / 2f, dotPaint)
        } else if (s.sim || pressureActive) {
            val ribbon = s.ribbon ?: buildRibbon(s.pts) { i -> strokeWidthOf(s, i) }.also { s.ribbon = it }
            drawBands(canvas, ribbon)
        } else {
            canvas.drawPath(s.path, inkPaint)
        }
    }

    /** 已完成笔画第 [i] 点的显示笔宽：触屏仿造走速度因子映射，手写笔走自适应压感映射。 */
    private fun strokeWidthOf(s: StrokeRec, i: Int): Float =
        if (s.sim) simWidth(s.pressureAt(i)) else widthFor(s.pressureAt(i))

    /** 正在写的这一笔第 [i] 点的显示笔宽（触屏仿造 / 自适应压感）。 */
    private fun liveWidthAt(i: Int): Float {
        val f = currentPress.getOrElse(i) { 1f }
        return if (strokeSim) simWidth(f) else widthFor(f)
    }

    /** 绘制退场墨迹：每组围绕自身重心收拢、整体上飘、渐隐。画完把共享画笔的 alpha 复原。 */
    private fun drawExitGroups(canvas: Canvas, h: Float) {
        val p = exitProgress
        val scale = 1f - INK_EXIT_SHRINK * p
        val dy = -h * INK_EXIT_RISE * p
        // alpha 基于画笔**原有** alpha 乘性衰减（strokeColor 理论可能带 alpha，不能写死 255）
        val inkAlpha = inkPaint.alpha
        val dotAlpha = dotPaint.alpha
        inkPaint.alpha = (inkAlpha * (1f - p)).toInt().coerceIn(0, 255)
        dotPaint.alpha = (dotAlpha * (1f - p)).toInt().coerceIn(0, 255)
        for (g in exitGroups) {
            canvas.save()
            canvas.translate(0f, dy)
            canvas.scale(scale, scale, g.cx, g.cy)
            for (rec in g.recs) drawStrokeRec(canvas, rec)
            canvas.restore()
        }
        inkPaint.alpha = inkAlpha
        dotPaint.alpha = dotAlpha
    }

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
        for (s in strokes) drawStrokeRec(canvas, s)

        // ── 退场墨迹（自动上屏）：收拢、上飘、淡出，与落字同窗口 ──
        if (exitGroups.isNotEmpty()) drawExitGroups(canvas, h)

        // ── 正在写的这一笔（每帧重建 path，只重建这一条）──
        if (current.isNotEmpty()) {
            if (current.size == 1) {
                canvas.drawCircle(
                    current[0].x,
                    current[0].y,
                    liveWidthAt(0) / 2f,
                    dotPaint
                )
            } else if (pressureActive || strokeSim) {
                // 每帧重建分段路径：点的量级只有几十到几百，与「每帧重建 livePath」同价，
                // 但换来笔尖粗细跟手（抬笔后才变粗细会明显突兀）。
                drawBands(canvas, buildRibbon(current) { i -> liveWidthAt(i) })
            } else {
                if (liveDirty) {
                    buildInkPath(current, livePath)
                    liveDirty = false
                }
                canvas.drawPath(livePath, inkPaint)
            }
        }

        // ── 运动预测补间（仅显示，不改墨迹数据） ──
        //
        // 用预测点把「正在写的这一笔」向前延伸一小段，抵消「事件 → 绘制」的感知延迟；
        // 官方约束：预测点必须随新事件被替换、不得用于最终渲染 —— 这里每帧重绘以真实
        // 末点为起点、预测点只画这一小段，也不写入 [current]/[strokes]（不进识别数据）。
        // 快速折返笔画的过冲用 [PREDICT_MAX_PX] 截断；整体可被 [PREDICT_ENABLED] 关闭。
        if (PREDICT_ENABLED && predictor != null && writePointerId >= 0 && current.size >= 2) {
            val pe = predictor.predict()
            if (pe != null) {
                val last = current[current.size - 1]
                val dx = pe.x - last.x
                val dy = pe.y - last.y
                val dist = kotlin.math.hypot(dx, dy)
                if (dist > 0.5f) {
                    val k = if (dist > PREDICT_MAX_PX) PREDICT_MAX_PX / dist else 1f
                    val w = liveWidthAt(currentPress.size - 1)
                    val oldW = inkPaint.strokeWidth
                    inkPaint.strokeWidth = w
                    canvas.drawLine(last.x, last.y, last.x + dx * k, last.y + dy * k, inkPaint)
                    inkPaint.strokeWidth = oldW
                }
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
        // ⚠️ 退场墨迹也算「板上有东西」：否则自动上屏动画的 200ms 里提示文字会提前浮现
        if (strokes.isEmpty() && current.isEmpty() && exitGroups.isEmpty() && hintText.isNotEmpty()) {
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
        if (writePointerId < 0 || latLastEventMs <= 0L) return
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
         * 自动上屏时墨迹「收拢淡出」退场动画的时长（毫秒）。
         * 150–250ms 是「看得清但不用等候」的区间；按真机手感微调。
         */
        private const val INK_EXIT_DURATION_MS = 200L

        /** 退场墨迹的收拢幅度：结束时缩放 = 1 - 该值（0.75 ⇒ 缩到 0.25 倍）。 */
        private const val INK_EXIT_SHRINK = 0.75f

        /** 退场墨迹的上飘距离（板高的比例）：四个调用方的内容区都在面板上方。 */
        private const val INK_EXIT_RISE = 0.10f

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

        /**
         * 运动预测总开关（显示层补间）：书写时把这一笔往前延伸一小段以抵消感知延迟。
         * 真机若出现过冲观感不可接受，直接置 false —— 预测只影响绘制，关闭零副作用。
         */
        private const val PREDICT_ENABLED = true

        /** 预测补间的最大延伸距离（px）：快速折返笔画预测容易过冲，截断在合理范围内。 */
        private const val PREDICT_MAX_PX = 40f
    }
}

/**
 * 一笔墨迹的带时间快照：点集 + 起止时间（`event.eventTime`，uptimeMillis）。
 * 供「按书写停顿逐字切分」使用。[pts] 是 [InkBoardView] 内部的列表引用本身。
 */
data class StrokeSnapshot(
    val pts: List<Offset>,
    val startMs: Long,
    val endMs: Long
)

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
