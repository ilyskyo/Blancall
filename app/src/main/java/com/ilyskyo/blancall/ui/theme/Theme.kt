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
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.SystemBarState
import kotlinx.coroutines.delay

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

private fun buildLightColorScheme(
    accent: AccentPreset,
    useBeige: Boolean,
    /**
     * true = 页面底色与卡片同色（旧版观感）；false = 底色比卡片深一档。
     *
     * 深一档是为了让卡片靠明度差立起来（不指望 1.29:1 的描边），
     * 但从旧版升上来的用户会先看到「整页变灰」，所以做成开关、且默认保持同色。
     */
    flatBackground: Boolean,
): ColorScheme {
    // 两套浅色底色：米黄（纸张）/ 中性（标准软件色）。
    // 共同点是 bg 比 surface 深一档 —— 卡片靠面差立起来，不再指望一条 1.29:1 的描边。
    val s = if (useBeige) LightPaper else LightNeutral
    val accentArgb = accent.primaryLight.toArgb()
    val (tertiaryColor, _) = tertiaryFromAccent(accentArgb, accent.primaryDark.toArgb())
    val tertiaryContainer = Color(ColorMath.blend(ColorMath.WHITE, tertiaryColor.toArgb(), 0.84f))
    return lightColorScheme(
        primary = accent.primaryLight,
        onPrimary = accent.onPrimaryLight,
        primaryContainer = accent.primaryContainerLight,
        onPrimaryContainer = accent.onPrimaryContainerLight,
        secondary = accent.primaryLight,
        onSecondary = accent.onPrimaryLight,
        secondaryContainer = derivedSecondaryContainer(accent.primaryContainerLight, s.surface),
        onSecondaryContainer = accent.onPrimaryContainerLight,
        tertiary = tertiaryColor,
        onTertiary = Color(ColorMath.readableOn(tertiaryColor.toArgb())),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = Color(ColorMath.darken(tertiaryContainer.toArgb(), 0.72f)),
        background = if (flatBackground) s.surface else s.background,
        onBackground = OnBackgroundLight,
        surface = s.surface,
        onSurface = OnSurfaceLight,
        surfaceVariant = s.surfaceVariant,
        onSurfaceVariant = s.onSurfaceVariant,
        surfaceContainerLowest = s.containerLowest,
        surfaceContainerLow = s.containerLow,
        surfaceContainer = s.container,
        surfaceContainerHigh = s.containerHigh,
        surfaceContainerHighest = s.containerHighest,
        surfaceDim = s.dim,
        surfaceBright = s.bright,
        outline = OutlineLight,
        outlineVariant = s.outlineVariant,
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

private fun buildDarkColorScheme(accent: AccentPreset, flatBackground: Boolean): ColorScheme {
    val derivedSecondaryContainer = derivedSecondaryContainer(accent.primaryContainerDark, SurfaceDark)
    val (_, tertiaryDark) = tertiaryFromAccent(accent.primaryLight.toArgb(), accent.primaryDark.toArgb())
    val tertiaryContainer = Color(ColorMath.darken(tertiaryDark.toArgb(), 0.55f))
    return darkColorScheme(
        primary = accent.primaryDark,
        onPrimary = accent.onPrimaryDark,
        primaryContainer = accent.primaryContainerDark,
        onPrimaryContainer = accent.onPrimaryContainerDark,
        secondary = accent.primaryDark,
        onSecondary = accent.onPrimaryDark,
        secondaryContainer = derivedSecondaryContainer,
        onSecondaryContainer = accent.onPrimaryContainerDark,
        tertiary = tertiaryDark,
        onTertiary = Color(ColorMath.readableOn(tertiaryDark.toArgb())),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = Color(ColorMath.blend(ColorMath.WHITE, tertiaryDark.toArgb(), 0.80f)),
        background = if (flatBackground) SurfaceDark else BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceContainerLowest = SurfaceDark,
        surfaceContainerLow = Color(0xFF161618),
        surfaceContainer = SurfaceVariantDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = Color(0xFF48484A),
        surfaceDim = Color(0xFF141416),
        surfaceBright = SurfaceVariantDark,
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

/**
 * 当前强调色的色相偏移（度）。
 *
 * 马卡龙卡片、玻璃光斑、图表都读它 —— 这样「换主题色」才不只是换按钮颜色。
 * 由 [hueShiftFor] 统一算出，中性色（石墨）为 0（保留原色板）。
 */
val LocalAccentHueShift = compositionLocalOf { 0f }

@Composable
fun isBlancallDark(): Boolean = LocalIsDark.current

/**
 * 在**独立组合**（`ComposeView.setContent`、Glance 之外的弹窗宿主）里拿当前明暗。
 *
 * 阅读页正文是独立 ComposeView，拿不到外层 CompositionLocal，旧代码因此在三个地方
 * 各自重算了一遍 `ThemeMode → isDark`。统一收到这里，新增模式只需要改这一处。
 */
@Composable
fun rememberAppIsDark(): Boolean {
    val mode by AppearanceStore.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    return when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
}

// ── 界面字体 ──

/** 内置霞鹜文楷（与阅读页共用同一份 ttf，不额外增包体）。 */
private val XwwkFamily = FontFamily(
    Font(R.font.xwwk_light, FontWeight.Light),
    Font(R.font.xwwk_regular, FontWeight.Normal),
    Font(R.font.xwwk_medium, FontWeight.Medium),
)

/**
 * 按用户选择重建 Typography。
 *
 * 只改「标题/正文正文族」（display* / headline* / title* / bodyLarge）——
 * label* 与 bodyMedium/Small 本来就是无衬线，换字体时会跟着衬线一起去掉才是错的。
 */
private fun uiTypography(font: String): Typography {
    val base = Typography
    val target = when (font) {
        AppearanceSnapshot.FONT_XWWK -> XwwkFamily
        AppearanceSnapshot.FONT_SANS -> FontFamily.Default
        else -> return base
    }
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = target),
        displayMedium = base.displayMedium.copy(fontFamily = target),
        displaySmall = base.displaySmall.copy(fontFamily = target),
        headlineLarge = base.headlineLarge.copy(fontFamily = target),
        headlineMedium = base.headlineMedium.copy(fontFamily = target),
        headlineSmall = base.headlineSmall.copy(fontFamily = target),
        titleLarge = base.titleLarge.copy(fontFamily = target),
        titleMedium = base.titleMedium.copy(fontFamily = target),
        titleSmall = base.titleSmall.copy(fontFamily = target),
        bodyLarge = base.bodyLarge.copy(fontFamily = target),
    )
}

@Composable
fun BlancallTheme(
    content: @Composable () -> Unit
) {
    val appearance by AppearanceStore.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val accent = appearance.accentPreset()
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamic = appearance.materialYou && dynamicSupported
    val context = LocalContext.current
    // Material You：开启动态取色时，浅/深两套系统色板各取一次，色相偏移也改成跟动态主色走
    val dynamicLight = remember(useDynamic) {
        if (useDynamic) runCatching { dynamicLightColorScheme(context) }.getOrNull() else null
    }
    val dynamicDark = remember(useDynamic) {
        if (useDynamic) runCatching { dynamicDarkColorScheme(context) }.getOrNull() else null
    }

    val baseScheme = when {
        useDynamic && darkTheme && dynamicDark != null -> dynamicDark
        useDynamic && !darkTheme && dynamicLight != null -> dynamicLight
        darkTheme -> buildDarkColorScheme(accent, appearance.flatBackground)
        else -> buildLightColorScheme(accent, appearance.beigeBackground, appearance.flatBackground)
    }
    val hueSource = dynamicLight?.primary?.toArgb() ?: accent.primaryLight.toArgb()
    val hueShift = remember(hueSource) { hueShiftFor(hueSource) }

    // 换主题 / 换强调色 / 切米黄底时，整页颜色「溶」过去而不是下一帧硬切。
    // 这里补的是**所有会在屏幕上同时出现的大面积角色**：早前只补了 8 个，
    // onSurfaceVariant / outlineVariant / secondaryContainer / tertiary 会瞬间硬跳
    // （背景在溶、次级文字和卡片描边已经换完了）。时长同时由 260 压到 180ms —— 白→黑
    // 的颜色插值必然经过 50% 灰，插得越久那段「闪灰」越像故障。
    val schemeFade = MotionFade.color(MotionFade.themeSwitch)
    val colorScheme = baseScheme.copy(
        background = animateColorAsState(baseScheme.background, schemeFade, label = "themeBg").value,
        onBackground = animateColorAsState(baseScheme.onBackground, schemeFade, label = "themeOnBg").value,
        surface = animateColorAsState(baseScheme.surface, schemeFade, label = "themeSurface").value,
        onSurface = animateColorAsState(baseScheme.onSurface, schemeFade, label = "themeOnSurface").value,
        surfaceVariant = animateColorAsState(baseScheme.surfaceVariant, schemeFade, label = "themeSurfVar").value,
        onSurfaceVariant = animateColorAsState(baseScheme.onSurfaceVariant, schemeFade, label = "themeOnSurfVar").value,
        primary = animateColorAsState(baseScheme.primary, schemeFade, label = "themePrimary").value,
        onPrimary = animateColorAsState(baseScheme.onPrimary, schemeFade, label = "themeOnPrimary").value,
        secondaryContainer = animateColorAsState(baseScheme.secondaryContainer, schemeFade, label = "themeSecCont").value,
        tertiary = animateColorAsState(baseScheme.tertiary, schemeFade, label = "themeTertiary").value,
        outline = animateColorAsState(baseScheme.outline, schemeFade, label = "themeOutline").value,
        outlineVariant = animateColorAsState(baseScheme.outlineVariant, schemeFade, label = "themeOutlineVar").value,
    )

    // 系统栏图标极性跟随同一条溶开：底色走到中点才翻转。
    // 立刻翻 → 浅色字还压在没变深的底上；等动画放完才翻 → 读成两个各拍各的动画。
    var barDark by remember { mutableStateOf(darkTheme) }
    LaunchedEffect(darkTheme) {
        if (!Motion.reduced) delay(MotionFade.themeSwitch / 2L)
        barDark = darkTheme
    }

    // 系统栏图标深浅色跟随主题（edge-to-edge 下系统栏透明，由页面背景延伸衬托；
    // Android 15+ 已忽略 deprecated 的 statusBarColor/navigationBarColor 写回，故不再涂色）。
    // 沉浸式期间（阅读模式等全屏界面）跳过，避免影响隐藏态。
    val view = LocalView.current
    if (!view.isInEditMode) {
        // 必须在**组合里**读一次 barDark：SideEffect 内部的读取不建立订阅，
        // 而 barDark 变化本身不重组这个作用域 —— 平时是那句 animateColorAsState
        // 每帧重组「顺带」把 SideEffect 又跑了一遍才碰巧生效。
        // 减动效（animator_duration_scale=0）下补间全成 snap、没有逐帧重组，
        // 深浅切换后系统栏图标极性会停在旧值（黑底配深色图标 = 时钟和电量看不见）。
        val lightBars = !barDark
        SideEffect {
            if (!SystemBarState.immersive) {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val insets = WindowInsetsControllerCompat(window, view)
                insets.isAppearanceLightStatusBars = lightBars
                insets.isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    // 界面字号缩放：缩的是 fontScale，不动 density —— dp 布局尺寸保持不变，
    // 只有 sp（含那 100 多处手写 sp）跟着变，比逐个改 typography 覆盖面完整。
    val density = LocalDensity.current
    val scaledDensity = remember(density, appearance.uiFontScale) {
        if (appearance.uiFontScale == 1f) density
        else Density(density.density, density.fontScale * appearance.uiFontScale)
    }
    val typography = remember(appearance.uiFont) { uiTypography(appearance.uiFont) }

    CompositionLocalProvider(
        LocalIsDark provides darkTheme,
        LocalAccentHueShift provides hueShift,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = BlancallShapes,
            content = content
        )
    }
}
