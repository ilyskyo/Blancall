// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 悬浮底部导航栏的「临时收起」开关（页面级请求）。
 *
 * ## 为什么需要它
 * 底部导航栏是**悬浮在页面容器之上**的玻璃条（高 64dp + 底距 14dp + 系统栏内边距）。
 * 「长按操作」类 UI（我的文章多选操作栏、首页卡片编辑态）把操作按钮放在**紧贴屏幕底部**
 * 的位置，正好落进导航栏的覆盖区 —— 按钮被压住、点不到（用户反馈「操作栏跑到导航栏下面」）。
 * 进入这些状态时请求收起，导航栏按既有下滑动画让位；退出状态后自动滑回。
 *
 * ## 用法（务必成对，随状态自动还原）
 * ```kotlin
 * DisposableEffect(crossSelectMode) {
 *     if (crossSelectMode) NavBarAutoHide.request(NavBarAutoHide.KEY_LIST_MULTI_SELECT)
 *     onDispose { if (crossSelectMode) NavBarAutoHide.release(NavBarAutoHide.KEY_LIST_MULTI_SELECT) }
 * }
 * ```
 * 用「键集合」而不是布尔值：多个来源同时请求时互不打架，最后一个释放后才恢复显示；
 * 页面在请求期间被销毁（切 tab / 导航离开）时 onDispose 兜底释放，不会永久藏栏。
 *
 * 只影响**窄屏底部导航栏**；大屏左侧 NavRail 不受影响（它不遮挡底部操作区）。
 * 调用方均为组合副作用（主线程），无需额外同步。
 *
 * ## 滚动驱动（页面不再为导航栏预留底部留白）
 * 页面挂上 [rememberAutoHideNavBarOnScroll] 后按「滑动中收起、停止即恢复、停在底部保持收起」：
 * - **滑动中**：收起（滚动内容不被悬浮底栏遮挡）；
 * - **滑动停止**：自动恢复显示（去抖无新滚动事件即视为停止）；
 * - **滑到底部**：保持收起——停在底部时也不恢复（最后一段内容天然不被遮挡）；
 *   向上回滚离开底部后停止 → 恢复。
 * 因此滚动容器无需 90–140dp 的「避让留白」。
 */
object NavBarAutoHide {

    /** 「我的文章」多选操作栏（删除选中 / 打标签 / 跨文复习）显示期间 */
    const val KEY_LIST_MULTI_SELECT = "list_multi_select"

    /** 首页卡片编辑态（长按卡片进入） */
    const val KEY_HOME_CARD_EDIT = "home_card_edit"

    /** 滚动驱动自动收起（[rememberAutoHideNavBarOnScroll] 内部使用） */
    const val KEY_SCROLL = "scroll"

    private val activeKeys = mutableSetOf<String>()

    private val _hidden = MutableStateFlow(false)

    /** 是否有任一来源请求收起（AppNavigation 读它决定底栏显隐） */
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    /** 请求收起（幂等：同一 key 只需成对调用一次）。 */
    fun request(key: String) {
        activeKeys.add(key)
        _hidden.value = activeKeys.isNotEmpty()
    }

    /** 释放收起请求（幂等）。 */
    fun release(key: String) {
        activeKeys.remove(key)
        _hidden.value = activeKeys.isNotEmpty()
    }
}

/**
 * 滚动驱动的底栏自动收起（用户规则）：
 * - **滑动中**：收起（内容滚动时不被悬浮底栏遮挡）；
 * - **滑动停止**：自动恢复显示（去抖 [RESTORE_DELAY_MS] 无新滚动事件即视为停止）；
 * - **滑到底部**：保持收起——停在底部时也不恢复（最后一段内容不被遮挡）；
 *   向上回滚离开底部后停止 → 恢复。
 *
 * 悬挂到页面的滚动容器（LazyColumn / LazyVerticalGrid / verticalScroll 所在 Modifier 链）
 * 即可；页面销毁时自动释放滚动键，不会把底栏永久藏住（此前的「收起后不再出现」即由此
 * 去抖恢复机制+页面销毁兼底解决）。
 */
@Composable
fun rememberAutoHideNavBarOnScroll(): NestedScrollConnection {
    val scope = rememberCoroutineScope()
    // 页面销毁（切 tab / 导航离开）时释放滚动键：否则在别页滚动过的状态会把
    // 底栏永久藏住 —— 键集合机制只保证「谁请求谁释放」。
    DisposableEffect(Unit) {
        onDispose { NavBarAutoHide.release(NavBarAutoHide.KEY_SCROLL) }
    }
    return remember {
        object : NestedScrollConnection {
            /** 最近一次滚动时是否已到滚动末端（底部）；向上回滚即清除 */
            private var atEnd = false
            private var restoreJob: Job? = null

            /** 重置「停止」计时：超时无新滚动事件才视为滑动停止 */
            private fun scheduleRestore() {
                restoreJob?.cancel()
                restoreJob = scope.launch {
                    delay(RESTORE_DELAY_MS)
                    // 停在底部保持收起；其余情况恢复显示
                    if (!atEnd) NavBarAutoHide.release(NavBarAutoHide.KEY_SCROLL)
                }
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy != 0f) {
                    // 滑动中：收起；向上回滚（看更早内容）= 已离开「停在底部」语义
                    if (dy > 2f) atEnd = false
                    NavBarAutoHide.request(NavBarAutoHide.KEY_SCROLL)
                    scheduleRestore()
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 还有向下滚动意图却滚不动 ⇒ 已到滚动末端（底部）
                if (available.y < -2f) atEnd = true
                return Offset.Zero
            }
        }
    }
}

/** 滑动停止判定延时：无新滚动事件超过该时长即视为停止（恢复底栏）。 */
private const val RESTORE_DELAY_MS = 350L
