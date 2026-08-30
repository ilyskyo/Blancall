// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.ilyskyo.blancall.ui.common.SystemBarState

/**
 * 全局大圆角体系（Material 3 自定义 Shapes）：
 * 卡片 / 按钮 / 输入框 / 弹窗统一圆润风格，所有使用 MaterialTheme.shapes 的组件自动生效。
 */
val BlancallShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

/**
 * 基于主题色（primary）派生 secondaryContainer / tertiaryContainer，
 * 使它们随主题色变化而非固定颜色。
 * 通过将 primaryContainer 与 surface 按比例混合，得到与主色调和而不同的次级容器色。
 */
private fun derivedSecondaryContainer(primaryContainer: Color, surface: Color, alpha: Float = 0.5f): Color =
    primaryContainer.copy(alpha = alpha).compositeOver(surface)

private fun buildLightColorScheme(accent: AccentPreset, useBeige: Boolean): ColorScheme {
    // 根据用户开关决定底色：开启用暖米黄（人文风），关闭用纯白（标准软件色）
    val bg = if (useBeige) BackgroundLight else Color(0xFFFFFFFF)
    val surface = if (useBeige) SurfaceLight else Color(0xFFFFFFFF)
    // 派生 secondary 系列：基于 primary 与中性色混合，跟随主题色变化
    val derivedSecondaryContainer = derivedSecondaryContainer(accent.primaryContainerLight, surface)
    return lightColorScheme(
        primary = accent.primaryLight,
        onPrimary = accent.onPrimaryLight,
        primaryContainer = accent.primaryContainerLight,
        onPrimaryContainer = accent.onPrimaryContainerLight,
        secondary = accent.primaryLight,
        onSecondary = accent.onPrimaryLight,
        secondaryContainer = derivedSecondaryContainer,
        onSecondaryContainer = accent.onPrimaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        background = bg,
        onBackground = OnBackgroundLight,
        surface = surface,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        inverseSurface = InverseSurfaceLight,
        inverseOnSurface = InverseOnSurfaceLight,
        inversePrimary = InversePrimaryLight,
        scrim = ScrimLight,
    )
}

private fun buildDarkColorScheme(accent: AccentPreset): ColorScheme {
    val derivedSecondaryContainer = derivedSecondaryContainer(accent.primaryContainerDark, SurfaceDark)
    return darkColorScheme(
        primary = accent.primaryDark,
        onPrimary = accent.onPrimaryDark,
        primaryContainer = accent.primaryContainerDark,
        onPrimaryContainer = accent.onPrimaryContainerDark,
        secondary = accent.primaryDark,
        onSecondary = accent.onPrimaryDark,
        secondaryContainer = derivedSecondaryContainer,
        onSecondaryContainer = accent.onPrimaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        inverseSurface = InverseSurfaceDark,
        inverseOnSurface = InverseOnSurfaceDark,
        inversePrimary = InversePrimaryDark,
        scrim = ScrimDark,
    )
}

/**
 * 单一明暗真理源：综合用户手动主题（SYSTEM / DARK / LIGHT）与系统夜间模式，
 * 供所有组件取代 isSystemInDarkTheme() 使用，保证手动深色 / 浅色选择完整生效。
 */
val LocalIsDark = compositionLocalOf { false }

@Composable
fun isBlancallDark(): Boolean = LocalIsDark.current

@Composable
fun BlancallTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val themeMode by ThemeManager.themeMode.collectAsState()
    val accentIndex by AppPrefs.accentColorFlow.collectAsState()
    val useBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val accent = AccentPresets.getOrElse(accentIndex) { AccentPresets[0] }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> buildDarkColorScheme(accent)
        else -> buildLightColorScheme(accent, useBeige)
    }

    // 系统栏图标深浅色跟随主题（edge-to-edge 下系统栏透明，由页面背景延伸衬托；
    // Android 15+ 已忽略 deprecated 的 statusBarColor/navigationBarColor 写回，故不再涂色）。
    // 沉浸式期间（阅读模式等全屏界面）跳过，避免影响隐藏态。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            if (!SystemBarState.immersive) {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val insets = WindowInsetsControllerCompat(window, view)
                insets.isAppearanceLightStatusBars = !darkTheme
                insets.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalIsDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = BlancallShapes,
            content = content
        )
    }
}
