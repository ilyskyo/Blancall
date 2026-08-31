// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ilyskyo.blancall.util.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AI 对话配置（可创建多个，开关形式选一个作为当前生效配置）。
 *
 * @param id 唯一标识
 * @param name 用户自定义昵称（显示在设置页与聊天页）
 * @param baseUrl OpenAI 兼容 API 地址
 * @param apiKeyEnc API Key 密文（SecurePrefs 加密，绝不落明文）
 * @param model 模型名
 */
data class AiChatProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyEnc: String,
    val model: String
) {
    /** 解密 API Key（IO 线程调用；解密失败返回空串） */
    fun decryptApiKey(): String =
        if (apiKeyEnc.isBlank()) "" else SecurePrefs.decrypt(apiKeyEnc) ?: ""
}

/**
 * 联网搜索配置（可创建多个，开关形式选一个作为当前生效配置）。
 *
 * @param provider 服务商："tavily"（官方端点）或 "custom"（自定义端点）
 * @param baseUrl 自定义端点（provider=custom 时必填，支持 Tavily 兼容格式）
 * @param authStyle 认证方式："body"（JSON body 携带 api_key）或 "bearer"（Authorization 头）
 */
data class AiSearchProfile(
    val id: String,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val authStyle: String,
    val apiKeyEnc: String
) {
    /** 解密 API Key（IO 线程调用；解密失败返回空串） */
    fun decryptApiKey(): String =
        if (apiKeyEnc.isBlank()) "" else SecurePrefs.decrypt(apiKeyEnc) ?: ""
}

/**
 * AI / 联网搜索 多配置存储（SharedPreferences + JSON 持久化）。
 *
 * - 对话配置与搜索配置各自独立列表，各自维护"当前生效"的一个
 * - Key 一律以密文落盘（SecurePrefs AES/GCM），列表 JSON 中只存密文
 * - 兼容升级前的单配置字段，首次加载时自动迁移为一条配置
 */
object AiConfigStore {

    private const val KEY_CHAT_PROFILES = "ai_chat_profiles"
    private const val KEY_ACTIVE_CHAT_ID = "ai_active_chat_id"
    private const val KEY_SEARCH_PROFILES = "ai_search_profiles"
    private const val KEY_ACTIVE_SEARCH_ID = "ai_active_search_id"

    // ── 旧版单配置字段（迁移用） ──
    private const val LEGACY_BASE_URL = "ai_base_url"
    private const val LEGACY_MODEL = "ai_model"
    private const val LEGACY_API_KEY = "ai_api_key_enc"
    private const val LEGACY_SEARCH_KEY = "ai_search_api_key_enc"

    private lateinit var prefs: SharedPreferences

    private val _chatProfilesFlow = MutableStateFlow<List<AiChatProfile>>(emptyList())
    val chatProfilesFlow: StateFlow<List<AiChatProfile>> = _chatProfilesFlow.asStateFlow()

    private val _activeChatIdFlow = MutableStateFlow<String?>(null)
    val activeChatIdFlow: StateFlow<String?> = _activeChatIdFlow.asStateFlow()

    private val _searchProfilesFlow = MutableStateFlow<List<AiSearchProfile>>(emptyList())
    val searchProfilesFlow: StateFlow<List<AiSearchProfile>> = _searchProfilesFlow.asStateFlow()

    private val _activeSearchIdFlow = MutableStateFlow<String?>(null)
    val activeSearchIdFlow: StateFlow<String?> = _activeSearchIdFlow.asStateFlow()

    /** 当前生效的 AI 对话配置（未选择返回 null） */
    val activeChatProfile: AiChatProfile?
        get() = _chatProfilesFlow.value.find { it.id == _activeChatIdFlow.value }

