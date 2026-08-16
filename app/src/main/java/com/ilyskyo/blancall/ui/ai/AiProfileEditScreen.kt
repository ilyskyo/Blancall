// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配置编辑页：新建 / 编辑对话配置或搜索配置。
 *
 * 参数：
 * - type: "chat" 对话配置 / "search" 搜索配置
 * - id: 编辑的配置 id；为空表示新建
 */
@Composable
fun AiProfileEditScreen(
    navController: NavController,
    type: String,
    profileId: String?
) {
    val isChat = type == "chat"
    val existingChat = remember(profileId) {
        if (isChat) AiConfigStore.chatProfilesFlow.value.find { it.id == profileId } else null
    }
    val existingSearch = remember(profileId) {
        if (!isChat) AiConfigStore.searchProfilesFlow.value.find { it.id == profileId } else null
    }

    var name by remember { mutableStateOf(existingChat?.name ?: existingSearch?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingChat?.baseUrl ?: existingSearch?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(existingChat?.model ?: "") }
    var provider by remember { mutableStateOf(existingSearch?.provider ?: "tavily") }
    var authStyle by remember { mutableStateOf(existingSearch?.authStyle ?: "body") }
    var saving by remember { mutableStateOf(false) }

    val isEditing = profileId != null
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isEditing) "编辑${if (isChat) "对话" else "搜索"}配置" else "新建${if (isChat) "对话" else "搜索"}配置",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // ── 配置昵称 ──
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配置昵称") },
                    supportingText = { Text("给这个配置起个名字，例如「我的 DeepSeek」「工作用 Kimi」") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(12.dp))

                if (isChat) {
                    // ── AI 对话配置 ──
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API 地址") },
                        supportingText = { Text("OpenAI 兼容接口，如 https://api.deepseek.com/v1") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (isEditing) "API Key（留空则不修改）" else "API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名") },
                        supportingText = { Text("如 deepseek-chat / glm-4-flash / kimi-latest") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    // ── 联网搜索配置 ──
                    Text("服务商", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = provider == "tavily",
                            onClick = { provider = "tavily" },
                            label = { Text("Tavily", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = provider == "custom",
                            onClick = { provider = "custom" },
                            label = { Text("自定义服务", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (provider == "custom") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("搜索服务地址") },
                            supportingText = { Text("POST 请求地址，如 https://your-search-service.com/search") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("认证方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = authStyle == "body",
                                onClick = { authStyle = "body" },
                                label = { Text("请求体携带 Key", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = authStyle == "bearer",
                                onClick = { authStyle = "bearer" },
                                label = { Text("Bearer 认证头", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (isEditing) "API Key（留空则不修改）" else "API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Key 经系统加密存储，仅存本机，不会上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // ── 保存 ──
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    // Keystore 加解密放 IO 线程
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (isChat) {
                                if (isEditing) {
                                    AiConfigStore.updateChatProfile(profileId, name, baseUrl, apiKey, model)
                                } else {
                                    AiConfigStore.addChatProfile(name, baseUrl, apiKey, model)
                                }
                            } else {
                                if (isEditing) {
                                    AiConfigStore.updateSearchProfile(profileId, name, provider, baseUrl, authStyle, apiKey)
                                } else {
                                    AiConfigStore.addSearchProfile(name, provider, baseUrl, authStyle, apiKey)
                                }
                            }
                        }
                        navController.popBackStack()
                    }
                },
                enabled = name.isNotBlank() &&
                    if (isChat) baseUrl.isNotBlank() && model.isNotBlank()
                    else (provider == "tavily" || baseUrl.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isEditing) "保存修改" else "创建配置")
            }
        }
    }
}
