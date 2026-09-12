// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.AiClozeGenerator
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.data.ai.AiClient
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * AI 控制器（仅 Pro 版）：承载「难度采集 / 训练分析」相关的状态与逻辑，
 * 从 [PracticeViewModel] 抽离而成，行为与原实现保持一致。
 *
 * 设计要点（保障「行为零变化」）：
 * - 所有 AI 状态（难度采集页、生成中/进度/错误、训练分析）的私有 backing flow 仍保留为
 *   `internal`，仅供同包的 [PracticeViewModel] 在 `regenerateCurrentModeViaAi` / `reset`
 *   等无法搬离 VM 的生成/清理路径中直接写入；UI 只通过同名只读 `StateFlow` 读取。
 * - 需要读取/触发 VM 侧状态的地方，一律通过构造时注入的 lambda 回调完成，
 *   不持有 VM 引用、不反向依赖 VM 的生成逻辑。
 * - `regenerateCurrentModeViaAi` / `generateDictationLocal` 及其对挖空结果、段落、答案状态的
 *   写入与保存流程深度耦合，按解耦准则保留在 VM 中，本控制器只通过回调触发它们。
 */

/** AI 挖空生成总超时（毫秒）：超时自动回退本地算法，避免"一直加载" */
internal const val AI_GENERATE_TIMEOUT_MS = 40_000L

