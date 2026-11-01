// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import kotlin.math.cos
import kotlin.math.sin

/** 五分钟无操作时的确认状态（三态，互斥） */
internal enum class IdlePromptStage {
    /** 不显示 */
    Hidden,

    /** 问一句"还在读吗" */
    Confirm,

    /** 点了"在读"之后的庆祝 */
    Cheer
}

/**
 * 五分钟无操作 → 确认 → 庆祝/放行。
 *
 * ## 为什么是"确认"而不是"提醒你已经停了"
 * 阅读时一屏文字读三五分钟不翻页是正常状态，App 没有能力分辨"沉浸"与"走开"，
 * 所以这里问的是**事实**（你还在读吗），不是评价（你已经很久没动了）。
 * 两种回答都必须体面：说"在读"得到的是肯定，说"先去忙"得到的是放行 —— 没有一条路是"你错了"。
 * （自决理论：控制性语言与羞辱式反馈会触发心理逆反与回避；自责会让人更不愿回来。）
 *
 * ## 为什么"真棒"后面还要跟一行具体的分钟数
 * 纯评价（"真棒！"）的强化作用衰减很快，而且会把注意力从"我在读什么"移到"我在刷 App"。
 * 补一行事实（又读了 N 分钟）等于把表扬落在一个可核对的结果上：信息性反馈，
 * 既是肯定也不制造"必须更努力"的暗示。
 */
@Composable
internal fun ReadingIdlePrompt(
    stage: IdlePromptStage,
    idleMinutes: Int,
    accent: Color,
    isDark: Boolean,
    onStillReading: () -> Unit,
    onBusy: () -> Unit,
    onCheerDone: () -> Unit
) {
    when (stage) {
        IdlePromptStage.Hidden -> Unit
        IdlePromptStage.Confirm -> BlancallAlertDialog(
            onDismissRequest = onBusy,
            title = {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_idle_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_idle_body, idleMinutes),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = onStillReading) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.reader_idle_yes),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onBusy) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.reader_idle_no),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        IdlePromptStage.Cheer -> IdleCheerCard(
            minutes = idleMinutes,
            accent = accent,
            isDark = isDark,
            onDone = onCheerDone
        )
    }
}

/**
 * 庆祝卡片：一圈粒子往外散 + 自绘对勾 + 一句"真棒"。
 *
 * 全部自绘（项目规定不用 emoji / 现成符号）。动画播一次就停，1.4 秒后自动收起 ——
 * 阅读页里不该留一个需要用户去点的庆祝窗口，那会把"读得投入"变成"操作 App"。
 */
@Composable
private fun IdleCheerCard(
    minutes: Int,
    accent: Color,
    isDark: Boolean,
    onDone: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, MotionFade.number(MotionFade.enter))
        kotlinx.coroutines.delay(900)
        onDone()
    }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        // 系统窗口动画在部分 ROM 是「右下角推入」，与页面内的入场节奏不是一回事
        com.ilyskyo.blancall.ui.common.SuppressDialogWindowAnimation()
        Column(
            modifier = Modifier
                .width(240.dp)
                .height(200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 粒子：8 个方向散开 + 中心对勾
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = progress.value
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension * 0.48f
                    // 越往后越淡、越远
                    repeat(8) { i ->
                        val angle = (Math.PI * 2 / 8) * i - Math.PI / 2
                        val r = maxR * (0.35f + 0.65f * t)
                        val dot = size.minDimension * 0.045f * (1f - 0.4f * t)
                        drawCircle(
                            color = accent.copy(alpha = (1f - t) * 0.85f),
                            radius = dot,
                            center = Offset(
                                x = center.x + cos(angle).toFloat() * r,
                                y = center.y + sin(angle).toFloat() * r
                            )
                        )
                    }
                    // 中心：一个圆圈 + 对勾（两笔，纯线）
                    drawCircle(
                        color = accent.copy(alpha = 0.16f),
                        radius = size.minDimension * 0.34f,
                        center = center
                    )
                    val s = size.minDimension
                    val w = s * 0.045f
                    drawLine(
                        color = accent,
                        start = Offset(center.x - s * 0.14f, center.y + s * 0.01f),
                        end = Offset(center.x - s * 0.02f, center.y + s * 0.13f),
                        strokeWidth = w,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = accent,
                        start = Offset(center.x - s * 0.02f, center.y + s * 0.13f),
                        end = Offset(center.x + s * 0.16f, center.y - s * 0.12f),
                        strokeWidth = w,
                        cap = StrokeCap.Round
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(com.ilyskyo.blancall.R.string.reader_idle_cheer),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else Color.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(com.ilyskyo.blancall.R.string.reader_idle_cheer_detail, minutes),
                style = MaterialTheme.typography.bodySmall,
                color = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
