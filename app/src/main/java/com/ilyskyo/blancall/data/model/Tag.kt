// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.model

/**
 * 文章标签模型与标签库快照（多对多：一篇文章可绑定多个标签）。
 *
 * - [Tag.color] 为 0xRRGGBB（无 alpha）；持久化时写 "#RRGGBB" 字符串。
 * - [TagData.tags] 的**数组顺序即展示/排序顺序**（与 HomeLayoutStore.cards 同语义）。
 * - [TagData.links]：articleId → 已绑定 tag id 集合；读取时已过滤失效 tag id（自愈）。
 */
data class Tag(
    val id: Long,
    val name: String,
    /** 0xRRGGBB（无 alpha） */
    val color: Int,
)

/** 标签库快照（不可变；由 TagStore 的 StateFlow 发布） */
data class TagData(
    val tags: List<Tag> = emptyList(),
    val links: Map<Long, Set<Long>> = emptyMap(),
)
