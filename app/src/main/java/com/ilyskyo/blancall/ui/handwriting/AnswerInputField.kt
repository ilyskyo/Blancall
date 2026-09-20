// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilyskyo.blancall.data.handwriting.HandwritingRecognizer
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.penTapToHandwriting
import com.ilyskyo.blancall.ui.common.suppressAsPalmMisTouch
import com.ilyskyo.blancall.ui.practice.HintOutlinedField
import com.ilyskyo.blancall.ui.theme.AppPrefs

/**
 * 作答输入框（键盘 ⇄ 手写 双模式）。
 *
 * ## 为什么要包一层
 * 手写不是「输入法」，而是**作答方式**：用户要能一眼看出当前是哪种方式，
 * 并随时切回去。因此把「输入框 + 手写面板 + 模式切换」作为一个整体交付，
 * 各练习页只关心 [value] / [onValueChange]，无需自己管理面板状态。
 *
 * ## 模式持久化
 * 当前模式存在 [AppPrefs.handwritingInputEnabled]，**跨页面、跨启动保留**。
 * 用户明确要求：「选了笔就一直用笔，直到我自己切回键盘」——
 * 所以切到手写后进入下一个空、下一篇文章仍是手写。
 *
 * ## 手写结果如何上屏
 * 单字识别成功 → [onValueChange] 追加到已有文本末尾（不是替换），
 * 这样连续写多个字就是连续输入。低置信时面板只展示候选，等用户点选，
 * 绝不把没把握的字直接写进答案——错一个字就是错一空。
 */
