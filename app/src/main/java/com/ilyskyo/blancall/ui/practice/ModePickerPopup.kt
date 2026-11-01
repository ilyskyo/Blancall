// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.animateColorAsState
import com.ilyskyo.blancall.ui.common.LiquidGlassPopupBackdrop
import com.ilyskyo.blancall.ui.common.PopupGlassPlate
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlin.math.max
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 模式选择结果：基础三模式，或「自定义挖空」（不带 BlancallMode，由调用方导航到
 * 配置列表页 custom_cloze_list 走配置选择流程）。
 */
sealed interface PickerSelection {
    data class Base(val mode: BlancallMode) : PickerSelection
    data object Custom : PickerSelection
}

// ── 弹窗尺寸常量 ──
// 高度估算按内容动态计算：标题区 64dp + 每个模式项 56dp（当前 4 项 ≈ 288dp）。
// 估算宁可偏大不可偏小：偏小会导致 DOWN 方向弹出后底部越过屏幕（真机翻车点）。
private val PopupHeaderEstimateDp = 64.dp
private val PopupItemEstimateDp = 56.dp
private val PopupItemCount = 4
private val PopupEstimateDp get() = PopupHeaderEstimateDp + PopupItemEstimateDp * PopupItemCount
private val PopupWidthDp = 320.dp
private val PopupMinMarginDp = 16.dp
private val PopupArrowGapDp = 4.dp

// 平滑切换（Morph）参数：按钮 → 面板的形变起点与锚点
private data class MorphTransform(
    val startScaleX: Float,
    val startScaleY: Float,
    val originX: Float,
    val originY: Float
)

// ── 动画规格 ──
// 原先这里躺着三个 private 常量（320/180 两套 tween + 一个 PressSpring），
// 注释说要与「当时那套手写转场」（TouchRevealTransition，已删）保持一致，却复制了一份私有的数字 ——
// 词表的意义就在于「同一件事只有一个数」，所以三条都改成从 Motion 取：
// 容器进出场用 Motion.reveal() / Motion.revealOut()，按压用 Motion.press()。

/**
 * 自适应模式选择弹窗（生产级动效版）。
 *
 * 设计目标：
 * 1. **统一动画时钟**：容器 + 项共享同一进度源；容器进/退场的推进谱取自全局词表
 *    （[com.ilyskyo.blancall.ui.common.Motion.reveal] / [com.ilyskyo.blancall.ui.common.Motion.revealOut]，
 *    两条都 NoBouncy —— 这里推的是 0..1 的进度值，过冲越过 1 会让换算出的缩放大于目标尺寸）
 * 2. **平滑切换**：按钮 → 面板（PPT Morph 效果）：以按钮中心为锚点，
 *    宽、高分别从按钮尺寸插值到面板尺寸，按钮平滑"长成"选项卡
 * 3. **错峰顺滑**：进度分段错峰，三项像被同一只手依次"摆"出来，无跳变
 * 4. **背景遮罩 fade**：220ms 同步淡入，让内容"从背后抬起"而非"凭空出现"
 * 5. **按压反馈**：缩放取词表的 Container 档（`Motion.Scale.container`），
 *    并且缩放、底色、tonalElevation 三件都由**同一个** pressT 进度派生 —— 同一拍，
 *    不再出现「东西沉下去了、光没跟上」（改造前底色与 elevation 是硬切）
 * 6. **退场：原路收回**：反向缩放回按钮中心（transformOrigin 锚点），
 *    末段快速淡出，无生硬 fadeOut
 */
