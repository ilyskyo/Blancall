// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassCard

/**
 * 「句子卡片」大卡片界面的视图层（自 SentenceCardScreen 拆出，组织方式对齐 HomeCardViews）。
 * 全部为无状态组件，状态与手势由 SentenceCardScreen 持有与组装。
 */

/** 一张句子卡：来源标题（今日句带角标）+ 句文（内滚居中）+ 底部状态行 */
@Composable
internal fun SentenceCardFace(
    item: DailySentenceCoordinator.QueueItem,
    state: FsrsEngine.CardState?,
    now: Long,
    modifier: Modifier,
) {
    val rated = isRatedToday(state, now)
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.title.isNotBlank()) "《${item.title}》" else "句子卡片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.isToday) {
                    // 今日句角标：仅队列置顶的当天句子出现
                    Text(
                        "今日",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.headlineSmall,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                sentenceCardStatus(state, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (rated) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** 评级按钮（统一 52dp 高、圆角 16dp、带效果描述的无障碍语义） */
@Composable
internal fun RatingButton(
    label: String,
    desc: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(52.dp)
            .semantics { contentDescription = desc },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = content.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** 队列为空（无文章 / 无合格句）时的空态 */
@Composable
internal fun SentenceEmptyView(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                kind = AppIconKind.Library,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "今天没有可记的句子",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "先去导入一篇文章吧",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("返回", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 队列评完（或滑到末尾）时的完成页：本次会话评级分布小结 + 未评时提供"继续记忆"。
 *
 * @param ratedCount   队列中今天已评的句子数（含进入本次界面前已评的）
 * @param againCount   本次会话三键评级分布（忘记 / 不熟 / 记住了）；无会话评级时以 ratedCount 文案兜底
 */
@Composable
internal fun SentenceDoneView(
    ratedCount: Int,
    againCount: Int,
    hardCount: Int,
    goodCount: Int,
    firstUnrated: Int,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                kind = AppIconKind.Celebrate,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "今天的句子都记完了",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (againCount + hardCount + goodCount > 0) {
                "忘记 $againCount · 不熟 $hardCount · 记住了 $goodCount"
            } else {
                "本次共复习 $ratedCount 句"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        if (firstUnrated >= 0) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("继续记忆", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("返回首页", style = MaterialTheme.typography.labelLarge)
        }
    }
}
