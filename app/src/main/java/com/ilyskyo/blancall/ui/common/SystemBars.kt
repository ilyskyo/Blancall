// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全局沉浸态标记：BlancallTheme 的 SideEffect 依据它跳过系统栏着色写回，
 * 避免沉浸期间主题重组（如米黄开关）把状态栏/导航栏涂成不透明色、或让
 * ColorOS 手势横线重新浮现。
 */
object SystemBarState {
    var immersive by mutableStateOf(false)
        internal set
}

/**
 * 沉浸式系统栏 Effect：`enabled=true` 时隐藏状态栏/导航栏（含手势横线），
 * 从屏幕边缘上滑可临时唤出（[WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]），
 * 松手后自动再隐；`enabled=false` 或离开组合时恢复。
 *
 * 兜底：
 * - ColorOS/OPPO 系对手势横线 OEM 独立绘制，`hide(Type.navigationBars())` 常只隐藏透明条、
 *   横线残留——命中 ColorOS 时叠加 legacy SYSTEM_UI_FLAG 组合强制隐藏。
 * - 低版本（API 26-28）无 WindowInsetsController 完整语义，走 legacy 分支。
 *
 * 注意：onDispose 必须复位 [SystemBarState.immersive] 并恢复系统栏显示，防止串台。
 */
@Composable
fun ImmersiveSystemBarsEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        if (enabled && window != null && controller != null) {
            SystemBarState.immersive = true
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 兜底：手势导航下的 OEM 差异（ColorOS）与低版本 API
            if (isGestureNavigation(window) || isColorOsRom()) {
                hideLegacy(window)
            }
        }

        onDispose {
            SystemBarState.immersive = false
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                restoreLegacy(window)
            }
        }
    }
}

/**
 * 是否手势导航：Android 10+ 读 Settings.Secure "navigation_mode"（0=三键 1=手势 2=其他）。
 * 读取失败或 API 26-28 时按底部导航区 insets 高度兜底（手势横线区 <32dp，三键栏 ≥48dp）；
 * 仍无法判断则返回 false（此时走 legacy 分支同样能隐藏）。
 */
fun isGestureNavigation(window: Window): Boolean {
    val context = window.context
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val mode = Settings.Secure.getInt(context.contentResolver, "navigation_mode")
            return mode == 1
        } catch (_: Exception) {
            // 读不到（部分 ROM 无此键），走 insets 兜底
        }
    }
    return try {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(window.decorView.rootWindowInsets)
            .getInsets(WindowInsetsCompat.Type.navigationBars())
        val h = insets.bottom.toFloat() / window.decorView.resources.displayMetrics.density
        h > 0f && h < 48f
    } catch (_: Exception) {
        false
    }
}

/** 是否 OPPO 系 ROM（ColorOS 对手势横线做 OEM 独立绘制，hide() 常不生效） */
fun isColorOsRom(): Boolean {
    val brand = (Build.BRAND ?: "").lowercase()
    val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
    val product = (Build.PRODUCT ?: "").lowercase()
    return listOf("oppo", "realme", "oneplus", "coloros").any {
        brand.contains(it) || manufacturer.contains(it) || product.contains(it)
    }
}

/** legacy SYSTEM_UI_FLAG 组合：ColorOS / API26-28 的强制隐藏兜底 */
private fun hideLegacy(window: Window) {
    val decor = window.decorView
    val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    decor.systemUiVisibility = decor.systemUiVisibility or flags
}

private fun restoreLegacy(window: Window) {
    val decor = window.decorView
    val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    decor.systemUiVisibility = decor.systemUiVisibility and flags.inv()
}
