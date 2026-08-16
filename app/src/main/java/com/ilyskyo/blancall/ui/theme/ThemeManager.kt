// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

/**
 * 主题管理器（全局单例，SharedPreferences 持久化）
 */
object ThemeManager {
    private lateinit var prefs: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    @SuppressLint("ApplySharedPref")
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("theme_mode", null)
        _themeMode.value = savedName?.let { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) }
            ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) {
        // 先持久化再更新 StateFlow，避免进程意外终止时状态与磁盘不一致
        if (::prefs.isInitialized) {
            prefs.edit { putString("theme_mode", mode.name) }
        }
        _themeMode.value = mode
    }

    fun toggleTheme(): ThemeMode {
        val next = when (_themeMode.value) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(next)
        return next
    }
}
