// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ── 暖米黄 palette（人文风）+ 中性 palette（标准软件色）──
//
// 两套浅色底色都遵守同一条层级规则：**背景比卡片深一档**。
// 旧版关掉米黄时背景与卡片都是纯白（#FFFFFF / #FFFFFF），卡片只剩一条
// 1dp 描边可辨（实测对比度 1.29:1）—— 关掉米黄就等于关掉层级。

val OnBackgroundLight = Color(0xFF1E1B18)
val OnSurfaceLight = Color(0xFF1E1B18)
val OnSurfaceVariantLight = Color(0xFF4F4C47)
val OutlineLight = Color(0xFF7D7A74)
val SurfaceVariantLight = Color(0xFFF0EBE3)
val OutlineVariantLight = Color(0xFFC9C3BB)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410E0B)
val InverseSurfaceLight = Color(0xFF322F2A)
val InverseOnSurfaceLight = Color(0xFFF5EFE2)
val InversePrimaryLight = Color(0xFF8DA9FF)
val ScrimLight = Color(0xFF000000)

/**
 * 浅色表面层级。
 *
 * @param background 页面底（最深）
 * @param surface 卡片面（最亮）
 */
@Immutable
data class LightSurfaces(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outlineVariant: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val dim: Color,
    val bright: Color,
    val onSurfaceVariant: Color,
)

/** 米黄（纸张）：暖、偏黄，正文块读起来像书页。 */
val LightPaper = LightSurfaces(
    background = Color(0xFFF6F0E5),
    surface = Color(0xFFFFFCF8),
    surfaceVariant = Color(0xFFF0EBE3),
    outlineVariant = Color(0xFFC9C3BB),
    containerLowest = Color(0xFFFFFFFF),
    containerLow = Color(0xFFFBF7F0),
    container = Color(0xFFF6F0E5),
    containerHigh = Color(0xFFEFE8DC),
    containerHighest = Color(0xFFE8E0D2),
    dim = Color(0xFFEFE8DC),
    bright = Color(0xFFFFFCF8),
    onSurfaceVariant = Color(0xFF4F4C47),
)

/** 中性（标准软件色）：冷白、干净，不参与「人文纸张」叙事。 */
val LightNeutral = LightSurfaces(
    background = Color(0xFFF3F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E9EC),
    outlineVariant = Color(0xFFCFCFD4),
    containerLowest = Color(0xFFFFFFFF),
    containerLow = Color(0xFFF9F9FB),
    container = Color(0xFFF3F3F5),
    containerHigh = Color(0xFFEDEDF0),
    containerHighest = Color(0xFFE6E6EA),
    dim = Color(0xFFEDEDF0),
    bright = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF4A4A4F),
)

// ── 深色：纯黑底 + 逐级抬升（#000000 → #1C1C1E → #2C2C2E → #3A3A3C）──
val BackgroundDark = Color(0xFF000000)
val OnBackgroundDark = Color(0xFFF4F5F8)
val SurfaceDark = Color(0xFF1C1C1E)
val OnSurfaceDark = Color(0xFFF4F5F8)
val SurfaceVariantDark = Color(0xFF2C2C2E)
val SurfaceContainerHighDark = Color(0xFF3A3A3C)
val OnSurfaceVariantDark = Color(0xFFCBD0DA)
val OutlineDark = Color(0xFF9097A2)
val OutlineVariantDark = Color(0xFF4A4E57)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val InverseSurfaceDark = Color(0xFFE7E5DE)
val InverseOnSurfaceDark = Color(0xFF322F2A)
val InversePrimaryDark = Color(0xFF3B5DE7)
val ScrimDark = Color(0xFF000000)
val ErrorDark = Color(0xFFFFB4AB)

// 默认靖蓝 Primary（深色）
val PrimaryDark = Color(0xFF8DA9FF)
val OnPrimaryDark = Color(0xFF001B7E)
val PrimaryContainerDark = Color(0xFF1F36A5)
val OnPrimaryContainerDark = Color(0xFFDDE1FF)

val SecondaryDark = Color(0xFFC2C5DD)
val OnSecondaryDark = Color(0xFF2C334D)
val SecondaryContainerDark = Color(0xFF434964)
val OnSecondaryContainerDark = Color(0xFFDFE1F9)

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

// ── 从一个 ARGB 反推整套 accent（自定义主题色）──

/** 预设基色相：马卡龙色板就是围绕它调出来的，自定义色的色相偏移以此为原点。 */
internal const val ACCENT_BASE_HUE = 215.9f

/**
 * 色相偏移量：让「马卡龙卡片」「玻璃光斑」跟着主题色一起转。
 *
 * 中性色（石墨、或用户自定义的灰）饱和度几乎为 0 —— 此时色相毫无意义，
 * 直接返回 0，保留原色板（否则会把整套 pastel 甩到一个随机色相上，
 * 灰色主题反而变成彩色）。
 */
fun hueShiftFor(argb: Int): Float {
    val hsl = ColorMath.rgbToHsl(argb)
    if (hsl[1] < 0.06f) return 0f
    var delta = hsl[0] - ACCENT_BASE_HUE
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

/**
 * 自定义主题色：由单个 ARGB 推出 M3 需要的 8 个角色。
 *
 * 规则（与预设同口径）：
 * - 浅色 primary 就是用户选的颜色本身；前景色取对比度更高的白或近黑；
 * - 容器色 = 该色与白按 14% 混合，容器前景 = 压暗到 72%；
 * - 深色 primary 若在黑底上不足 4.5:1 会被提亮到刚好够，
 *   容器 = 深色 primary 压暗，容器前景 = 提亮 —— 保证深色下文字不糊。
 */
fun accentPresetFromArgb(argb: Int): AccentPreset {
    val primaryLight = argb or (0xFF shl 24)
    val onPrimaryLight = ColorMath.readableOn(primaryLight)
    val containerLight = ColorMath.blend(ColorMath.WHITE, primaryLight, 0.14f)
    val onContainerLight = ColorMath.darken(primaryLight, 0.72f)
    val primaryDark = ColorMath.lightenUntilContrast(primaryLight, ColorMath.BLACK, 4.5)
    val onPrimaryDark = ColorMath.readableOn(primaryDark)
    val containerDark = ColorMath.darken(primaryDark, 0.62f)
    val onContainerDark = ColorMath.blend(ColorMath.WHITE, primaryDark, 0.78f)
    return AccentPreset(
        // 名字不上屏（色板上只画圆点），用 ASCII 是为了不触发「硬编码中文只许减少」的棘轮
        name = "custom",
        primaryLight = Color(primaryLight),
        onPrimaryLight = Color(onPrimaryLight),
        primaryContainerLight = Color(containerLight),
        onPrimaryContainerLight = Color(onContainerLight),
        primaryDark = Color(primaryDark),
        onPrimaryDark = Color(onPrimaryDark),
        primaryContainerDark = Color(containerDark),
        onPrimaryContainerDark = Color(onContainerDark),
    )
}

/**
 * 三级色（tertiary）：预设里不单独维护，由 accent 转过 150° 得到。
 * 旧版是写死的棕色 —— 选「翠绿」时会和绿撞成两套配色，这是根因。
 */
fun tertiaryFromAccent(accentLight: Int, accentDark: Int): Pair<Color, Color> {
    val light = ColorMath.rotateHue(accentLight, 150f)
    val dark = ColorMath.rotateHue(accentDark, 150f)
    return Color(light) to Color(dark)
}
