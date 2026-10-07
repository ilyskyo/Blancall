// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 共享元素（hero）转场的接线层。
 *
 * 为什么要自己搭：本项目锁的 Compose 1.10.4 **没有** `LocalSharedTransitionScope`
 * （已核 `_navsrc/anim/.../SharedTransitionScope.kt` —— 整个共享转移树里没有任何
 * CompositionLocal，scope 只作为 [SharedTransitionLayout] content lambda 的 receiver 给出）。
 * 而入口卡在 NavHost 的一个目的地里、详情头部在另一个目的地里，拿不到 receiver 就调不了
 * `sharedElement`，所以这里自建两个组合局部把作用域往下传：
 *
 * - [LocalHeroScope]：整棵 NavHost 共用一个 `SharedTransitionScope`（[HeroTransitionHost] 提供）；
 * - [LocalHeroEntryScope]：**每个目的地自己的** `AnimatedVisibilityScope`（[HeroEntry] 提供）。
 *   必须按条目分开 —— `sharedElement` 要求两侧各自属于自己那一页的可见性转场；
 *   全 app 共用一个的话，两页的可见性互相污染，退场也被算成同一场转场。
 *
 * navigation-compose 这一侧能直接喂：已核 `NavGraphBuilder.composable` 的 content 是
 * `@Composable AnimatedContentScope.(NavBackStackEntry) -> Unit`，而
 * `AnimatedContentScope : AnimatedVisibilityScope`（`AnimatedContent.kt:710`），
 * 所以 `composable { ... }` 里的 `this` 原样传进 [HeroEntry] 就行，不用自己再套一层 AnimatedVisibility。
 */
private val LocalHeroScope = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalHeroEntryScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 把 [content] 接进共享元素作用域：包在 NavHost 外面用一次。
 *
 * 必须待在**嵌套 ComposeView 的 setContent 之内** —— 作用域是组合局部的，
 * 包在 setContent 外面等于没包（目的地在另一个组合根里，取不到这个 receiver）。
 */
@Composable
fun HeroTransitionHost(content: @Composable () -> Unit) {
    // fillMaxSize 不是保险起见：SharedTransitionLayout 自己是个 Layout 节点，
    // 不给它尺寸约束，NavHost 拿到的就是 wrap 约束，整页布局会塌。
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalHeroScope provides this, content = content)
    }
}

/** 给某个返回栈条目的内容提供它自己的 [AnimatedVisibilityScope]。 */
@Composable
fun HeroEntry(entryScope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeroEntryScope provides entryScope, content = content)
}

/**
 * 把这个节点标成跨页共享元素，[key] 两侧必须一致（如 `"article/$id"`）。
 *
 * 任一侧缺作用域（页面没接 [HeroEntry]、或目的地在 [HeroTransitionHost] 之外）时**原样返回**：
 * 不抛错也不改观感，漏接的路线就只是「没有 hero」。这样 hero 才能一页一页地加，
 * 加一半的中间状态也不会把哪条路线弄坏。
 *
 * @param renderInOverlay false = 不把这个子树抬进 overlay，只在原位变形。
 *   默认 true（飞越其他内容之上，才是 hero 的读法）。**承载玻璃的页要传 false**：
 *   抬进 overlay 改变的是「玻璃图层底下是什么」，而 LiquidGlassView 一旦被条件性移出组合
 *   就会重绑采样源 —— GlassPlate / BottomNavBar / ReadingModeScreen 三处注释记的就是
 *   这么干引起的 RenderThread 栈溢出崩溃。一对元素的两端要传同一个值。
 */
@Composable
fun Modifier.heroElement(key: Any?, renderInOverlay: Boolean = true): Modifier {
    val heroScope = LocalHeroScope.current ?: return this
    val entryScope = LocalHeroEntryScope.current ?: return this
    if (key == null) return this
    val state = heroScope.rememberSharedContentState(key)
    return with(heroScope) {
        sharedElement(
            sharedContentState = state,
            animatedVisibilityScope = entryScope,
            renderInOverlayDuringTransition = renderInOverlay,
        )
    }
}