@Composable
fun AdaptiveModePicker(
    visible: Boolean,
    anchorRect: Rect?,
    onDismiss: () -> Unit,
    onModeSelected: (PickerSelection) -> Unit
) {
    // 内部可见状态：退场动画播完才真正移除弹窗。
    // 不能用 visible 直接控制（visible=false 立即 return 会让 Popup 瞬间消失，
    // 退出动画根本没机会执行 —— "点取消缩回去"因此失效）。
    var isShowing by remember { mutableStateOf(visible) }

    // 页面内渲染无独立窗口拦截返回键：选项卡显示时系统返回 → 关闭选项卡
    BackHandler(enabled = isShowing) { onDismiss() }

    // 必须注册在 return 之前：visible 变 true 时恢复渲染
    LaunchedEffect(visible) {
        if (visible) isShowing = true
    }

    if (!isShowing) return

    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val screenHeightPx = containerSize.height.toFloat()
    val screenWidthPx = containerSize.width.toFloat()
    val popupEstimatePx = with(density) { PopupEstimateDp.toPx() }
    val minMarginPx = with(density) { PopupMinMarginDp.toPx() }
    val popupWidthPx = with(density) { PopupWidthDp.toPx() }
    val arrowGapPx = with(density) { PopupArrowGapDp.toPx() }

    val isDark = isBlancallDark()

    // 覆盖层自身的位置与尺寸（onGloballyPositioned 上报，见下方覆盖层 Box）。
    // ⚠️ 锚点来自 positionInWindow（窗口坐标系），而覆盖层绘制在自己的局部坐标系中 ——
    // 平板（左侧导航栏）或页面内偏移下两者不同源，直接用窗口坐标定位会整体错位、面板画到
    // 屏幕外（真机反馈：右列卡片的「练习」弹出面板出屏）。以下统一换算到覆盖层局部坐标。
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val localW = if (overlaySize.width > 0) overlaySize.width.toFloat() else screenWidthPx
    val localH = if (overlaySize.height > 0) overlaySize.height.toFloat() else screenHeightPx

    // 无锚点时的伪锚点：覆盖层横向中心、纵向 38% 处，向下弹出居中面板。
    // 「保存成功 → 开始练习」路径没有可锚定的按钮，**不能沿用上次练习按钮的旧锚点**：
    // 那篇文章所在的行可能在屏幕外，会把选项卡弹到屏幕外（用户反馈「点了开始练习没有弹出」）
    val effectiveAnchor = anchorRect?.let { a ->
        Rect(
            a.left - overlayOrigin.x,
            a.top - overlayOrigin.y,
            a.right - overlayOrigin.x,
            a.bottom - overlayOrigin.y
        )
    } ?: Rect(
        Offset(localW / 2, localH * 0.38f),
        Offset(localW / 2, localH * 0.38f)
    )
    val spaceAbove = effectiveAnchor.top
    val spaceBelow = localH - effectiveAnchor.bottom
    val neededSpace = popupEstimatePx + minMarginPx
    val canExpandUp = spaceAbove >= neededSpace
    val canExpandDown = spaceBelow >= neededSpace

    val expandDirection: ExpandDirection = when {
        canExpandUp && canExpandDown -> if (spaceAbove > spaceBelow) ExpandDirection.UP else ExpandDirection.DOWN
        canExpandUp -> ExpandDirection.UP
        canExpandDown -> ExpandDirection.DOWN
        else -> ExpandDirection.BOTTOM_SHEET
    }

    if (expandDirection == ExpandDirection.BOTTOM_SHEET) {
        // 底部面板的退场由 GlassModalBottomSheet 自己播完（M3 hide + scrim），
        // 宿主等它给出回执后才撤掉组合 —— 旧实现 visible=false 立刻 isShowing=false，
        // 面板和遮罩在同帧消失，等于没有退场。
        AnimatedOverlayPresence(
            visible = visible,
            onExitFinished = { isShowing = false },
        ) {
            AdaptiveBottomSheetPicker(onDismiss = onDismiss, onModeSelected = onModeSelected)
        }
        return
    }

    val centerX = (effectiveAnchor.left + effectiveAnchor.right) / 2
    var popupX = centerX - popupWidthPx / 2
    if (popupX + popupWidthPx > localW - minMarginPx) {
        popupX = localW - popupWidthPx - minMarginPx
    }
    if (popupX < minMarginPx) {
        popupX = minMarginPx
    }

    // 上下双向钳制：锚点可能位于屏幕之外（首页卡片可拖动，锚定行的位置可超出窗口），
    // 不钳制会把选项卡整块画到屏幕外 —— 点了「开始练习」看不到弹窗（用户反馈根因之一）
    val maxPopupTop = (localH - popupEstimatePx - minMarginPx).coerceAtLeast(minMarginPx)
    val popupY = when (expandDirection) {
        ExpandDirection.DOWN -> (effectiveAnchor.bottom + arrowGapPx).coerceIn(minMarginPx, maxPopupTop)
        ExpandDirection.UP -> (effectiveAnchor.top - popupEstimatePx - arrowGapPx).coerceIn(minMarginPx, maxPopupTop)
        else -> effectiveAnchor.bottom
    }

    // ── 统一动画时钟 ──
    // 一个 Animatable 驱动整组（容器 + 项），避免 tween 与 spring 混用造成节奏割裂
    val containerProgress = remember { Animatable(0f) }

    // 内容首帧布局就绪标记：Popup 窗口创建 + 内容完成首次布局后才播放进入动画。
    // 就绪前 alpha 保持 0（完全透明），避免首帧出现空矩形/透明矩形闪烁。
    var contentReady by remember { mutableStateOf(false) }

    // 卡片实际尺寸：布局就绪后用于计算"按钮 → 面板"的形变比例
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // 平滑切换参数：按钮变身面板（PPT 平滑切换效果）
    // 起点 = 按钮的宽高比例，缩放锚点 = 按钮中心（可能位于面板坐标系之外）
    var morph by remember { mutableStateOf(MorphTransform(0.05f, 0.05f, 0.5f, 0.5f)) }

    // 退出动画进行中：末段快速淡出，避免缩回按钮时与按钮图案重叠产生残影
    var isExiting by remember { mutableStateOf(false) }

    // 弹窗生命周期（仅 UP/DOWN 浮窗路径可达；底部面板的关闭复位见上方 closeEffect）：
    // visible → true 启动进入；false 先播完退场动画再移除
    LaunchedEffect(visible) {
        if (!visible) {
            // 反向收回：180ms 缩回按钮中心（transformOrigin 锚在按钮），利落退场
            isExiting = true
            containerProgress.animateTo(0f, Motion.revealOut())
            isShowing = false
            return@LaunchedEffect
        }
        isExiting = false
        containerProgress.snapTo(0f)
        // 1) 等待内容完成首次布局（onSizeChanged 置位 contentReady）
        while (!contentReady) {
            withFrameNanos { }
        }
        // 2) 再等两帧：确保 Popup 窗口完成首帧合成（图层完全就绪）
        repeat(2) { withFrameNanos { } }
        // 3) 计算平滑切换形变参数：宽、高分别从按钮尺寸插值到面板尺寸，
        //    锚点 = 按钮中心（按钮扁 → 面板高，视觉上按钮"长成"面板）
        val popupW = cardSize.width.toFloat().coerceAtLeast(1f)
        val popupH = cardSize.height.toFloat().coerceAtLeast(1f)
        val btnW = (effectiveAnchor.right - effectiveAnchor.left).coerceAtLeast(1f)
        val btnH = (effectiveAnchor.bottom - effectiveAnchor.top).coerceAtLeast(1f)
        morph = MorphTransform(
            startScaleX = (btnW / popupW).coerceIn(0.02f, 1f),
            startScaleY = (btnH / popupH).coerceIn(0.02f, 1f),
            originX = ((effectiveAnchor.left + effectiveAnchor.right) / 2f - popupX) / popupW,
            originY = ((effectiveAnchor.top + effectiveAnchor.bottom) / 2f - popupY) / popupH
        )
        containerProgress.animateTo(1f, Motion.reveal())
    }

    val cp = containerProgress.value

    // ── 阴影和透明度必须分家 ──
    //
    // 改造前 alpha 写在**挂着 .shadow() 的那一层**上，两个症状同一个根：
    // ① alpha < 1 会把这一整块推进离屏合成缓冲，elevation 投影在缓冲里按**矩形层边界**重算，
    //    圆角卡片底下于是浮出一个直角外框（BackButton.kt 里记录过同一现象，2026-10-09 真机截图）；
    // ② 投影也吃这条 alpha，卡片本体先看得见、影子要等 alpha 爬够才显形，
    //    读起来就是「阴影慢半拍、然后啪一下出现」。
    // 所以：透明度下沉到卡片本体（下面的内层 Box），阴影这一层只留**缩放**——
    // 单纯的变换不需要离屏层，投影就能老老实实贴着圆角轮廓、和卡片同一帧长出来。
    val cardAlpha = if (contentReady) {
        if (isExiting && cp < 0.35f) (cp / 0.35f).coerceIn(0f, 1f) else cp
    } else 0f
    // 影子跟着进度长（cp 本身是弹簧，前段涨得快），退场再原路收回去：
    // 不写死 18dp —— 面板还小的时候底下压着一圈全尺寸暗影，看起来像没动
    val shadowLift = 18.dp * cp.coerceIn(0f, 1f)

    // 页面内覆盖层渲染：不创建独立窗口。
    // 点击卡片外部区域 → 关闭选项卡；卡片自身交互正常
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayOrigin = coords.positionInWindow()
                overlaySize = coords.size
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // ── 浮层怎么「浮」起来：靠投影，不靠遮罩 ──
        //
        // 试过两个方向：
        // ① 只做近不透明的白面板 —— 叠在同色页面上糊成一张纸，边界只剩一条极淡的边；
        // ② 加页面遮罩 —— 有效，但本 App 没有 edge-to-edge，各页面的组合根都在系统栏之内，
        //    fillMaxSize 只拿到「状态栏下沿到导航栏上沿」这块内容区，上下各留一条没被压暗的白边
        //    （用户截图指出）。要盖满得按物理屏幕尺寸铺 + 负偏移硬顶出去，依赖祖先不裁剪，脆。
        // 所以最终走 ③：**面板自己投影**。阴影只跟着面板走，与屏幕尺寸、系统栏、祖先裁剪全都无关，
        // 也不会像遮罩那样把整页压灰。elevation 用 18dp：在近白页面上刚好托起一层轮廓，
        // 又不到「悬浮球」那种重。深色下阴影本来就看不见，靠面板自身比背景亮一档（#2C2C2E on #1C1C1E）区分。

        // 卡片容器（液态玻璃 + 投影）
        Box(
            modifier = Modifier
                .widthIn(max = PopupWidthDp)
                .offset { IntOffset(popupX.toInt(), max(0, popupY.toInt())) }
        ) {
        // 弹窗卡片：圆角裁切 + 液态玻璃背景
        // 形变动画由外层 graphicsLayer 完成
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 这一层**不许再写 alpha**：一写整块进离屏缓冲，下面的投影就被按矩形重算，
                // 直角外框 + 影子迟到会一起回来（见上方 cardAlpha / shadowLift 的说明）
                .graphicsLayer {
                        scaleX = morph.startScaleX + (1f - morph.startScaleX) * cp
                        scaleY = morph.startScaleY + (1f - morph.startScaleY) * cp
                        transformOrigin = TransformOrigin(morph.originX, morph.originY)
                    }
                // 阴影放在 graphicsLayer 之内：入场是「从锚点长出来」的缩放，
                // 阴影必须跟着一起缩，否则面板还小的时候底下已经压着一圈全尺寸的暗影。
                // clip = false —— 默认会把自己裁掉，阴影就没了。
                // ambient 压轻、spot 留在下侧：要的是「底部托起一层」，不是四面发光。
                .shadow(
                    elevation = shadowLift,
                    shape = RoundedCornerShape(20.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.22f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            if (it.width > 0) {
                                cardSize = it
                                if (!contentReady) contentReady = true
                            }
                        }
                        // 透明度只作用在卡片本体（玻璃 + 内容），与上面那层投影彻底脱钩
                        .graphicsLayer { alpha = cardAlpha }
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    // ── 1) 玻璃底：液态玻璃（API33+）或兜底玻璃板（低版本）──
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        LiquidGlassPopupBackdrop(
                            isDark = isDark,
                            cornerPx = with(density) { 20.dp.toPx() },
                            modifier = Modifier.matchParentSize()
                        )
                    } else {
                        PopupGlassPlate(
                            isDark = isDark,
                            glassActive = false,
                            modifier = Modifier.matchParentSize()
                        )
                    }

                    // ── 2) 控件内容：绘于玻璃之上（保证文字清晰不被折射模糊）──
                    ModeListContent(
                        expandDirection = expandDirection,
                        containerProgress = cp,
                        isExiting = isExiting,
                        onModeSelected = { onModeSelected(it); onDismiss() }
                    )
                }
            }
        }
    }
}

