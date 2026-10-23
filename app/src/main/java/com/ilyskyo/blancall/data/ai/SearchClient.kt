// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import android.content.Context
import com.ilyskyo.blancall.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 联网搜索客户端：调用 Tavily Search API（专为 AI 设计，返回可直接投喂 LLM 的结果摘要）。
 *
 * 用于 AI 对话时的知识核验：搜索 → 结果注入上下文 → AI 基于搜索 + 文章回答。
 */
object SearchClient {

    /** 单条搜索结果 */
    data class SearchResult(
        val title: String,
        val content: String,
        val url: String
    )

    /**
     * 可展示给用户的业务错误。网络层不持有 Context，错误只登记资源号与参数，
     * 由展示方调用 [messageFor] 按当前界面语言渲染。
     */
    class SearchException(
        message: String,
        val messageRes: Int? = null,
        val formatArgs: List<Any> = emptyList()
    ) : Exception(message) {
        fun messageFor(context: Context): String =
            messageRes?.let { context.getString(it, *formatArgs.toTypedArray()) } ?: message.orEmpty()
    }

    private const val ENDPOINT = "https://api.tavily.com/search"

    /**
     * 执行搜索。必须在 IO 线程调用。
     *
     * 配置真正生效：provider=custom 且 baseUrl 非空 → 用用户自定义端点（原样使用，不拼路径）；
     * 否则回退到 Tavily 官方端点（默认行为与旧版完全一致）。
     * 认证方式：authStyle=bearer → Key 走 Authorization 头（body 不再带 api_key）；
     * 否则维持原样（JSON body 携带 api_key）。
     *
     * @param profile 当前生效的搜索配置（含 provider / baseUrl / authStyle / apiKeyEnc）
     * @param query  搜索关键词（自动截断防超长）
     * @param maxResults 返回条数上限
     */
    fun search(profile: AiSearchProfile, query: String, maxResults: Int = 5): List<SearchResult> {
        // 端点解析：自定义模式必须用用户填的完整 URL；provider=custom 却没填地址时**报错而非回退**
        // ——回退到 Tavily 会把用户的自定义 Key 发给第三方，这正是本次修复要消除的问题。
        val endpoint = if (profile.provider == "custom") {
            val raw = profile.baseUrl.trim()
            if (raw.isEmpty()) throw SearchException("Empty URL", R.string.search_error_url_empty)
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                throw SearchException("Invalid URL", R.string.search_error_url_invalid)
            }
            raw
        } else {
            ENDPOINT
        }
        // 解密 Key 仍在此处完成：search 本身只在 IO 线程被调用（见文档注释），满足 decryptApiKey 的线程约束
        val apiKey = profile.decryptApiKey()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            // Bearer 认证：Key 放请求头；body 认证（默认）才在 JSON 里带 api_key
            if (profile.authStyle == "bearer") {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            doOutput = true
        }
        try {
            val body = JSONObject().apply {
                // body 认证（默认）才把 api_key 放进 JSON；bearer 模式不放，改用请求头携带
                if (profile.authStyle != "bearer") {
                    put("api_key", apiKey)
                }
                put("query", query.take(300))
                put("max_results", maxResults)
                put("search_depth", "basic")
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                try {
                    conn.errorStream?.use { it.readBytes().take(1024) }
                } catch (_: Exception) { }
                throw when (code) {
                    401, 403 -> SearchException("HTTP 401/403", R.string.search_error_auth)
                    429 -> SearchException("HTTP 429", R.string.search_error_quota)
                    else -> SearchException("HTTP $code", R.string.search_error_http, listOf(code))
                }
            }

            val json = JSONObject(
                conn.inputStream.bufferedReader(Charset.forName("UTF-8")).readText()
            )
            val arr = json.optJSONArray("results") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                SearchResult(
                    title = r.optString("title", ""),
                    content = r.optString("content", ""),
                    url = r.optString("url", "")
                )
            }
        } catch (e: SearchException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw SearchException("UnknownHost", R.string.search_error_unreachable)
        } catch (e: java.net.SocketTimeoutException) {
            throw SearchException("SocketTimeout", R.string.search_error_timeout)
        } catch (e: java.io.IOException) {
            throw SearchException("IOException", R.string.search_error_network)
        } finally {
            conn.disconnect()
        }
    }
}
