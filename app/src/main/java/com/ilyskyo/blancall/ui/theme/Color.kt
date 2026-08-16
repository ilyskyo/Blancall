// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ── Warm Parchment palette（暖米黄人文风）──

// Light theme
val PrimaryLight = Color(0xFF3B5DE7)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFDDE1FF)
val OnPrimaryContainerLight = Color(0xFF001452)

val SecondaryLight = Color(0xFF5A6184)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDFE1F9)
val OnSecondaryContainerLight = Color(0xFF171E3D)

val TertiaryLight = Color(0xFF8B4A2E)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFDBCC)
val OnTertiaryContainerLight = Color(0xFF340E00)

val BackgroundLight = Color(0xFFFAF7F2)
val OnBackgroundLight = Color(0xFF1E1B18)
val SurfaceLight = Color(0xFFFFFCF8)
val OnSurfaceLight = Color(0xFF1E1B18)
val SurfaceVariantLight = Color(0xFFF0EBE3)
val OnSurfaceVariantLight = Color(0xFF4F4C47)
val OutlineLight = Color(0xFF7D7A74)
val OutlineVariantLight = Color(0xFFCAC4CF)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410E0B)
val InverseSurfaceLight = Color(0xFF322F2A)
val InverseOnSurfaceLight = Color(0xFFF5EFE2)
val InversePrimaryLight = Color(0xFF8DA9FF)
val ScrimLight = Color(0xFF000000)

// Dark theme — 纯黑底色（深色模式各页面背景均为纯黑，无任何黄色调）
// 层级抬升：背景保持纯黑，卡片 / 容器逐级抬升（Apple iOS 系统填充层级：
// #000000 → #1C1C1E → #2C2C2E → #3A3A3C），深色下形成清晰纵深、不再糊成一片。
val BackgroundDark = Color(0xFF000000)
val OnBackgroundDark = Color(0xFFF4F5F8)
val SurfaceDark = Color(0xFF1C1C1E)        // 一级抬升：卡片表面
val OnSurfaceDark = Color(0xFFF4F5F8)
val SurfaceVariantDark = Color(0xFF2C2C2E)   // 二级抬升：次级表面
val SurfaceContainerHighDark = Color(0xFF3A3A3C) // 更高层：浮层 / 入口按钮 / 顶栏
val OnSurfaceVariantDark = Color(0xFFCBD0DA)
val OutlineDark = Color(0xFF9097A2)
val OutlineVariantDark = Color(0xFF3F434C)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val InverseSurfaceDark = Color(0xFFE7E5DE)
val InverseOnSurfaceDark = Color(0xFF322F2A)
val InversePrimaryDark = Color(0xFF3B5DE7)
val ScrimDark = Color(0xFF000000)

// 默认靖蓝 Primary（深色）
val PrimaryDark = Color(0xFF8DA9FF)
val OnPrimaryDark = Color(0xFF001B7E)
val PrimaryContainerDark = Color(0xFF1F36A5)
val OnPrimaryContainerDark = Color(0xFFDDE1FF)

val SecondaryDark = Color(0xFFC2C5DD)
val OnSecondaryDark = Color(0xFF2C334D)
val SecondaryContainerDark = Color(0xFF434964)
val OnSecondaryContainerDark = Color(0xFFDFE1F9)

val TertiaryDark = Color(0xFFFFB59A)
val OnTertiaryDark = Color(0xFF531F06)
val TertiaryContainerDark = Color(0xFF6F341A)
val OnTertiaryContainerDark = Color(0xFFFFDBCC)

val ErrorDark = Color(0xFFFFB4AB)

// ── 主题色预设（Material 3 规范：6 套可选 accent）──

// 标记为 @Immutable：作为 Composable 参数传递时可跳过重组（所有字段均为 val 且为稳定类型）
@Immutable
data class AccentPreset(
    val name: String,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
)

val AccentPresets = listOf(
    AccentPreset(
        name = "靛蓝",
        primaryLight = Color(0xFF3B5DE7),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFDDE1FF),
        onPrimaryContainerLight = Color(0xFF001452),
        primaryDark = Color(0xFF8DA9FF),
        onPrimaryDark = Color(0xFF001B7E),
        primaryContainerDark = Color(0xFF1F36A5),
        onPrimaryContainerDark = Color(0xFFDDE1FF),
    ),
    AccentPreset(
        name = "海蓝",
        primaryLight = Color(0xFF1565C0),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFD1E4FF),
        onPrimaryContainerLight = Color(0xFF001D36),
        primaryDark = Color(0xFF9ECAFF),
        onPrimaryDark = Color(0xFF003258),
        primaryContainerDark = Color(0xFF00497D),
        onPrimaryContainerDark = Color(0xFFD1E4FF),
    ),
    AccentPreset(
        name = "翠绿",
        primaryLight = Color(0xFF006C4C),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFF89F8C7),
        onPrimaryContainerLight = Color(0xFF002114),
        primaryDark = Color(0xFF6CDBA7),
        onPrimaryDark = Color(0xFF003826),
        primaryContainerDark = Color(0xFF005139),
        onPrimaryContainerDark = Color(0xFF89F8C7),
    ),
    AccentPreset(
        name = "暖橙",
        primaryLight = Color(0xFF9C4300),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFDBC8),
        onPrimaryContainerLight = Color(0xFF331100),
        primaryDark = Color(0xFFFFB787),
        onPrimaryDark = Color(0xFF532200),
        primaryContainerDark = Color(0xFF763300),
        onPrimaryContainerDark = Color(0xFFFFDBC8),
    ),
    AccentPreset(
        name = "玫红",
        primaryLight = Color(0xFFB31654),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFD9E2),
        onPrimaryContainerLight = Color(0xFF3E0018),
        primaryDark = Color(0xFFFFB0CB),
        onPrimaryDark = Color(0xFF65002D),
        primaryContainerDark = Color(0xFF8E0041),
        onPrimaryContainerDark = Color(0xFFFFD9E2),
    ),
    AccentPreset(
        name = "石墨",
        primaryLight = Color(0xFF595959),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFDBDBDB),
        onPrimaryContainerLight = Color(0xFF1A1A1A),
        primaryDark = Color(0xFFC2C2C2),
        onPrimaryDark = Color(0xFF2E2E2E),
        primaryContainerDark = Color(0xFF424242),
        onPrimaryContainerDark = Color(0xFFDBDBDB),
    ),
)
