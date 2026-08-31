// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.LiquidGlassPopupBackdrop
import com.ilyskyo.blancall.ui.common.PopupGlassPlate
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import kotlin.math.max

// ── 弹窗尺寸常量 ──
private val PopupEstimateDp = 310.dp
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
// 容器进出场与 TouchRevealTransition（全局数据/我的文章页转场）同款节奏：
// 进入 320ms FastOutSlowInEasing，退出 180ms LinearOutSlowInEasing，
// 缩放 0.8 → 1，无 spring 弹性拖尾，手感顺滑统一。
private val ContainerEnterTween = tween<Float>(320, easing = FastOutSlowInEasing)
private val ContainerExitTween = tween<Float>(180, easing = LinearOutSlowInEasing)
private val PressSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh
)

/**
 * 自适应模式选择弹窗（生产级动效版）。
 *
 * 设计目标：
 * 1. **统一动画时钟**：容器 + 项共享同一进度源；容器缩放采用与
 *    TouchRevealTransition（全局数据/我的文章页转场）同款的 tween 曲线
 * 2. **平滑切换**：按钮 → 面板（PPT Morph 效果）：以按钮中心为锚点，
 *    宽、高分别从按钮尺寸插值到面板尺寸，按钮平滑"长成"选项卡
 * 3. **错峰顺滑**：进度分段错峰，三项像被同一只手依次"摆"出来，无跳变
 * 4. **背景遮罩 fade**：220ms 同步淡入，让内容"从背后抬起"而非"凭空出现"
 * 5. **按压反馈**：每项按下时 0.97 缩放 + 高 stiffness spring，跟手即响应
 * 6. **退场：原路收回**：180ms 反向缩放回按钮中心（transformOrigin 锚点），
 *    末段快速淡出，无生硬 fadeOut
 */
