// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 通知点击 → 页面跳转的桥梁。
 * MainActivity 解析通知 Intent → 写入 channel → AppNavigation 收集并导航。
 *
 * 使用 Channel(CONFLATED) 而非 StateFlow：
 * - 支持连续相同的路由（StateFlow 会去重丢弃）
 * - receiveAsFlow 收集即消费，避免双重导航
 * - consume 原子（tryReceive）
 */
object NavigationDispatcher {

    private val _channel = Channel<String>(Channel.CONFLATED)

    /** 待导航路由流，AppNavigation 收集此流执行导航（收集即消费） */
    val pendingRoute: Flow<String> = _channel.receiveAsFlow()

    /**
     * 请求导航到指定路由，例如 "practice/42"
     */
    fun navigate(route: String) {
        _channel.trySend(route)
    }

    /**
     * 原子消费一条待导航路由（如存在），消费后从缓冲中移除。
     * 保留以兼容外部调用；AppNavigation 内部已改为 collect 消费，通常无需手动调用。
     */
    fun consume(): String? {
        return _channel.tryReceive().getOrNull()
    }
}
