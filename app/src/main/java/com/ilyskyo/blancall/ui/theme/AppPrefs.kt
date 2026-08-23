// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.util.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用偏好设置（SharedPreferences 持久化）
 */
object AppPrefs {
    private lateinit var prefs: SharedPreferences

    /**
     * 已知图标 key 集合（[com.ilyskyo.blancall.ui.common.AppIconKind] 的小写名）。
     * SP key 沿用旧 "emoji_icon" 以兼容历史值；init() 中若存储值不在本集合（如旧 emoji），
     * 迁移重置为默认 "logo"。
     */
    private val KNOWN_ICON_KEYS = setOf(
        "logo", "celebrate", "edit", "inbox", "arrowforward", "openinfull", "check"
    )

    private val _predictiveBackFlow = MutableStateFlow(true)
    /** 响应式状态流，Compose 中通过 collectAsState() 订阅 */
    val predictiveBackFlow: StateFlow<Boolean> = _predictiveBackFlow.asStateFlow()

    private val _accentColorFlow = MutableStateFlow(0)
    /** 主题色索引（0=靛蓝 1=海蓝 2=翠绿 3=暖橙 4=玫红 5=石墨） */
    val accentColorFlow: StateFlow<Int> = _accentColorFlow.asStateFlow()

    private val _homeIconKeyFlow = MutableStateFlow("logo")
    /** 首页 Logo 图标 key（AppIconKind 的小写名，如 "logo" / "celebrate" …） */
    val homeIconKeyFlow: StateFlow<String> = _homeIconKeyFlow.asStateFlow()

    private val _subtitleFlow = MutableStateFlow("Fill the blank, recall the knowledge.")
    /** 首页副标题（默认品牌标语，可自定义） */
    val subtitleFlow: StateFlow<String> = _subtitleFlow.asStateFlow()

    private val _showHomeEmojiFlow = MutableStateFlow(true)
    /** 首页左上角表情图标显示开关 */
    val showHomeEmojiFlow: StateFlow<Boolean> = _showHomeEmojiFlow.asStateFlow()

    private val _lightBeigeBackgroundFlow = MutableStateFlow(false)
    /** 浅色模式米黄底色开关：开启使用暖米黄底色，关闭使用纯白底色（深色模式不受影响） */
    val lightBeigeBackgroundFlow: StateFlow<Boolean> = _lightBeigeBackgroundFlow.asStateFlow()

    private val _reviewTemplateFlow = MutableStateFlow("standard")
    /** 复习模板 ID（sprint / standard / deep） */
    val reviewTemplateFlow: StateFlow<String> = _reviewTemplateFlow.asStateFlow()

    private val _hiddenArticleIdsFlow = MutableStateFlow<Set<Long>>(emptySet())
    /** 首页隐藏的文章 ID 集合（仅从首页"最近文章"移除，不从文章列表删除） */
    val hiddenArticleIdsFlow: StateFlow<Set<Long>> = _hiddenArticleIdsFlow.asStateFlow()

    // ── AI 学习助手 ──
    private val _aiEnabledFlow = MutableStateFlow(false)
    /** AI 功能总开关（关闭后所有 AI 入口隐藏） */
    val aiEnabledFlow: StateFlow<Boolean> = _aiEnabledFlow.asStateFlow()

    private val _aiBaseUrlFlow = MutableStateFlow("")
    /** AI API 地址（OpenAI 兼容格式，由用户自行填写） */
    val aiBaseUrlFlow: StateFlow<String> = _aiBaseUrlFlow.asStateFlow()

    private val _aiModelFlow = MutableStateFlow("")
    /** AI 模型名（由用户自行填写） */
    val aiModelFlow: StateFlow<String> = _aiModelFlow.asStateFlow()

    private val _aiHistoryEnabledFlow = MutableStateFlow(false)
    /** 保存与 AI 的对话（开启后对话持久化到本机，可查看历史；关闭后不保存且首页 AI 入口隐藏） */
    val aiHistoryEnabledFlow: StateFlow<Boolean> = _aiHistoryEnabledFlow.asStateFlow()

    private val _aiSearchEnabledFlow = MutableStateFlow(false)
    /** 联网搜索（开启后提问自动联网搜索核验，需配置 Tavily Key） */
    val aiSearchEnabledFlow: StateFlow<Boolean> = _aiSearchEnabledFlow.asStateFlow()