@Composable
fun AdaptiveModePicker(
    visible: Boolean,
    anchorRect: Rect?,
    onDismiss: () -> Unit,
    onModeSelected: (BlancallMode) -> Unit
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
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val popupEstimatePx = with(density) { PopupEstimateDp.toPx() }
    val minMarginPx = with(density) { PopupMinMarginDp.toPx() }
    val popupWidthPx = with(density) { PopupWidthDp.toPx() }
    val arrowGapPx = with(density) { PopupArrowGapDp.toPx() }

    val isDark = isBlancallDark()

    val effectiveAnchor = anchorRect ?: Rect(Offset.Zero, Offset(screenHeightPx / 2, screenHeightPx / 2))
    val spaceAbove = effectiveAnchor.top
    val spaceBelow = screenHeightPx - effectiveAnchor.bottom
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
        AdaptiveBottomSheetPicker(onDismiss = onDismiss, onModeSelected = onModeSelected)
        return
    }

    val centerX = (effectiveAnchor.left + effectiveAnchor.right) / 2
    var popupX = centerX - popupWidthPx / 2
    if (popupX + popupWidthPx > screenWidthPx - minMarginPx) {
        popupX = screenWidthPx - popupWidthPx - minMarginPx
    }
    if (popupX < minMarginPx) {
        popupX = minMarginPx
    }

    val popupY = when (expandDirection) {
        ExpandDirection.DOWN -> effectiveAnchor.bottom + arrowGapPx
        ExpandDirection.UP -> effectiveAnchor.top - popupEstimatePx - arrowGapPx
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

    // 弹窗生命周期：visible → true 启动进入；false 先播完退场动画再移除
    LaunchedEffect(visible) {
        if (!visible) {
            // 反向收回：180ms 缩回按钮中心（transformOrigin 锚在按钮），利落退场
            isExiting = true
            containerProgress.animateTo(0f, ContainerExitTween)
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
        containerProgress.animateTo(1f, ContainerEnterTween)
    }

    val cp = containerProgress.value

    // 页面内覆盖层渲染：不创建独立窗口。
    // 点击卡片外部区域 → 关闭选项卡；卡片自身交互正常
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 卡片容器（液态玻璃，无额外阴影预留）
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
                .graphicsLayer {
                        scaleX = morph.startScaleX + (1f - morph.startScaleX) * cp
                        scaleY = morph.startScaleY + (1f - morph.startScaleY) * cp
                        alpha = if (contentReady) {
                            if (isExiting && cp < 0.35f) (cp / 0.35f).coerceIn(0f, 1f) else cp
                        } else 0f
                        transformOrigin = TransformOrigin(morph.originX, morph.originY)
                    }
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
    onModeSelected: (BlancallMode) -> Unit
) {
    GlassModalBottomSheet(
        onDismissRequest = onDismiss
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
    onModeSelected: (BlancallMode) -> Unit
) {
    // 项进入窗口：[项进入起点, 项完全显示]。三段错峰但用同一个弹性曲线
    data class ModeEntry(
        val emoji: String,
        val label: String,
        val sublabel: String,
        val mode: BlancallMode
    )
    val entries = remember {
        listOf(
            ModeEntry("📝", "句子挖空", "从句/半句/整句 — 理解式记忆", BlancallMode.SENTENCE),
            ModeEntry("🔤", "字词挖空", "1-3字词精准填空 — 细节记忆", BlancallMode.WORD),
            ModeEntry("✍️", "反向默写", "段落打散默写 — 整段还原", BlancallMode.REVERSE)
        )
    }
    // ── 错峰窗口：把 containerProgress 切成 4 段不重叠区间，每段 0.25 ──
    // 标题：[0.0, 0.25] → 容器刚冒出时立刻出现
    // 项 0：[0.25, 0.5] → 标题后紧跟第一项（"句子挖空"）
    // 项 1：[0.5, 0.75] → "字词挖空"
    // 项 2：[0.75, 1.0] → "反向默写"
    // 不重叠 + 线性插值 → 在容器 tween 曲线节奏下产生"逐个滑出"的视觉
    val steps = entries.size + 1  // 标题 + entries
    fun itemProgress(index: Int): Float {
        // 退出时所有项保持完整（alpha=1），由容器整体缩放 + 末段淡出统一收场，
        // 避免内容逐项消失的"散架感"，与 TouchReveal 整体缩回手感一致。
        if (isExiting) return 1f
        // 把总进度 [0, 1] 切成 steps 段，每段长度一致
        val seg = 1f / steps
        val start = index * seg
        val end = start + seg
        return ((containerProgress - start) / (end - start)).coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 6.dp)
    ) {
        // 标题：index 0，最先出现
        val titleProgress = itemProgress(0)
        Text(
            text = "选择练习模式",
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
        entries.forEachIndexed { i, entry ->
            val p = itemProgress(i + 1)
            val slideFromTop = expandDirection == ExpandDirection.DOWN || expandDirection == ExpandDirection.BOTTOM_SHEET
            val itemSlide = (1f - p) * if (slideFromTop) 14f else -14f
            PressableModeItem(
                progress = p,
                itemSlideY = itemSlide,
                emoji = entry.emoji,
                label = entry.label,
                sublabel = entry.sublabel,
                onClick = { onModeSelected(entry.mode) }
            )
        }
    }
}

/**
 * 单项：进度驱动的入场 + 按下时的反馈。
 * 使用 graphicsLayer 而非 AnimatedVisibility，避免多 spec 叠加造成的节奏割裂。
 */
@Composable
private fun PressableModeItem(
    progress: Float,
    itemSlideY: Float,
    emoji: String,
    label: String,
    sublabel: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 按压：0.97 缩放，高 stiffness spring 跟手
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = PressSpring,
        label = "pressScale"
    )

    val itemShape = RoundedCornerShape(14.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .graphicsLayer {
                // 入场进度与按压进度合成：press 在 progress=1 后才介入
                val entryScale = 0.94f + 0.06f * progress
                val totalScale = entryScale * pressScale
                scaleX = totalScale
                scaleY = totalScale
                translationY = itemSlideY
                alpha = progress
            }
            // 描边：极淡的轮廓线，只做边界暗示，不抢视线
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (isBlancallDark()) 0.10f else 0.06f
                ),
                shape = itemShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = itemShape,
        color = if (isPressed)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
        // 不透明实色：与半透明玻璃背景形成明暗层次（不能改成半透明，否则又糊在一起）
            MaterialTheme.colorScheme.surface,
        tonalElevation = if (isPressed) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 按钮坐标包装器：捕获按钮屏幕坐标，触发弹窗。
 */
@Composable
fun ModePickerAnchor(
    showPicker: Boolean,
    onDismiss: () -> Unit,
    onModeSelected: (BlancallMode) -> Unit,
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
