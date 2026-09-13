// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 关键交互的统一触感反馈（长按 / 拖拽与缩放开始 / 下拉达阈值等）。
 *
 * 实现取向：**优先系统振动服务直振一段短促「咔哒」**（[VibrationEffect.createOneShot]），
 * 原因：Compose 的 `HapticFeedbackType.LongPress` 走 `View.performHapticFeedback`，
 * 在部分定制 ROM 上会被系统触感策略弱化到几乎无感（用户反馈「长按没有震动」）；
 * 直振的幅度/时长可控，各机型表现一致。
 *
 * 兜底：设备无振动器或直振调用失败时，退回 View 的 LONG_PRESS 触感，静默失败不影响主流程。
 * 使用方式（Composable 内拿到调用器，可自由捕获进手势闭包 / NestedScrollConnection）：
 * ```
 * val haptic = rememberConfirmHaptic()
 * haptic()   // 在需要反馈的节点调用一次
 * ```
 */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) {
        {
            val vibrated = runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(24L, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            // 无振动器 / 被 ROM 拦截：退回 View 触感兜底，保证「至少有反馈」
            if (!vibrated) {
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            }
            Unit
        }
    }
}
