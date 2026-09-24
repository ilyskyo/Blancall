// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
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
 * - 高置信（top-1 ≥ 0.80 且领先 top-2 ≥ 0.15）时**自动上屏**，且该批墨迹以
 *   「收拢→淡出」退场动画消失（[InkBoardView.animateStrokesOut]），与落字同窗口衔接；
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
    script: HandwritingScript = HandwritingScript.Chinese,
    /**
     * 该空标准答案中「当前已输入内容之后的下一个期望字符」（调用方计算：
     * `answer.getOrNull(当前输入长度)`）；null = 不启用生僻字守卫。
     *
     * 用途：极少数超出识别字表（HWDB 全集 7356 类）的生僻字，
     * 手写**必然认不出**，模型很可能「自信地」输出一个形近错字——若自动上屏就把
     * 错字写进了答案。此时改为提示引导键盘输入（tap 上方内容栏即可打字）。
     */
    expectedNextChar: Char? = null,
    /**
     * 中文脚本下是否允许「拉丁模型回退救援」（主模型没把握时用拉丁模型候选补充/接管）。
     *
     * 调用方按「下一个待填字」判定传入：**答案是汉字 ⇒ 禁**（用户要求：答案只含汉字时
     * 不做英文识别 —— 真机教训：写石旁字时拉丁模型给出 A:0.85/5:0.43/n:0.29 混进候选条，
     * 干扰选字、诱导误点）；待填字本身是拉丁字符（如「A 股」的 A）或没有待填字
     * （null，自由书写）⇒ 允许。
     */
    allowLatinFallback: Boolean = true,
    /**
     * 英文默写的「答案先验」：本空从当前位置起的**剩余标准答案**（如已写「L」则为「ove」）。
     *
     * 用途：EMNIST 模型分不清 o/0、1/l 这类形状相同的字符（真机：写「Love」输出「L0Ve」），
     * 逐段识别列与剩余答案做**容错匹配**（忽略大小写 + 混淆组等价）成功后，按**答案形式**
     * 整词提交 —— 不依赖置信度阈值，也不怕数字/字母混淆。仅拉丁脚本下生效；
     * null = 不启用（自由书写 / 无标准答案场景）。
     */
    expectedWord: String? = null
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
    // 拉丁模型是否随包分发（assets 缺失时英文空只能走键盘，跨文种回退也要跳过）
    val latinAvailable = remember { HandwritingRecognizer.hasLatinModel(context) }
    // 诊断开关：只在 debug 包打日志。手写链路（尤其英文模型）出问题时，
    // 「写一次、看日志」比来回猜「是不是预处理/字符表/文种选错」高效得多。
    val isDebuggable = remember {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

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

    // ── 连写逐段确认 ──
    // 规则（用户定的）：连写「我喜欢你」时，**置信度高的段直接上屏、该段墨迹从板上消失**；
    // 一旦遇到置信度低的段就**停在那一段**等用户点候选，**该段及其之后段的墨迹全部保留**
    // ——用户要能看着自己刚写的字挑候选。选完继续往后推进：
    // 所以「你」虽然认得准，也要等「欢」选完才轮到它出结果。
    var pendingSegments by remember { mutableStateOf<List<List<List<Offset>>>>(emptyList()) }
    var pendingResults by remember {
        mutableStateOf<List<HandwritingResult?>>(emptyList())
    }
    var pendingAt by remember { mutableStateOf(0) }

    /**
     * 本批段识别期间「是否因期望字表外而禁自动上屏」（由 [recognizeCurrent] 协程写入，
     * [advancePending] 读取）。只拦自动上屏，不影响候选展示与点选。
     */
    var pendingRareBlock by remember { mutableStateOf(false) }

    /** 期望字是表外生僻字时的统一提示文案（整板/段两条路径共用）。 */
    val rareGuardHint: String? = expectedNextChar?.let {
        "「$it」是生僻字，手写识别暂不支持——点上方内容栏可用键盘输入"
    }

    /**
     * 从第 [from] 段开始逐段推进：高置信的段累积成**一批**上屏并抹掉它们的墨迹；
     * 遇到第一个低置信（或整段没认出来）的段就停下，把它的候选交给用户。
     * 识别侧自动推进（[prefix] 为空）时抹墨走退场动画；点选路径仍为瞬时清除。
     *
     * ⚠️ 上屏必须**按批**（一次 [commitChars] 带多个字）：调用方的 `value + 新增`
     * 是组合期快照，同一帧里分多次写会互相覆盖、只剩最后一个字。
     *
     * @param prefix 用户点选的候选字（连写点选路径传入）。它必须与后续可自动上屏的段
     *   **合并成同一批**提交——若先单独 commit 一次再走本函数，同帧两次写入会互相覆盖，
     *   点选的字会被后一批（旧快照 + 后续段）覆盖丢失（真机：连写「谁说你」→ 点「谁」
     *   候选 → 只上屏「说你」）。
     */
    fun advancePending(from: Int, prefix: List<Char> = emptyList()) {
        val segs = pendingSegments
        val results = pendingResults
        if (from >= segs.size) {
            // 无后续段时，prefix（点选字）也必须上屏——单独按批提交后收尾
            if (prefix.isNotEmpty()) commitChars(prefix)
            pendingSegments = emptyList()
            pendingResults = emptyList()
            pendingAt = 0
            result = null
            statusText = null
            return
        }
        val commit = ArrayList<Char>(prefix.size + segs.size)
        commit.addAll(prefix)
        val consumed = ArrayList<List<Offset>>()
        // 每段自己的笔画（供「自动上屏」路径的退场动画按段分组收拢）；与 consumed 同源
        val consumedGroups = ArrayList<List<List<Offset>>>()
        var i = from
        while (i < segs.size) {
            val r = results.getOrNull(i)
            val best = r?.best
            // ⚠️ !pendingRareBlock：期望字表外时**不自动上屏**（真机：写「砯」被切成
            // 「石|水」，「石」高置信上屏成错字）——但候选仍照常展示供用户点选。
            if (r != null && best != null && r.isConfident && !pendingRareBlock) {
                commit.add(best.char)
                consumedGroups.add(segs[i])
                segs[i].forEach { consumed.add(it) }
                i++
            } else {
                break
            }
        }
        if (consumed.isNotEmpty()) {
            val view = inkRef.get()
            // 只有识别侧自动推进（prefix 为空）才播退场动画；点选路径（prefix 非空，
            // 含其后续自动推进的段）与改动前逐帧一致，不引入任何新动画。
            if (prefix.isEmpty() && autoCommit && enabled) view?.animateStrokesOut(consumedGroups)
            else view?.removeStrokes(consumed)
        }
        if (commit.isNotEmpty()) commitChars(commit)
        if (i < segs.size) {
            // 停在这一段等用户点候选；板上只留下「这一段 + 后面的段」
            pendingAt = i
            result = results.getOrNull(i)
            statusText = when {
                // 期望字表外：候选仍是形近字，附键盘引导（拦自动上屏、不拦点选）
                pendingRareBlock -> rareGuardHint
                results.getOrNull(i) == null -> "这一段没认出来，可以重写"
                else -> null
            }
        } else {
            pendingSegments = emptyList()
            pendingResults = emptyList()
            pendingAt = 0
            result = null
            statusText = null
        }
    }

    /** 放弃当前的连写确认序列（用户重新落笔 / 划掉 / 清空时调用）。 */
    fun dropPending() {
        pendingSegments = emptyList()
        pendingResults = emptyList()
        pendingAt = 0
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

    /**
     * 识别一张位图（结束后释放位图）。
     *
     * ## 文种策略
     * 主文种由调用方按空的标准答案给定（见 [HandwritingScript.forAnswer]）：
     * - **纯拉丁答案（英语空，script = Latin）：只用英语模型。** 主模型没把握时
     *   展示字母候选让用户点选，不让中文模型的候选掺进来——真机教训：潦草字母 'b'
     *   被中文模型认成「占」，排进候选条反而干扰选择（用户明确要求「答案只有
     *   英文字母时仅启用英语识别，这样准确性高」）。
     * - **中文/未定文种：允许拉丁模型回退救援**（如混合答案「A股」里写字母、
     *   纯中文空里写英文的「只会键盘」场景）：主模型**几乎没把握**（低于
     *   [ALT_TAKEOVER_WEAK_PRIMARY]）而副模型（拉丁）单模型高置信时才接管；
     *   否则两边候选合并展示（主文种优先）供点选。
     *
     * ⚠️ 自动上屏仍只认「单模型 [HandwritingResult.isConfident]」，合并结果
     * 跨模型比较置信度并不可靠。
     */
    fun recognizeBitmapWithFallback(bmp: Bitmap): HandwritingResult? {
        return try {
            val primary = recognizer.recognize(bmp, script, TOP_K)
            if (primary != null && primary.isConfident) return primary

            // 纯拉丁答案：仅英语识别，不回退、不合并
            if (script == HandwritingScript.Latin) {
                if (isDebuggable) {
                    Log.d(
                        TAG_INK,
                        "latin-only: primary=${
                            primary?.best?.let { "${it.char}:${"%.2f".format(it.confidence)}" }
                        }"
                    )
                }
                return primary
            }

            // 中文/未定文种：下一个待填字是汉字 ⇒ **禁用回退**（答案只含汉字时不做英文
            // 识别，防止字母/数字候选混入）；只有待填字本身是拉丁字符才需要它上场。
            if (!allowLatinFallback) {
                if (isDebuggable) {
                    Log.d(
                        TAG_INK,
                        "latin-fallback off: primary=${
                            primary?.best?.let { "${it.char}:${"%.2f".format(it.confidence)}" }
                        }"
                    )
                }
                return primary
            }
            if (!latinAvailable) return primary
            val alt = recognizer.recognize(bmp, HandwritingScript.Latin, TOP_K)
            // 副文种接管的门槛：主模型**几乎没把握**（低于弱线）而副模型高置信。
            // 主模型仍有判断时（如中文模型给了个低置信汉字），副文种的巧合候选
            // 不得接管上屏，只合并候选让用户点选。
            val primaryWeak = primary == null ||
                (primary.best?.confidence ?: 0f) < ALT_TAKEOVER_WEAK_PRIMARY
            val adopted = when {
                alt == null -> primary
                alt.isConfident && primaryWeak -> alt
                primary == null -> alt
                else -> primary.merge(alt, TOP_K)
            }
            if (isDebuggable) {
                Log.d(
                    TAG_INK,
                    "fallback: script=$script alt=Latin " +
                        "primary=${primary?.best?.let { "${it.char}:${"%.2f".format(it.confidence)}" }} " +
                        "alt=${alt?.best?.let { "${it.char}:${"%.2f".format(it.confidence)}" }} " +
                        "adopted=${adopted?.best?.char}"
                )
            }
            adopted
        } finally {
            bmp.recycle()
        }
    }

    /** 把当前笔画集渲染成位图并识别 */
    fun recognizeCurrent() {
        val ink = inkRef.get()?.snapshotWithTiming().orEmpty()
        if (ink.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return
        val inkStrokes = ink.map { it.pts }
        // 宽度先验按文种给：汉字近方形（1.0），拉丁小写字母连写只有高的六成左右
        // （0.62）—— 用汉字先验去数英文字母会少算一半，这是英文连写拆不准的主因。
        val aspect = if (script == HandwritingScript.Latin) CHAR_ASPECT_LATIN else CHAR_ASPECT_CJK
        if (isDebuggable) {
            Log.d(
                TAG_INK,
                "recognize: script=$script strokes=${ink.size} pts=${inkStrokes.sumOf { it.size }} " +
                    "canvas=${canvasSize.width}x${canvasSize.height} nativeReady=${recognizer.isReady(script)}"
            )
        }
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
                // 切分策略（见 splitInkSmart）：几何优先（与改造前逐位一致）；
                // 几何切不开时用「书写停顿」补充——紧凑连写正是几何失效、停顿仍在的场景。
                // 两者都切不出（如「一」无纵向空隙）则回落单字识别（下方②）。
                val segments = withContext(Dispatchers.Default) {
                    splitInkSmart(ink, aspect)
                }
                if (isDebuggable) {
                    // 仅 debug：区分「几何切的」还是「时间切的」，方便真机校准阈值
                    val viaGeometry = withContext(Dispatchers.Default) {
                        splitInkByXGap(inkStrokes, aspect) != null
                    }
                    Log.d(
                        TAG_INK,
                        "split source=${if (segments == null) "none" else if (viaGeometry) "geo" else "time"} " +
                            "segments=${segments?.size ?: 0} aspect=$aspect"
                    )
                }
                if (segments != null) {
                    val segResults = withContext(Dispatchers.Default) {
                        segments.map { seg -> renderInk(seg)?.let { recognizeBitmapWithFallback(it) } }
                    }
                    if (isDebuggable) {
                        Log.d(
                            TAG_INK,
                            "segments=${segments.size} -> " +
                                segResults.joinToString(" | ") { r ->
                                    r?.candidates?.take(5)
                                        ?.joinToString("/") { "${it.char}:${"%.2f".format(it.confidence)}" }
                                        ?: "null"
                                }
                        )
                    }

                    // 生僻字守卫（段路径）：期望字表外时**只禁自动上屏**（真机：写「砯」被
                    // 切成「石|水」，「石」高置信自动上屏会静默写错字），候选照常展示供点选，
                    // 停在段时附键盘引导 —— 写正常字（高置信）不再被一律拦截
                    // （用户反馈：写「哈」也被拦是过度拦截）。
                    pendingRareBlock = withContext(Dispatchers.Default) {
                        val next = expectedNextChar
                        next != null && !HandwritingRecognizer.isCjkCharSupported(context, next)
                    }

                    // ── 拉丁（英语单词）：**整词一次性提交** ──
                    // 用户明确要求「写完一整个单词一整个地识别，而不是一个字母一个字母」。
                    // 全部段可用且各段置信度达标（见 [latinWordCommitChars]）时，把整个词
                    // 作为**一批**一次上屏；否则回落到逐段安全推进（高置信段批上屏、
                    // 低置信段停下等点选）。
                    if (script == HandwritingScript.Latin) {
                        // ① 答案先验优先：剩余答案已知且「逐段识别列」与其容错匹配（0↔o、1↔l…）
                        //    ⇒ 直接按答案形式提交（真机：写 Love 输出 L0Ve，靠这条救回）。
                        val letters = segResults.mapNotNull { it?.best?.char }
                        // 双宽段标记：段宽 ≳ 2 倍单字宽 ⇒ 两个同字母连写被并成一段
                        // （真机：hello 的「ll」被并段，模型必然认不出——该段按答案吃两位）
                        val unitW = canvasSize.height * aspect
                        val doubleFlags = segments.map { seg ->
                            val xs = seg.flatten()
                            xs.isNotEmpty() && (xs.maxOf { it.x } - xs.minOf { it.x }) > unitW * 1.6f
                        }
                        val byAnswer = if (letters.size == segResults.size) {
                            expectedWord?.let { matchLatinWordToAnswer(letters, it, doubleFlags) }
                        } else null
                        // ② 常规整词提交：各段置信度达标（个别字母稍低不阻塞）
                        val word: List<Char>? = byAnswer?.toList() ?: latinWordCommitChars(segResults)
                        if (word != null) {
                            if (isDebuggable && byAnswer != null) {
                                Log.d(
                                    TAG_INK,
                                    "latin-answer-match: ${letters.joinToString("")} -> $byAnswer"
                                )
                            }
                            val consumed = ArrayList<List<Offset>>()
                            segments.forEach { seg -> seg.forEach { consumed.add(it) } }
                            if (consumed.isNotEmpty()) {
                                val view = inkRef.get()
                                // 整词自动上屏同样播退场动画（逐段各自收拢，与批量落字同窗口）；
                                // 两个特性开关关闭时保持原有的瞬时清除行为。
                                if (autoCommit && enabled) view?.animateStrokesOut(segments)
                                else view?.removeStrokes(consumed)
                            }
                            commitChars(word)
                            pendingSegments = emptyList()
                            pendingResults = emptyList()
                            pendingAt = 0
                            result = null
                            statusText = null
                            return@launch
                        }
                    }

                    // 逐段推进：高置信的段直接上屏（墨迹随之消失），
                    // 低置信的段停下等用户点候选（该段及之后段的墨迹保留）。
                    pendingSegments = segments
                    pendingResults = segResults
                    pendingAt = 0
                    advancePending(0)
                    return@launch
                }

                // ② 方形墨迹（或切不出段）：按单字识别
                val whole = withContext(Dispatchers.Default) {
                    renderInk(inkStrokes)?.let { recognizeBitmapWithFallback(it) }
                }
                // 多字形态守卫：切分失败 + 墨迹按格写且明显偏宽 ⇒ 禁止自动上屏。
                // 防「连写三字 → 一个字的高置信乱码被静默写进答案」（伤害最大的一类错）。
                val guarded = needsMulticharGuard(inkStrokes, canvasSize.height.toFloat(), aspect)
                // 生僻字守卫：下一个期望字符是表外汉字 ⇒ 手写必然认不出，禁止自动上屏，
                // 改为引导键盘输入（详见参数注释）。首次查询会读一次字表（有缓存）。
                val rareGuard = withContext(Dispatchers.Default) {
                    val next = expectedNextChar
                    next != null && !HandwritingRecognizer.isCjkCharSupported(context, next)
                }
                if (isDebuggable) {
                    Log.d(
                        TAG_INK,
                        "whole: " + (
                            whole?.candidates?.take(3)?.joinToString(", ") {
                                "${it.char}:${"%.2f".format(it.confidence)}"
                            } ?: "null"
                            ) + " confident=${whole?.isConfident} autoCommit=$autoCommit " +
                            "guarded=$guarded rareGuard=$rareGuard next=$expectedNextChar"
                    )
                }
                if (rareGuard && (whole == null || !whole.isConfident)) {
                    // 期望字表外 + 结果低置信 ⇒ 形近候选无意义（也是误点来源），只留键盘引导。
                    // 高置信结果不在此列：用户写的就是个正常字，照常上屏（用户反馈：写
                    // 「哈」被拦是过度拦截 —— 拦截只针对「疑似写表外字认不出」的低置信情况）。
                    result = null
                    statusText = rareGuardHint
                    return@launch
                }
                if (whole != null && whole.isConfident && autoCommit && !guarded) {
                    commitChars(listOf(whole.best!!.char))
                    val view = inkRef.get()
                    // 自动上屏：墨迹「收拢→淡出」退场（只动识别快照里的那批笔画，
                    // 不误清动画期间新落的笔）；enabled=false 时保持原来的瞬时清板。
                    if (enabled) view?.animateStrokesOut(listOf(inkStrokes))
                    else view?.clearInk()
                    return@launch
                }
                if (whole != null && whole.candidates.isNotEmpty()) {
                    result = whole
                    statusText = when {
                        guarded -> "像是写了多个字，请写开一点，也可以直接从候选里点选"
                        else -> null
                    }
                    cbRecognized?.invoke(whole)
                } else {
                    statusText = when {
                        guarded -> "像是写了多个字，请写开一点，也可以直接从候选里点选"
                        else -> "没认出来，再写工整一点试试"
                    }
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
     * 抬笔后延迟一段时间再识别：给「连笔写同一个（词）字」留出续写窗口。
     *
     * ## 为什么必须延迟
     * 汉字是多笔画的，「好」= 女 + 子。若每抬笔就识别，写到「女」就会被当成一个字
     * 高置信上屏并清板 —— 用户接着写「子」反而变成第二个字，整个功能不可用（真机实测）。
     * 停顿内只要落了新笔，这个任务就被取消、墨迹继续累积；停顿满才代表「这个字写完了」。
     *
     * ## 延迟按文种分开
     * - 中文 [RECOGNIZE_DELAY_MS]（550ms）：笔画间 150–400ms、字间 600ms+，550ms 是
     *   区分「同一字多笔」与「换字」的合适分界；
     * - 拉丁 [RECOGNIZE_DELAY_LATIN_MS]（900ms）：英语是**整词**识别，字母连写时
     *   字母间停顿可达几百 ms，沿用 550ms 会把「半个单词」提前识别上屏 ——
     *   真机现象就是「一个字母一个字母往外蹦」，与用户预期的「写完整个单词一整个地识别」不符。
     */
    fun scheduleRecognize() {
        cancelPendingRecognize()
        val delayMs = if (script == HandwritingScript.Latin) RECOGNIZE_DELAY_LATIN_MS
        else RECOGNIZE_DELAY_MS
        recognizeJob.set(scope.launch {
            delay(delayMs)
            recognizeCurrent()
        })
    }

    fun clearAll() {
        cancelPendingRecognize()
        dropPending()
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
                dropPending()
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
        // 又开始写了 ⇒ 上一轮连写确认序列作废，不再自动往后推
        dropPending()
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
                v.hintText = if (script == HandwritingScript.Latin) HINT_TEXT_LATIN else HINT_TEXT
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
                // ⚠️⚠️ 这里**绝对不要**再用 Compose 的 `pointerInput` 去「消费笔事件」。
                // 曾经为了阻止 LazyColumn 在写字时滚动加过这么一层：
                //     .pointerInput(Unit) { awaitPointerEventScope { while (true) {
                //         val e = awaitPointerEvent(PointerEventPass.Initial)
                //         if (e.changes.any { it.pressed && it.type == PointerType.Stylus })
                //             e.changes.forEach { it.consume() } } } }
                // 后果是**整块书写板再也收不到笔事件**（真机现象：完全写不出墨、无任何字迹），
                // 因为互操作 AndroidViewHolder 的事件分发与 Compose 的消费状态是同一套 ——
                // 上层一旦 consume，事件就到不了 InkBoardView。
                //
                // 正确做法是**让 InkBoardView 自己处理**（见 InkBoardView.onTouchEvent）：
                //   · 起笔时 `parent.requestDisallowInterceptTouchEvent(true)`；
                //   · 对笔触点返回 true（消费），对手指空闲态返回 false（放行页面滚动）。
                // 子 View 的消费状态会被互操作层同步回 Compose，LazyColumn 自然就不会滚。
        )

        // ── 操作行：标点快捷 / 清空 / 收起 ──
        // ⚠️ 必须放在书写区**外面**，两个理由：
        // ① **互操作的 AndroidView 永远绘制在 Compose 内容之上**，不透明的纸面会把叠上去的
        //    按钮整块盖住；
        // ② 笔落在书写板范围内时事件由 `InkBoardView` 全权处理（它要画墨，必须消费），
        //    叠在板上的按钮用笔也就点不到了（真机反馈「用笔没法点清空」）。
        // 移出后笔/手指都直接命中按钮，代价只是按钮不再叠在墨迹上。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 标点一键插入 ──
            // 两套识别模型的字符表**都不含标点**（中文 7356 项为汉字与字母数字符号、
            // 拉丁 EMNIST 47 类 = 数字 + 字母），所以手写标点永远认不出来。
            // 这是模型能力边界，不是 bug ⇒ 在这里给一条零成本的出路，
            // 别让用户为了写一个逗号切回键盘。
            //
            // ⚠️ 用 FlowRow 而不是 Row：常用标点扩充后一行放不下时自动折行，
            // 不会把右侧「清空/收起」挤变形（手机窄屏尤其明显）。
            FlowRow(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.Center
            ) {
                punctuationKeys(script).forEach { p ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            // ⚠️ clip 必须放在 clickable **之前**：clickable 的按压/悬停指示器
                            // 绘制在它自己的节点边界内，只有链上先 clip 才能把它裁成圆角；
                            // 否则圆角键上会浮出一个直角灰块（Surface 自己的 shape 只裁剪背景，
                            // 管不到挂在 modifier 上的 clickable —— 真机复现过）。
                            //
                            // 这里刻意**不**改成 Surface(onClick = …) 重载：那个重载会带上
                            // 48dp 最小交互尺寸，一行 6 个标点会被撑到近 300dp 宽、把操作行挤变形。
                            .clip(RoundedCornerShape(6.dp))
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
                                    // ⚠️ 点选字与「后续可自动上屏段」必须**合并为同一批**提交：
                                    // 同帧两次 onValueChange 会因调用方 `value + chars` 的快照
                                    // 互相覆盖（真机：连写「谁说你」→ 点「谁」候选 →
                                    // 只有「说你」上屏，「谁」被后一次写入覆盖丢失）。
                                    // 因此不再单独 commitChars，点选字作为 prefix 交给
                                    // advancePending 与后续段一起、一次批次上屏（commitChars
                                    // 内部统一记录撤回时间戳，与自动上屏共用同一步）。
                                    val seg = pendingSegments.getOrNull(pendingAt)
                                    if (seg != null) {
                                        // 连写逐段确认：抹掉这一段的墨迹后继续往后推
                                        // （后面的段该自动上屏就上屏，该继续停就继续停）
                                        inkRef.get()?.removeStrokes(seg)
                                        advancePending(pendingAt + 1, prefix = listOf(c.char))
                                    } else {
                                        // 非连写（单字识别）路径：独立一次提交后清板
                                        commitChars(listOf(c.char))
                                        clearAll()
                                    }
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
 * 副文种接管自动上屏的「主模型弱线」：主文种 top-1 低于该值（或完全无结果）时，
 * 副文种的高置信结果才允许直接采用。
 *
 * 依据真机日志：主模型仍有判断（如拉丁 b:0.56）时，副文种的巧合候选（中文「占」）
 * 不得接管上屏，只能进合并候选等用户点选；只有主模型基本是噪声输出（< 0.45）
 * 才说明用户写的确实不是本文种。
 */
private const val ALT_TAKEOVER_WEAK_PRIMARY = 0.45f

/**
 * 空板提示文字。
 *
 * 由 InkBoardView 内部绘制（不是 Compose Text）—— 互操作的 AndroidView 永远画在
 * Compose 内容之上，留在 Compose 里会被不透明的纸面盖住。
 */
private const val HINT_TEXT = "用笔在这里写一个字"

/** 拉丁（英语单词）空板提示：强调「整词连写」，与整词识别策略一致。 */
private const val HINT_TEXT_LATIN = "用笔连着写完这个单词"

/**
 * 「划掉撤回」的有效窗口（毫秒）。
 *
 * 只在刚上屏后的这段时间内允许划掉撤回，超出窗口则划掉只作用于板上墨迹。
 * 取 4 秒的依据：用户看清识别结果、意识到「这不是我要写的字」所需的时间通常在这个量级；
 * 再放宽会让误划有机会删掉更早写好的正确答案，而删除答案是不可逆的体验伤害。
 */
private const val SCRATCH_UNDO_WINDOW_MS = 4_000L

/**
 * 手写诊断日志 TAG（仅 debug 包输出）。抓取：`adb logcat -s BlancallInk`
 *
 * 打点：文种 / 墨迹点数 / 模型是否就绪 / 切段数与每段首候选 / 整板 top3 与置信度。
 * 英文识别这类「看不出所以然」的问题，靠这几行就能定位到
 * 「文种选错 / 模型没加载 / 预处理把前景吃掉了 / 候选本来就对但没上屏」中的哪一种。
 */
private const val TAG_INK = "BlancallInk"

/**
 * 抬笔后等多久才识别（毫秒，汉字）。
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
 * 抬笔后等多久才识别（毫秒，拉丁/英语单词）。
 *
 * 英语按**整词**识别（见 [latinWordCommitChars]）：字母连写的字母间停顿可达几百 ms，
 * 沿用中文的 550ms 会把「半个单词」提前识别上屏（真机现象：一个字母一个字母往外蹦）。
 * 900ms 让「写完整词后的停顿」成为唯一触发点。
 *
 * 取 900ms 而非更长：单词听写场景下写完即停顿，900ms 内出结果不至于「没反应」；
 * 若用户反馈「写词中间还是被抢跑」，优先调大这个值。
 */
private const val RECOGNIZE_DELAY_LATIN_MS = 900L

/**
 * 手写态下可一键插入的常用标点（中文）。
 *
 * ⚠️ **标点不可能靠手写识别出来**：中文模型是 7356 项（HWDB 全集，不含标点类），
 * 拉丁模型是 EMNIST 47 类（数字 + 大小写字母），**两套字符表都没有标点类**。
 * 想让标点也能"写"出来，只能换/补一个含标点的识别模型，属于模型侧工作。
 * 在此之前，把最高频的几个标点放在手边是唯一零成本的出路。
 */
private val PUNCT_CJK = listOf('，', '。', '、', '？', '！', '：', '；')

/** 手写态下可一键插入的常用标点（拉丁/英文）。 */
private val PUNCT_LATIN = listOf(',', '.', '?', '!', ';', ':', '\'', '-')

/** 按文种取标点快捷集：当前空的答案是什么语言，就给什么标点。 */
private fun punctuationKeys(script: HandwritingScript): List<Char> =
    if (script == HandwritingScript.Latin) PUNCT_LATIN else PUNCT_CJK

// ============ 拉丁（英语单词）整词提交 ============

/** 整词提交的最低段置信度下限（低于它说明该字母不可信，停止整词自动上屏）。 */
private const val LATIN_WORD_MIN_SEG = 0.50f

/** 整词提交的各段平均置信度下限（个别字母稍低不阻塞整词，但整体要够好）。 */
private const val LATIN_WORD_MIN_AVG = 0.70f

/**
 * 拉丁（英语单词）的「整词一次性提交」判定。
 *
 * ## 为什么
 * 用户明确要求：写完一整个单词，一个整个地识别（像单词默写软件），
 * 而不是字母逐个上屏。单词是作为一个整体输入的，逐字母上屏既碎又慢。
 *
 * ## 判据（两者全满足才提交，否则返回 null 回落逐段点选）
 * 1. 每段都有识别结果；
 * 2. 最低段 ≥ [LATIN_WORD_MIN_SEG] 且各段平均 ≥ [LATIN_WORD_MIN_AVG]
 *    —— 比单字母阈值（isConfident 0.80）宽：个别字母稍低不阻塞整词，
 *    但出现明显不可信段（< 0.50）时不冒险，交回逐段点选兜底。
 *
 * 返回整词字符列（直接可上屏）；不满足返回 null。
 * 注：该判定只决定「是否整词一键上屏」，与单字自动上屏的 isConfident 是两套判据，
 * 互不干扰（后者仍用于逐段推进与点选路径）。
 */
internal fun latinWordCommitChars(
    results: List<HandwritingResult?>,
    minSeg: Float = LATIN_WORD_MIN_SEG,
    minAvg: Float = LATIN_WORD_MIN_AVG
): List<Char>? {
    if (results.isEmpty()) return null
    val bests = results.map { it?.best ?: return null }
    val minConf = bests.minOf { it.confidence }
    val avgConf = bests.map { it.confidence }.average().toFloat()
    return if (minConf >= minSeg && avgConf >= minAvg) bests.map { it.char } else null
}

// ============ 答案先验：拉丁词容错匹配（EMNIST 数字/字母混淆） ============

/**
 * EMNIST 训练集里这些字符形状几乎相同，单字母模型天生分不清
 * （真机：写「Love」→ 模型输出「L0Ve」，「o」变数字零）。容错匹配时组内字符视为等价。
 */
private val LATIN_CONFUSION_GROUPS = listOf(
    "0oO", "1lI", "5sS", "2zZ", "8Bb", "9gq", "6bG"
)

/**
 * 把「逐段识别出的字母列」与已知标准答案做**容错匹配**（忽略大小写 + 混淆组等价）。
 *
 * 用途（英文默写场景的答案先验）：识别接近但有个别混淆字符（L0Ve vs Love）时，
 * 不必依赖置信度阈值，直接**按答案的实际形式提交** —— 既纠正 o/0 混淆，也保证
 * 大小写与答案一致（答案要求大写就提交大写）。
 *
 * [doubleFlags]：哪些段是「双宽段」（宽度 ≳ 2 倍单字宽，即两个同字母连写被并成一段，
 * 真机：hello 的「ll」）。双宽段吃答案的两位且**要求这两位同字符**（ll/oo/ee…），
 * 该段的识别结果不参与校验（合并段模型必然认不出，而答案约束已足够强）。
 *
 * 仅在「长度对齐且逐位全匹配（含混淆等价）」时返回答案，不做模糊猜测。
 *
 * @return 匹配成功时返回 [expected] 本身（答案形式）；否则 null。
 */
internal fun matchLatinWordToAnswer(
    letters: List<Char>,
    expected: String,
    doubleFlags: List<Boolean> = List(letters.size) { false }
): String? {
    if (expected.isEmpty() || letters.size != doubleFlags.size) return null
    fun eq(a: Char, b: Char): Boolean =
        a.equals(b, ignoreCase = true) ||
            LATIN_CONFUSION_GROUPS.any {
                it.contains(a, ignoreCase = true) && it.contains(b, ignoreCase = true)
            }
    // 段 → 答案贪心对齐：单段吃一位（需混淆等价）；双宽段吃两位（需两位同字符）
    var ei = 0
    for (i in letters.indices) {
        val take = if (doubleFlags[i]) 2 else 1
        if (ei + take > expected.length) return null
        if (take == 2) {
            if (!expected[ei].equals(expected[ei + 1], ignoreCase = true)) return null
        } else {
            if (!eq(expected[ei], letters[i])) return null
        }
        ei += take
    }
    return if (ei == expected.length) expected else null
}