/** 兑底：ModalBottomSheet 走自己的官方动画，避免重复实现 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptiveBottomSheetPicker(
    onDismiss: () -> Unit,
    onModeSelected: (PickerSelection) -> Unit
) {
    // 外部持有 sheetState：关闭走「双通道」——
    // ① M3 的 onDismissRequest（遮罩点击 / 返回键）；
    // ② 兜底监听 currentValue 回到 Hidden（面板被收起的最终事实）→ 驱动 onDismiss。
    // 无论 M3 回调链是否完整送达，只要面板在视觉上被收起，组合必定被移除，
    // 模态窗口不会残留（残留会吞掉后续所有点击——真机回归 bug 的根因）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }
            .dropWhile { it != SheetValue.Expanded } // 跳过打开前的初始 Hidden（尚未展开）
            .first { it == SheetValue.Hidden }       // 展开后被收起（遮罩点击 / 下滑）→ 关闭
        onDismiss()
    }
    // 兑底关闭通道：hide() 一旦启动（targetValue 离开 Expanded）即通知关闭，不等收起动画完成——
    // 即使 M3 的 onDismissRequest 回调因任何原因未送达，面板也能被确定移除，
    // 模态窗口不会残留（残留会吞掉后续所有点击）
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue }
            .dropWhile { it != SheetValue.Expanded }
            .first { it != SheetValue.Expanded }
        onDismiss()
    }
    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ModeListContent(
            expandDirection = ExpandDirection.BOTTOM_SHEET,
            containerProgress = 1f,
            isExiting = false,
            onModeSelected = { onModeSelected(it); onDismiss() }
        )
    }
}

private enum class ExpandDirection { UP, DOWN, BOTTOM_SHEET }

/**
 * 模式列表内容：接收 containerProgress 0..1，按项索引错峰播放。
 *
 * 项动画：每项有自己的 progress = containerProgress 在 [start, end] 区间内映射。
 * 错峰通过 start 时间点后移实现，所有项共用同一 spring 节奏 → 顺滑统一。
 */
