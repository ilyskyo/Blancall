// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.ui.common.StylusActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
     * 板面墨迹变化对提示计时的处置（纯逻辑，便于单测）。
     *
     * - [CANCEL] 板上有墨迹 ⇒ 取消计时并清掉已淡显的提示字。
     *   **只在真正有墨迹的时候拦**：手写态并非全程无提示，用户要求
     *   「手写也该有强弱提示，只是别在写着的时候冒出来」。
     * - [RESUME] 板空 ⇒ 从首次 10s 重新计时（不接着刚才的进度，
     *   否则多笔字「女」抬笔写「子」的间隙会立刻弹出下一个提示字）。
     * - [IDLE] 已提交 / 提示被关 ⇒ 什么都不做。
     */
    internal enum class InkHintAction { CANCEL, RESUME, IDLE }

    internal fun inkHintAction(
        hasInkOnBoard: Boolean,
        submitted: Boolean,
        hintEnabled: Boolean
    ): InkHintAction = when {
        submitted || !hintEnabled -> InkHintAction.IDLE
        hasInkOnBoard -> InkHintAction.CANCEL
        else -> InkHintAction.RESUME
    }

    /**
     * 书写板此刻有没有墨迹（正在写的那一笔、或已抬笔但还留在板上的都算）。
     *
     * ⚠️ 这里**不能**用 `AppPrefs.handwritingInputEnabled`：那是「默认输入方式」开关，
     * 开着就永远为真，等于回到「手写全程无提示」。也不能用 `StylusActivity.isWriting`
     * ——那是掌托守卫，只对笔置位，手指书写永远判不到。
     */
    internal fun PracticeViewModel.hasInkOnBoard(): Boolean = StylusActivity.hasInkOnBoard

    /** 板面墨迹变化 ⇒ 提示计时启停。订阅 [StylusActivity.inkFlow]，VM 存活期间常驻。 */
    internal fun PracticeViewModel.bindInkHintTimer() {
        viewModelScope.launch {
            StylusActivity.inkFlow.collect { hasInk ->
                when (inkHintAction(hasInk, _isSubmitted.value, _showHint.value)) {
                    InkHintAction.CANCEL -> stopAllBlankHints()
                    InkHintAction.RESUME -> resumeHintTimer()
                    InkHintAction.IDLE -> Unit
                }
            }
        }
    }

    /**
     * 板面清空后把提示计时接回去。
     *
     * 恢复到**刚才那个空**（[lastHintedBlank]），而不是「当前聚焦的空」：
     * 弹层里写的是弹层对应的空，焦点可能在别处，恢复到错的空会让提示串位。
     */
    internal fun PracticeViewModel.resumeHintTimer() {
        when (_mode.value) {
            BlancallMode.REVERSE -> startDictationHint()
            BlancallMode.SENTENCE, BlancallMode.WORD ->
                lastHintedBlank?.let { maybeStartBlankHint(it) }
        }
    }

    internal fun PracticeViewModel.maybeStartBlankHint(blankIndex: Int) {
        if (_isSubmitted.value || !_showHint.value || hasInkOnBoard()) {
            blankHintJobs[blankIndex]?.cancel()
            return
        }
        val expected = expectedText(blankIndex) ?: return
        if (expected.isBlank()) return
        lastHintedBlank = blankIndex
        startBlankHint(blankIndex, expected)
    }

    /**
     * 确保提示计时运行（进入作答界面 / 聚焦某空时调用）：
     * 无论用户是否输入过，只要满足无操作时长就淡显 / 强填下一个字。
     *
     * @param blankIndex 字词/句子模式当前聚焦的空；反向默写传 null（整段输入计时）
     */
    internal fun PracticeViewModel.ensureHintTimer(blankIndex: Int? = null) {
        // 板上有墨迹时不启动（见 hasInkOnBoard）；板空后由 bindInkHintTimer 接回
        if (_isSubmitted.value || !_showHint.value || hasInkOnBoard()) return
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
        // 板上有墨迹时不提示（见 hasInkOnBoard）
        if (_isSubmitted.value || !_showHint.value || hasInkOnBoard()) {
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