    /** 当前生效的联网搜索配置（未选择返回 null） */
    val activeSearchProfile: AiSearchProfile?
        get() = _searchProfilesFlow.value.find { it.id == _activeSearchIdFlow.value }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        _chatProfilesFlow.value = readProfiles(KEY_CHAT_PROFILES) { parseChatProfile(it) }
        _activeChatIdFlow.value = prefs.getString(KEY_ACTIVE_CHAT_ID, null)
        _searchProfilesFlow.value = readProfiles(KEY_SEARCH_PROFILES) { parseSearchProfile(it) }
        _activeSearchIdFlow.value = prefs.getString(KEY_ACTIVE_SEARCH_ID, null)
        migrateLegacy()
        // 激活 id 失效（配置被删）时回退：优先第一个，否则置空
        if (_activeChatIdFlow.value != null && activeChatProfile == null) {
            _activeChatIdFlow.value = _chatProfilesFlow.value.firstOrNull()?.id
        }
        if (_activeSearchIdFlow.value != null && activeSearchProfile == null) {
            _activeSearchIdFlow.value = _searchProfilesFlow.value.firstOrNull()?.id
        }
    }

    // ── AI 对话配置 ──

    /**
     * 新增对话配置。加密失败（如系统密钥库不可用）时返回 null，
     * 调用方应提示用户重试——绝不写入空密文（否则表现为"已保存却永久不可用"）。
     */
    fun addChatProfile(name: String, baseUrl: String, apiKey: String, model: String): AiChatProfile? {
        val apiKeyEnc = SecurePrefs.encrypt(apiKey) ?: return null
        val profile = AiChatProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名配置" },
            baseUrl = baseUrl.trim(),
            apiKeyEnc = apiKeyEnc,
            model = model.trim()
        )
        _chatProfilesFlow.value = _chatProfilesFlow.value + profile
        persistChatProfiles()
        // 第一个创建的配置自动设为生效
        if (_activeChatIdFlow.value == null) {
            _activeChatIdFlow.value = profile.id
            prefs.edit { putString(KEY_ACTIVE_CHAT_ID, profile.id) }
        }
        return profile
    }

    /**
     * 更新对话配置。返回 false 表示填写了新 Key 但加密失败（密钥库不可用），
     * 调用方应提示用户重试（原密文保持不变）。
     */
    fun updateChatProfile(id: String, name: String, baseUrl: String, apiKey: String, model: String): Boolean {
        // 仅当填写了新 Key 时才重新加密；加密失败（密钥库不可用）返回 false，提示用户重试
        val newApiKeyEnc = if (apiKey.isBlank()) null else (SecurePrefs.encrypt(apiKey) ?: return false)
        _chatProfilesFlow.value = _chatProfilesFlow.value.map { p ->
            if (p.id == id) {
                p.copy(
                    name = name.trim().ifBlank { "未命名配置" },
                    baseUrl = baseUrl.trim(),
                    // Key 留空表示不修改（沿用原密文）
                    apiKeyEnc = newApiKeyEnc ?: p.apiKeyEnc,
                    model = model.trim()
                )
            } else p
        }
        persistChatProfiles()
        return true
    }

    /** 重命名配置（仅改昵称，不动 API 信息） */
    fun renameChatProfile(id: String, newName: String) {
        val name = newName.trim().ifBlank { return }
        _chatProfilesFlow.value = _chatProfilesFlow.value.map { if (it.id == id) it.copy(name = name) else it }
        persistChatProfiles()
    }

    fun deleteChatProfile(id: String) {
        _chatProfilesFlow.value = _chatProfilesFlow.value.filter { it.id != id }
        persistChatProfiles()
        if (_activeChatIdFlow.value == id) {
            _activeChatIdFlow.value = _chatProfilesFlow.value.firstOrNull()?.id
            prefs.edit { putString(KEY_ACTIVE_CHAT_ID, _activeChatIdFlow.value) }
        }
    }

    /** 开关式单选：开启该配置 = 设为当前生效 */
    fun setActiveChat(id: String) {
        if (_chatProfilesFlow.value.none { it.id == id }) return
        _activeChatIdFlow.value = id
        prefs.edit { putString(KEY_ACTIVE_CHAT_ID, id) }
    }

    // ── 联网搜索配置 ──

    fun addSearchProfile(
        name: String,
        provider: String,
        baseUrl: String,
        authStyle: String,
        apiKey: String
    ): AiSearchProfile? {
        // 加密失败（如系统密钥库不可用）时返回 null，绝不写入空密文
        val apiKeyEnc = SecurePrefs.encrypt(apiKey) ?: return null
        val profile = AiSearchProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名配置" },
            provider = provider,
            baseUrl = baseUrl.trim(),
            authStyle = authStyle,
            apiKeyEnc = apiKeyEnc
        )
        _searchProfilesFlow.value = _searchProfilesFlow.value + profile
        persistSearchProfiles()
        if (_activeSearchIdFlow.value == null) {
            _activeSearchIdFlow.value = profile.id
            prefs.edit { putString(KEY_ACTIVE_SEARCH_ID, profile.id) }
        }
        return profile
    }

    /**
     * 更新搜索配置。返回 false 表示填写了新 Key 但加密失败（密钥库不可用）。
     */
    fun updateSearchProfile(id: String, name: String, provider: String, baseUrl: String, authStyle: String, apiKey: String): Boolean {
        val newApiKeyEnc = if (apiKey.isBlank()) null else (SecurePrefs.encrypt(apiKey) ?: return false)
        _searchProfilesFlow.value = _searchProfilesFlow.value.map { p ->
            if (p.id == id) {
                p.copy(
                    name = name.trim().ifBlank { "未命名配置" },
                    provider = provider,
                    baseUrl = baseUrl.trim(),
                    authStyle = authStyle,
                    apiKeyEnc = newApiKeyEnc ?: p.apiKeyEnc
                )
            } else p
        }
        persistSearchProfiles()
        return true
    }

    fun renameSearchProfile(id: String, newName: String) {
        val name = newName.trim().ifBlank { return }
        _searchProfilesFlow.value = _searchProfilesFlow.value.map { if (it.id == id) it.copy(name = name) else it }
        persistSearchProfiles()
    }

    fun deleteSearchProfile(id: String) {
        _searchProfilesFlow.value = _searchProfilesFlow.value.filter { it.id != id }
        persistSearchProfiles()
        if (_activeSearchIdFlow.value == id) {
            _activeSearchIdFlow.value = _searchProfilesFlow.value.firstOrNull()?.id
            prefs.edit { putString(KEY_ACTIVE_SEARCH_ID, _activeSearchIdFlow.value) }
        }
    }

    /** 开关式单选：开启该配置 = 设为当前生效 */
    fun setActiveSearch(id: String) {
        if (_searchProfilesFlow.value.none { it.id == id }) return
        _activeSearchIdFlow.value = id
        prefs.edit { putString(KEY_ACTIVE_SEARCH_ID, id) }
    }

    // ── 持久化 ──

    private fun persistChatProfiles() {
        if (!::prefs.isInitialized) return
        val arr = JSONArray()
        _chatProfilesFlow.value.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKeyEnc", p.apiKeyEnc)
                    .put("model", p.model)
            )
        }
        prefs.edit { putString(KEY_CHAT_PROFILES, arr.toString()) }
    }

    private fun persistSearchProfiles() {
        if (!::prefs.isInitialized) return
        val arr = JSONArray()
        _searchProfilesFlow.value.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("provider", p.provider)
                    .put("baseUrl", p.baseUrl)
                    .put("authStyle", p.authStyle)
                    .put("apiKeyEnc", p.apiKeyEnc)
            )
        }
        prefs.edit { putString(KEY_SEARCH_PROFILES, arr.toString()) }
    }

    private fun <T> readProfiles(key: String, parser: (JSONObject) -> T): List<T> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(key, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try { parser(arr.optJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseChatProfile(obj: JSONObject): AiChatProfile = AiChatProfile(
        id = obj.optString("id"),
        name = obj.optString("name", "未命名配置"),
        baseUrl = obj.optString("baseUrl", ""),
        apiKeyEnc = obj.optString("apiKeyEnc", ""),
        model = obj.optString("model", "")
    )

    private fun parseSearchProfile(obj: JSONObject): AiSearchProfile = AiSearchProfile(
        id = obj.optString("id"),
        name = obj.optString("name", "未命名配置"),
        provider = obj.optString("provider", "tavily"),
        baseUrl = obj.optString("baseUrl", ""),
        authStyle = obj.optString("authStyle", "body"),
        apiKeyEnc = obj.optString("apiKeyEnc", "")
    )

    /**
     * 升级迁移：旧版单配置（ai_base_url / ai_model / ai_api_key_enc / ai_search_api_key_enc）
     * 首次加载时自动转为一条新配置，避免用户升级后丢失已有配置。
     */
    private fun migrateLegacy() {
        if (!::prefs.isInitialized) return
        if (_chatProfilesFlow.value.isEmpty() && !(prefs.getString(LEGACY_BASE_URL, "") ?: "").isBlank()) {
            val legacy = AiChatProfile(
                id = "legacy",
                name = "默认配置",
                baseUrl = prefs.getString(LEGACY_BASE_URL, "") ?: "",
                apiKeyEnc = prefs.getString(LEGACY_API_KEY, "") ?: "",
                model = prefs.getString(LEGACY_MODEL, "") ?: ""
            )
            _chatProfilesFlow.value = listOf(legacy)
            _activeChatIdFlow.value = legacy.id
            persistChatProfiles()
            prefs.edit {
                putString(KEY_ACTIVE_CHAT_ID, legacy.id)
                remove(LEGACY_BASE_URL)
                remove(LEGACY_MODEL)
                remove(LEGACY_API_KEY)
            }
        }
        if (_searchProfilesFlow.value.isEmpty() && !(prefs.getString(LEGACY_SEARCH_KEY, "") ?: "").isBlank()) {
            val legacy = AiSearchProfile(
                id = "legacy-search",
                name = "Tavily",
                provider = "tavily",
                baseUrl = "",
                authStyle = "body",
                apiKeyEnc = prefs.getString(LEGACY_SEARCH_KEY, "") ?: ""
            )
            _searchProfilesFlow.value = listOf(legacy)
            _activeSearchIdFlow.value = legacy.id
            persistSearchProfiles()
            prefs.edit {
                putString(KEY_ACTIVE_SEARCH_ID, legacy.id)
                remove(LEGACY_SEARCH_KEY)
            }
        }
    }
}
