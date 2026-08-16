// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Article(
    val id: Long = 0,
    val title: String,
    val content: String,
    // 创建时间；默认取当前时间，构造时确定。更新文章内容时应刷新 updatedAt
    val createdAt: Long = System.currentTimeMillis(),
    // 更新时间；默认取当前时间。仓库层 update 时应刷新此字段以反映最后修改
    val updatedAt: Long = System.currentTimeMillis()
)
