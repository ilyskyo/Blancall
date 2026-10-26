// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.StudyCoach
import com.ilyskyo.blancall.data.model.PracticeStatus
import org.json.JSONObject
import java.io.File

/**
 * `practice_state_*.json` 的唯一扫描入口。
 *
 * 只返回「进行中且还有空没答」的原始事实，按最近练习倒序；
 * 暂停文章、已删除文章、空记录数等业务过滤统一在 [StudyCoach.prepare] 内完成。
 * 首页、统计页、复习通知都从这里取数，避免三处各写一份扫描逻辑后口径分叉。
 */
object InProgressPracticeStore {

    fun scan(filesDir: File): List<StudyCoach.InProgressPractice> {
        val files = filesDir.listFiles { file ->
            file.name.startsWith("practice_state_") && file.name.endsWith(".json")
        } ?: return emptyList()

        return files.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                if (json.optString("status", "") != PracticeStatus.IN_PROGRESS.name) {
                    return@runCatching null
                }
                val total = json.optInt("totalBlanks", 0)
                val answered = json.optInt("answeredCount", 0)
                if (total - answered <= 0) return@runCatching null
                StudyCoach.InProgressPractice(
                    articleId = json.optLong("articleId", -1L),
                    mode = json.optString("mode", ""),
                    answered = answered,
                    total = total,
                    lastTime = json.optLong("lastPracticeTime", 0L),
                )
            }.getOrNull()
        }.sortedWith(
            compareByDescending<StudyCoach.InProgressPractice> { it.lastTime }
                .thenBy { it.articleId }
        )
    }
}
