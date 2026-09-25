// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.ui.common.StylusActivity
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 提示计时（弱提示淡显 / 强提示自动填入）与提示文本工具。
 *
 * 从 PracticeViewModel.kt 拆出（纯搬移：函数签名加接收者 + private→internal；行为不变）。
 */

    internal fun PracticeViewModel.startBlankHint(blankIndex: Int, expected: String) {
        blankHintJobs[blankIndex]?.cancel()
        // 立即清掉该空残留的旧提示字：被 cancel 的协程不会执行末尾清理，不清会一直挂着旧提示
        _hintChars.value = _hintChars.value - blankIndex
        blankHintJobs[blankIndex] = viewModelScope.launch {
            try {
                var firstWait = true
                while (isActive) {
                    val cur = currentAnswerText(blankIndex)
                    if (cur.length >= expected.length) break
                    val ch = expected[cur.length]                       // 下一个应填的字
                    val v0 = blankInputVersions[blankIndex] ?: 0
                    delay(if (firstWait) HINT_FIRST_WAIT_MS else 0L)    // 首次 10s；强填后下一字立即淡显
                    if (v0 != (blankInputVersions[blankIndex] ?: 0)) break  // 用户新输入打断（外层会重启）
                    // 弱提示：淡显下一字（UI 端 5s 淡入动画）
                    _weakHintCount.value += 1
                    _hintChars.value = _hintChars.value + (blankIndex to ch)
                    delay(HINT_FADE_MS)
                    if (v0 != (blankInputVersions[blankIndex] ?: 0)) break
                    delay(HINT_AFTER_WAIT_MS)
                    if (v0 != (blankInputVersions[blankIndex] ?: 0)) break
                    // 强提示：自动填入（不打断当前循环，继续提示下一个字）
                    if (currentAnswerText(blankIndex).length < expected.length) {
                        setAnswerText(blankIndex, currentAnswerText(blankIndex) + ch)
                        _strongHintCount.value += 1
                        firstWait = false
                        continue
                    }
                    break
                }
            } finally {
                // 正常结束或被取消都清理提示字，避免旧提示残留
                _hintChars.value = _hintChars.value - blankIndex
            }
        }
    }

    /**
     * 手写作答期间**不做任何提示**。
     *
     * 弱提示的节奏是「无输入 10s → 淡显下一字 → 再 5s → **自动填入**」，用户正拿笔写字时：
     * 淡显的字会被他当成自己写的、自动填入更是直接替他改答案 —— 纯打断。
     * 用户要求：只要有手写笔在手写，就不要提示。
     */
    internal fun PracticeViewModel.isHandwritingActive(): Boolean =
        AppPrefs.handwritingInputEnabled || StylusActivity.isWriting

    internal fun PracticeViewModel.maybeStartBlankHint(blankIndex: Int) {
        if (_isSubmitted.value || !_showHint.value || isHandwritingActive()) {
            blankHintJobs[blankIndex]?.cancel()
            return
        }
        val expected = expectedText(blankIndex) ?: return
        if (expected.isBlank()) return
        startBlankHint(blankIndex, expected)
    }

    /**
     * 确保提示计时运行（进入作答界面 / 聚焦某空时调用）：
     * 无论用户是否输入过，只要满足无操作时长就淡显 / 强填下一个字。
     *
     * @param blankIndex 字词/句子模式当前聚焦的空；反向默写传 null（整段输入计时）
     */
    internal fun PracticeViewModel.ensureHintTimer(blankIndex: Int? = null) {
        // 手写作答期间不提示（见 isHandwritingActive）
        if (_isSubmitted.value || !_showHint.value || isHandwritingActive()) return
        when (_mode.value) {
            BlancallMode.SENTENCE, BlancallMode.WORD -> {
                val idx = blankIndex ?: return
                if (expectedText(idx) == null) return
                // 聚焦切换：其余空的计时与淡显只保留当前空，避免后台提示串位
                blankHintJobs.entries.forEach { (bid, job) -> if (bid != idx) job.cancel() }
                blankHintJobs.keys.retainAll(setOf(idx))
                if (_hintChars.value.size > 1 || _hintChars.value.keys.firstOrNull() != idx) {
                    _hintChars.value = _hintChars.value.filterKeys { it == idx }
                }
                maybeStartBlankHint(idx)
            }
            BlancallMode.REVERSE -> {
                // 计时已运行则不重置（仅输入变化时经 updateDictationInput 才重计）
                if (dictationHintJob?.isActive == true) return
                startDictationHint()
            }
        }
    }

    internal fun PracticeViewModel.expectedText(blankIndex: Int): String? = when (_mode.value) {
        BlancallMode.SENTENCE -> _sentenceCloze.value?.blanks?.getOrNull(blankIndex)?.originalText
        BlancallMode.WORD -> _wordCloze.value?.blanks?.getOrNull(blankIndex)?.originalChar
        else -> null
    }

    internal fun PracticeViewModel.currentAnswerText(blankIndex: Int): String = when (_mode.value) {
        BlancallMode.SENTENCE -> _sentenceAnswers.value[blankIndex].orEmpty()
        BlancallMode.WORD -> _wordAnswers.value[blankIndex].orEmpty()
        else -> ""
    }

    internal fun PracticeViewModel.setAnswerText(blankIndex: Int, text: String) {
        when (_mode.value) {
            BlancallMode.SENTENCE -> _sentenceAnswers.value = _sentenceAnswers.value + (blankIndex to text)
            BlancallMode.WORD -> _wordAnswers.value = _wordAnswers.value + (blankIndex to text)
            // 反向默写使用独立的整段输入，不走按空作答路径
            BlancallMode.REVERSE -> {}
        }
    }

    internal fun PracticeViewModel.stopAllBlankHints() {
        blankHintJobs.values.forEach { it.cancel() }
        blankHintJobs.clear()
        _hintChars.value = emptyMap()
        dictationHintJob?.cancel()
        _dictationHint.value = null
    }

    /**
     * 反向默写（整段输入）弱/强提示：按原文顺序，无输入 10s 淡显下一字（5s）、
     * 再 5s 无输入自动填入并循环；任何键入重新计时。
     */
    internal fun PracticeViewModel.startDictationHint() {
        // 手写作答期间不提示（见 isHandwritingActive）
        if (_isSubmitted.value || !_showHint.value || isHandwritingActive()) {
            dictationHintJob?.cancel()
            _dictationHint.value = null
            return
        }
        val expected = _dictationResult.value?.clauses?.joinToString("") ?: return
        if (expected.isBlank()) return
        dictationHintJob?.cancel()
        _dictationHint.value = null
        dictationHintJob = viewModelScope.launch {
            try {
                var firstWait = true
                while (isActive) {
                    val cur = _dictationInput.value
                    if (cur.length >= expected.length) break
                    val ch = expected[cur.length]
                    val v0 = dictationInputVersion
                    delay(if (firstWait) HINT_FIRST_WAIT_MS else 0L)
                    if (v0 != dictationInputVersion) break
                    // 弱提示：淡显下一字（UI 端 5s 淡入动画）
                    _weakHintCount.value += 1
                    _dictationHint.value = ch
                    delay(HINT_FADE_MS)
                    if (v0 != dictationInputVersion) break
                    delay(HINT_AFTER_WAIT_MS)
                    if (v0 != dictationInputVersion) break
                    // 强提示：自动填入（不打断循环，继续提示下一个字）
                    if (_dictationInput.value.length < expected.length) {
                        _dictationInput.value += ch
                        _strongHintCount.value += 1
                        firstWait = false
                        continue
                    }
                    break
                }
            } finally {
                // 正常结束或被取消都清理提示字，避免旧提示残留
                _dictationHint.value = null
            }
        }
    }

/**
 * 首次无输入 10s 后淡显提示字；淡显完成后再等 5s 无输入则强填
 */
internal const val HINT_FIRST_WAIT_MS = 10_000L
/** 提示字淡入时长（UI 动画同步 5s） */
internal const val HINT_FADE_MS = 5_000L
/** 淡显完成后再等待时长（到点未输入则自动填入） */
internal const val HINT_AFTER_WAIT_MS = 5_000L

