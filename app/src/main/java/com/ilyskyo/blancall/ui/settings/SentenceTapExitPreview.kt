// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.pressClick
import kotlinx.coroutines.delay

/**
 * 「点按翻页动画」的实时预览。
 *
 * 为什么要有它：速率的单位是毫秒，但**毫秒不是用户能判断的东西** ——
 * 0.12 秒和 0.18 秒的区别只有看过才知道。所以设置页不能只给一个滑杆，
 * 必须让这张小卡当场飞一次，用同一份 [Motion.cardTapExit] 谱子、同一段位移算式
 * （`translationY` / `rotationZ` / 末段减淡都照抄句子卡那一层），所见即所得。
 *
 * 触发条件是 `speed` / `enabled` 变了，或者用户点了一下预览卡：
 * 滑杆是分档的（不是每帧连续），所以拖动过程中的每一格都会重放一次 —— 松手前就已经看过。
 *
 * 关掉开关时这张卡**不动**，只停在那里 ——「不动」本身就是关掉之后的样子，
 * 没必要再演一遍「瞬间换卡」（那会读成一次闪烁，而不是一次设置生效）。
 */
@Composable
fun SentenceTapExitPreview(
    enabled: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val offset = remember { Animatable(0f) }
    var containerHeight by remember { mutableIntStateOf(0) }
    // 每飞完一次换一套「下一张」的线条宽度：飞走、停一下、新的那张出现，
    // 三步读起来才是「换了一张」，否则那张卡看起来只是弹了一下又回来了。
    var round by remember { mutableIntStateOf(0) }
    var replay by remember { mutableIntStateOf(0) }

    // 位移量：整块预览高度的 1.2 倍，飞出去时正好完全离场（和句子卡用同一个比例）
    val flyDistance = if (containerHeight > 0) containerHeight.toFloat() * 1.2f else 600f

    LaunchedEffect(enabled, speed, replay) {
        offset.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        // 先停一瞬，让眼睛先确认「这里有一张卡」，否则它一出现就开始飞，看不清起始位置
        delay(180)
        offset.animateTo(-flyDistance, Motion.cardTapExit(speed))
        delay(320)
        round++
        offset.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .onSizeChanged { containerHeight = it.height }
            .pressClick(tier = PressTier.Container) { replay++ },
    ) {
        PreviewCard(
            firstBar = if (round % 2 == 0) 0.92f else 0.64f,
            secondBar = if (round % 2 == 0) 0.55f else 0.78f,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp)
                .graphicsLayer {
                    translationY = offset.value
                    // 与句子卡离场层同一条算式：一边飞一边轻微侧倾，末段减淡
                    rotationZ = offset.value / 56f
                    alpha = 1f - ((-offset.value / flyDistance).coerceIn(0f, 1f) * 0.35f)
                },
        )
    }
}

/** 预览里的「迷你句子卡」：标题 + 两条骨架线，不写假正文（假句会让人以为那是内容）。 */
@Composable
private fun PreviewCard(
    firstBar: Float,
    secondBar: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    stringResource(R.string.sentence_cards),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SkeletonBar(firstBar)
            SkeletonBar(secondBar)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)),
    )
}
