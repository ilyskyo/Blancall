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
import androidx.compose.ui.res.stringResource
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
    var saveError by remember { mutableStateOf(false) }

    val isEditing = profileId != null
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // 表单最后一栏（API Key）本来就贴着底栏，edge-to-edge 下 adjustResize 实测零位移，
            // 没有这一条键盘会把它整条盖掉（与 AiScreen / PracticeScreen 同一处理）
            .imePadding()
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
                    stringResource(if (isEditing) { if (isChat) com.ilyskyo.blancall.R.string.ai_edit_chat_profile else com.ilyskyo.blancall.R.string.ai_edit_search_profile } else { if (isChat) com.ilyskyo.blancall.R.string.ai_new_chat_profile else com.ilyskyo.blancall.R.string.ai_new_search_profile }),
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
                    label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_nickname)) },
                    supportingText = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_nickname_hint)) },
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
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_api_url)) },
                        supportingText = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_api_url_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (isEditing) stringResource(com.ilyskyo.blancall.R.string.ai_api_key_keep) else stringResource(com.ilyskyo.blancall.R.string.ai_api_key)) },
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
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_model_name)) },
                        supportingText = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_model_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    // ── 联网搜索配置 ──
                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_provider), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_custom_provider), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (provider == "custom") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_search_url)) },
                            supportingText = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_search_url_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_auth_style), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = authStyle == "body",
                                onClick = { authStyle = "body" },
                                label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_auth_body), style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = authStyle == "bearer",
                                onClick = { authStyle = "bearer" },
                                label = { Text(stringResource(com.ilyskyo.blancall.R.string.ai_auth_bearer), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (isEditing) stringResource(com.ilyskyo.blancall.R.string.ai_api_key_keep) else stringResource(com.ilyskyo.blancall.R.string.ai_api_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.ai_key_security),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

                // ── 保存 ──
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    saveError = false
                    // Keystore 加解密放 IO 线程
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            val editingId = profileId
                            when {
                                isChat && isEditing -> editingId != null &&
                                    AiConfigStore.updateChatProfile(editingId, name, baseUrl, apiKey, model)
                                isChat -> AiConfigStore.addChatProfile(name, baseUrl, apiKey, model) != null
                                isEditing -> editingId != null &&
                                    AiConfigStore.updateSearchProfile(editingId, name, provider, baseUrl, authStyle, apiKey)
                                else -> AiConfigStore.addSearchProfile(name, provider, baseUrl, authStyle, apiKey) != null
                            }
                        }
                        if (ok) {
                            navController.popBackStack()
                        } else {
                            // 加密保存失败（密钥库不可用等）：留在当前页提示，绝不静默丢失配置
                            saving = false
                            saveError = true
                        }
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
                Text(if (isEditing) stringResource(com.ilyskyo.blancall.R.string.ai_save_changes) else stringResource(com.ilyskyo.blancall.R.string.ai_create_profile))
            }

            if (saveError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.ai_key_encrypt_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}