@Composable
fun AnswerInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** 弱提示字（键盘态跟在光标后淡入；手写态不显示——手写时看字会「照抄」） */
    hintChar: Char? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    minHeight: Dp = 56.dp,
    textStyle: TextStyle = LocalTextStyle.current,
    imeAction: ImeAction = ImeAction.Default,
    /** 是否允许切换手写（提交后 / 只读态应关闭） */
    allowHandwritingSwitch: Boolean = true,
    /**
     * 手写态下的「非当前作答目标」紧凑模式。
     *
     * ## 为什么需要它
     * 词卡模式一页可能有十几个空。每个空都渲染一整块书写板（≥140dp）时，
     * 列表会变成一望无际的「书写板墙」：一屏放不下两个空，用户也分不清
     * 自己正在写哪一个空。所以只给**当前目标**渲染书写板，其余空走这个紧凑模式
     * ——只显示已写内容，高度约一行。点一下该空即可把它切换为当前目标。
     *
     * 手写模式关闭时本参数无效果（各空照常渲染键盘输入框）。
     */
    handwritingCompact: Boolean = false,
    /**
     * 识别文种：由调用方按**该空的标准答案**自动选择（见 [HandwritingScript.forAnswer]）。
     * 英文空走 EMNIST 拉丁模型，中文空走 7356 类（HWDB 全集）模型。
     */
    script: HandwritingScript = HandwritingScript.Chinese,
    /**
     * 笔落在输入框上时的动作。
     *
     * 传 null（默认）⇒ 旧行为：把「默认输入方式」切成手写（书写板常驻）。
     * 传回调 ⇒ 交由调用方接管，练习页统一改成「弹出底部书写板」。
     *
     * ⚠️ 之所以要这个回调：笔点应当是**临时**行为 —— 「手写 / 键盘」开关表示的是
     * **默认输入方式**，笔来了就写、笔走了回到默认样子；若笔点顺手改写开关，
     * 「默认」就不再是用户选的那个了。
     */
    onPenTap: (() -> Unit)? = null,
    /**
     * 忽略「手写模式」开关，**本字段强制走手写态**。
     *
     * 用途：笔点在某个作答目标上时，把该目标临时变成书写区 ——
     * 开关表示的是「没有笔时的默认输入方式」，笔来了就该能手写，
     * 但不该因为这个动作去改写用户的默认设置。
     */
    forceHandwriting: Boolean = false,
    /**
     * 该空标准答案中「已输入内容之后的下一个期望字符」；null = 不生僻字守卫。
     * 由调用方计算（`answer.getOrNull(当前输入长度)`），透传给 [HandwritingPanel]。
     */
    expectedNextChar: Char? = null,
    /**
     * 英文默写的「答案先验」：本空从当前位置起的剩余标准答案（如已写「L」则为「ove」）；
     * 透传给 [HandwritingPanel] 做容错匹配（详见面板参数注释）。null = 不启用。
     */
    expectedWord: String? = null
) {
    // ⚠️⚠️ 这里**不能**只读 HandwritingRecognizer.isNativeAvailable 就完事 —— 那是一个死锁：
    //
    // native 库是在 getInstance() 首次被调用时才 System.loadLibrary 的，而 getInstance 只在
    // HandwritingPanel 组合时才会被调到；面板又只在「已经切到手写」之后才组合。
    // ⇒ 冷启动进程里这个标志永远是 false：
    //    「手写/键盘」胶囊永远不渲染、笔点输入框也不生效 —— 手写功能从入口就不可达。
    //   （真机第一轮验收抓到的就是这个：截图里输入框下面连胶囊都没有。）
    //
    // 所以这里负责在字段出现时触发一次初始化。loadLibrary 是 dlopen 一块 10MB 的 .so，
    // 放 IO 线程，避免主线程合成时卡一下；模型与字符表仍是真正切到手写时才懒加载。
    val libReadyInit = HandwritingRecognizer.isNativeAvailable
    var libReady by remember { mutableStateOf(libReadyInit) }
    val engineContext = LocalContext.current
    LaunchedEffect(Unit) {
        if (!libReady) {
            libReady = withContext(Dispatchers.IO) {
                HandwritingRecognizer.getInstance(engineContext)
                HandwritingRecognizer.isNativeAvailable
            }
        }
    }
    // 该文种的模型是否可用：英文模型可能未随包分发（assets 缺失），此时英文空只能走键盘
    val scriptModelAvailable = if (script == HandwritingScript.Chinese) true
    else remember(script) { HandwritingRecognizer.hasLatinModel(engineContext) }
    val engineAvailable = libReady && scriptModelAvailable
    val handwritingMode by AppPrefs.handwritingInputEnabledFlow.collectAsStateWithLifecycle()

    // 笔点回调必须兜住：`pointerInput` 只在 key 变化时重启，直接捕获会冻成
    // 「首次组合那次」的实例（与手写面板里踩过的回调陈旧坑同源）。
    val cbPenTap = rememberUpdatedState(onPenTap)

    // 手写态显示区（内嵌可编辑文本）的焦点入口：
    // 手指 tap ⇒ requestFocus ⇒ 系统键盘弹出（与键盘态 HintOutlinedField 同一条链路）。
    val answerFocusRequester = remember { FocusRequester() }

    val useHandwriting = (handwritingMode || forceHandwriting) && enabled && allowHandwritingSwitch && engineAvailable
    // 紧凑模式只在「手写模式开着 + 可用」时生效；否则一律走常规输入框
    val compactHandwriting = handwritingCompact && useHandwriting

    // 「笔点输入框 → 切手写」只对**完整输入框**生效。
    // 紧凑卡（词卡模式的非当前目标）有自己的点击处理（激活为目标），
    // 若在这里也拦截，会出现「笔点了 A 卡、书写板却出现在 B 卡」的错位。
    val penToHandwriting = engineAvailable && enabled && allowHandwritingSwitch && !handwritingCompact

    Column(modifier = modifier.animateContentSize()) {
        // ── 输入区：键盘 / 手写（完整书写板）/ 手写（紧凑只读行）──
        if (useHandwriting && !compactHandwriting) {
            // 手写态（当前作答目标）：屏上显示已写内容 + 面板本身
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 光标位置控制：BasicTextField(String) 的内部 selection 不可控，
                        // 真机出现「聚焦后光标停在开头闪」；改用受控 TextFieldValue ——
                        // 聚焦与外部更新（手写上屏 / 退格）时光标都落在串尾（下一个待输入处），
                        // 点文字中间时由 BasicTextField 自身命中更新 selection。
                        var tfv by remember {
                            mutableStateOf(TextFieldValue(value, TextRange(value.length)))
                        }
                        LaunchedEffect(value) {
                            if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // ── 笔点不弹键盘 ──
                                // 手写态下显示区是「已写内容」，笔点它不应触发 IME 手写模式
                                // （真机日志证实：系统会请求 IME 手写模式，而多数 IME 静默失败）。
                                // 书写板就在下方，笔点这里只吞掉事件、不做任何事。
                                .penTapToHandwriting(enabled = enabled) { /* 笔点无操作 */ }
                                // ── 手指 tap → 唤出系统键盘，即时打字增删改 ──
                                // 与「手写/键盘」胶囊（全局默认方式）互不干扰：这里只把焦点
                                // 交给内嵌的 BasicTextField，键盘打字与手写上屏共用同一个 value。
                                // 点文字由 BasicTextField 自身命中（光标落在点击处）；
                                // 点文字以外的空白则聚焦（光标在串尾，适合补写）。
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    // 点空白：光标置尾再聚焦（补写场景），而不是默认留在开头
                                    tfv = tfv.copy(selection = TextRange(tfv.text.length))
                                    answerFocusRequester.requestFocus()
                                }
                        ) {
                            BasicTextField(
                                value = tfv,
                                onValueChange = {
                                    tfv = it
                                    onValueChange(it.text)
                                },
                                enabled = enabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(answerFocusRequester),
                                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = singleLine,
                                maxLines = maxLines,
                                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (value.isEmpty()) {
                                            Text(
                                                placeholder.ifBlank { "用笔在下方书写" },
                                                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        if (value.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            BackspaceButton(onClick = { onValueChange(value.dropLast(1)) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HandwritingPanel(
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.coerceAtLeast(140.dp)),
                enabled = enabled,
                autoCommit = true,
                // 识别出的内容追加到末尾。⚠️ 回调是**按批**给的（连写会一次识别出多个字）：
                // 必须把整批一次性拼上进 `value`，绝不能在同一个帧里分多次写 ——
                // 这里的 `value` 是**组合时的快照**，同帧读多少次都是同一个值，
                // 分多次写会互相覆盖、只剩最后一个字（真机现象「连写三字只出一个」）。
                onCharsPicked = { chars -> onValueChange(value + chars.joinToString("")) },
                // 「划掉撤回」：删掉刚上屏的那个字（删到空串即为 no-op，不会越界）
                onUndoLast = { onValueChange(value.dropLast(1)) },
                script = script,
                expectedNextChar = expectedNextChar,
                // 答案这个位置是汉字 ⇒ 禁用拉丁回退（用户要求：答案只含汉字时不做英文识别，
                // 防止 A/5 这类拉丁候选混进候选条诱导误点）；待填字是拉丁字符（「A 股」的 A）
                // 或无待填字（null，自由书写）时保留回退救援。
                allowLatinFallback = expectedNextChar?.let {
                    HandwritingScript.isLatinInputChar(it)
                } != false,
                expectedWord = expectedWord
            )
        } else if (compactHandwriting) {
            // 手写态（非当前目标）：只呈现已写内容，把纵向空间让给真正的书写板
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value.ifEmpty { placeholder.ifBlank { "点这里作答" } },
                    style = textStyle.copy(
                        color = if (value.isEmpty())
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // ── 笔点输入框 = 直接进入手写 ──
                    // 拿着笔的人点「输入答案」，期望的是写字；这时弹软键盘是最差路径
                    // （真机日志证实：系统会去请求 IME 手写模式，而多数 IME 不支持，静默失败）。
                    // 在 Initial pass（早于输入框的 Main pass）拦下**笔**的按下，切到书写板并
                    // 消费整段手势 —— 输入框拿不到焦点，键盘不会弹。
                    // 手指点不受影响，照常弹键盘。
                    .then(
                        if (penToHandwriting) Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = down.changes.firstOrNull() ?: return@awaitEachGesture
                                // 已经被更外层处理过（例如练习页挂在整张作答卡/整段输入区上的
                                // `penTapToHandwriting`）就不要再切一次模式 ——
                                // 否则会同时出现「底部书写弹层 + 内嵌书写板」两个书写区。
                                if (pressed.isConsumed) return@awaitEachGesture
                                if (pressed.type != PointerType.Stylus || !enabled) {
                                    // 手指 / 鼠标 / 不可用态：不消费，让输入框照常获得焦点
                                    return@awaitEachGesture
                                }
                                // 有接管者就交给它（练习页会弹底部书写板，且**不改**默认输入方式）；
                                // 没有则退化为旧行为：直接切到手写模式
                                val tap = cbPenTap.value
                                if (tap != null) tap() else AppPrefs.handwritingInputEnabled = true
                                // 吞掉这一笔的后续事件，直到抬起
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    e.changes.forEach { it.consume() }
                                    if (e.changes.all { it.changedToUp() }) break
                                }
                            }
                        } else Modifier
                    )
            ) {
                HintOutlinedField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = placeholder,
                hintChar = hintChar,
                enabled = enabled,
                isError = isError,
                singleLine = singleLine,
                maxLines = maxLines,
                minHeight = minHeight,
                textStyle = textStyle,
                imeAction = imeAction
            )
            }
        }

        // ── 模式切换行 ──
        // 紧凑模式（非当前作答目标）不渲染：十几个空各挂一个「手写/键盘」胶囊，
        // 既是噪音，也会把每张卡再撑高一行。
        if (allowHandwritingSwitch && engineAvailable && !compactHandwriting) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSwitchChip(
                    handwriting = useHandwriting,
                    enabled = enabled,
                    onToggle = {
                        AppPrefs.handwritingInputEnabled = !useHandwriting
                    }
                )
            }
        }
    }
}

