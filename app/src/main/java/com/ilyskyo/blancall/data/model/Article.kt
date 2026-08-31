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
    val updatedAt: Long = System.currentTimeMillis(),
    // 段落首行是否允许自动缩进：true=粘贴/纯文本等排版自由文本（导入时补两格缩进）；
    // false=PDF/Word 等保持原文不动。阅读/背诵据此统一决定是否缩进。
    val autoIndent: Boolean = true,
    // 作者（选填）：导入时填写 / 详情页可改；列表、详情、搜索结果展示
    val author: String = ""
)
