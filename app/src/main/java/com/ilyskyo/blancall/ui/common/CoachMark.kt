// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.delay

/**
 * 「使用指引」锚点：把某个组件在窗口里的位置记下来，供 [CoachMark] 定位高亮圈。
 *
 * 用窗口坐标而不是相对父布局的坐标：指引是一个覆盖整屏的 Popup，它只认窗口坐标。
 */
@Stable
class CoachAnchorState {
    var rect by mutableStateOf(Rect.Zero)
        internal set
}

/** 把当前组件的窗口矩形写进 [state]。放在要指的那个元素上即可。 */
fun Modifier.coachAnchor(state: CoachAnchorState): Modifier =
    onGloballyPositioned { state.rect = it.boundsInWindow() }

@Composable
fun rememberCoachAnchor(): CoachAnchorState = remember { CoachAnchorState() }

/** 高亮圈形状：按钮类用圆形，卡片/行类用圆角矩形。 */
enum class CoachShape { Round, Card }

/**
 * 一次性使用指引（CoachMark）。
 *
 * 为什么需要它：本 App 有大量「长按才有」的操作（首页进编辑、列表多选、配置卡菜单、
 * 标题滑动切模式……），而长按没有任何视觉可负担的提示。文字说明只写在帮助页，
 * 而帮助页本身不会被打开 —— 等于这些能力对新用户不存在。
 *
 * 形态：压暗极淡的遮罩 + 目标处一圈描边 + 一次「按下去」的缩放脉冲 + 旁边一张说明卡。
 * 不画箭头、不做聚光灯挖洞：这个 App 的视觉语言是克制，遮罩一旦做成聚光灯就很吵。
 *
 * 一次性语义：`coachKey` 记在 [AppPrefs.coachSeenFlow] 里，看过即不再出现；
 * 设置页「重新播放使用指引」清空该集合即可全部重来。
 *
 * @param anchor   由 [coachAnchor] 记录的目标位置；[Rect.Zero] 表示尚未布局好（不显示）
 * @param enabled  是否允许出现（例如「提示开关打开时才教长按」）
 */
@Composable
fun CoachMark(
    coachKey: String,
    anchor: CoachAnchorState,
    title: String,
    body: String,
    shape: CoachShape = CoachShape.Round,
    enabled: Boolean = true,
    delayMs: Long = 450L,
) {
    val seen by AppPrefs.coachSeenFlow.collectAsState()
    val rect = anchor.rect
    var armed by remember(coachKey) { mutableStateOf(false) }
    // 延迟一拍再亮：目标刚组合时 rect 还是 Zero，且页面入场动画未结束，立刻盖上去会抖
    LaunchedEffect(coachKey, enabled) {
        if (!enabled || coachKey in seen) return@LaunchedEffect
        delay(delayMs)
        armed = true
    }
    if (!armed || !enabled || coachKey in seen || rect == Rect.Zero) return

    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val screenW = with(density) { window.width.toDp() }
    val screenH = with(density) { window.height.toDp() }
    val cardW = (screenW - 32.dp).coerceAtMost(300.dp)

    // 高亮圈：紧贴锚点外缘再外扩一圈留白。
    // 旧版按 `max(w,h) × 1.8` 再夹到 56..96 的正方形 —— 凡是锚点比 96dp 大的接入点
    // （整块列表 / 整张画布），圈都会缩成屏幕正中的一个 96dp 小方块，圈到的是空白，
    // 而不是要长按的那张卡。这里改成「锚点实际尺寸 + 留白」：Card 贴合、Round 取长边成圆。
    val PAD = 10.dp
    val rectLeft = with(density) { rect.left.toDp() }
    val rectTop = with(density) { rect.top.toDp() }
    val rectW = with(density) { rect.width.toDp() }
    val rectH = with(density) { rect.height.toDp() }

    val isRound = shape == CoachShape.Round
    val ringW = if (isRound) (maxOf(rectW, rectH) + PAD * 2).coerceIn(48.dp, 120.dp)
    else (rectW + PAD * 2).coerceAtMost(screenW - 16.dp)
    val ringH = if (isRound) ringW else (rectH + PAD * 2).coerceAtMost(screenH - 16.dp)
    val ringX = (rectLeft - PAD).coerceIn(8.dp, (screenW - ringW - 8.dp).coerceAtLeast(8.dp))
    val ringY = (rectTop - PAD).coerceIn(8.dp, (screenH - ringH - 8.dp).coerceAtLeast(8.dp))
    val anchorCx = ringX + ringW / 2
    val anchorCy = ringY + ringH / 2

    // 说明卡：默认挂在圈下方；放不下翻到上方，再整体夹进屏幕（正文换行多也不会溢出）
    val cardEstH = 156.dp
    val belowY = ringY + ringH + 14.dp
    val placeBelow = belowY + cardEstH <= screenH - 16.dp
    val cardY = (if (placeBelow) belowY else ringY - 14.dp - cardEstH)
        .coerceIn(16.dp, (screenH - cardEstH - 16.dp).coerceAtLeast(16.dp))
    val cardX = (anchorCx - cardW / 2).coerceIn(16.dp, (screenW - cardW - 16.dp).coerceAtLeast(16.dp))

    // 「按下去」脉冲：缩一下再弹回，重复三次；减动效时不动（见 Motion.reduced）
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(coachKey, armed) {
        if (Motion.reduced) return@LaunchedEffect
        repeat(3) {
            // 收缩用 pressAlpha 的 tween（110ms），回弹用 press 的弹簧 —— 都在动效词表里，
            // 不引入任何词表之外的时长/刚度（MotionVocabularyTest 会红）
            pulse.animateTo(0.82f, MotionFade.alpha(MotionFade.pressAlpha))
            pulse.animateTo(1f, Motion.press())
            delay(380)
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, 0),
        properties = PopupProperties(focusable = true),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(screenW, screenH)
                // 轻遮罩：只压一点点，够把注意力引过去，又不至于让页面像弹窗
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { AppPrefs.markCoachSeen(coachKey) }
        ) {
            val ringShape = if (isRound) CircleShape else RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .offset(x = ringX, y = ringY)
                    .size(ringW, ringH)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, ringShape)
            )
            Column(
                modifier = Modifier
                    .offset(x = cardX, y = cardY)
                    .width(cardW)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(16.dp),
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { AppPrefs.markCoachSeen(coachKey) }) {
                        Text(
                            stringResource(R.string.coach_got_it),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