@Composable
private fun ModeListContent(
    expandDirection: ExpandDirection,
    containerProgress: Float,
    isExiting: Boolean,
    onModeSelected: (PickerSelection) -> Unit
) {
    data class ModeEntry(
        val label: String,
        val glyph: ModeGlyph,
        val mode: BlancallMode = BlancallMode.SENTENCE,
        val custom: Boolean = false
    )
    // 只留模式名 + 一枚自绘图形，不带 emoji、不带说明文字：
    // 「句子挖空 / 字词挖空 / 反向默写」本身就是自解释的动词词组，
    // 副标题只是在替用户预先解释一遍，试一次就懂的东西不需要提前讲。
    val entries = listOf(
        ModeEntry(stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
            ModeGlyph.SENTENCE, BlancallMode.SENTENCE),
        ModeEntry(stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
            ModeGlyph.WORD, BlancallMode.WORD),
        ModeEntry(stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse),
            ModeGlyph.REVERSE, BlancallMode.REVERSE),
        ModeEntry(stringResource(com.ilyskyo.blancall.R.string.custom_cloze),
            ModeGlyph.CUSTOM, custom = true)
    )
    // ── 错峰窗口：把 containerProgress 切成 4 段不重叠区间，每段 0.25 ──
    // 标题：[0.0, 0.25] → 容器刚冒出时立刻出现
    // 项 0：[0.25, 0.5] → 标题后紧跟第一项（"句子挖空"）
    // 项 1：[0.5, 0.75] → "字词挖空"
    // 项 2：[0.75, 1.0] → "反向默写"
    // 不重叠 + 线性插值 → 在容器 tween 曲线节奏下产生"逐个滑出"的视觉
    val steps = entries.size + 1  // 标题 + entries
    fun itemProgress(index: Int): Float {
        // 退出时所有项保持完整（alpha=1），由容器整体缩放 + 末段淡出统一收场，
        // 避免内容逐项消失的"散架感"：整体缩回读起来像一个物体在离开，逐项淡出像页面在坏。
        if (isExiting) return 1f
        // 把总进度 [0, 1] 切成 steps 段，每段长度一致
        val seg = 1f / steps
        val start = index * seg
        val end = start + seg
        return ((containerProgress - start) / (end - start)).coerceIn(0f, 1f)
    }

    Column(
        modifier = if (expandDirection == ExpandDirection.BOTTOM_SHEET) {
            // BOTTOM_SHEET：外层 GlassModalBottomSheet 自带 verticalScroll（内容即可滚动），
            // 此处不得再叠加滚动 —— 嵌套垂直滚动会让内层收到无限高约束并直接崩溃
            //（IllegalStateException: Vertically scrollable ... infinity ...，真机闪退根因）
            Modifier.padding(top = 20.dp, bottom = 10.dp)
        } else {
            // UP / DOWN 浮窗：容器自身无滚动，内容在这里滚动（单层，无嵌套）
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 10.dp)
        }
    ) {
        // 标题：index 0，最先出现
        val titleProgress = itemProgress(0)
        Text(
            text = stringResource(com.ilyskyo.blancall.R.string.pick_practice_mode),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    alpha = titleProgress
                    translationY = (1f - titleProgress) * 6f
                }
        )
        Spacer(Modifier.height(14.dp))

        // 项：index 1..3，错峰出现
        //
        // 不铺底色：这个控件长在近白玻璃上，填充只会显透或显闷；分组交给等距排列 +
        // 贯穿的不透明分隔线（理由见 PracticeModeSheets.kt 顶部）。
        ModeOptionGroup {
            entries.forEachIndexed { i, entry ->
                if (i > 0) ModeOptionDivider()
                val p = itemProgress(i + 1)
                val slideFromTop = expandDirection == ExpandDirection.DOWN || expandDirection == ExpandDirection.BOTTOM_SHEET
                val itemSlide = (1f - p) * if (slideFromTop) 6f else -6f
                PressableModeItem(
                    progress = p,
                    itemSlideY = itemSlide,
                    label = entry.label,
                    glyph = entry.glyph,
                    onClick = {
                        onModeSelected(
                            if (entry.custom) PickerSelection.Custom
                            else PickerSelection.Base(entry.mode)
                        )
                    }
                )
            }
        }
    }
}

