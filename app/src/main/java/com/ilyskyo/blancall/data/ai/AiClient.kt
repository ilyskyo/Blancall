// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * AI 对话客户端：调用 OpenAI 兼容的 chat/completions 接口（SSE 流式返回）。
 *
 * 兼容服务：OpenAI / DeepSeek / 通义千问 / 智谱 GLM / Kimi 等（只要支持 OpenAI 格式）。
 *
 * 安全约定：
 * - API Key 仅通过 Authorization: Bearer 头传递，不写入日志
 * - 使用系统默认 TLS 证书校验，不绕过 HTTPS 检查
 * - 错误信息分类返回，不携带敏感信息
 */
object AiClient {

    /** 对话消息（OpenAI 格式） */
    data class ChatMessage(val role: String, val content: String)

    /** 可展示给用户的业务错误 */
    class AiException(message: String) : Exception(message)

    /**
     * 流式对话：逐段返回 AI 增量内容。
     *
     * @param baseUrl API 地址（如 https://api.openai.com/v1，可自定义兼容服务）
     * @param apiKey  API Key
     * @param model   模型名
     * @param messages 对话历史（首条通常为 system 提示词）
     * @return 增量文本流（每项为一段新内容）；网络/鉴权错误以 AiException 抛出
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>
    ): Flow<String> = flow {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            // 明确要求不压缩：部分网关会强制 gzip，不解压会读到乱码（null/null 片段）
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            val body = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", JSONArray().apply {
                    messages.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.content)) }
                })
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                // 限量读取错误信息（仅用于分类，避免超大错误体占用内存）
                try {
                    conn.errorStream?.use { it.readBytes().take(2048) }
                } catch (_: Exception) { }
                throw when (code) {
                    401, 403 -> AiException("API Key 无效或无权限，请在设置中检查")
                    429 -> AiException("请求过于频繁（限流），请稍后再试")
                    400 -> AiException("请求参数有误，请检查模型名与 API 地址")
                    else -> AiException("服务请求失败（$code）")
                }
            }

            // 读取响应流：若服务端仍返回 gzip（无视 identity），检测后自动解压
            val encoding = conn.getHeaderField("Content-Encoding")
            val rawInput: InputStream = conn.inputStream
            val input: InputStream = if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
                GZIPInputStream(rawInput)
            } else {
                rawInput
            }

            // 逐行解析 SSE：data: {...}，结束标记 data: [DONE]
            val reader = input.bufferedReader(Charset.forName("UTF-8"))
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    // 注意：optString(name) 在字段缺失时返回字符串 "null"，
                    // 而流式首条 delta 通常只有 role 无 content，必须用 has() 校验过滤
                    val deltaObj = JSONObject(data)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")
                    val delta = if (deltaObj != null && deltaObj.has("content")) {
                        deltaObj.optString("content")
                    } else {
                        null
                    }
                    // 双重过滤：字段缺失（null）以及部分中转 API 发出的字面 "null" 字符串
                    if (!delta.isNullOrEmpty() && delta != "null") emit(delta)
                } catch (_: Exception) {
                    // 跳过无法解析的片段（保持流式稳定性）
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw AiException("无法连接服务器，请检查网络或 API 地址")
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException("请求超时，请稍后重试")
        } catch (e: java.io.IOException) {
            throw AiException("网络连接失败，请检查网络")
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
