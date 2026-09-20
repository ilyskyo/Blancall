// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import com.ilyskyo.blancall.data.repository.ReaderPrefs
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.ui.theme.AppPrefs

/**
 * 阅读设置「按文章独立」的共用入口（阅读页 / 遮挡配置列表与编辑器 / 首页卡片都走这里）。
 *
 * 设计见 [ReaderPrefsStore]：有存档用存档；没有则以全局 AppPrefs 当前值作为基线。
 * 所有入口的改动**只写当前文章的存档**，不再污染全局（真机反馈：A 文章调完 B 也跟着变）。
 */

/** 无存档文章的基线：全局 AppPrefs 当前值（兼容既有偏好）。 */
internal fun readerPrefsBaseline(): ReaderPrefs = ReaderPrefs(
    bgMode = AppPrefs.readingBgMode,
    fontId = AppPrefs.readingFontId,
    fontWeight = AppPrefs.readingFontWeight,
    fontPx = AppPrefs.readingFont,
    lineHeight = AppPrefs.readingLineHeight,
    layoutMode = AppPrefs.readingLayoutMode,
    occlusionEnabled = AppPrefs.readingOcclusionEnabled,
    occlusionMode = AppPrefs.readingOcclusionMode,
    occlusionColor = AppPrefs.readingOcclusionColor,
    occlusionCustomConfigId = AppPrefs.readingOcclusionCustomConfigId
)

/** 读取文章阅读设置：有存档用存档，无则全局基线。 */
internal fun loadArticleReaderPrefs(store: ReaderPrefsStore, articleId: Long): ReaderPrefs =
    store.get(articleId) ?: readerPrefsBaseline()

/** 局部更新并持久化「某篇文章」的阅读设置（各入口共用；JSON 很小，可在 IO 线程调用）。 */
internal fun updateArticleReaderPrefs(
    store: ReaderPrefsStore,
    articleId: Long,
    transform: (ReaderPrefs) -> ReaderPrefs
): ReaderPrefs {
    val next = transform(loadArticleReaderPrefs(store, articleId))
    store.save(articleId, next)
    return next
}
