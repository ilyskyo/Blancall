// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilyskyo.blancall.data.handwriting.HandwritingRecognizer
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.data.handwriting.HandwritingResult
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * 手写输入面板：在手写区写完一个字 → 自动识别 → 候选字上屏。
 *
 * ## 交互设计（对齐 Windows 手写板手感）
 * - **只有手写笔（[PointerType.Stylus]）落笔才进入书写**：手指保留原有滚动/点按行为。
 *   这样「一手扶屏固定 + 一手握笔书写」不会互相打断。
 * - 抬笔后自动触发识别，约 2–5ms 出结果（模型实测），因此无需"识别"按钮。
 * - 高置信（top-1 ≥ 0.80 且领先 top-2 ≥ 0.15）时**自动上屏**；
 *   否则只展示候选，等用户点选 —— 宁可多一次点选，也不误判。
 *
 * ## 与挖空/输入框的关系
 * 面板只负责「产出字符」，不关心字符最终写到哪儿。调用方通过 [onCharsPicked] 接管。
 * 因此同一套面板既能服务答题输入框，也能服务挖空处就地书写。
 *
 * ⚠️ **一次识别可能产出多个字**（连写时按纵向空隙切段逐字识别），所以回调是
 * **按批**给的（`List<Char>`）而不是逐字回调。这不是设计洁癖，是必须的：
 * 调用方的更新写法普遍是 `onValueChange(value + ch)` —— 它捕获的是**组合时的快照**。
 * 若面板在同一帧里连着调 N 次，每次都用同一个旧快照去拼，N 次写入互相覆盖，
 * **最后只剩最后一个字**（真机现象「连写三个字只出来一个」）。
 * 按批给 ⇒ 一次识别只产生一次写入 ⇒ 这类覆盖从结构上不可能发生。
 *
 * @param onCharsPicked 用户确认识别结果后的回调（自动上屏或点选候选均走这里）。
 *                      **一次调用可能带多个字**，调用方必须整体追加，不可逐字拼接常量快照。
 * @param autoCommit 是否允许「高置信自动上屏」。挖空处就地书写时通常需要；
 *                   输入框内连续书写时可关闭，改为逐字点选。
 * @param onRecognized 识别结果回调，供调用方展示置信度或做后续判断
 */
