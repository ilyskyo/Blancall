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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.ilyskyo.blancall.ui.theme.AppPrefs

/**
 * 触感事件的**语义档位**。
 *
 * 一档对应一种手感，而不是一个毫秒数 —— 毫秒数在下面的私有常量里，
 * 改数值不改动调用点，加语义才需要动调用点。
 */
enum class HapticTier {
    /** 轻：开关、勾选、单选切换 —— 「换了一格」。 */
    Toggle,

    /** 中：确认、提交、长按达阈值 —— 「收到了」。 */
    Confirm,

    /** 双段：拖拽/缩放落位、下拉揭示跨过阈值 —— 「跨过了一道界」。 */
    Threshold,

    /** 重：删除等破坏性操作的回执 —— 「真的办了，而且收不回去」。 */
    Destructive,
}

/** 用户在设置里选的触感强度；`Off` 是完全不振，而不是「变轻」。 */
enum class HapticLevel { Off, Light, Standard, Strong }

/**
 * 偏好字符串 → 档位。
 * 认不出的值一律退回 Standard 而不是抛错：这条值来自 SharedPreferences，
 * 老版本或手改过的文件都可能留下陌生字符串，触感不该因此静默失效。
 */
internal fun String.toHapticLevel(): HapticLevel = when (this) {
    "off" -> HapticLevel.Off
    "light" -> HapticLevel.Light
    "strong" -> HapticLevel.Strong
    else -> HapticLevel.Standard
}

// ── 各档的具体参数（时长 ms / 幅度 1..255）──
// 轻 10/90、中 24/DEFAULT、重 35/255；Threshold 是两段连打。
private const val TOGGLE_MS = 10L
private const val TOGGLE_AMPLITUDE = 90
private const val CONFIRM_MS = 24L
private const val THRESHOLD_MS = 45L
private const val DESTRUCTIVE_MS = 35L
private const val DESTRUCTIVE_AMPLITUDE = 255

private fun buildEffect(tier: HapticTier): VibrationEffect = when (tier) {
    HapticTier.Toggle -> VibrationEffect.createOneShot(TOGGLE_MS, TOGGLE_AMPLITUDE)
    // DEFAULT_AMPLITUDE 让系统按设备能力决定幅度 —— 这一档是「基准」，不想再加一层人为值
    HapticTier.Confirm -> VibrationEffect.createOneShot(CONFIRM_MS, VibrationEffect.DEFAULT_AMPLITUDE)
    HapticTier.Threshold -> VibrationEffect.createWaveform(
        // timings[0] = 0：先静音一拍，让第二下的间隔成为「两段」的听感来源
        longArrayOf(0L, THRESHOLD_MS, THRESHOLD_MS),
        intArrayOf(0, 180, 255),
        // API 26 只有三参重载，repeat = -1 即「不循环」；
        // VibrationEffect.REPEAT_NO_REPEAT 这个常量要到 API 31 才有，minSdk 26 用不了。
        -1,
    )
    HapticTier.Destructive -> VibrationEffect.createOneShot(DESTRUCTIVE_MS, DESTRUCTIVE_AMPLITUDE)
}

/**
 * 把用户强度偏好叠到语义档位上。
 *
 * 用**换档**而不是把时长/幅度乘一个系数：乘系数会得到「21ms/79」这类没人能复述的数字，
 * 而换档让每一档的手感始终是那四个定义好的手感之一。
 * Strong 不越过 Destructive、Light 不越过 Toggle —— 上下都有天花板，避免「强」变成糊成一片的抖。
 */
internal fun HapticTier.withLevel(level: HapticLevel): HapticTier = when (level) {
    HapticLevel.Off -> this          // 由调用方短路，这里不换档
    HapticLevel.Light -> when (this) {
        HapticTier.Toggle -> HapticTier.Toggle
        HapticTier.Confirm -> HapticTier.Toggle
        HapticTier.Threshold -> HapticTier.Confirm
        HapticTier.Destructive -> HapticTier.Confirm
    }
    HapticLevel.Standard -> this
    HapticLevel.Strong -> when (this) {
        HapticTier.Toggle -> HapticTier.Confirm
        HapticTier.Confirm -> HapticTier.Threshold
        HapticTier.Threshold -> HapticTier.Destructive
        HapticTier.Destructive -> HapticTier.Destructive
    }
}

private fun resolveVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * 关键交互的统一触感反馈。
 *
 * 实现取向：**优先系统振动服务直振**（[VibrationEffect.createOneShot] /
 * [VibrationEffect.createWaveform]）。原因：Compose 的 `HapticFeedbackType.LongPress`
 * 走 `View.performHapticFeedback`，在部分定制 ROM 上会被系统触感策略弱化到几乎无感
 * （用户反馈「长按没有震动」）；直振的幅度/时长可控，各机型表现一致。
 * `createOneShot`/`createWaveform` 是 API 26，本项目 minSdk 26 —— 不需要版本守卫。
 *
 * 也**故意不去镜像系统的全局触感开关**（`VibratorManager` 在这个版本也没有可读的
 * 触感设置项）：读开关等于把「定制 ROM 弱化触感」这个问题重新请回来。
 * 用户想关，用设置页里自己的「触感强度：关」。
 *
 * 兜底：设备无振动器或直振失败时退回 View 的 LONG_PRESS，静默失败不影响主流程。
 *
 * 用法（可自由捕获进手势闭包 / NestedScrollConnection）：
 * ```
 * val onToggle = rememberHaptic(HapticTier.Toggle)
 * onToggle()
 * ```
 */
@Composable
fun rememberHaptic(tier: HapticTier = HapticTier.Confirm): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    // 从 StateFlow 读而不是读一次快照：设置页里改了强度，
    // 正在屏幕上的那些调用点要立刻拿到新闭包，不必退回上一页再进来才生效。
    val level = AppPrefs.hapticLevelFlow.collectAsState().value.toHapticLevel()
    return remember(context, view, tier, level) {
        {
            if (level == HapticLevel.Off) {
                Unit
            } else {
                val effective = tier.withLevel(level)
                val vibrated = runCatching {
                    val vibrator = resolveVibrator(context)
                    if (vibrator != null && vibrator.hasVibrator()) {
                        vibrator.vibrate(buildEffect(effective))
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (!vibrated) {
                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                }
                Unit
            }
        }
    }
}

/**
 * 「确认」档的旧签名，保留以免改动既有调用点。
 * 新代码请直接写 [rememberHaptic] 并显式选档 —— 档位就是这套反馈的意义所在。
 */
@Composable
fun rememberConfirmHaptic(): () -> Unit = rememberHaptic(HapticTier.Confirm)
