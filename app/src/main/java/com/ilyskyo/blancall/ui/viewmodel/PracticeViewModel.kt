// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.AnswerChecker
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.CrossTextReview
import com.ilyskyo.blancall.algorithm.DictationScorer
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SectionSplitter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.PracticeState
import com.ilyskyo.blancall.data.model.PracticeStatus
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BlancallMode { SENTENCE, WORD, REVERSE }

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

    // 是否显示答案提示（首字+字数）
    private val _showHint = MutableStateFlow(false)
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

    // 当前文章的所有练习记录（用于构建错误画像）
    private var articleRecords: List<PracticeRecord> = emptyList()

    // 错误画像记忆化缓存：buildErrorProfile 为纯函数，仅在入参引用变化时重算，输出与原来完全一致
    private var errorProfileCache: BlancallGenerator.ErrorProfile? = null
    private var errorProfileCacheKey: List<PracticeRecord>? = null
    private fun errorProfileFor(records: List<PracticeRecord>): BlancallGenerator.ErrorProfile {
        if (records === errorProfileCacheKey && errorProfileCache != null) return errorProfileCache!!
        val p = buildErrorProfile(records)
        errorProfileCache = p
        errorProfileCacheKey = records
        return p
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
            regenerateCloze()
        }
    }

    /** 跨文本联动：加载多篇文章混合练习 */
    fun loadArticles(articleIds: List<Long>) {
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
                    }
                }
            } catch (_: Exception) { /* 生成失败保持 null，UI 显示空态 */ }

            if (mixed == null) return@launch
            _crossSourceInfo.value = mixed!!.sources
            _crossArticleTitles.value = mixed!!.articleTitles

            _article.value = Article(
                id = -1L,
                title = articles.joinToString(" · ") { it.title },
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
                _totalBlanks.value = 0
                return@launch
            }
            _sections.value = secs
            _selectedSections.value = secs.map { it.index }.toSet()
            _rankedSections.value = ranked
            _sentenceCloze.value = sentenceResult
            _wordCloze.value = wordResult
            _dictationResult.value = dictationResult
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
    }

    fun setStrategy(newStrategy: BlancallGenerator.Strategy) {
        _strategy.value = newStrategy
        // 策略变更 → 重新生成（不清答案）
        regenerateCloze()
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

    private fun regenerateCloze() {
        val content = _article.value?.content
        if (content.isNullOrBlank()) return
        val secs = _sections.value
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
                }
            } catch (_: Exception) { /* 保持旧值，避免崩溃 */ }
            _sentenceCloze.value = sentenceResult
            _wordCloze.value = wordResult
            _dictationResult.value = dictationResult
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
            val effectiveContent = getEffectiveContent(content, secs)
            _wordAnswers.value = emptyMap()
            val recordsSnapshot = articleRecords
            val strat = _strategy.value
            val classical = _classicalMode.value
            var result: BlancallGenerator.WordClozeResult? = null
            try {
                withContext(Dispatchers.Default) {
                    val errorProfile = errorProfileFor(recordsSnapshot)
                    result = BlancallGenerator.generateWordCloze(
                        effectiveContent, count = count, errorProfile = errorProfile,
                        strategy = strat, classicalMode = classical
                    )
                }
            } catch (_: Exception) { /* 生成失败保持旧值，避免崩溃 */ }
            _wordCloze.value = result
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
    }

    fun dismissBlankCountWarning() {
        _blankCountWarning.value = null
    }

    fun useSuggestedBlankCount() {
        val warning = _blankCountWarning.value ?: return
        setWordBlankCount(warning.suggestedBlanks)
    }

    fun updateAnswer(blankIndex: Int, answer: String) {
        when (_mode.value) {
            BlancallMode.SENTENCE -> _sentenceAnswers.value = _sentenceAnswers.value + (blankIndex to answer)
            BlancallMode.WORD -> _wordAnswers.value = _wordAnswers.value + (blankIndex to answer)
            // 反向默写使用独立的整段输入，不走按空作答路径
            BlancallMode.REVERSE -> {}
        }
    }

    /** 更新反向默写的整段输入文本 */
    fun updateDictationInput(text: String) {
        // 防御：超长输入截断（渲染与判分的双重保护，防止极端输入导致性能/内存问题）
        _dictationInput.value = if (text.length > 50_000) text.take(50_000) else text
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
                        rating = rating.value
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
                        rating = rating.value
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
                dictationInput = dictationInput
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
        val secs = _sections.value
        val effectiveContent = getEffectiveContent(content, secs)
        // 取消上一次的生成协程
        wordGenerateJob?.cancel()
        sentenceGenerateJob?.cancel()
        dictationGenerateJob?.cancel()
        val recordsSnapshot = articleRecords
        val strat = _strategy.value
        val classical = _classicalMode.value
        val job = viewModelScope.launch {
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
            } catch (_: Exception) { /* 重做失败保持旧值，避免崩溃 */ }
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
