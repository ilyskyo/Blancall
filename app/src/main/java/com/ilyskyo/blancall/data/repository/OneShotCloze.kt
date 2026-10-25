// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次性挖空：只在内存里活一次的配置，供「遮挡段转练」「错题只练这一批」使用。
 *
 * ## 为什么不能写进 CustomClozeStore
 * 自定义挖空是用户**手工维护的长期配置**；这里生成的是「按当下这一段/这一批错」的
 * 一次性快照。写进 store 会让配置列表里堆满一次性的东西，也等于把两套语义混成一套
 * （报告里明确列为红线）。因此：不落盘、不备份、进程死亡即消失。
 *
 * 代价与处理：进程被系统杀死后回到该路由会取不到配置。调用方必须提示
 * 「练习已失效，请重新选择」而不是静默生成一篇随机挖空（那会让用户以为配置生效了）。
 */
object OneShotCloze {

    data class Pending(
        val articleId: Long,
        val config: CustomClozeStore.CustomConfig,
    )

    private val tokens = AtomicLong(System.currentTimeMillis())
    private val pending = ConcurrentHashMap<Long, Pending>()

    /** 暂存一套配置，返回一次性 token（拼进导航路由）。 */
    fun put(articleId: Long, config: CustomClozeStore.CustomConfig): Long {
        val token = tokens.incrementAndGet()
        pending[token] = Pending(articleId, config)
        // 防御性上限：正常使用只会有一两个在途；异常调用也不允许无限增长
        while (pending.size > MAX_PENDING) {
            pending.keys.firstOrNull()?.let { pending.remove(it) }
        }
        return token
    }

    /** 取出并消费；articleId 不匹配视为失效（防止把 A 篇的配置套到 B 篇）。 */
    fun take(articleId: Long, token: Long): Pending? {
        if (token <= 0L) return null
        val p = pending.remove(token) ?: return null
        return p.takeIf { it.articleId == articleId }
    }

    private const val MAX_PENDING = 8
}
