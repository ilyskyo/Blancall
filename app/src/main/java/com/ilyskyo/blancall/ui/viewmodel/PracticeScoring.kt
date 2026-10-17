// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.ReviewLogStore
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
            val prev = store.get(articleId) ?: FsrsEngine.CardState()
            val now = System.currentTimeMillis()
            val newState = FsrsEngine.review(prev, rating, now)
            // 持久化切 IO 线程，避免主线程阻塞文件写
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    store.save(articleId, newState)
                    // 复习事件日志（B3-4）：与状态更新同批落盘；写失败不阻断状态保存
                    ReviewLogStore.append(
                        reviewLogFile(), "a:$articleId", now, rating.value,
                        elapsedMs = if (prev.lastReview > 0) now - prev.lastReview else 0L,
                        state = newState
                    )
                }
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

    /**
     * 逐句写回句子级 FSRS 状态（键 = `s:<articleId>:<hash16>`，与每日一句同一命名空间）。
     *
     * 这里刻意不复用 [resolveRating] 的相似度通道：逐句只有「这几个空写对几个」，
     * 没有整段默写相似度这个概念，硬套会让 `useSimilarityRating` 那个开关在句子级悄悄失效。
     * 写入走 IO 线程、任何失败只记日志（与 [updateFsrsState] 同样的「绝不影响练习主流程」）。
     */
    internal fun PracticeViewModel.updateSentenceStates(ratings: Map<String, FsrsEngine.Rating>) {
        if (ratings.isEmpty()) return
        try {
            val store = FsrsStateStore.getInstance(
                getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
            )
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    ratings.forEach { (key, rating) ->
                        // 逐条串行：同一次提交里同一个键不会被并发写两次，
                        // 而 store 内部的 persistLock 负责挡跨提交的并发（见 FsrsStateStore）
                        val prev = store.getSentence(key) ?: FsrsEngine.CardState()
                        val now = System.currentTimeMillis()
                        val next = FsrsEngine.review(prev, rating, now)
                        store.saveSentence(key, next)
                        // 复习事件日志（B3-4）：每条状态更新配一条事件；写失败不阻断状态保存
                        ReviewLogStore.append(
                            reviewLogFile(), key, now, rating.value,
                            elapsedMs = if (prev.lastReview > 0) now - prev.lastReview else 0L,
                            state = next
                        )
                    }
                }
            }
        } catch (_: Exception) { /* 句子级状态写失败不影响本次练习结果 */ }
    }

/** 复习事件日志文件（B3-4）：filesDir 根，命名与 [ReviewLogStore] 同源 */
private fun PracticeViewModel.reviewLogFile(): File =
    getApplication<Application>().filesDir.resolve(ReviewLogStore.FILE_NAME)
