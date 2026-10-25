// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.data.model.PracticeState
import com.ilyskyo.blancall.data.model.PracticeStatus
import com.ilyskyo.blancall.notification.PracticeLiveUpdate
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练习进度落盘与清除（「继续练习」的恢复来源）。
 *
 * 从 PracticeViewModel.kt 拆出（纯搬移；落盘仍走 AtomicFiles，行为不变）。
 */

    /** 保存练习进度到文件（为「继续练习」预留）。必须从协程调用，文件写入切到 IO 线程。
     *  dictationInput 仅反向默写模式使用，其他模式传空串即可。
     *  返回是否真的写成功：退后台要「先存下来再上岛」，不能把失败当成已保存。 */
    internal suspend fun PracticeViewModel.savePracticeState(articleId: Long, answers: Map<Int, String>, dictationInput: String = ""): Boolean {
        return try {
            // 本次挖好的空序列化：供「继续练习」恢复（无需重新生成/选难度）
            val clozeJson = when (_mode.value) {
                BlancallMode.SENTENCE -> _sentenceCloze.value?.let { BlancallGenerator.sentenceClozeToJson(it) }
                BlancallMode.WORD -> _wordCloze.value?.let { BlancallGenerator.wordClozeToJson(it) }
                BlancallMode.REVERSE -> _dictationResult.value?.let { BlancallGenerator.dictationToJson(it) }
            }
            // 反向默写以"是否已输入"作为已答进度，便于首页"继续练习"卡片显示剩余量
            val answeredCount = if (_mode.value == BlancallMode.REVERSE) {
                if (dictationInput.isNotBlank()) _totalBlanks.value else 0
            } else {
                answers.values.count { it.isNotBlank() }
            }
            val state = PracticeState(
                articleId = articleId,
                mode = _mode.value.name,
                status = PracticeStatus.IN_PROGRESS,
                totalBlanks = _totalBlanks.value,
                answeredCount = answeredCount,
                answers = answers.filter { it.value.isNotBlank() },
                dictationInput = dictationInput,
                clozeJson = clozeJson,
                // 自定义练习的身份：供「继续练习」恢复后重新挂上锁定
                configId = activeCustomConfig?.id ?: 0L,
                // 句级聚焦会话的目标句：恢复时要按同一窗口重建锚点（见 PracticeViewModel.loadArticle）
                focusSentence = activeFocusWindow?.focusSentence ?: -1
            )
            val file = getApplication<Application>().filesDir.resolve("practice_state_${articleId}.json")
            withContext(Dispatchers.IO) {
                val json = org.json.JSONObject()
                json.put("articleId", state.articleId)
                json.put("mode", state.mode)
                json.put("status", state.status.name)
                json.put("totalBlanks", state.totalBlanks)
                json.put("answeredCount", state.answeredCount)
                json.put("dictationInput", state.dictationInput)
                json.put("lastPracticeTime", state.lastPracticeTime)
                if (state.clozeJson != null) json.put("clozeJson", state.clozeJson)
                if (state.configId > 0L) json.put("configId", state.configId)
                if (state.focusSentence >= 0) json.put("focusSentence", state.focusSentence)
                val ansObj = org.json.JSONObject()
                state.answers.forEach { (k, v) -> ansObj.put(k.toString(), v) }
                json.put("answers", ansObj)
                // 原子写 + fsync（练习进度是「继续练习」的恢复来源，防写一半被杀损坏）
                AtomicFiles.writeTextAtomic(file, json.toString())
            }
            true
        } catch (_: Exception) {
            // 静默保存，不影响主流程；false 只用于阻止「没存下也上岛」
            false
        }
    }

    /**
     * 应用退到后台时保存当前作答，供常驻通知点进来续练。
     *
     * 与「部分提交」的区别：这里不判分、不写记录、不改 FSRS，只留一份可恢复的答题现场。
     * 没有任何输入 / 跨文练习 / 已提交的会话返回 null —— 前两者恢复不了，后者已经结束了。
     */
    internal suspend fun PracticeViewModel.saveForAppExit(): PracticeLiveUpdate.ExitSnapshot? {
        if (_isSubmitted.value || isCrossMode.value) return null
        val article = article.value ?: return null
        if (article.id <= 0L) return null
        val mode = _mode.value
        val answers = when (mode) {
            BlancallMode.SENTENCE -> _sentenceAnswers.value
            BlancallMode.WORD -> _wordAnswers.value
            BlancallMode.REVERSE -> emptyMap()
        }
        val dictation = _dictationInput.value
        val hasInput = if (mode == BlancallMode.REVERSE) dictation.isNotBlank()
        else answers.values.any { it.isNotBlank() }
        val total = _totalBlanks.value
        if (!hasInput || total <= 0) return null
        if (!savePracticeState(article.id, answers, dictation)) return null
        return PracticeLiveUpdate.ExitSnapshot(
            articleId = article.id,
            articleTitle = article.title,
            mode = mode,
            totalBlanks = total,
            answeredBlanks = if (mode == BlancallMode.REVERSE) total
            else answers.values.count { it.isNotBlank() },
            typedChars = dictation.count { !it.isWhitespace() },
        )
    }

    /** 删除指定文章的练习进度文件（完整提交后调用，避免首页继续显示已完成练习） */
    internal fun PracticeViewModel.clearPracticeState(articleId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().filesDir
                        .resolve("practice_state_${articleId}.json").delete()
                } catch (_: Exception) { }
            }
        }
    }
