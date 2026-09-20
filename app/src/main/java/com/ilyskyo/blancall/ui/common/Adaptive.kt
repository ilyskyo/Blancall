// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 全局窗口尺寸类别（Material 3 大屏适配）：
 * - [WindowWidthSizeClass.Compact]   < 600dp   手机竖屏
 * - [WindowWidthSizeClass.Medium]    600–839dp 平板竖屏 / 折叠屏展开
 * - [WindowWidthSizeClass.Expanded]  ≥ 840dp   平板横屏 / 手机横屏
 *
 * 由 [com.ilyskyo.blancall.MainActivity] 在根组合提供；未提供时回退 Compact，
 * 保证任何单页预览/测试场景都不会因缺省而崩溃。
 *
 * 注：[WindowSizeClass] 的构造函数是 private，缺省值只能用
 * [WindowSizeClass.calculateFromSize] 造一个 Compact 实例。
 */
val LocalWindowSizeClass = staticCompositionLocalOf { CompactWindowSizeClass }

/** 缺省 / 兜底用的 Compact 档：420×900dp 落在 Compact × Medium 区间。 */
private val CompactWindowSizeClass: WindowSizeClass =
    WindowSizeClass.calculateFromSize(DpSize(420.dp, 900.dp))

/** 是否处于「大屏」档（Medium 及以上）：平板竖屏、折叠屏展开、手机横屏都算。 */
val LocalIsLargeScreen: Boolean
    @Composable get() = LocalWindowSizeClass.current.isLargeScreen()

/** 是否处于「扩展」档（Expanded，≥840dp）：平板横屏、手机横屏。 */
val LocalIsExpandedScreen: Boolean
    @Composable get() = LocalWindowSizeClass.current.widthSizeClass == WindowWidthSizeClass.Expanded

/** 宽屏判定：Medium 及以上。用于决定「是否启用多列 / 侧边导航」等结构性变化。 */
fun WindowSizeClass.isLargeScreen(): Boolean =
    widthSizeClass == WindowWidthSizeClass.Medium ||
        widthSizeClass == WindowWidthSizeClass.Expanded

/** 是否横向空间非常充裕（Expanded）：可用两栏布局。 */
fun WindowSizeClass.isExpanded(): Boolean =
    widthSizeClass == WindowWidthSizeClass.Expanded

/** 高度是否受限（横屏时常见）：用于压缩上下留白。 */
fun WindowSizeClass.isHeightConstrained(): Boolean =
    heightSizeClass == WindowHeightSizeClass.Compact

/**
 * 阅读/长文的「可读宽度」上限。
 *
 * 平板横屏时若正文铺满全宽，一行会到 100+ 字符，眼睛在行间回扫极易串行——
 * 这是大屏适配里最典型的问题。按排版惯例，中文正文单行 25–35 字最舒适，
 * 故在此把阅读正文限制在合理宽度并居中。
 */
val ReadingMaxWidth: Dp = 720.dp

/** 表单 / 设置类页面的内容最大宽度：避免输入框在平板上被拉成长条。 */
val FormMaxWidth: Dp = 640.dp

/**
 * 练习页在宽屏下的内容上限。
 *
 * 句子挖空在宽屏下是「左文右答」双栏，需要的横向空间远大于表单类页面；
 * 若沿用 600dp，两栏各只剩约 290dp（中文 16sp 约 15 字/行），比手机单栏还难读。
 */
val PracticeMaxWidth: Dp = 1000.dp

/**
 * 大屏侧边导航栏（[com.ilyskyo.blancall.ui.common.NavRail]）的宽度 —— **唯一真源**。
 *
 * 凡是要「扣掉导航栏宽度」再判断可用空间的逻辑都必须引用它。
 * 否则用 `screenWidthDp` 判断会高估一个导航栏的宽度，
 * 在边界值上（如 700dp 的折叠屏竖屏）会把双栏切得两条都过窄。
 */
val NavRailWidth: Dp = 76.dp

/**
 * 「左文右答」双栏作答所需的最小**内容**宽度（dp，已扣掉导航栏）。
 *
 * 720dp 保证每栏 ≥ 约 350dp：中文正文 16sp 时约 22 字/行，再窄读句子就会频繁串行，
 * 而默写必须能一眼扫到整句。
 */
const val TwoPaneMinWidthDp: Int = 720

/**
 * 双栏作答所需的最小高度（dp）。
 *
 * 右栏要同时装下「输入区 + 手写板（≥140dp）+ 模式切换 + 上一空/下一空」约 280dp。
 * 手机横屏宽度常常达标（900dp）但高度只有 400dp 左右，会把书写板挤出可视区 ——
 * 所以高度必须一并限制，只看宽度是错的。
 */
const val TwoPaneMinHeightDp: Int = 480

/**
 * 内容限宽容器：在宽屏下把内容限制在 [maxWidth] 内并水平居中；
 * 窄屏保持原样（铺满）。
 *
 * @param maxWidthPx 最大宽度，默认 [FormMaxWidth]
 */
@Composable
fun AdaptiveWidthContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = FormMaxWidth,
    alignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (LocalIsLargeScreen) Modifier.widthIn(max = maxWidth) else Modifier),
            content = content
        )
    }
}

/** 便捷：宽屏时给内容加水平内边距，窄屏不加。 */
@Composable
fun Modifier.adaptiveHorizontalPadding(
    compact: Dp = 0.dp,
    large: Dp = 24.dp
): Modifier = this.padding(horizontal = if (LocalIsLargeScreen) large else compact)

/** 便捷：按屏幕档位取不同的 PaddingValues。 */
@Composable
fun adaptivePadding(
    compact: PaddingValues,
    large: PaddingValues
): PaddingValues = if (LocalIsLargeScreen) large else compact

/**
 * 首页卡片网格列数策略。
 *
 * 原先锁竖屏的理由是「横屏下首页内容区仅 406dp 高，卡片网格被压成一行半」——
 * 那个问题的真正解法是按可用宽度动态决定列数，而不是把整个 App 锁死竖屏。
 * 列数随宽度增加，行高自然下降，横屏也能容纳完整若干行。
 *
 * @param availableWidthDp 网格可用宽度（dp）
 * @param cardMinWidthDp   单卡最小舒适宽度
 */
fun gridColumnsFor(availableWidthDp: Float, cardMinWidthDp: Float = 168f): Int {
    if (availableWidthDp <= 0f) return 2
    val cols = (availableWidthDp / cardMinWidthDp).toInt()
    return cols.coerceIn(2, 6)
}

/** 多列网格内容最大宽度（统计/多卡片页面用），避免超宽屏下单卡被拉得极宽。 */
val GridMaxWidth: Dp = 1100.dp

/**
 * 提供窗口尺寸类别给整棵树。放在根组合（MainActivity setContent 内）最外层。
 */
@Composable
fun ProvideWindowSizeClass(
    windowSizeClass: WindowSizeClass,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalWindowSizeClass provides windowSizeClass,
        content = content
    )
}

/** 供非 Composable 场景（如 ViewModel / 工具函数）显式持有时使用。 */
data class WindowMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val isLarge: Boolean,
    val isExpanded: Boolean
)

/** 备用 CompositionLocal：纯数据形式的窗口信息，便于测试注入。 */
val LocalWindowMetrics = compositionLocalOf { WindowMetrics(412, 915, false, false) }
