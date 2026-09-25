// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提交后的评分辅助（FSRS 状态更新与评级解析）。
 *
 * 从 PracticeViewModel.kt 拆出（纯搬移；行为不变）。
 */

    /**
     * 练习结束后更新 FSRS 记忆状态：评级 → 状态更新 → 持久化。
     * FSRS 按文章维护独立难度与稳定性，实现完全自适应的复习调度。
     * 任何失败都不影响练习主流程。
     */
    internal fun PracticeViewModel.updateFsrsState(articleId: Long, rating: FsrsEngine.Rating) {
        // 跨文练习的 articleId = -1（混合内容无单篇归属）：负/零 id 不写 FSRS，避免孤儿状态
        if (articleId <= 0L) return
        try {
            val store = FsrsStateStore.getInstance(
                getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
            )
            val newState = FsrsEngine.review(
                store.get(articleId) ?: FsrsEngine.CardState(),
                rating
            )
            // 持久化切 IO 线程，避免主线程阻塞文件写
            viewModelScope.launch {
                withContext(Dispatchers.IO) { store.save(articleId, newState) }
            }
        } catch (_: Exception) { /* FSRS 状态更新失败不影响主流程 */ }
    }

    /**
     * 评级解析：默认按默写相似度→四档（FSRS-6 产品语义）；
     * 回退开关开启旧行为时按正确率→四档。
     */
    internal fun PracticeViewModel.resolveRating(similarity: Float, accuracy: Float): FsrsEngine.Rating =
        if (AppPrefs.useSimilarityRating) FsrsEngine.gradeFromSimilarity(similarity)
        else FsrsEngine.ratingFromAccuracy(accuracy)
