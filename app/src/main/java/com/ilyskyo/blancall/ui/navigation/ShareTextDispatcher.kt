// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 系统分享文本的桥：Activity 的 Intent 与 Compose 导航之间隔着生命周期边界，
 * 而冷启动时正文还没组合，不能直接 navigate。
 *
 * 用 [Channel] 而不是 StateFlow：同一段文字被分享两次也必须各弹一次确认页
 * （StateFlow 会按相等性去重，第二次分享会静默消失）。
 */
object ShareTextDispatcher {
    private val channel = Channel<String>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    fun submit(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isNotEmpty()) channel.trySend(text!!)
    }
}