/**
 * 单项：进度驱动的入场 + 按下时的底纹反馈。
 * 使用 graphicsLayer 而非 AnimatedVisibility，避免多 spec 叠加造成的节奏割裂。
 *
 * **这一行不做缩放**：它和上下邻居是紧贴的，一缩放就会在分组底色上露出四道缝，
 * 看起来像整组被拆开。分组列表的按压反馈交给底纹（见 [ModeOptionRow]），
 * 这是 iOS 设置页的做法，也是唯一不破坏「整组是一块」的做法。
 */
@Composable
private fun PressableModeItem(
    progress: Float,
    itemSlideY: Float,
    label: String,
    glyph: ModeGlyph,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    ModeOptionRow(
        label = label,
        glyph = glyph,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier.graphicsLayer {
            translationY = itemSlideY
            alpha = progress
        }
    )
}

/**
 * 按钮坐标包装器：捕获按钮屏幕坐标，触发弹窗。
 */
@Composable
fun ModePickerAnchor(
    showPicker: Boolean,
    onDismiss: () -> Unit,
    onModeSelected: (PickerSelection) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    var anchorRect by remember { mutableStateOf(Rect.Zero) }

    Box {
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInWindow()
                val size = coordinates.size
                anchorRect = Rect(
                    left = pos.x,
                    top = pos.y,
                    right = pos.x + size.width,
                    bottom = pos.y + size.height
                )
            }
        ) {
            content(Modifier)
        }

        AdaptiveModePicker(
            visible = showPicker,
            anchorRect = anchorRect.takeIf { it != Rect.Zero },
            onDismiss = onDismiss,
            onModeSelected = onModeSelected
        )
    }
}
