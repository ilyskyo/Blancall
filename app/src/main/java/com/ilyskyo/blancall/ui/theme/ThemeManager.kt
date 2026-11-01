// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * 主题模式
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * 主题门面（保留旧调用点）。
 *
 * 真正的状态与落盘已经收归 [AppearanceStore]：
 * 外观要能被「一键备份」带走，而备份包只收 filesDir 下的 json，
 * SharedPreferences 里的值换机就丢。这里只做转发，**不要再往本对象里加状态**
 * —— 否则又会分叉出第二个真理源。
 */
object ThemeManager {

    fun init(context: Context) {
        AppearanceStore.init(context)
    }

    val themeMode: StateFlow<ThemeMode> get() = AppearanceStore.themeMode

    fun setThemeMode(mode: ThemeMode) = AppearanceStore.setThemeMode(mode)

    fun toggleTheme(): ThemeMode = AppearanceStore.toggleTheme()
}