/**
 * 单个挖空处「就地书写」面板（配合遮块使用）。
 *
 * 与 [AnswerInputField] 的区别：这里产出的是**该空的最终答案**（一次一个字地写，
 * 直到用户点「完成」），而不是往长文本里连续追加。用于遮块点开后就地书写。
 */
@Composable
fun InlineHandwritingAnswer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    script: HandwritingScript = HandwritingScript.Chinese,
    /** 下一个期望字符（生僻字守卫，同 [AnswerInputField]）；null = 不启用 */
    expectedNextChar: Char? = null,
    /** 英文默写的「答案先验」（剩余标准答案）；null = 不启用。 */
    expectedWord: String? = null
) {
    Column(modifier = modifier.animateContentSize()) {
        if (value.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    BackspaceButton(onClick = { onValueChange(value.dropLast(1)) })
                    if (onDone != null) {
                        Spacer(Modifier.width(4.dp))
                        AppIcon(
                            kind = AppIconKind.Check,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableNoRipple { onDone() },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        HandwritingPanel(
            modifier = Modifier.fillMaxWidth(),
            autoCommit = true,
            // 按批追加（同上面的说明：分多次写会因快照陈旧互相覆盖）
            onCharsPicked = { chars -> onValueChange(value + chars.joinToString("")) },
            onUndoLast = { onValueChange(value.dropLast(1)) },
            script = script,
            expectedNextChar = expectedNextChar,
            // 汉字待填字 ⇒ 禁用拉丁回退（与上方内嵌面板同一规则）
            allowLatinFallback = expectedNextChar?.let {
                HandwritingScript.isLatinInputChar(it)
            } != false,
            expectedWord = expectedWord
        )
    }
}

/** 输入方式小胶囊：`[✎ 手写]` / `[⌨ 键盘]` */
@Composable
private fun ModeSwitchChip(
    handwriting: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (handwriting) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickableNoRipple(enabled = enabled) { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                kind = if (handwriting) AppIconKind.Stylus else AppIconKind.Edit,
                modifier = Modifier.size(16.dp),
                tint = if (handwriting) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (handwriting) "手写" else "键盘",
                style = MaterialTheme.typography.labelMedium,
                color = if (handwriting) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 退格按钮（手写态没有软键盘，需要一个明确的删除入口） */
@Composable
private fun BackspaceButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickableNoRipple { onClick() }
    ) {
        Text(
            "⌫",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * 无涟漪点击：这些是绘制/输入辅助控件，涟漪会干扰书写区的视觉干净度。
 *
 * 统一加了**掌托守卫**：手写态下退格、切模式、确认这三个控件就贴在书写板边上，
 * 用户握笔时手掌边缘极容易蹭到——真实反馈里「写着写着答案被删了一个字」
 * 就是退格键被掌托点中的结果。守卫在笔书写期间直接丢弃这次点击。
 */
private fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { if (!suppressAsPalmMisTouch()) onClick() }
    )
}