    private val _useSimilarityRatingFlow = MutableStateFlow(true)
    /** 练习评级方式：true=默写相似度→四档（FSRS-6 默认）；false=旧正确率→四档（回退） */
    val useSimilarityRatingFlow: StateFlow<Boolean> = _useSimilarityRatingFlow.asStateFlow()

    private val _builtInLibraryEnabledFlow = MutableStateFlow(false)
    /** 内置素材库开关：开启后底部导航栏新增「素材库」入口，可在应用内离线查看内置的西方思想素材 */
    val builtInLibraryEnabledFlow: StateFlow<Boolean> = _builtInLibraryEnabledFlow.asStateFlow()

    private val _onboardingSeenFlow = MutableStateFlow(false)
    /** 首次使用引导页是否已看过（首启展示一次，之后可在设置里重看） */
    val onboardingSeenFlow: StateFlow<Boolean> = _onboardingSeenFlow.asStateFlow()

    @SuppressLint("ApplySharedPref")
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        _predictiveBackFlow.value = prefs.getBoolean("predictive_back", true)
        _accentColorFlow.value = prefs.getInt("accent_color", 0)
        _homeIconKeyFlow.value = prefs.getString("emoji_icon", "logo")?.takeIf { it in KNOWN_ICON_KEYS } ?: "logo"
        _subtitleFlow.value = prefs.getString("subtitle", "Fill the blank, recall the knowledge.") ?: "Fill the blank, recall the knowledge."
        _showHomeEmojiFlow.value = prefs.getBoolean("show_home_emoji", false)
        _lightBeigeBackgroundFlow.value = prefs.getBoolean("light_beige_background", false)
        _reviewTemplateFlow.value = prefs.getString("review_template", "standard") ?: "standard"
        _hiddenArticleIdsFlow.value = prefs.getStringSet("hidden_articles", emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        _aiEnabledFlow.value = prefs.getBoolean("ai_enabled", false)
        _aiBaseUrlFlow.value = prefs.getString("ai_base_url", "") ?: ""
        _aiModelFlow.value = prefs.getString("ai_model", "") ?: ""
        _aiHistoryEnabledFlow.value = prefs.getBoolean("ai_history_enabled", false)
        _aiSearchEnabledFlow.value = prefs.getBoolean("ai_search_enabled", false)
        _useSimilarityRatingFlow.value = prefs.getBoolean("use_similarity_rating", true)
        _builtInLibraryEnabledFlow.value = prefs.getBoolean("built_in_library_enabled", false)
        _onboardingSeenFlow.value = prefs.getBoolean("onboarding_seen", false)
    }