@Composable
fun HandwritingPanel(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoCommit: Boolean = true,
    onCharsPicked: (List<Char>) -> Unit,
    onRecognized: ((HandwritingResult) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    /**
     * 「划掉」撤回上一个**已上屏**的字。传 null 表示调用方不支持撤回
     * （此时板上无笔画时划掉只作静默忽略，不会误删）。
     */
    onUndoLast: (() -> Unit)? = null,
    /**
     * 识别文种：决定用哪套模型与字符表。调用方按**该空的标准答案**自动选择
     * （见 [HandwritingScript.forAnswer]）。
     */
    script: HandwritingScript = HandwritingScript.Chinese
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 统一强触感（与首页长按/拖拽同一套「咔嗒」）：划掉是破坏性手势，必须有确认感
    val confirmHaptic = rememberConfirmHaptic()

    // ⚠️⚠️ 回调必须用 rememberUpdatedState 兜住，**不能**直接使用组合参数。
    //
    // 原因一（跨重组陈旧）：真正调用这些回调的是手势/书写回调链，而它们被只创建一次的
    // InkBoardView 与协程长期持有 —— 普通重组不会重建这些持有者，于是它们会一直用
    // 「面板首次挂载那次组合」的回调实例。调用方写的
    //   onCharsPicked = { chars -> onValueChange(value + chars) }
    // 里那个 value 就会被冻结在挂载那一刻（通常是空串）。
    //
    // 原因二（同帧多次写入，**光靠 rememberUpdatedState 修不掉**）：
    // 调用方的 value 是**参数快照**，同一帧里读多少次都是同一个值。
    // 所以面板绝不能在一次识别里逐字回调 N 次 —— 那 N 次写入会互相覆盖、
    // 只剩最后一个字。⇒ 这就是 [onCharsPicked] 必须**按批**的原因。
    val cbCharsPicked by rememberUpdatedState(onCharsPicked)
    val cbRecognized by rememberUpdatedState(onRecognized)
    val cbUndoLast by rememberUpdatedState(onUndoLast)

    // 最近一次「字上屏」的时间戳：把「划掉撤回」限制在刚写完的几秒内，
    // 避免误划把很久以前写的答案删掉。普通持有对象，不参与重组。
    val lastCommitAtMs = remember { AtomicLong(0L) }

    /** 待触发的识别任务。停顿内落新笔即取消（见 [RECOGNIZE_DELAY_MS]）。 */
    val recognizeJob = remember { AtomicReference<Job?>(null) }

    // ── 墨迹 ──
    //
    // ⚠️ 墨迹**由 InkBoardView 独占持有**（原生自绘）。Compose 侧只留一个引用，
    // 用于「取快照去识别」与「清空」，不再自己存一份点集 —— 两处状态一定不同步，
    // 那是最难查的一类 bug。
    //
    // 这样做的直接收益：书写过程中**没有任何 Compose 状态写入**，也就没有
    // 重组 → 布局 → 绘制 → 合成 这条链条，只剩一次 `view.invalidate()`。
    val inkRef = remember { AtomicReference<InkBoardView?>(null) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    var recognizing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HandwritingResult?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    // 笔尖悬停（仅主动笔具备的能力）与墨迹本身一样，已下沉到 InkBoardView 内部绘制 ——
    // 悬停提示要跟着笔尖走，留在 Compose 里同样会被流水线拖慢。
    // 这里只需要基础色（不再 copy(alpha)：透明度由 View 侧按「空闲/悬停」两档施加，
    // 否则会叠加两次 alpha，格子比改造前更淡）。
    val strokeColor = MaterialTheme.colorScheme.onSurface
    val paperColor = MaterialTheme.colorScheme.surface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val density = LocalDensity.current

    val recognizer = remember { HandwritingRecognizer.getInstance(context) }
    val nativeOk = remember { HandwritingRecognizer.isNativeAvailable }

    // 识别失败时不静默：明确告知用户为何写不出字
    if (!nativeOk) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ) {
            Text(
                "本机未提供手写识别引擎，请改用键盘输入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    /**
     * 上屏识别结果（**一次一个批次**），并记录时间戳。
     * 自动上屏与点候选**都走这里**，保证两条路径的行为与「可撤回窗口」判定一致。
     *
     * 一定要按批传：调用方的更新是 `value + 新内容`，同一帧里分多次写会互相覆盖。
     */
    fun commitChars(chars: List<Char>) {
        if (chars.isEmpty()) return
        lastCommitAtMs.set(System.currentTimeMillis())
        cbCharsPicked(chars)
    }

    /** 把指定笔画集渲染成白底黑字位图（供识别）。 */
    fun renderInk(ink: List<List<Offset>>): Bitmap? {
        if (ink.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return null
        val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paperColor.toArgbInt())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor.toArgbInt()
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        ink.forEach { pts ->
            if (pts.size == 1) {
                canvas.drawCircle(pts[0].x, pts[0].y, STROKE_WIDTH_PX / 2f, paint)
            } else {
                var prev = pts[0]
                for (i in 1 until pts.size) {
                    canvas.drawLine(prev.x, prev.y, pts[i].x, pts[i].y, paint)
                    prev = pts[i]
                }
            }
        }
        return bmp
    }

    /** 识别一张位图（释放位图；识别在调用方协程线程之外执行需自行切线程）。 */
    fun recognizeBitmap(bmp: Bitmap): HandwritingResult? {
        val r = recognizer.recognize(bmp, script, TOP_K)
        bmp.recycle()
        return r
    }

    /** 把当前笔画集渲染成位图并识别 */
    fun recognizeCurrent() {
        val ink = inkRef.get()?.snapshotStrokes().orEmpty()
        if (ink.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return
        recognizing = true
        statusText = null

        scope.launch {
            try {
                // ① 墨迹明显偏宽 ⇒ 优先按「连写多字」切段逐字识别。
                //
                // ⚠️⚠️ 绝不能用「整板识别的置信度」来决定要不要切段：
                // 单字模型喂进三个字，照样会**高置信地**输出一个乱码字
                // （真机：写「我好想」→ 只出一个候选）。几何证据（宽高比）
                // 比分类器置信度可靠得多。
                //
                // 墨迹接近方形才按单字走；切不出 ≥2 段（如「一」没有纵向空隙）也回落单字。
                //
                // 宽度先验按文种给：汉字近方形（1.0），拉丁小写字母连写只有高的六成左右
                // （0.62）—— 用汉字先验去数英文字母会少算一半，这是英文连写拆不准的主因。
                val segments = withContext(Dispatchers.Default) {
                    splitInkByXGap(
                        ink,
                        if (script == HandwritingScript.Latin) CHAR_ASPECT_LATIN
                        else CHAR_ASPECT_CJK
                    )
                }
                if (segments != null) {
                    val segResults = withContext(Dispatchers.Default) {
                        segments.map { seg -> renderInk(seg)?.let { recognizeBitmap(it) } }
                    }
                    if (segResults.all { it != null && it.candidates.isNotEmpty() }) {
                        // 每段取首候选、从左到右依次上屏。
                        // 每一段才是模型的设计工况（单字输入），这里不再用 isConfident 拦 ——
                        // 连写场景下，取首候选远好于把整板认成一个乱码字。
                        //
                        // ⚠️ 必须**一次提交整批**：逐字调回调会让 N 次写入基于同一个旧快照
                        // 互相覆盖，最后只剩最后一个字（真机「连写三字只出一个」的根因）。
                        commitChars(segResults.map { checkNotNull(it).best!!.char })
                        inkRef.get()?.clearInk()
                        return@launch
                    }
                    statusText = "有笔画没认出来，可点「清空」重写"
                    return@launch
                }

                // ② 方形墨迹（或切不出段）：按单字识别
                val whole = withContext(Dispatchers.Default) {
                    renderInk(ink)?.let { recognizeBitmap(it) }
                }
                if (whole != null && whole.isConfident && autoCommit) {
                    commitChars(listOf(whole.best!!.char))
                    inkRef.get()?.clearInk()
                    return@launch
                }
                if (whole != null && whole.candidates.isNotEmpty()) {
                    result = whole
                    cbRecognized?.invoke(whole)
                } else {
                    statusText = "没认出来，再写工整一点试试"
                }
            } finally {
                recognizing = false
            }
        }
    }

    fun cancelPendingRecognize() {
        recognizeJob.getAndSet(null)?.cancel()
    }

    /**
     * 抬笔后延迟 [RECOGNIZE_DELAY_MS] 再识别：给「连笔写同一个字」留出续写窗口。
     *
     * ## 为什么必须延迟
     * 汉字是多笔画的，「好」= 女 + 子。若每抬笔就识别，写到「女」就会被当成一个字
     * 高置信上屏并清板 —— 用户接着写「子」反而变成第二个字，整个功能不可用（真机实测）。
     * 停顿内只要落了新笔，这个任务就被取消、墨迹继续累积；停顿满才代表「这个字写完了」。
     */
    fun scheduleRecognize() {
        cancelPendingRecognize()
        recognizeJob.set(scope.launch {
            delay(RECOGNIZE_DELAY_MS)
            recognizeCurrent()
        })
    }

    fun clearAll() {
        cancelPendingRecognize()
        inkRef.get()?.clearInk()
        result = null
        statusText = null
    }

    /**
     * 命中「划掉重写」手势后的处理。
     *
     * 两级语义，按「当前板上有没有东西」分流：
     * 1. 板上还有未上屏的笔画 → **清空这个字重写**（等价于点「清空」，但手不用离开书写区）；
     * 2. 板上已空、且刚刚（[SCRATCH_UNDO_WINDOW_MS] 内）自动上屏过一个字 → **撤回那个字**。
     *    这一条专门覆盖「高置信但认错、字被静默写进答案」这个自动上屏特有的高频场景 ——
     *    否则用户得去找「⌫」按钮，而那个按钮未必在视野里。
     *
     * 两种都不成立时静默吞掉这一笔：它是划不是字，计入笔画只会让下一次识别多一条废线。
     */
    fun handleScratchOut() {
        val hasInk = inkRef.get()?.hasInk() == true
        val withinUndoWindow =
            System.currentTimeMillis() - lastCommitAtMs.get() <= SCRATCH_UNDO_WINDOW_MS
        val canUndo = cbUndoLast != null && withinUndoWindow
        when {
            hasInk -> {
                inkRef.get()?.clearInk()
                result = null
                statusText = "已划掉，重新写"
                confirmHaptic()
            }
            canUndo -> {
                cbUndoLast?.invoke()
                statusText = "已撤回上一个字"
                confirmHaptic()
            }
            else -> {
                // 板上本来就空、也没有刚上屏的字 → 无可撤销，静默忽略
                statusText = null
            }
        }
    }

    // ── 起笔 / 抬笔回调 ──
    //
    // 这两个 lambda 会被 InkBoardView 永久持有（View 只创建一次），
    // 所以必须包进 rememberUpdatedState：否则它冻结的是「面板首次挂载那次组合」的实现，
    // 里面读到的 result / statusText 永远是旧值 —— 与旧实现里
    // pointerInput 闭包捕获陈旧回调是同一类 bug。
    val onStrokeStartState = rememberUpdatedState<() -> Unit> {
        // 新的一笔落下 = 这个字还没写完：取消尚未触发的识别
        // （写「好」写到「女」抬笔，绝不能抢跑上屏），
        // 若候选正显示（上一次低置信待选），视为继续写同一个字 —— 隐藏候选、保留墨迹。
        cancelPendingRecognize()
        if (result != null) {
            result = null
            statusText = null
        }
    }

    val onStrokeEndState = rememberUpdatedState<(List<Offset>, Int, Int) -> Boolean> { pts, w, h ->
        // 板面尺寸以「这一笔落下时的实际尺寸」为准：这样全屏/分屏切换、字号变化后
        // 也不会拿旧尺寸去渲染位图或做「划掉」的手势判定。
        canvasSize = IntSize(w, h)
        // 抬笔：先判「划掉重写」手势（去而复返的长横划）。是手势就整笔丢弃、不触发识别 ——
        // 否则这条划会被当成一个「字」送去识别，白白多一条废线、还可能污染候选。
        if (isScratchOut(pts, w.toFloat(), h.toFloat())) {
            handleScratchOut()
            false
        } else {
            // ⚠️ 不立即识别：等一个停顿。多笔字（「好」= 女 + 子）中间必然抬笔，
            // 抬笔即识别会把「女」当成一个字抢跑上屏并清板 —— 用户接着写「子」
            // 反而变成第二个字（真机实测）。停顿内落新笔即取消，继续累积。
            scheduleRecognize()
            true
        }
    }

    Column(modifier = modifier) {
        // ── 书写区 ──
        //
        // ⚠️⚠️ 这里是整个手写体验最关键的一处，**不要退回用 Compose 画墨**：
        // Compose 画「正在写的那一笔」每帧要走 状态写入→重组→布局→绘制→合成，
        // 输入事件又按帧批处理；两者叠加，笔尖到墨迹要 2–4 帧，
        // 真机主观感受就是「笔迹糊在后面」。
        // 现在墨迹由 InkBoardView 原生自绘：书写过程中只有一次 invalidate()、
        // 不碰组合与布局，并开启 requestUnbufferedDispatch 让笔事件不攒批。
        //
        // ⚠️ 另一个必须知道的约束：**互操作的 AndroidView 永远绘制在 Compose 内容之上**。
        // 所以原来叠在板上的「用笔在这里写一个字」与识别中转圈，都已移进 View 内部；
        // 若把它们留在 Compose 里，会被不透明的纸面整个盖住（看起来像提示消失了）。
        AndroidView(
            factory = { ctx ->
                InkBoardView(ctx).also { v ->
                    inkRef.set(v)
                    v.onStrokeStart = { onStrokeStartState.value() }
                    v.onStrokeEnd = { p, w, h -> onStrokeEndState.value(p, w, h) }
                }
            },
            update = { v ->
                v.writingEnabled = enabled
                v.strokeColor = strokeColor.toArgbInt()
                v.paperColor = paperColor.toArgbInt()
                v.gridColor = gridColor.toArgbInt()
                v.hintColor = hintColor.toArgbInt()
                v.hintText = HINT_TEXT
                v.hintTextSizePx = with(density) { 14.sp.toPx() }
                v.cornerRadiusPx = with(density) { 10.dp.toPx() }
                v.isRecognizing = recognizing
            },
            modifier = Modifier
                .fillMaxWidth()
                // 注意：面板根部的 Column 还要放「清空/收起」行与候选条，
                // 因此调用方给面板的高度约束必须是 heightIn(min=…)，**不能**是固定 height(…) ——
                // 固定高度会把按钮行挤出可视区（真机踩过：「清空键没有了」）。
                .heightIn(min = 150.dp, max = 200.dp)
                .onSizeChanged { canvasSize = it }
        )

        // ── 操作行：标点快捷 / 清空 / 收起 ──
        // ⚠️ 必须放在书写区**外面**：起笔在 PointerEventPass.Initial 就消费笔事件，
        // 若按钮叠在书写区上，笔永远点不到它们（真机反馈「用笔没法点清空」）。
        // 移出后笔/手指都直接命中按钮，代价只是按钮不再叠在墨迹上。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 标点一键插入 ──
            // 两套识别模型的字符表**都不含标点**（中文 3755 个 GB2312 一级汉字、
            // 拉丁 EMNIST 47 类 = 数字 + 字母），所以手写标点永远认不出来。
            // 这是模型能力边界，不是 bug ⇒ 在这里给一条零成本的出路，
            // 别让用户为了写一个逗号切回键盘。
            Row(verticalAlignment = Alignment.CenterVertically) {
                punctuationKeys(script).forEach { p ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clickable(enabled = enabled) { commitChars(listOf(p)) }
                    ) {
                        Text(
                            p.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { clearAll() }, enabled = enabled) { Text("清空") }
                if (onDismiss != null) {
                    TextButton(onClick = { clearAll(); onDismiss() }, enabled = enabled) { Text("收起") }
                }
            }
        }

        // ── 候选字条 ──
        AnimatedVisibility(
            visible = result != null || statusText != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                if (statusText != null) {
                    Text(
                        statusText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result?.let { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        r.candidates.take(CANDIDATE_BAR_SIZE).forEach { c ->
                            CandidateChip(
                                char = c.char,
                                confidence = c.confidence,
                                highlighted = c === r.best,
                                onClick = {
                                    // 走统一的 commitChars：与自动上屏共用「记录时间戳」这一步，
                                    // 否则点候选上屏的字不在可撤回窗口内，划掉会撤到更早的字
                                    commitChars(listOf(c.char))
                                    clearAll()
                                }
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${r.elapsedMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/** 单个候选字按钮，下方带一条置信度细条 */
@Composable
private fun CandidateChip(
    char: Char,
    confidence: Float,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    // ⚠️ 必须用 Surface 的 onClick 重载，**不能**写成 `Surface(...) { }` 外面再套
    // `Modifier.clickable` —— 那样按压/悬停指示由 clickable 节点绘制，而它是**矩形**，
    // 圆角按键上会浮出一个比按键大的直角矩形高亮（真机反馈）。
    // Surface(onClick) 内部会按 shape 裁剪指示器，按压反馈与按键形状、尺寸完全一致。
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                char.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(22.dp)
                    .height(2.dp)
                    .background(
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(1.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(confidence.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(
                            if (highlighted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

private const val STROKE_WIDTH_PX = 6f
private const val TOP_K = 5
private const val CANDIDATE_BAR_SIZE = 5

/**
 * 空板提示文字。
 *
 * 由 InkBoardView 内部绘制（不是 Compose Text）—— 互操作的 AndroidView 永远画在
 * Compose 内容之上，留在 Compose 里会被不透明的纸面盖住。
 */
private const val HINT_TEXT = "用笔在这里写一个字"

/**
 * 「划掉撤回」的有效窗口（毫秒）。
 *
 * 只在刚上屏后的这段时间内允许划掉撤回，超出窗口则划掉只作用于板上墨迹。
 * 取 4 秒的依据：用户看清识别结果、意识到「这不是我要写的字」所需的时间通常在这个量级；
 * 再放宽会让误划有机会删掉更早写好的正确答案，而删除答案是不可逆的体验伤害。
 */
private const val SCRATCH_UNDO_WINDOW_MS = 4_000L

/**
 * 抬笔后等多久才识别（毫秒）。
 *
 * 汉字是多笔画的，「好」= 女 + 子，笔中间必然离屏。取值是权衡：
 * - 太小（<300ms）：笔画间的自然停顿就会触发，写到一半被抢跑上屏；
 * - 太大（>800ms）：写完一个字要干等很久才上屏，且连续写下一个字时会觉得没反应。
 *
 * 550ms 对「笔画间 150–400ms、字间 600ms+」的一般书写节奏是够用的分隔。
 * 如果用户反馈「写到一半还是被上屏」，优先调大这个值而不是改识别逻辑。
 */
private const val RECOGNIZE_DELAY_MS = 550L

/**
 * 手写态下可一键插入的常用标点（中文）。
 *
 * ⚠️ **标点不可能靠手写识别出来**：中文模型是 3755 个 GB2312 一级汉字，
 * 拉丁模型是 EMNIST 47 类（数字 + 大小写字母），**两套字符表都没有标点类**。
 * 想让标点也能"写"出来，只能换/补一个含标点的识别模型，属于模型侧工作。
 * 在此之前，把最高频的几个标点放在手边是唯一零成本的出路。
 */
private val PUNCT_CJK = listOf('，', '。', '、', '？', '！', '：')

/** 手写态下可一键插入的常用标点（拉丁/英文）。 */
private val PUNCT_LATIN = listOf(',', '.', '?', '!', ';', '\'')

/** 按文种取标点快捷集：当前空的答案是什么语言，就给什么标点。 */
private fun punctuationKeys(script: HandwritingScript): List<Char> =
    if (script == HandwritingScript.Latin) PUNCT_LATIN else PUNCT_CJK