class PracticeAiController(
    private val scope: CoroutineScope,
    private val appContext: Application,
    private val getArticle: () -> Article?,
    private val getMode: () -> BlancallMode,
    private val getCheckResults: () -> Map<Int, AnswerChecker.CheckDetail>,
    private val getDictationCheckResult: () -> AnswerChecker.DictationCheckResult?,
    private val getWeakHintCount: () -> Int,
    private val getStrongHintCount: () -> Int,
    private val getSectionMode: () -> SectionMode,
    private val getSelectedSections: () -> Set<Int>,
    private val setStrategy: (BlancallGenerator.Strategy) -> Unit,
    private val setSectionMode: (SectionMode) -> Unit,
    private val setSelectedSections: (Set<Int>) -> Unit,
    private val regenerateCurrentModeViaAi: () -> Unit,
    private val regenerateCloze: () -> Unit,
    private val generateDictationLocal: () -> Unit,
    private val onDifficultyCollectionCancelled: () -> Unit
) {

    // ── AI 挖空（Pro 版）状态 ──

    /** 是否显示难度信息采集页（AI 开启且尚未指定难度时） */
    internal val _showDifficultyInfo = MutableStateFlow(false)
    val showDifficultyInfo: StateFlow<Boolean> = _showDifficultyInfo.asStateFlow()

    /** 已确认的难度档位（难/中等/合适），模式切换时沿用 */
    internal val _difficulty = MutableStateFlow("合适")
    val difficulty: StateFlow<String> = _difficulty.asStateFlow()

    /** 已确认的自定义需求，作为 AI 的可选范围/空数提示 */
    internal val _customRequest = MutableStateFlow("")
    val customRequest: StateFlow<String> = _customRequest.asStateFlow()

    /** AI 生成中（显示挖空生成加载页） */
    internal val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    /** AI 挖空已接收的内容字符数（用于生成页进度反馈，数字递增表明 AI 正在生成） */
    internal val _aiProgress = MutableStateFlow(0)
    val aiProgress: StateFlow<Int> = _aiProgress.asStateFlow()

    /** AI 生成错误提示（失败时回退本地算法，仍可练习） */
    internal val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    internal var aiGenerateJob: Job? = null

    /** 是否已确认过难度（确认后不再重复弹出；模式切换沿用难度直接生成） */
    internal var difficultyConfirmed = false

    /** 用户选择「使用本地算法」后置为 true：后续切模式一律走本地算法，不再用 AI 生成 */
    internal var useLocalCloze = false

    // ── 训练分析（Pro）状态 ──

    internal val _trainingAnalysis = MutableStateFlow<String?>(null)
    /** 本次训练分析（Markdown 文本）；null=尚未生成 */
    val trainingAnalysis: StateFlow<String?> = _trainingAnalysis.asStateFlow()

    internal val _analysisLoading = MutableStateFlow(false)
    val analysisLoading: StateFlow<Boolean> = _analysisLoading.asStateFlow()

    internal val _analysisError = MutableStateFlow(false)
    val analysisError: StateFlow<Boolean> = _analysisError.asStateFlow()

    internal var analysisJob: Job? = null

    // ── 难度采集页统一入口 ──

    /** AI 已开启且未确认难度时弹出采集页（幂等，重复调用无副作用） */
    internal fun maybeCollectDifficultyIfNeeded() {
        if (isAiAvailable() && !difficultyConfirmed && !_showDifficultyInfo.value) {
            _showDifficultyInfo.value = true
            _aiError.value = null
            _isAiGenerating.value = false
        }
    }

    private fun hasAiProfile(): Boolean =
        AiConfigStore.activeChatProfile?.apiKeyEnc?.isNotBlank() == true

    /** AI 引擎是否可用（设置里开启 AI 且已配置有效对话连接） */
    fun isAiAvailable(): Boolean = AppPrefs.aiEnabled && hasAiProfile()

    /** 挖空是否走 AI：AI 引擎可用且开启「使用AI挖空」。关闭时挖空用本地算法，但采集页仍会弹出。 */
    fun isAiClozeEnabled(): Boolean = isAiAvailable() && AppPrefs.useAiCloze

    /** 难度采集页确认：记录难度、挖空策略与自定义需求，并按 AI 生成当前模式挖空。 */
    fun confirmDifficultyAndGenerate(
        difficultyLabel: String,
        custom: String,
        strategy: BlancallGenerator.Strategy
    ) {
        _difficulty.value = AiClozeGenerator.normalizeDifficulty(difficultyLabel)
        _customRequest.value = custom.trim()
        setStrategy(strategy)
        difficultyConfirmed = true
        useLocalCloze = false  // 用户确认 AI 难度 → 走 AI 生成
        _showDifficultyInfo.value = false
        regenerateCurrentModeViaAi()
    }

    /** 取消难度采集：回到进入采集页之前的地方（模式选择浮层重新出现）。 */
    fun cancelDifficultyCollection() {
        _showDifficultyInfo.value = false
        _isAiGenerating.value = false
        _aiError.value = null
        // 清理当前模式的半成品（VM 侧挖空/答案状态），回到"未选模式"状态（Screen 侧恢复模式选择浮层）
        onDifficultyCollectionCancelled()
    }

    /** 采集页"跳过 AI，用本地算法生成"：不采集难度，直接本地挖空。 */
    fun skipAiAndGenerateLocal() {
        _showDifficultyInfo.value = false
        _isAiGenerating.value = false
        _aiError.value = null
        // 用户选择「使用本地算法」：确认无需 AI 难度采集，后续切换模式不再弹采集页、一律走本地
        difficultyConfirmed = true
        useLocalCloze = true
        if (getMode() == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    /** 本地采集页确认（「使用AI挖空」关闭时）：记录挖空策略与段落选择，按本地算法生成当前模式挖空。 */
    fun confirmLocalClozeSettings(
        strategy: BlancallGenerator.Strategy,
        sectionMode: SectionMode,
        selected: Set<Int>
    ) {
        setStrategy(strategy)
        setSectionMode(sectionMode)
        setSelectedSections(selected)
        difficultyConfirmed = true
        useLocalCloze = true  // 本地确认 → 后续一律走本地算法
        _showDifficultyInfo.value = false
        _isAiGenerating.value = false
        _aiError.value = null
        if (getMode() == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    /** 取消当前 AI 生成并回退本地算法（生成页"本地生成"按钮）。 */
    fun cancelAiGeneration() {
        aiGenerateJob?.cancel()
        _isAiGenerating.value = false
        _aiError.value = null
        if (getMode() == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    // ── 训练分析（Pro）：提交判分后由 AI 生成"本次训练分析"（Markdown） ──

    /** 提交判分后调用：AI 开启时自动生成训练分析（失败可手动重试）。 */
    fun generateTrainingAnalysis() {
        if (!isAiAvailable()) return
        if (_analysisLoading.value) return
        analysisJob?.cancel()
        _analysisLoading.value = true
        _analysisError.value = false
        scope.launch {
            val profile = AiConfigStore.activeChatProfile
            if (profile == null) {
                _analysisLoading.value = false
                _analysisError.value = true
                return@launch
            }
            val messages = listOf(
                AiClient.ChatMessage("system", AiClozeGenerator.buildAnalysisSystemPrompt()),
                AiClient.ChatMessage(
                    "user",
                    AiClozeGenerator.buildAnalysisRequest(
                        title = getArticle()?.title.orEmpty(),
                        modeLabel = when (getMode()) {
                            BlancallMode.SENTENCE -> "句子挖空"
                            BlancallMode.WORD -> "字词挖空"
                            BlancallMode.REVERSE -> "反向默写"
                        },
                        scoreLine = buildAnalysisScoreLine(),
                        mistakeSummary = buildAnalysisMistakeSummary(),
                        weakHints = getWeakHintCount(),
                        strongHints = getStrongHintCount()
                    )
                )
            )
            val sb = StringBuilder()
            try {
                val apiKey = withContext(Dispatchers.IO) { profile.decryptApiKey() }
                val completed = withTimeoutOrNull(AI_GENERATE_TIMEOUT_MS) {
                    AiClient.streamChat(profile.baseUrl, apiKey, profile.model, messages)
                        .collect { sb.append(it) }
                    true
                } ?: false
                if (!completed || sb.isEmpty()) throw AiClient.AiException("分析生成超时或无内容")
                val text = sb.toString().trim()
                _trainingAnalysis.value = text
                // 持久化训练分析：退出批改页后仍可在「AI 历史」/学习数据中查看
                persistTrainingAnalysis(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _trainingAnalysis.value = null
                _analysisError.value = true
            } finally {
                _analysisLoading.value = false
            }
        }
    }

    /** 把训练分析写入 ai_analysis/<timestamp>.json */
    private fun persistTrainingAnalysis(text: String) {
        try {
            val dir = File(appContext.filesDir, "ai_analysis").apply { mkdirs() }
            val createdAt = System.currentTimeMillis()
            val json = org.json.JSONObject().apply {
                put("createdAt", createdAt)
                put("articleId", getArticle()?.id ?: 0L)
                put("articleTitle", getArticle()?.title.orEmpty())
                put("modeLabel", when (getMode()) {
                    BlancallMode.SENTENCE -> "句子挖空"
                    BlancallMode.WORD -> "字词挖空"
                    BlancallMode.REVERSE -> "反向默写"
                })
                put("weakHints", getWeakHintCount())
                put("strongHints", getStrongHintCount())
                put("analysis", text)
            }
            File(dir, "$createdAt.json").writeText(json.toString())
        } catch (_: Exception) { /* 分析持久化失败不影响主流程 */ }
    }

    private fun buildAnalysisScoreLine(): String {
        val mode = getMode()
        if (mode == BlancallMode.REVERSE) {
            val r = getDictationCheckResult()
            return if (r == null) "综合得分 0%" else "综合得分 ${(r.overallScore * 100).toInt()}%"
        }
        val res = getCheckResults()
        val total = res.size
        val correct = res.values.count { it.result == AnswerChecker.Result.CORRECT }
        val sim = if (total > 0) res.values.map { it.similarity }.average().toFloat() else 0f
        return "正确 $correct/$total，平均相似度 ${(sim * 100).toInt()}%，共 $total 个空"
    }

    private fun buildAnalysisMistakeSummary(): String {
        val lines = mutableListOf<String>()
        if (getMode() == BlancallMode.REVERSE) {
            getDictationCheckResult()?.sentences?.forEachIndexed { i, s ->
                if (s.result != AnswerChecker.Result.CORRECT) {
                    if (s.matchIndex < 0) {
                        lines.add("- 第${i + 1}处：未匹配到原文（可能漏背或张冠李戴）")
                    } else {
                        lines.add("- 第${i + 1}处：应为「${s.matchedOriginal?.take(20)}」实际「${s.userText.take(20)}」")
                    }
                }
            }
        } else {
            getCheckResults().entries.sortedBy { it.key }.forEach { (idx, d) ->
                if (d.result != AnswerChecker.Result.CORRECT) {
                    val typeName = when (d.result) {
                        AnswerChecker.Result.TYPO -> "错别字（同音/形近误写）"
                        AnswerChecker.Result.MISSING -> "漏字"
                        AnswerChecker.Result.EXTRA -> "多字"
                        AnswerChecker.Result.WRONG_ORDER -> "顺序颠倒"
                        else -> "不正确"
                    }
                    lines.add("- 空${idx + 1}（$typeName）：应为「${d.correctAnswer}」实际「${d.userAnswer}」")
                }
            }
        }
        return if (lines.isEmpty()) "本次全部作答正确（或未发现错误）" else lines.joinToString("\n")
    }

    /** reset() 清理训练分析状态（analysisJob 取消 + 三项复位） */
    fun clearAnalysisState() {
        analysisJob?.cancel()
        _trainingAnalysis.value = null
        _analysisLoading.value = false
        _analysisError.value = false
    }
}