    var predictiveBackEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("predictive_back", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("predictive_back", value) }
                _predictiveBackFlow.value = value
            }
        }

    var accentColorIndex: Int
        get() = if (::prefs.isInitialized) prefs.getInt("accent_color", 0) else 0
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putInt("accent_color", value) }
                _accentColorFlow.value = value
            }
        }

    var homeIconKey: String
        get() = if (::prefs.isInitialized) {
            (prefs.getString("emoji_icon", "logo") ?: "logo").takeIf { it in KNOWN_ICON_KEYS } ?: "logo"
        } else {
            "logo"
        }
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("emoji_icon", value) }
                _homeIconKeyFlow.value = value
            }
        }

    var subtitle: String
        get() = if (::prefs.isInitialized) prefs.getString("subtitle", "Fill the blank, recall the knowledge.") ?: "Fill the blank, recall the knowledge." else "Fill the blank, recall the knowledge."
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("subtitle", value) }
                _subtitleFlow.value = value
            }
        }

    var showHomeEmoji: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("show_home_emoji", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("show_home_emoji", value) }
                _showHomeEmojiFlow.value = value
            }
        }

    /** 浅色模式米黄底色开关（深色模式始终纯黑） */
    var lightBeigeBackgroundEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("light_beige_background", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("light_beige_background", value) }
                _lightBeigeBackgroundFlow.value = value
            }
        }

    var reviewTemplateId: String
        get() = if (::prefs.isInitialized) prefs.getString("review_template", "standard") ?: "standard" else "standard"
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("review_template", value) }
                _reviewTemplateFlow.value = value
                // 同步 FSRS 目标留存率（「复习频率」设置 = FSRS 复习强度）
                FsrsEngine.configure(FsrsEngine.DEFAULT_PARAMS, FsrsEngine.retentionForTemplate(value))
            }
        }

    /** 将文章加入首页隐藏列表（仅从首页"最近文章"移除，不从文章列表删除） */
    fun hideArticleFromHome(id: Long) {
        if (::prefs.isInitialized) {
            val current = _hiddenArticleIdsFlow.value
            if (id !in current) {
                val updated = current + id
                prefs.edit { putStringSet("hidden_articles", updated.map { it.toString() }.toSet()) }
                _hiddenArticleIdsFlow.value = updated
            }
        }
    }

    // ── AI 学习助手 ──

    var aiEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("ai_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("ai_enabled", value) }
                _aiEnabledFlow.value = value
            }
        }

    var aiBaseUrl: String
        get() = if (::prefs.isInitialized)
            prefs.getString("ai_base_url", "") ?: ""
        else ""
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("ai_base_url", value) }
                _aiBaseUrlFlow.value = value
            }
        }

    var aiModel: String
        get() = if (::prefs.isInitialized)
            prefs.getString("ai_model", "") ?: ""
        else ""
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putString("ai_model", value) }
                _aiModelFlow.value = value
            }
        }

    /** 保存与 AI 的对话开关 */
    var aiHistoryEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("ai_history_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("ai_history_enabled", value) }
                _aiHistoryEnabledFlow.value = value
            }
        }

    /** 联网搜索开关 */
    var aiSearchEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("ai_search_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("ai_search_enabled", value) }
                _aiSearchEnabledFlow.value = value
            }
        }

    /** 练习评级方式开关：相似度→四档（默认）/ 正确率→四档（回退旧行为） */
    var useSimilarityRating: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("use_similarity_rating", true) else true
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("use_similarity_rating", value) }
                _useSimilarityRatingFlow.value = value
            }
        }

    /** 内置素材库开关（开启后底部导航栏出现「素材库」入口，可离线查看内置西方思想内容） */
    var builtInLibraryEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("built_in_library_enabled", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("built_in_library_enabled", value) }
                _builtInLibraryEnabledFlow.value = value
            }
        }

    /** 首次使用引导页是否已看过；首启展示一次后置为 true，之后可从设置里重看 */
    var onboardingSeen: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean("onboarding_seen", false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit { putBoolean("onboarding_seen", value) }
                _onboardingSeenFlow.value = value
            }
        }

    /**
     * 搜索服务 API Key（Tavily）：与 AI Key 相同，经 Keystore AES/GCM 加密存储，
     * 解密失败自动清空由用户重新配置。
     */
    var aiSearchApiKey: String
        get() {
            if (!::prefs.isInitialized) return ""
            val enc = prefs.getString("ai_search_api_key_enc", "") ?: return ""
            if (enc.isBlank()) return ""
            return SecurePrefs.decrypt(enc) ?: run {
                prefs.edit { remove("ai_search_api_key_enc") }
                ""
            }
        }
        set(value) {
            if (::prefs.isInitialized) {
                if (value.isBlank()) {
                    prefs.edit { remove("ai_search_api_key_enc") }
                } else {
                    val enc = SecurePrefs.encrypt(value)
                    if (enc != null) {
                        prefs.edit { putString("ai_search_api_key_enc", enc) }
                    } else {
                        prefs.edit { remove("ai_search_api_key_enc") }
                    }
                }
            }
        }

    /**
     * AI API Key：经 Android Keystore AES/GCM 加密后存储（只落密文，绝不落明文）。
     * 解密失败（如备份恢复后密钥不可用）时自动清空密文，由用户重新配置。
     */
    var aiApiKey: String
        get() {
            if (!::prefs.isInitialized) return ""
            val enc = prefs.getString("ai_api_key_enc", "") ?: return ""
            if (enc.isBlank()) return ""
            return SecurePrefs.decrypt(enc) ?: run {
                // 密钥失效：清除密文，避免反复解密失败
                prefs.edit { remove("ai_api_key_enc") }
                ""
            }
        }
        set(value) {
            if (::prefs.isInitialized) {
                if (value.isBlank()) {
                    prefs.edit { remove("ai_api_key_enc") }
                } else {
                    val enc = SecurePrefs.encrypt(value)
                    if (enc != null) {
                        prefs.edit { putString("ai_api_key_enc", enc) }
                    } else {
                        // 加密失败（Keystore 异常等）：不保存，避免写入无效数据
                        prefs.edit { remove("ai_api_key_enc") }
                    }
                }
            }
        }
}
