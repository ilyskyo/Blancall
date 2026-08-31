// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.AiClozeGenerator
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.CrossTextReview
import com.ilyskyo.blancall.algorithm.DictationScorer
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.PracticeState
import com.ilyskyo.blancall.data.model.PracticeStatus
import com.ilyskyo.blancall.data.ai.AiClient
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

enum class BlancallMode { SENTENCE, WORD, REVERSE }

/** AI 挖空生成总超时（毫秒）：超时自动回退本地算法，避免"一直加载" */
private const val AI_GENERATE_TIMEOUT_MS = 40_000L

/** 段落分层复习模式 */
enum class SectionMode { FULL, SELECTED, WEAKNESS }

/** 空数无法满足时的警告信息 */
data class BlankCountWarning(
    val requestedCount: Int,
    val maxBlanks: Int,
    val suggestedBlanks: Int,
    val actualCount: Int
)

/** 构建错误画像：从练习记录中提取每个句子/字/词的错误率 */
fun buildErrorProfile(records: List<PracticeRecord>): BlancallGenerator.ErrorProfile {
    val sentenceErrors = mutableMapOf<Int, Float>()
    val charErrors = mutableMapOf<Char, Float>()
    val wordErrors = mutableMapOf<String, Float>()

    // 统计每种错误的出现次数和总练习次数（近似）
    for (record in records) {
        for (m in record.mistakes) {
            // 字符错误率
            m.correctAnswer.forEach { ch ->
                charErrors[ch] = (charErrors[ch] ?: 0f) + 1f
            }
            // 词错误率
            if (m.correctAnswer.length in 1..3) {
                wordErrors[m.correctAnswer] = (wordErrors[m.correctAnswer] ?: 0f) + 1f
            }
        }
    }

    // 归一化到 0-1
    val maxCharErr = charErrors.values.maxOrNull() ?: 1f
    val maxWordErr = wordErrors.values.maxOrNull() ?: 1f
    if (maxCharErr > 0) charErrors.keys.forEach { charErrors[it] = (charErrors[it] ?: 0f) / maxCharErr }
    if (maxWordErr > 0) wordErrors.keys.forEach { wordErrors[it] = (wordErrors[it] ?: 0f) / maxWordErr }

    return BlancallGenerator.ErrorProfile(
        sentenceErrorRates = sentenceErrors,
        charErrorRates = charErrors,
        wordErrorRates = wordErrors
    )
}

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ArticleRepository(
        application.filesDir.resolve("articles.json").absolutePath
    )

    private val _article = MutableStateFlow<Article?>(null)
    val article: StateFlow<Article?> = _article.asStateFlow()

    private val _mode = MutableStateFlow(BlancallMode.SENTENCE)
    val mode: StateFlow<BlancallMode> = _mode.asStateFlow()

    // 句子挖空
    private val _sentenceCloze = MutableStateFlow<BlancallGenerator.SentenceClozeResult?>(null)
    val sentenceCloze: StateFlow<BlancallGenerator.SentenceClozeResult?> = _sentenceCloze.asStateFlow()

    // 字词挖空
    private val _wordCloze = MutableStateFlow<BlancallGenerator.WordClozeResult?>(null)
    val wordCloze: StateFlow<BlancallGenerator.WordClozeResult?> = _wordCloze.asStateFlow()

    // 用户答案——两种模式各自独立保存，切换时不丢失
    private val _sentenceAnswers = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val _wordAnswers = MutableStateFlow<Map<Int, String>>(emptyMap())
    val userAnswers: StateFlow<Map<Int, String>> = combine(
        _mode, _sentenceAnswers, _wordAnswers
    ) { mode, s, w ->
        when (mode) {
            BlancallMode.SENTENCE -> s
            BlancallMode.WORD -> w
            BlancallMode.REVERSE -> s
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _checkResults = MutableStateFlow<Map<Int, AnswerChecker.CheckDetail>>(emptyMap())
    val checkResults: StateFlow<Map<Int, AnswerChecker.CheckDetail>> = _checkResults.asStateFlow()

    private val _isSubmitted = MutableStateFlow(false)
    val isSubmitted: StateFlow<Boolean> = _isSubmitted.asStateFlow()

    // 是否从上次的练习进度恢复（用于 PracticeScreen 跳过模式选择界面）
    private val _resumed = MutableStateFlow(false)
    val resumed: StateFlow<Boolean> = _resumed.asStateFlow()

    // 提交中（判分进行时），用于禁用提交按钮防重复点击 + UI 显示 loading
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _totalBlanks = MutableStateFlow(0)
    val totalBlanks: StateFlow<Int> = _totalBlanks.asStateFlow()

    // 字词挖空数量（0 = 自动）
    private val _wordBlankCount = MutableStateFlow(0)
    val wordBlankCount: StateFlow<Int> = _wordBlankCount.asStateFlow()

    // 是否启用默写提示（菜单"显示提示"开关，默认开启）：控制弱/强提示功能
    private val _showHint = MutableStateFlow(true)
    val showHint: StateFlow<Boolean> = _showHint.asStateFlow()

    // 空数警告提示
    private val _blankCountWarning = MutableStateFlow<BlankCountWarning?>(null)
    val blankCountWarning: StateFlow<BlankCountWarning?> = _blankCountWarning.asStateFlow()

    // 挖空策略
    private val _strategy = MutableStateFlow(BlancallGenerator.Strategy.BALANCED)
    val strategy: StateFlow<BlancallGenerator.Strategy> = _strategy.asStateFlow()

    // 古文模式
    private val _classicalMode = MutableStateFlow(false)
    val classicalMode: StateFlow<Boolean> = _classicalMode.asStateFlow()

    // ── AI 挖空（Pro 版）──
    /** 是否显示难度信息采集页（AI 开启且尚未指定难度时） */
    private val _showDifficultyInfo = MutableStateFlow(false)
    val showDifficultyInfo: StateFlow<Boolean> = _showDifficultyInfo.asStateFlow()

    /** 已确认的难度档位（难/中等/合适），模式切换时沿用 */
    private val _difficulty = MutableStateFlow("合适")
    val difficulty: StateFlow<String> = _difficulty.asStateFlow()

    /** 已确认的自定义需求，作为 AI 的可选范围/空数提示 */
    private val _customRequest = MutableStateFlow("")
    val customRequest: StateFlow<String> = _customRequest.asStateFlow()

    /** AI 生成中（显示挖空生成加载页） */
    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    /** AI 挖空已接收的内容字符数（用于生成页进度反馈，数字递增表明 AI 正在生成） */
    private val _aiProgress = MutableStateFlow(0)
    val aiProgress: StateFlow<Int> = _aiProgress.asStateFlow()

    /** AI 生成错误提示（失败时回退本地算法，仍可练习） */
    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    /** 练习页字号缩放倍数（双指缩放），便于个性化阅读 */
    private val _fontScale = MutableStateFlow(1f)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private var aiGenerateJob: Job? = null

    // 反向默写（段落打散默写）—— 把段落切成句子并打乱顺序作为线索，用户默写原文
    private val _dictationResult = MutableStateFlow<BlancallGenerator.DictationResult?>(null)
    val dictationResult: StateFlow<BlancallGenerator.DictationResult?> = _dictationResult.asStateFlow()

    // 用户默写输入的整段文本
    private val _dictationInput = MutableStateFlow("")
    val dictationInput: StateFlow<String> = _dictationInput.asStateFlow()

    // 反向默写整段判分结果
    private val _dictationCheckResult = MutableStateFlow<AnswerChecker.DictationCheckResult?>(null)
    val dictationCheckResult: StateFlow<AnswerChecker.DictationCheckResult?> = _dictationCheckResult.asStateFlow()

    // 段落分层（F3）
    private val _sections = MutableStateFlow<List<SectionSplitter.Section>>(emptyList())
    val sections: StateFlow<List<SectionSplitter.Section>> = _sections.asStateFlow()

    private val _sectionMode = MutableStateFlow(SectionMode.FULL)
    val sectionMode: StateFlow<SectionMode> = _sectionMode.asStateFlow()

    private val _selectedSections = MutableStateFlow<Set<Int>>(emptySet())
    val selectedSections: StateFlow<Set<Int>> = _selectedSections.asStateFlow()

    private val _rankedSections = MutableStateFlow<List<SectionSplitter.RankedSection>>(emptyList())
    val rankedSections: StateFlow<List<SectionSplitter.RankedSection>> = _rankedSections.asStateFlow()

    /**
     * 句子锚点表：**当前 effectiveContent 中的句子索引 → 该句在文章全文中的字符起始位置**。
     *
     * 判分时 blank 携带的 sentenceIndex 只相对 effectiveContent（段落模式下只是若干段落的拼接
     * 子集），与记忆热力图所用的「全文切句」口径不同——标题行在全文切句中独立成句，而段落
     * contentOnly 不含标题，两者会整体错位。记录字符位置可跨口径稳定对齐。
     *
     * 由 [buildSentenceAnchors] 在每次生成挖空时同步计算；为空表示尚未生成，
     * 此时判分不写 answeredSentenceStarts，热力图自动回退到整篇统计。
     */
    private val _sentenceAnchors = MutableStateFlow<List<Int>>(emptyList())
    val sentenceAnchors: StateFlow<List<Int>> = _sentenceAnchors.asStateFlow()

    // 沉浸模式（F4）
    private val _immersiveMode = MutableStateFlow(false)
    val immersiveMode: StateFlow<Boolean> = _immersiveMode.asStateFlow()

    /** 渐进式挖空密度等级：0=初级, 1=中级, 2=高级 */
    private val _progressiveLevel = MutableStateFlow(0)
    val progressiveLevel: StateFlow<Int> = _progressiveLevel.asStateFlow()

    /** 当前连续正确数（用于渐进升级） */
    private var consecutiveCorrect = 0

    /** 本次练习开始时间戳，提交时用于计算 duration（毫秒）。0 表示尚未开始 */
    private var practiceStartTime: Long = 0L

    // 跨文本联动（F7）
    private val _isCrossMode = MutableStateFlow(false)
    val isCrossMode: StateFlow<Boolean> = _isCrossMode.asStateFlow()

    private val _crossSourceInfo = MutableStateFlow<List<CrossTextReview.SourceInfo>>(emptyList())
    val crossSourceInfo: StateFlow<List<CrossTextReview.SourceInfo>> = _crossSourceInfo.asStateFlow()

    private val _crossArticleTitles = MutableStateFlow<List<String>>(emptyList())
    val crossArticleTitles: StateFlow<List<String>> = _crossArticleTitles.asStateFlow()

    // 当前已加载的文章 id（幂等保护：旋转重建不重复加载，避免清空答案/重生成题目）
    private var loadedArticleId: Long? = null
    private var loadedArticleIds: List<Long>? = null

    /** 当前文章的所有练习记录（用于构建错误画像） */
    private var articleRecords: List<PracticeRecord> = emptyList()

    // 错误画像记忆化缓存：buildErrorProfile 为纯函数，仅在入参引用变化时重算，输出与原来完全一致
    private var errorProfileCache: BlancallGenerator.ErrorProfile? = null
    private var errorProfileCacheKey: List<PracticeRecord>? = null
    private fun errorProfileFor(records: List<PracticeRecord>): BlancallGenerator.ErrorProfile {
        // 缓存只缓存错误率统计；memoryFactor 依赖 FSRS 实时留存率（随时间衰减），须每次现算
        if (records === errorProfileCacheKey && errorProfileCache != null) {
            return errorProfileCache!!.copy(memoryFactor = currentMemoryFactor())
        }
        val p = buildErrorProfile(records).copy(memoryFactor = currentMemoryFactor())
        errorProfileCache = p
        errorProfileCacheKey = records
        return p
    }

    /**
     * 当前文章的记忆强度因子（FSRS 导出），>1 表示记忆偏弱：
     * - 未练习过（无 FSRS 状态）→ 1f（中性，按历史错误策略即可）
     * - 留存率越低 → 因子越高（该多挖、更倾斜薄弱处）
     * - 遗忘次数多再小幅加码
     */
    private fun currentMemoryFactor(): Float {
        val articleId = _article.value?.id ?: return 1f
        val state = FsrsStateStore.getInstance(
            getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
        ).get(articleId) ?: return 1f
        if (state.reviewCount <= 0 || state.stability <= 0.0) return 1f
        val retention = FsrsEngine.retentionRate(state) // 0..1
        var factor = when {
            retention < 0.5 -> 1.45f
            retention < 0.7 -> 1.25f
            retention < 0.85 -> 1.10f
            else -> 1f
        }
        if (state.lapses >= 2) factor += 0.1f
        return factor.coerceIn(1f, 1.6f)
    }

    // 用于取消上一次生成的协程，防止竞态条件（快速切换文章/模式/策略时避免旧结果覆盖新结果）
    private var wordGenerateJob: Job? = null
    private var sentenceGenerateJob: Job? = null
    private var dictationGenerateJob: Job? = null
    private var loadJob: Job? = null

    // 推荐的挖空数量（根据文本长度自动计算）
    val recommendedWordBlankCount: Int
        get() {
            val chineseCount = _article.value?.content?.count { it in '一'..'鿿' } ?: 0
            return when {
                chineseCount <= 10 -> maxOf(1, chineseCount / 2)
                chineseCount <= 50 -> maxOf(3, chineseCount / 5)
                chineseCount <= 200 -> maxOf(5, chineseCount / 10)
                else -> maxOf(10, chineseCount / 20)
            }.coerceIn(1, 50)
        }

    fun loadArticle(articleId: Long, resume: Boolean = false, initialSectionMode: SectionMode? = null) {
        // 幂等：旋转横屏等配置变更导致 Activity 重建时 LaunchedEffect 会重跑，
        // 同一篇文章直接跳过，避免清空已输入答案并重新生成题目。
        if (loadedArticleId == articleId) return
        loadedArticleId = articleId
        // 「继续练习」是否已恢复上次挖好的空（恢复成功时跳过 L426 重新生成）
        var resumeRestoredCloze = false
        // 取消上一次加载，防止快速切换文章时旧结果覆盖新结果
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 文章与历史记录读取放在 IO 线程，避免主线程阻塞
            val art = withContext(Dispatchers.IO) {
                try { repository.getArticleById(articleId) } catch (_: Exception) { null }
            } ?: run {
                _article.value = null
                return@launch
            }
            _article.value = art
            val recordRepo = RecordRepository.getInstance(
                getApplication<Application>().filesDir.resolve("records.json").absolutePath
            )
            articleRecords = withContext(Dispatchers.IO) {
                try { recordRepo.getByArticleId(articleId) } catch (_: Exception) { emptyList() }
            }
            // 读取上次未完成进度（IO 线程）
            val savedState = if (resume) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = getApplication<Application>().filesDir.resolve("practice_state_${articleId}.json")
                        if (file.exists()) {
                            val json = org.json.JSONObject(file.readText())
                            PracticeState(
                                articleId = json.optLong("articleId", articleId),
                                mode = json.optString("mode", ""),
                                status = try { PracticeStatus.valueOf(json.optString("status", "IN_PROGRESS")) }
                                    catch (_: Exception) { PracticeStatus.IN_PROGRESS },
                                totalBlanks = json.optInt("totalBlanks", 0),
                                answeredCount = json.optInt("answeredCount", 0),
                                answers = json.optJSONObject("answers")?.keys()?.asSequence()
                                    ?.associate { k -> k.toInt() to json.getJSONObject("answers").getString(k) }
                                    ?: emptyMap(),
                                dictationInput = json.optString("dictationInput", ""),
                                clozeJson = if (json.has("clozeJson")) json.getString("clozeJson") else null,
                                lastPracticeTime = json.optLong("lastPracticeTime", System.currentTimeMillis())
                            )
                        } else null
                    } catch (_: Exception) { null }
                }
            } else null
            // 清空上一篇文章的答案与反向默写状态
            _sentenceAnswers.value = emptyMap()
            _wordAnswers.value = emptyMap()
            _checkResults.value = emptyMap()
            _isSubmitted.value = false
            _dictationInput.value = ""
            _dictationCheckResult.value = null
            // 切换文章：停止提示计时并清零提示统计
            stopAllBlankHints()
            _weakHintCount.value = 0
            _strongHintCount.value = 0
            // 重置挖空数量为自动
            _wordBlankCount.value = 0
            // 重置段落选择
            _sectionMode.value = SectionMode.FULL
            _selectedSections.value = emptySet()
            // 应用恢复的练习进度（mode 必须在 _totalBlanks 计算前恢复，因为后者依赖前者）
            if (savedState != null) {
                val restoredMode = try { BlancallMode.valueOf(savedState.mode) } catch (_: Exception) { null }
                if (restoredMode != null) {
                    _mode.value = restoredMode
                    when (restoredMode) {
                        BlancallMode.SENTENCE -> _sentenceAnswers.value = savedState.answers
                        BlancallMode.WORD -> _wordAnswers.value = savedState.answers
                        BlancallMode.REVERSE -> _dictationInput.value = savedState.dictationInput
                    }
                    _resumed.value = true
                    // 恢复上次挖好的空（clozeJson）：直接复用上次挖空，无需重新生成/选难度
                    val restored = savedState.clozeJson
                    val restoredOk = when (restoredMode) {
                        BlancallMode.SENTENCE -> {
                            _sentenceCloze.value = restored?.let { BlancallGenerator.sentenceClozeFromJson(it) }
                            _sentenceCloze.value != null
                        }
                        BlancallMode.WORD -> {
                            _wordCloze.value = restored?.let { BlancallGenerator.wordClozeFromJson(it) }
                            _wordCloze.value != null
                        }
                        BlancallMode.REVERSE -> {
                            _dictationResult.value = restored?.let { BlancallGenerator.dictationFromJson(it) }
                            _dictationResult.value != null
                        }
                    }
                    resumeRestoredCloze = restoredOk
                    if (restoredOk) {
                        // 直接恢复挖空：进度条立即用保存的总空数显示（不延迟）
                        _totalBlanks.value = savedState.totalBlanks.coerceAtLeast(0)
                    } else {
                        // 旧记录无挖空 → 走统一入口（AI 开启时可能弹采集页）重新生成
                        maybeCollectDifficultyIfNeeded()
                    }
                } else {
                    _resumed.value = false
                }
            } else {
                _resumed.value = false
            }
            // 空内容兜底
            if (art.content.isBlank()) {
                _sections.value = emptyList()
                _rankedSections.value = emptyList()
                _sentenceCloze.value = null
                _wordCloze.value = null
                _dictationResult.value = null
                _totalBlanks.value = 0
                return@launch
            }
            // 段落切分 + 错误率统计在后台线程完成（挖空由 regenerateCloze 统一生成）
            var secs: List<SectionSplitter.Section> = emptyList()
            var ranked: List<SectionSplitter.RankedSection> = emptyList()
            try {
                withContext(Dispatchers.Default) {
                    val errorProfile = errorProfileFor(articleRecords)
                    secs = SectionSplitter.split(art.content)
                    ranked = SectionSplitter.rankByErrorRate(secs, errorProfile.sentenceErrorRates)
                }
            } catch (_: Exception) { /* 切分失败保持空列表，UI 显示空态 */ }
            _sections.value = secs
            _rankedSections.value = ranked
            // 应用初始段落模式（薄弱集训等入口）：必须在生成挖空前应用，
            // 否则 regenerateCloze 会基于默认 FULL 模式生成全文
            if (initialSectionMode != null) {
                _sectionMode.value = initialSectionMode
                _selectedSections.value = when (initialSectionMode) {
                    SectionMode.FULL -> secs.map { it.index }.toSet()
                    SectionMode.WEAKNESS -> {
                        val weak = ranked.filter { it.errorRate > 0f }.map { it.section.index }.toSet()
                        if (weak.isEmpty()) secs.map { it.index }.toSet() else weak
                    }
                    SectionMode.SELECTED -> secs.map { it.index }.toSet()
                }
            } else {
                _sectionMode.value = SectionMode.FULL
                _selectedSections.value = secs.map { it.index }.toSet()
            }
            // 按已应用的段落模式统一生成挖空（内部读取 _article/_sections/_sectionMode/_selectedSections）
            // 「继续练习」已恢复上次挖好的空时跳过重新生成，保留恢复的挖空
            if (!resumeRestoredCloze) {
                regenerateCloze()
            }
        }
    }

    /** 跨文本联动：加载多篇文章混合练习 */
    fun loadArticles(articleIds: List<Long>) {
        // 幂等：旋转重建重跑时跳过，保护跨文本练习已输入答案
        if (loadedArticleIds == articleIds) return
        loadedArticleIds = articleIds
        _isCrossMode.value = articleIds.size > 1
        // 取消上一次加载，防止竞态
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val articles = withContext(Dispatchers.IO) {
                try { articleIds.mapNotNull { repository.getArticleById(it) } }
                catch (_: Exception) { emptyList() }
            }
            if (articles.isEmpty()) return@launch

            val triples = articles.map { Triple(it.id, it.title, it.content) }
            val strat = _strategy.value
            val classical = _classicalMode.value

            // 先加载所有文章的练习记录合并（IO 线程），
            // 再后台做文本混合 + 段落切分 + 错误率统计 + 挖空
            val recordRepo = RecordRepository.getInstance(
                getApplication<Application>().filesDir.resolve("records.json").absolutePath
            )
            articleRecords = withContext(Dispatchers.IO) {
                try { articleIds.flatMap { recordRepo.getByArticleId(it) } }
                catch (_: Exception) { emptyList() }
            }

            var mixed: CrossTextReview.MixedContent? = null
            var secs: List<SectionSplitter.Section> = emptyList()
            var ranked: List<SectionSplitter.RankedSection> = emptyList()
            var sentenceResult: BlancallGenerator.SentenceClozeResult? = null
            var wordResult: BlancallGenerator.WordClozeResult? = null
            var dictationResult: BlancallGenerator.DictationResult? = null
            var anchors: List<Int> = emptyList()
            try {
                withContext(Dispatchers.Default) {
                    mixed = CrossTextReview.mix(triples)
                    val content = mixed?.content.orEmpty()
                    if (content.isNotBlank()) {
                        val errorProfile = errorProfileFor(articleRecords)
                        secs = SectionSplitter.split(content)
                        ranked = SectionSplitter.rankByErrorRate(secs, errorProfile.sentenceErrorRates)
                        val effectiveContent = getEffectiveContent(content, secs)
                        sentenceResult = BlancallGenerator.generateSentenceCloze(
                            effectiveContent, errorProfile = errorProfile, strategy = strat
                        )
                        wordResult = BlancallGenerator.generateWordCloze(
                            effectiveContent, errorProfile = errorProfile, strategy = strat, classicalMode = classical
                        )
                        dictationResult = BlancallGenerator.generateDictation(effectiveContent)
                        // 跨文本模式强制全篇：锚点即全文切句位置
                        anchors = buildSentenceAnchors(
                            content, effectiveContent, secs, secs.map { it.index }.toSet()
                        )
                    }
                }
            } catch (_: Exception) { /* 生成失败保持 null，UI 显示空态 */ }

            if (mixed == null) return@launch
            _crossSourceInfo.value = mixed!!.sources
            _crossArticleTitles.value = mixed!!.articleTitles

            _article.value = Article(
                id = -1L,
                title = articles.joinToString(" · ") { it.title },
                author = articles.map { it.author.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(" · "),
                content = mixed!!.content
            )

            // 清空状态
            _sentenceAnswers.value = emptyMap()
            _wordAnswers.value = emptyMap()
            _checkResults.value = emptyMap()
            _isSubmitted.value = false
            _dictationInput.value = ""
            _dictationCheckResult.value = null
            _wordBlankCount.value = 0
            _sectionMode.value = SectionMode.FULL
            _selectedSections.value = emptySet()

            val content = mixed!!.content
            if (content.isBlank()) {
                _sections.value = emptyList()
                _rankedSections.value = emptyList()
                _sentenceCloze.value = null
                _wordCloze.value = null
                _dictationResult.value = null
                _sentenceAnchors.value = emptyList()
                _totalBlanks.value = 0
                return@launch
            }
            _sections.value = secs
            _selectedSections.value = secs.map { it.index }.toSet()
            _rankedSections.value = ranked
            _sentenceCloze.value = sentenceResult
            _wordCloze.value = wordResult
            _dictationResult.value = dictationResult
            _sentenceAnchors.value = anchors
            _totalBlanks.value = when (_mode.value) {
                BlancallMode.SENTENCE -> sentenceResult?.blanks?.size ?: 0
                BlancallMode.WORD -> wordResult?.blanks?.size ?: 0
                BlancallMode.REVERSE -> dictationResult?.clauses?.size ?: 0
            }
        }
    }

    fun setMode(newMode: BlancallMode) {
        _mode.value = newMode
        _totalBlanks.value = when (newMode) {
            BlancallMode.SENTENCE -> _sentenceCloze.value?.blanks?.size ?: 0
            BlancallMode.WORD -> _wordCloze.value?.blanks?.size ?: 0
            BlancallMode.REVERSE -> _dictationResult.value?.clauses?.size ?: 0
        }
        // 选定模式即开始计时（重做时重置起点）
        practiceStartTime = System.currentTimeMillis()
        maybeCollectDifficultyIfNeeded()
    }

    // ── AI 采集页统一入口 ──
    // 无论从哪个路径进入练习（模式选择浮层 / 外部初始模式 / 恢复上次进度 / 标题滑动切换），
    // 只要 AI 已开启且尚未确认过难度，都先弹出难度+策略采集页，避免"没有采集页"。
    /** 是否已确认过难度（确认后不再重复弹出；模式切换沿用难度直接生成） */
    private var difficultyConfirmed = false
    /** 用户选择「使用本地算法」后置为 true：后续切模式一律走本地算法，不再用 AI 生成 */
    private var useLocalCloze = false

    /** AI 已开启且未确认难度时弹出采集页（幂等，重复调用无副作用） */
    private fun maybeCollectDifficultyIfNeeded() {
        if (isAiAvailable() && !difficultyConfirmed && !_showDifficultyInfo.value) {
            _showDifficultyInfo.value = true
            _aiError.value = null
            _isAiGenerating.value = false
        }
    }

    // ═══════════════════════════════════════════
    //  AI 挖空（Pro）：模式选择 → 难度采集 → 生成
    // ═══════════════════════════════════════════

    private fun hasAiProfile(): Boolean =
        AiConfigStore.activeChatProfile?.apiKeyEnc?.isNotBlank() == true

    /** AI 引擎是否可用（设置里开启 AI 且已配置有效对话连接） */
    fun isAiAvailable(): Boolean = AppPrefs.aiEnabled && hasAiProfile()

    /** 挖空是否走 AI：AI 引擎可用且开启「使用AI挖空」。关闭时挖空用本地算法，但采集页仍会弹出。 */
    fun isAiClozeEnabled(): Boolean = isAiAvailable() && AppPrefs.useAiCloze

    /**
     * 用户从模式选择页点选一个模式。AI 开启时先进入难度采集页，
     * 待用户确认难度后再生成；本地模式下直接使用已预生成的内容。
     */
    fun onModeChosen(newMode: BlancallMode) {
        // setMode 内已统一处理 AI 难度采集页（AI 开启且未确认过难度时弹出）
        setMode(newMode)
    }

    /** 难度采集页确认：记录难度、挖空策略与自定义需求，并按 AI 生成当前模式挖空。 */
    fun confirmDifficultyAndGenerate(
        difficultyLabel: String,
        custom: String,
        strategy: BlancallGenerator.Strategy = _strategy.value
    ) {
        _difficulty.value = AiClozeGenerator.normalizeDifficulty(difficultyLabel)
        _customRequest.value = custom.trim()
        _strategy.value = strategy
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
        // 清理当前模式的半成品，回到"未选模式"状态（Screen 侧恢复模式选择浮层）
        when (_mode.value) {
            BlancallMode.SENTENCE -> {
                _sentenceCloze.value = null
                _sentenceAnswers.value = emptyMap()
            }
            BlancallMode.WORD -> {
                _wordCloze.value = null
                _wordAnswers.value = emptyMap()
            }
            BlancallMode.REVERSE -> {
                _dictationResult.value = null
                _dictationInput.value = ""
            }
        }
        _checkResults.value = emptyMap()
        _isSubmitted.value = false
        _dictationCheckResult.value = null
        _totalBlanks.value = 0
    }

    /** 采集页"跳过 AI，用本地算法生成"：不采集难度，直接本地挖空。 */
    fun skipAiAndGenerateLocal() {
        _showDifficultyInfo.value = false
        _isAiGenerating.value = false
        _aiError.value = null
        // 用户选择「使用本地算法」：确认无需 AI 难度采集，后续切换模式不再弹采集页、一律走本地
        difficultyConfirmed = true
        useLocalCloze = true
        if (_mode.value == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    /** 本地采集页确认（「使用AI挖空」关闭时）：记录挖空策略与段落选择，按本地算法生成当前模式挖空。 */
    fun confirmLocalClozeSettings(
        strategy: BlancallGenerator.Strategy,
        sectionMode: SectionMode,
        selected: Set<Int>
    ) {
        _strategy.value = strategy
        _sectionMode.value = sectionMode
        _selectedSections.value = selected
        difficultyConfirmed = true
        useLocalCloze = true  // 本地确认 → 后续一律走本地算法
        _showDifficultyInfo.value = false
        _isAiGenerating.value = false
        _aiError.value = null
        if (_mode.value == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    /** 取消当前 AI 生成并回退本地算法（生成页"本地生成"按钮）。 */
    fun cancelAiGeneration() {
        aiGenerateJob?.cancel()
        _isAiGenerating.value = false
        _aiError.value = null
        if (_mode.value == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
    }

    // ═══════════════════════════════════════════
    //  训练分析（Pro）：提交判分后由 AI 生成"本次训练分析"（Markdown）
    // ═══════════════════════════════════════════
    private val _trainingAnalysis = MutableStateFlow<String?>(null)
    /** 本次训练分析（Markdown 文本）；null=尚未生成 */
    val trainingAnalysis: StateFlow<String?> = _trainingAnalysis.asStateFlow()

    private val _analysisLoading = MutableStateFlow(false)
    val analysisLoading: StateFlow<Boolean> = _analysisLoading.asStateFlow()

    private val _analysisError = MutableStateFlow(false)
    val analysisError: StateFlow<Boolean> = _analysisError.asStateFlow()

    private var analysisJob: Job? = null

    /** 提交判分后调用：AI 开启时自动生成训练分析（失败可手动重试）。 */
    fun generateTrainingAnalysis() {
        if (!isAiAvailable()) return
        if (_analysisLoading.value) return
        analysisJob?.cancel()
        _analysisLoading.value = true
        _analysisError.value = false
        viewModelScope.launch {
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
                        title = _article.value?.title.orEmpty(),
                        modeLabel = when (_mode.value) {
                            BlancallMode.SENTENCE -> "句子挖空"
                            BlancallMode.WORD -> "字词挖空"
                            BlancallMode.REVERSE -> "反向默写"
                        },
                        scoreLine = buildAnalysisScoreLine(),
                        mistakeSummary = buildAnalysisMistakeSummary(),
                        weakHints = _weakHintCount.value,
                        strongHints = _strongHintCount.value
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
            val app = getApplication<Application>()
            val dir = File(app.filesDir, "ai_analysis").apply { mkdirs() }
            val createdAt = System.currentTimeMillis()
            val json = org.json.JSONObject().apply {
                put("createdAt", createdAt)
                put("articleId", _article.value?.id ?: 0L)
                put("articleTitle", _article.value?.title.orEmpty())
                put("modeLabel", when (_mode.value) {
                    BlancallMode.SENTENCE -> "句子挖空"
                    BlancallMode.WORD -> "字词挖空"
                    BlancallMode.REVERSE -> "反向默写"
                })
                put("weakHints", _weakHintCount.value)
                put("strongHints", _strongHintCount.value)
                put("analysis", text)
            }
            File(dir, "$createdAt.json").writeText(json.toString())
        } catch (_: Exception) { /* 分析持久化失败不影响主流程 */ }
    }

    private fun buildAnalysisScoreLine(): String {
        val mode = _mode.value
        if (mode == BlancallMode.REVERSE) {
            val r = _dictationCheckResult.value
            return if (r == null) "综合得分 0%" else "综合得分 ${(r.overallScore * 100).toInt()}%"
        }
        val res = _checkResults.value
        val total = res.size
        val correct = res.values.count { it.result == AnswerChecker.Result.CORRECT }
        val sim = if (total > 0) res.values.map { it.similarity }.average().toFloat() else 0f
        return "正确 $correct/$total，平均相似度 ${(sim * 100).toInt()}%，共 $total 个空"
    }

    private fun buildAnalysisMistakeSummary(): String {
        val lines = mutableListOf<String>()
        if (_mode.value == BlancallMode.REVERSE) {
            _dictationCheckResult.value?.sentences?.forEachIndexed { i, s ->
                if (s.result != AnswerChecker.Result.CORRECT) {
                    if (s.matchIndex < 0) {
                        lines.add("- 第${i + 1}处：未匹配到原文（可能漏背或张冠李戴）")
                    } else {
                        lines.add("- 第${i + 1}处：应为「${s.matchedOriginal?.take(20)}」实际「${s.userText.take(20)}」")
                    }
                }
            }
        } else {
            _checkResults.value.entries.sortedBy { it.key }.forEach { (idx, d) ->
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

    /**
     * 顶部标题滑动 / 唤醒菜单切换模式：AI 开启时按用户上次选定的难度重新生成；
     * 本地模式下内容已预生成，直接切换即可。
     */
    fun switchMode(newMode: BlancallMode) {
        setMode(newMode)
        // 未确认过难度/设置时 setMode 已弹出采集页，等待确认，不立即生成
        if (difficultyConfirmed) {
            regenerateCurrentModeViaAi()
        }
    }

    /** 字号缩放（双指缩放手势驱动），范围 0.6x ~ 3.0x。 */
    fun setFontScale(scale: Float) {
        _fontScale.value = scale.coerceIn(0.6f, 3.0f)
    }

    /** 连续缩放：以增量因子叠加（每帧手势比率相乘，便于跟手），范围 0.6x ~ 3.0x。 */
    fun adjustFontScale(factor: Float) {
        if (factor <= 0f || factor.isNaN()) return
        _fontScale.value = (_fontScale.value * factor).coerceIn(0.6f, 3.0f)
    }

    /**
     * 按当前模式重新生成挖空：AI 开启走 AI（返回坐标→本地解码挖空），
     * 反向默写 / AI 不可用时回到本地算法。调用方负责管理模式与答案状态。
     */
    private fun regenerateCurrentModeViaAi() {
        val mode = _mode.value
        // 「使用AI挖空」关闭、或用户已选择「使用本地算法」时：一律走本地算法
        val useAi = isAiClozeEnabled() && !useLocalCloze
        if (!useAi) {
            if (mode == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
            return
        }
        aiGenerateJob?.cancel()
        aiGenerateJob = viewModelScope.launch {
            val content = _article.value?.content ?: return@launch
            val secs = _sections.value
            val effectiveContent = getEffectiveContent(content, secs)
            if (effectiveContent.isBlank()) return@launch

            _isAiGenerating.value = true
            _aiError.value = null
            _aiProgress.value = 0
            try {
                val profile = AiConfigStore.activeChatProfile ?: run {
                    _isAiGenerating.value = false
                    regenerateCloze()
                    return@launch
                }
                val sentences = SentenceSplitter.split(effectiveContent)
                if (sentences.isEmpty()) {
                    _isAiGenerating.value = false
                    regenerateCloze()
                    return@launch
                }
                // 锚点与 AI 挖空同源（都基于 effectiveContent 切句），必须同步更新，
                // 否则判分时 sentenceIndex 无法换算成全文字符位置。
                val selectedSnap = _selectedSections.value
                _sentenceAnchors.value = withContext(Dispatchers.Default) {
                    buildSentenceAnchors(content, effectiveContent, secs, selectedSnap)
                }
                // 切换到该模式时先清空旧答案与结果态
                when (mode) {
                    BlancallMode.SENTENCE -> _sentenceAnswers.value = emptyMap()
                    BlancallMode.WORD -> _wordAnswers.value = emptyMap()
                    BlancallMode.REVERSE -> {}
                }
                _checkResults.value = emptyMap()
                _isSubmitted.value = false
                _dictationCheckResult.value = null

                val dictationClauses = if (mode == BlancallMode.REVERSE) {
                    BlancallGenerator.generateDictation(effectiveContent).clauses
                } else emptyList()

                // 采集页确认过的策略（均衡/薄弱优先/全覆盖）随请求一起发给 AI
                val strategyLabel = AiClozeGenerator.strategyLabel(_strategy.value)
                val messages = when (mode) {
                    BlancallMode.SENTENCE -> listOf(
                        AiClient.ChatMessage("system", AiClozeGenerator.buildSystemPrompt()),
                        AiClient.ChatMessage(
                            "user",
                            AiClozeGenerator.buildSentenceRequest(
                                effectiveContent, sentences, _difficulty.value, _customRequest.value, strategyLabel
                            )
                        )
                    )
                    BlancallMode.WORD -> listOf(
                        AiClient.ChatMessage("system", AiClozeGenerator.buildSystemPrompt()),
                        AiClient.ChatMessage(
                            "user",
                            AiClozeGenerator.buildWordRequest(
                                effectiveContent, sentences, _difficulty.value, _customRequest.value, strategyLabel
                            )
                        )
                    )
                    BlancallMode.REVERSE -> listOf(
                        AiClient.ChatMessage("system", AiClozeGenerator.buildSystemPrompt()),
                        AiClient.ChatMessage(
                            "user",
                            AiClozeGenerator.buildDictationRequest(
                                effectiveContent, dictationClauses, _difficulty.value, _customRequest.value, strategyLabel
                            )
                        )
                    )
                }

                val sb = StringBuilder()
                // Keystore 解密放 IO 线程（部分设备硬件安全模块在主线程调用会阻塞/异常）
                val apiKey = withContext(Dispatchers.IO) { profile.decryptApiKey() }
                // 总超时兜底：AI 服务端空流/极慢时（SSE 不结束），超时后视为失败回退本地，
                // 避免"一直加载"。取消（用户点击本地生成 / 切换模式）不受影响。
                val completed = withTimeoutOrNull(AI_GENERATE_TIMEOUT_MS) {
                    AiClient.streamChat(profile.baseUrl, apiKey, profile.model, messages)
                        .collect { sb.append(it); _aiProgress.value = sb.length }
                    true
                } ?: false
                if (!completed) {
                    throw AiClient.AiException(
                        if (sb.isEmpty()) "AI 长时间无响应" else "AI 生成超时"
                    )
                }
                // 快速返回但没有任何有效内容（部分中转服务返回了无法解析的格式）→ 回退本地
                if (sb.isEmpty()) {
                    throw AiClient.AiException("AI 未返回有效挖空结果")
                }

                when (mode) {
                    BlancallMode.SENTENCE -> {
                        val result = AiClozeGenerator.buildSentenceResult(
                            sentences,
                            AiClozeGenerator.decodeSentenceIndices(sb.toString(), sentences.size)
                        )
                        // 解码为空 → 同样视为失败，回退本地（避免"超快但没挖空"）
                        if (result.blanks.isEmpty()) {
                            throw AiClient.AiException("AI 未返回有效挖空结果")
                        }
                        _sentenceCloze.value = result
                        _totalBlanks.value = result.blanks.size
                    }
                    BlancallMode.WORD -> {
                        val result = AiClozeGenerator.buildWordResult(
                            sentences,
                            AiClozeGenerator.decodeWordRanges(sb.toString())
                        )
                        // 解码为空 → 视为失败，回退本地（避免"超快但没挖空"）
                        if (result.blanks.isEmpty()) {
                            throw AiClient.AiException("AI 未返回有效挖空结果")
                        }
                        _wordCloze.value = result
                        _totalBlanks.value = result.blanks.size
                    }
                    BlancallMode.REVERSE -> {
                        val result = AiClozeGenerator.buildDictationResult(
                            dictationClauses,
                            AiClozeGenerator.decodeWordRanges(sb.toString())
                        )
                        _dictationResult.value = result
                        _totalBlanks.value = result.clauses.size
                    }
                }
                _isAiGenerating.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消（生成页"本地生成"/切换模式）：向上传播，不吞取消
                throw e
            } catch (e: Exception) {
                _isAiGenerating.value = false
                _aiError.value = "AI 挖空失败：${e.message}"
                // 失败回退本地算法，保证练习始终可用
                try {
                    if (mode == BlancallMode.REVERSE) generateDictationLocal() else regenerateCloze()
                } catch (_: Exception) { }
            }
        }
    }

    /** 仅重新生成反向默写（打散），不触碰句子/字词结果，避免覆盖 AI 内容。 */
    private fun generateDictationLocal() {
        dictationGenerateJob?.cancel()
        dictationGenerateJob = viewModelScope.launch {
            val content = _article.value?.content ?: return@launch
            val secs = _sections.value
            val effectiveContent = getEffectiveContent(content, secs)
            var res: BlancallGenerator.DictationResult? = null
            try {
                withContext(Dispatchers.Default) {
                    res = BlancallGenerator.generateDictation(effectiveContent)
                }
            } catch (_: Exception) { }
            _dictationResult.value = res
            if (_mode.value == BlancallMode.REVERSE) {
                _totalBlanks.value = res?.clauses?.size ?: 0
            }
        }
    }

    fun setStrategy(newStrategy: BlancallGenerator.Strategy) {
        _strategy.value = newStrategy
        // 策略变更 → 重新生成（AI 挖空开启时连同难度/需求一起发给 AI，否则本地重新挖空）
        if (isAiClozeEnabled() && _showDifficultyInfo.value.not()) {
            regenerateCurrentModeViaAi()
        } else if (_showDifficultyInfo.value.not()) {
            regenerateCloze()
        }
    }

    fun setClassicalMode(enabled: Boolean) {
        _classicalMode.value = enabled
        regenerateCloze()
    }

    // ── 段落分层管理（F3）──

    fun setSectionMode(mode: SectionMode) {
        _sectionMode.value = mode
        val secs = _sections.value
        when (mode) {
            SectionMode.FULL -> _selectedSections.value = secs.map { it.index }.toSet()
            SectionMode.WEAKNESS -> {
                // 自动选中错误率 > 0 的薄弱段落
                val ranked = _rankedSections.value
                val weak = ranked.filter { it.errorRate > 0f }.map { it.section.index }.toSet()
                _selectedSections.value = if (weak.isEmpty()) secs.map { it.index }.toSet() else weak
            }
            SectionMode.SELECTED -> { /* 保持当前选择不变 */ }
        }
        regenerateCloze()
    }

    fun toggleSection(sectionIndex: Int) {
        val current = _selectedSections.value.toMutableSet()
        if (current.contains(sectionIndex)) {
            if (current.size > 1) {  // 至少保留一个段落
                current.remove(sectionIndex)
            }
        } else {
            current.add(sectionIndex)
        }
        _selectedSections.value = current
        regenerateCloze()
    }

    fun selectAllSections() {
        val all = _sections.value.map { it.index }.toSet()
        // 已全选 → 取消全选（保留第一个，避免无段落可用）；否则全选
        _selectedSections.value = if (_selectedSections.value == all) {
            all.firstOrNull()?.let { setOf(it) } ?: all
        } else {
            all
        }
        regenerateCloze()
    }

    // ── 沉浸模式管理（F4）──

    fun toggleImmersiveMode() {
        _immersiveMode.value = !_immersiveMode.value
        if (_immersiveMode.value) {
            // 进入沉浸模式时重置渐进等级
            _progressiveLevel.value = 0
            consecutiveCorrect = 0
        }
    }

    /** 渐进升级：连续正确达到阈值时提升挖空密度 */
    fun recordProgressiveResult(allCorrect: Boolean, submitted: Boolean) {
        if (!submitted || !_immersiveMode.value) return
        if (allCorrect) {
            consecutiveCorrect++
            if (consecutiveCorrect >= 3 && _progressiveLevel.value < 2) {
                _progressiveLevel.value = _progressiveLevel.value + 1
                consecutiveCorrect = 0
            }
        } else {
            consecutiveCorrect = maxOf(0, consecutiveCorrect - 1)
        }
    }

    /** 获取渐进密度对应的挖空比例（占从句总数百分比） */
    fun getProgressiveDensity(): Float = when (_progressiveLevel.value) {
        0 -> 0.15f  // 初级：挖少量
        1 -> 0.33f  // 中级：挖三分之一
        2 -> 0.50f  // 高级：挖一半
        else -> 0.20f
    }

    /** 根据渐进等级重新生成挖空 */
    fun regenerateForProgressive() {
        regenerateCloze()
    }

    /** 根据当前段落模式获取实际用于挖空的文本内容 */
    private fun getEffectiveContent(fullContent: String, secs: List<SectionSplitter.Section>): String {
        if (_sectionMode.value == SectionMode.FULL) return fullContent
        val selected = _selectedSections.value
        if (selected.isEmpty() || selected.size == secs.size) return fullContent
        return secs
            .filter { it.index in selected }
            .sortedBy { it.startChar }
            .joinToString("\n\n") { it.text }
    }

    /**
     * 构建「effectiveContent 句子索引 → 该句在全文中的字符起始位置」锚点表，见 [_sentenceAnchors]。
     *
     * 两步定位：
     * 1. 按选中段落的字符区间，把子集内偏移换算为全文**近似**位置；
     * 2. 用句子文本在全文切句结果中**精确**匹配；句子重复出现时取位置最近者。
     *
     * 近似位置只用于重复句消歧，因此段落行首尾 trim 造成的几字符误差不会影响最终归属。
     */
    private fun buildSentenceAnchors(
        fullContent: String,
        effectiveContent: String,
        secs: List<SectionSplitter.Section>,
        selected: Set<Int>
    ): List<Int> {
        if (fullContent.isEmpty()) return emptyList()
        // 整篇（或选中全部段落）：子集即全文，直接用全文切句位置
        if (effectiveContent == fullContent) {
            return SentenceSplitter.splitWithPositions(fullContent).map { it.startIndex }
        }
        // 段落模式：建立 effectiveContent 偏移区间 → 全文起始偏移 的换算表
        val ordered = secs.filter { it.index in selected }.sortedBy { it.startChar }
        if (ordered.isEmpty()) return emptyList()
        // 三元组：子集内起始偏移、子集内结束偏移（exclusive）、该段在全文的起始偏移
        val spans = ArrayList<Triple<Int, Int, Int>>()
        var cursor = 0
        for (s in ordered) {
            val len = s.text.length
            spans.add(Triple(cursor, cursor + len, s.startChar))
            cursor += len + 2   // joinToString("\n\n") 的分隔符占 2 字符
        }
        val fullSents = SentenceSplitter.splitWithPositions(fullContent)
        val byText = fullSents.groupBy { it.text }
        return SentenceSplitter.splitWithPositions(effectiveContent).map { (text, start, _) ->
            val approx = spans.firstOrNull { start >= it.first && start < it.second }
                ?.let { (it.third + (start - it.first)).coerceIn(0, fullContent.length) }
                ?: start.coerceIn(0, fullContent.length)
            val candidates = byText[text]
            if (candidates.isNullOrEmpty()) approx
            else candidates.minByOrNull { abs(it.startIndex - approx) }!!.startIndex
        }
    }

    private fun regenerateCloze() {
        val content = _article.value?.content
        if (content.isNullOrBlank()) return
        val secs = _sections.value
        // 选中段落快照：effectiveContent 与锚点表必须基于同一次选择，否则会错位
        val selected = _selectedSections.value
        val effectiveContent = getEffectiveContent(content, secs)
        val recordsSnapshot = articleRecords
        val strat = _strategy.value
        val classical = _classicalMode.value
        // 取消上一次重生成，避免段落/策略快速切换时旧结果竞态覆盖
        sentenceGenerateJob?.cancel()
        wordGenerateJob?.cancel()
        dictationGenerateJob?.cancel()
        val job = viewModelScope.launch {
            // 结果先存局部变量，withContext 返回后在 Main 线程统一赋值，
            // 避免从 Dispatchers.Default 直接写 StateFlow 与 loadArticle 的 Main 线程写入交错竞态
            var sentenceResult: BlancallGenerator.SentenceClozeResult? = null
            var wordResult: BlancallGenerator.WordClozeResult? = null
            var dictationResult: BlancallGenerator.DictationResult? = null
            var anchors: List<Int> = emptyList()
            try {
                withContext(Dispatchers.Default) {
                    val errorProfile = errorProfileFor(recordsSnapshot)
                    sentenceResult = BlancallGenerator.generateSentenceCloze(
                        effectiveContent, errorProfile = errorProfile, strategy = strat
                    )
                    wordResult = BlancallGenerator.generateWordCloze(
                        effectiveContent, errorProfile = errorProfile, strategy = strat, classicalMode = classical
                    )
                    dictationResult = BlancallGenerator.generateDictation(effectiveContent)
                    anchors = buildSentenceAnchors(content, effectiveContent, secs, selected)
                }
            } catch (_: Exception) { /* 保持旧值，避免崩溃 */ }
            _sentenceCloze.value = sentenceResult
            _wordCloze.value = wordResult
            _dictationResult.value = dictationResult
            _sentenceAnchors.value = anchors
            _totalBlanks.value = when (_mode.value) {
                BlancallMode.SENTENCE -> sentenceResult?.blanks?.size ?: 0
                BlancallMode.WORD -> wordResult?.blanks?.size ?: 0
                BlancallMode.REVERSE -> dictationResult?.clauses?.size ?: 0
            }
        }
        sentenceGenerateJob = job
        wordGenerateJob = job
        dictationGenerateJob = job
    }

    /** 设置字词挖空数量并重新生成 */
    fun setWordBlankCount(count: Int) {
        _wordBlankCount.value = count
        _blankCountWarning.value = null  // 清除旧警告
        wordGenerateJob?.cancel()
        wordGenerateJob = viewModelScope.launch {
            val content = _article.value?.content ?: return@launch
            val secs = _sections.value
            val selected = _selectedSections.value
            val effectiveContent = getEffectiveContent(content, secs)
            _wordAnswers.value = emptyMap()
            val recordsSnapshot = articleRecords
            val strat = _strategy.value
            val classical = _classicalMode.value
            var result: BlancallGenerator.WordClozeResult? = null
            var anchors: List<Int> = emptyList()
            try {
                withContext(Dispatchers.Default) {
                    val errorProfile = errorProfileFor(recordsSnapshot)
                    result = BlancallGenerator.generateWordCloze(
                        effectiveContent, count = count, errorProfile = errorProfile,
                        strategy = strat, classicalMode = classical
                    )
                    anchors = buildSentenceAnchors(content, effectiveContent, secs, selected)
                }
            } catch (_: Exception) { /* 生成失败保持旧值，避免崩溃 */ }
            _wordCloze.value = result
            _sentenceAnchors.value = anchors
            if (_mode.value == BlancallMode.WORD) {
                _totalBlanks.value = result?.blanks?.size ?: 0
            }
            // 检查是否需要警告：用户指定了空数但实际生成不一致
            val r = result
            if (count > 0 && r != null && r.blanks.size != count) {
                _blankCountWarning.value = BlankCountWarning(
                    requestedCount = count,
                    maxBlanks = r.maxBlanks,
                    suggestedBlanks = r.suggestedBlanks,
                    actualCount = r.blanks.size
                )
            }
        }
    }

    fun toggleHint() {
        _showHint.value = !_showHint.value
        // 关闭时立即停止所有提示计时与淡显
        if (!_showHint.value) stopAllBlankHints()
    }

    fun dismissBlankCountWarning() {
        _blankCountWarning.value = null
    }

    fun useSuggestedBlankCount() {
        val warning = _blankCountWarning.value ?: return
        setWordBlankCount(warning.suggestedBlanks)
    }

    fun updateAnswer(blankIndex: Int, answer: String) {
        setAnswerText(blankIndex, answer)
        // 提示联动：任何键入都会重置该空的提示计时（用户主动输入优先）
        blankInputVersions[blankIndex] = (blankInputVersions[blankIndex] ?: 0) + 1
        maybeStartBlankHint(blankIndex)
    }

    // ═══════════════════════════════════════════
    //  填空辅助提示（弱提示淡显 / 强提示自动填入）
    //  流程：某空 10s 无输入 → 淡显下一字（5s）→ 再 5s 无输入 → 自动填入 → 立刻淡显下一字循环
    // 统计弱(淡显)/强(自动填入)次数并写入练习记录，AI 训练分析也会读取。
    // ═══════════════════════════════════════════
    private val _hintChars = MutableStateFlow<Map<Int, Char>>(emptyMap())
    /** 每个空当前要淡显的下一个字符（key=blankIndex） */
    val hintChars: StateFlow<Map<Int, Char>> = _hintChars.asStateFlow()

    /** 反向默写（整段输入）当前要淡显的下一个字符；null=无提示 */
    private val _dictationHint = MutableStateFlow<Char?>(null)
    val dictationHint: StateFlow<Char?> = _dictationHint.asStateFlow()

    private var dictationHintJob: Job? = null
    private var dictationInputVersion = 0

    private val _weakHintCount = MutableStateFlow(0)
    /** 弱提示次数：淡显了下一个字 */
    val weakHintCount: StateFlow<Int> = _weakHintCount.asStateFlow()

    private val _strongHintCount = MutableStateFlow(0)
    /** 强提示次数：自动帮填了一个字 */
    val strongHintCount: StateFlow<Int> = _strongHintCount.asStateFlow()

    private val blankHintJobs = mutableMapOf<Int, Job>()
    private val blankInputVersions = mutableMapOf<Int, Int>()

    private fun startBlankHint(blankIndex: Int, expected: String) {
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

    private fun maybeStartBlankHint(blankIndex: Int) {
        if (_isSubmitted.value || !_showHint.value) { blankHintJobs[blankIndex]?.cancel(); return }
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
    fun ensureHintTimer(blankIndex: Int? = null) {
        if (_isSubmitted.value || !_showHint.value) return
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

    private fun expectedText(blankIndex: Int): String? = when (_mode.value) {
        BlancallMode.SENTENCE -> _sentenceCloze.value?.blanks?.getOrNull(blankIndex)?.originalText
        BlancallMode.WORD -> _wordCloze.value?.blanks?.getOrNull(blankIndex)?.originalChar
        else -> null
    }

    private fun currentAnswerText(blankIndex: Int): String = when (_mode.value) {
        BlancallMode.SENTENCE -> _sentenceAnswers.value[blankIndex].orEmpty()
        BlancallMode.WORD -> _wordAnswers.value[blankIndex].orEmpty()
        else -> ""
    }

    private fun setAnswerText(blankIndex: Int, text: String) {
        when (_mode.value) {
            BlancallMode.SENTENCE -> _sentenceAnswers.value = _sentenceAnswers.value + (blankIndex to text)
            BlancallMode.WORD -> _wordAnswers.value = _wordAnswers.value + (blankIndex to text)
            // 反向默写使用独立的整段输入，不走按空作答路径
            BlancallMode.REVERSE -> {}
        }
    }

    private fun stopAllBlankHints() {
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
    private fun startDictationHint() {
        if (_isSubmitted.value || !_showHint.value) {
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

    private companion object {
        /** 首次无输入 10s 后淡显提示字；淡显完成后再等 5s 无输入则强填 */
        const val HINT_FIRST_WAIT_MS = 10_000L
        /** 提示字淡入时长（UI 动画同步 5s） */
        const val HINT_FADE_MS = 5_000L
        /** 淡显完成后再等待时长（到点未输入则自动填入） */
        const val HINT_AFTER_WAIT_MS = 5_000L
    }

    /** 更新反向默写的整段输入文本 */
    fun updateDictationInput(text: String) {
        // 防御：超长输入截断（渲染与判分的双重保护，防止极端输入导致性能/内存问题）
        _dictationInput.value = if (text.length > 50_000) text.take(50_000) else text
        // 提示联动：任何键入都会重置提示计时
        dictationInputVersion++
        startDictationHint()
    }

    fun submitAnswers() {
        // 提交中防重复点击
        if (_isSubmitting.value) return
        // 反向默写走独立的整段判分路径
        if (_mode.value == BlancallMode.REVERSE) {
            submitDictation()
        } else {
            submitInternal(partial = false)
        }
    }

    /** 部分提交：仅批改已填写的答案，空白不计入错误；反向默写模式下仅保存整段输入不判分 */
    fun submitPartial() {
        if (_mode.value == BlancallMode.REVERSE) {
            val articleId = _article.value?.id ?: return
            val input = _dictationInput.value
            viewModelScope.launch { savePracticeState(articleId, emptyMap(), input) }
            return
        }
        submitInternal(partial = true)
    }

    /** 反向默写提交：用整段判分算法比对用户默写与原文 */
    fun submitDictation() {
        if (_isSubmitting.value) return
        val articleId = _article.value?.id ?: return
        val dictation = _dictationResult.value ?: return
        val userInput = _dictationInput.value
        if (userInput.isBlank()) return
        // 判分放在后台线程，长文本编辑距离计算可能耗时
        val originalSentences = dictation.clauses
        val inputSnapshot = userInput
        val originalText = originalSentences.joinToString("")
        _isSubmitting.value = true
        viewModelScope.launch {
            val (checkResult, similarity) = withContext(Dispatchers.Default) {
                try {
                    val check = AnswerChecker.checkDictation(originalSentences, inputSnapshot)
                    // 连续相似度：原文整段 vs 用户默写（混合双通道评分，供 FSRS 评级与实验）
                    val sim = DictationScorer.score(originalText, inputSnapshot)
                    check to sim
                }
                // Throwable 兜底：编辑距离计算可能抛 Error（如 OOM），绝不能闪退
                catch (_: Throwable) {
                    AnswerChecker.DictationCheckResult(emptyList(), 0f, 0f, 0f, 0f) to 0f
                }
            }
            _dictationCheckResult.value = checkResult
            _isSubmitted.value = true
            // 提交完成：AI 开启时自动生成训练分析
            generateTrainingAnalysis()
            // 记录今天已学习（用于连续天数统计 + 智能提醒）
            ReminderPrefs.recordStudyToday()
            // 保存练习记录：用综合得分换算"正确数"
            val recordRepo = RecordRepository.getInstance(
                getApplication<Application>().filesDir.resolve("records.json").absolutePath
            )
            val total = checkResult.sentences.size.coerceAtLeast(1)
            val correctCount = (checkResult.overallScore * total).toInt()
            val rating = resolveRating(similarity, if (total > 0) correctCount.toFloat() / total else 0f)
            try {
                recordRepo.insert(
                    PracticeRecord(
                        articleId = articleId,
                        mode = _mode.value.name,
                        totalBlanks = total,
                        correctCount = correctCount,
                        mistakes = emptyList(),
                        duration = if (practiceStartTime > 0) System.currentTimeMillis() - practiceStartTime else 0L,
                        similarity = similarity,
                        rating = rating.value,
                        weakHints = _weakHintCount.value,
                        strongHints = _strongHintCount.value
                    )
                )
            } catch (_: Exception) { /* 记录失败不影响主流程 */ }
            // 更新 FSRS 记忆状态（自适应调度）
            updateFsrsState(articleId, rating)
            // 反向默写完整提交后清除进度文件
            clearPracticeState(articleId)
        }.also { it.invokeOnCompletion { _isSubmitting.value = false } }
    }

    private fun submitInternal(partial: Boolean) {
        val articleId = _article.value?.id ?: return
        val mode = _mode.value
        // 提交后停止所有填空提示（弱/强提示计时）
        stopAllBlankHints()
        val answers = when (mode) {
            BlancallMode.SENTENCE -> _sentenceAnswers.value
            BlancallMode.WORD -> _wordAnswers.value
            // 反向默写不应进入此路径
            BlancallMode.REVERSE -> return
        }
        val sentenceCloze = _sentenceCloze.value
        val wordCloze = _wordCloze.value
        _isSubmitting.value = true
        viewModelScope.launch {
            // 判分放后台线程：AnswerChecker.check 内部 levenshtein/LCS 为 O(m*n)，
            // 长答案或大空数下主线程同步执行会卡 UI/ANR
            val results = mutableMapOf<Int, AnswerChecker.CheckDetail>()
            withContext(Dispatchers.Default) {
                try {
                    when (mode) {
                        BlancallMode.SENTENCE -> {
                            val blancall = sentenceCloze ?: return@withContext
                            blancall.blanks.forEachIndexed { index, blank ->
                                val user = answers[index] ?: ""
                                if (partial && user.isBlank()) return@forEachIndexed
                                results[index] = AnswerChecker.check(blank.originalText, user)
                            }
                        }
                        BlancallMode.WORD -> {
                            val blancall = wordCloze ?: return@withContext
                            blancall.blanks.forEachIndexed { index, blank ->
                                val user = answers[index] ?: ""
                                if (partial && user.isBlank()) return@forEachIndexed
                                results[index] = AnswerChecker.check(blank.originalChar, user)
                            }
                        }
                        BlancallMode.REVERSE -> return@withContext
                    }
                } catch (_: Exception) { /* 判分异常不崩，结果为空 */ }
            }
            _checkResults.value = results
            _isSubmitted.value = true
            // 提交完成：AI 开启时自动生成训练分析
            generateTrainingAnalysis()

            // 记录今天已学习（用于连续天数统计 + 智能提醒）
            ReminderPrefs.recordStudyToday()

            // 保存练习记录（仅包含已批改的）
            val mistakes = results.entries
                .filter { it.value.result != AnswerChecker.Result.CORRECT }
                .map { MistakeDetail(it.key, it.value.correctAnswer, it.value.userAnswer, it.value.result.name) }
            val recordRepo = RecordRepository.getInstance(
                getApplication<Application>().filesDir.resolve("records.json").absolutePath
            )
            // 连续相似度：逐空相似度均值（口径与练习页展示一致），供 FSRS 评级与实验
            val similarity = if (results.isEmpty()) 0f
                else results.values.map { it.similarity }.average().toFloat()
            val correctCount = results.values.count { it.result == AnswerChecker.Result.CORRECT }
            val rating = resolveRating(similarity, if (results.isNotEmpty()) correctCount.toFloat() / results.size else 0f)
            // 本次实际作答的句子在【全文】中的字符起始位置（未完成提交被跳过的空不进入）。
            // 存字符位置而非句子索引：段落模式下「子集内索引」与热力图的「全文索引」整体错位
            // （标题行在全文切句中独立成句）。锚点为空（尚未生成）时结果为空 → 热力图回退整篇统计。
            val anchors = _sentenceAnchors.value
            // 运行时一致性保护：锚点数必须等于该 cloze 的句子数，否则两者不同源。
            // 典型场景：从进度文件恢复的挖空来自上次的段落子集，而恢复后段落模式被重置为整篇，
            // 此时 sentenceIndex 是子集索引、锚点却是全文位置，硬套会指错句子。
            // 宁可放弃精确统计（回退整篇），也不能错标。
            val anchorsUsable = anchors.isNotEmpty() && when (mode) {
                BlancallMode.SENTENCE -> anchors.size == sentenceCloze?.sentences?.size
                BlancallMode.WORD -> anchors.size == wordCloze?.sentences?.size
                else -> false
            }
            val answeredSentenceStarts = if (!anchorsUsable) emptyList()
            else results.keys.mapNotNull { blankIdx ->
                val sIdx = when (mode) {
                    BlancallMode.SENTENCE -> sentenceCloze?.blanks?.firstOrNull { it.index == blankIdx }?.sentenceIndex
                    BlancallMode.WORD -> wordCloze?.sentences?.indexOfFirst { s -> s.blanks.any { it == blankIdx } }
                        ?.takeIf { it >= 0 }
                    else -> null
                }
                sIdx?.let { anchors.getOrNull(it) }
            }.distinct()
            try {
                recordRepo.insert(
                    PracticeRecord(
                        articleId = articleId,
                        mode = mode.name,
                        totalBlanks = results.size,
                        correctCount = correctCount,
                        mistakes = mistakes,
                        duration = if (practiceStartTime > 0) System.currentTimeMillis() - practiceStartTime else 0L,
                        similarity = similarity,
                        rating = rating.value,
                        weakHints = _weakHintCount.value,
                        strongHints = _strongHintCount.value,
                        answeredSentenceStarts = answeredSentenceStarts
                    )
                )
            } catch (_: Exception) { /* 记录失败不影响主流程 */ }
            // 更新 FSRS 记忆状态（自适应调度）
            updateFsrsState(articleId, rating)
            // 部分提交时保存练习进度；完整提交后清除进度文件
            if (partial) {
                savePracticeState(articleId, answers)
            } else {
                clearPracticeState(articleId)
            }
        }.also { it.invokeOnCompletion { _isSubmitting.value = false } }
    }

    /**
     * 练习结束后更新 FSRS 记忆状态：评级 → 状态更新 → 持久化。
     * FSRS 按文章维护独立难度与稳定性，实现完全自适应的复习调度。
     * 任何失败都不影响练习主流程。
     */
    private fun updateFsrsState(articleId: Long, rating: FsrsEngine.Rating) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = FsrsStateStore.getInstance(
                        getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
                    )
                    val newState = FsrsEngine.review(
                        store.get(articleId) ?: FsrsEngine.CardState(), rating
                    )
                    store.save(articleId, newState)
                }
            } catch (_: Exception) { /* FSRS 状态更新失败不影响主流程 */ }
        }
    }

    /**
     * 评级解析：默认按默写相似度→四档（FSRS-6 产品语义）；
     * 回退开关开启旧行为时按正确率→四档。
     */
    private fun resolveRating(similarity: Float, accuracy: Float): FsrsEngine.Rating =
        if (AppPrefs.useSimilarityRating) FsrsEngine.gradeFromSimilarity(similarity)
        else FsrsEngine.ratingFromAccuracy(accuracy)

    /** 保存练习进度到文件（为「继续练习」预留）。必须从协程调用，文件写入切到 IO 线程。
     *  dictationInput 仅反向默写模式使用，其他模式传空串即可 */
    private suspend fun savePracticeState(articleId: Long, answers: Map<Int, String>, dictationInput: String = "") {
        try {
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
                clozeJson = clozeJson
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
                val ansObj = org.json.JSONObject()
                state.answers.forEach { (k, v) -> ansObj.put(k.toString(), v) }
                json.put("answers", ansObj)
                file.writeText(json.toString())
            }
        } catch (_: Exception) { /* 静默保存，不影响主流程 */ }
    }

    /** 删除指定文章的练习进度文件（完整提交后调用，避免首页继续显示已完成练习） */
    private fun clearPracticeState(articleId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().filesDir
                        .resolve("practice_state_${articleId}.json").delete()
                } catch (_: Exception) { }
            }
        }
    }

    fun reset() {
        val content = _article.value?.content
        if (content.isNullOrBlank()) return
        // 重做即重新计时，避免 duration 包含上次练习的停顿时间
        practiceStartTime = System.currentTimeMillis()
        // 重做：清除提示计时与统计、训练分析
        stopAllBlankHints()
        _weakHintCount.value = 0
        _strongHintCount.value = 0
        analysisJob?.cancel()
        _trainingAnalysis.value = null
        _analysisLoading.value = false
        _analysisError.value = false
        // AI 开启：按上次难度经 AI 重新挖空（反向默写仍在 AI 路径内走本地打散）
        if (isAiAvailable()) {
            _isSubmitted.value = false
            _checkResults.value = emptyMap()
            _dictationCheckResult.value = null
            when (_mode.value) {
                BlancallMode.SENTENCE -> _sentenceAnswers.value = emptyMap()
                BlancallMode.WORD -> _wordAnswers.value = emptyMap()
                BlancallMode.REVERSE -> {
                    _sentenceAnswers.value = emptyMap()
                    _dictationInput.value = ""
                }
            }
            regenerateCurrentModeViaAi()
            return
        }
        val secs = _sections.value
        val selected = _selectedSections.value
        val effectiveContent = getEffectiveContent(content, secs)
        // 取消上一次的生成协程
        wordGenerateJob?.cancel()
        sentenceGenerateJob?.cancel()
        dictationGenerateJob?.cancel()
        val recordsSnapshot = articleRecords
        val strat = _strategy.value
        val classical = _classicalMode.value
        val job = viewModelScope.launch {
            var anchors: List<Int> = emptyList()
            try {
                when (_mode.value) {
                    BlancallMode.SENTENCE -> {
                        var result: BlancallGenerator.SentenceClozeResult? = null
                        withContext(Dispatchers.Default) {
                            val errorProfile = errorProfileFor(recordsSnapshot)
                            result = BlancallGenerator.generateSentenceCloze(
                                effectiveContent, errorProfile = errorProfile, strategy = strat
                            )
                        }
                        _sentenceCloze.value = result
                        _totalBlanks.value = result?.blanks?.size ?: 0
                    }
                    BlancallMode.WORD -> {
                        var result: BlancallGenerator.WordClozeResult? = null
                        withContext(Dispatchers.Default) {
                            val errorProfile = errorProfileFor(recordsSnapshot)
                            result = BlancallGenerator.generateWordCloze(
                                effectiveContent, count = _wordBlankCount.value,
                                errorProfile = errorProfile, strategy = strat, classicalMode = classical
                            )
                        }
                        _wordCloze.value = result
                        _totalBlanks.value = result?.blanks?.size ?: 0
                    }
                    BlancallMode.REVERSE -> {
                        var result: BlancallGenerator.DictationResult? = null
                        withContext(Dispatchers.Default) {
                            result = BlancallGenerator.generateDictation(effectiveContent)
                        }
                        _dictationResult.value = result
                        _totalBlanks.value = result?.clauses?.size ?: 0
                    }
                }
                // 锚点只依赖 content/effectiveContent，与模式无关，统一算一次
                anchors = withContext(Dispatchers.Default) {
                    buildSentenceAnchors(content, effectiveContent, secs, selected)
                }
            } catch (_: Exception) { /* 重做失败保持旧值，避免崩溃 */ }
            _sentenceAnchors.value = anchors
            _isSubmitted.value = false
            _checkResults.value = emptyMap()
            _dictationCheckResult.value = null
            // 清空当前模式的答案
            when (_mode.value) {
                BlancallMode.SENTENCE -> _sentenceAnswers.value = emptyMap()
                BlancallMode.WORD -> _wordAnswers.value = emptyMap()
                // 反向默写清空整段输入
                BlancallMode.REVERSE -> {
                    _sentenceAnswers.value = emptyMap()
                    _dictationInput.value = ""
                }
            }
        }
        // 记录当前模式的Job
        when (_mode.value) {
            BlancallMode.SENTENCE -> sentenceGenerateJob = job
            BlancallMode.WORD -> wordGenerateJob = job
            BlancallMode.REVERSE -> dictationGenerateJob = job
        }
    }
}
