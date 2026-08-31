// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.ai.AiChatProfile
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.ai.AiSearchProfile
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassSwitch

/**
 * AI 配置管理页：
 * - AI 对话配置：可创建多个，开关式单选一个作为当前生效
 * - 联网搜索配置：同样支持多个与开关式选择（支持 Tavily 与自定义服务）
 * 每个配置支持编辑、重命名、删除。
 */
@Composable
fun AiConfigScreen(navController: NavController) {
    val chatProfiles by AiConfigStore.chatProfilesFlow.collectAsState()
    val activeChatId by AiConfigStore.activeChatIdFlow.collectAsState()
    val searchProfiles by AiConfigStore.searchProfilesFlow.collectAsState()
    val activeSearchId by AiConfigStore.activeSearchIdFlow.collectAsState()

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
                    "AI 配置",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ═══════ AI 对话配置 ═══════
                item {
                    Text("AI 对话配置", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (chatProfiles.isEmpty()) {
                    item {
                        EmptyConfigHint("还没有对话配置，点击下方按钮创建")
                    }
                } else {
                    items(chatProfiles, key = { it.id }) { profile ->
                        ChatProfileCard(
                            profile = profile,
                            active = profile.id == activeChatId,
                            onToggle = { AiConfigStore.setActiveChat(profile.id) },
                            onEdit = { navController.navigate("ai_profile_edit/chat?id=${profile.id}") },
                            onRename = { newName -> AiConfigStore.renameChatProfile(profile.id, newName) },
                            onDelete = { AiConfigStore.deleteChatProfile(profile.id) }
                        )
                    }
                }
                item {
                    GlassButton(
                        onClick = { navController.navigate("ai_profile_edit/chat") },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("＋ 创建对话配置", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }

                // ═══════ 联网搜索配置 ═══════
                item {
                    Text("联网搜索配置", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp))
                }
                if (searchProfiles.isEmpty()) {
                    item {
                        EmptyConfigHint("还没有搜索配置，点击下方按钮创建")
                    }
                } else {
                    items(searchProfiles, key = { it.id }) { profile ->
                        SearchProfileCard(
                            profile = profile,
                            active = profile.id == activeSearchId,
                            onToggle = { AiConfigStore.setActiveSearch(profile.id) },
                            onEdit = { navController.navigate("ai_profile_edit/search?id=${profile.id}") },
                            onRename = { newName -> AiConfigStore.renameSearchProfile(profile.id, newName) },
                            onDelete = { AiConfigStore.deleteSearchProfile(profile.id) }
                        )
                    }
                }
                item {
                    GlassButton(
                        onClick = { navController.navigate("ai_profile_edit/search") },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("＋ 创建搜索配置", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 空状态提示 */
@Composable
private fun EmptyConfigHint(text: String) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** AI 对话配置卡片：开关式选中 + 编辑/重命名/删除 */
@Composable
private fun ChatProfileCard(
    profile: AiChatProfile,
    active: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    ProfileCardFrame(active = active, onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text("使用中", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${profile.model} · ${profile.baseUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // 开关形式：开 = 选中该配置（单选，互斥）
            GlassSwitch(checked = active, onCheckedChange = { onToggle() })
        }
    }
    ProfileActions(
        onEdit = onEdit,
        onRename = onRename,
        onDelete = onDelete
    )
}

/** 联网搜索配置卡片 */
@Composable
private fun SearchProfileCard(
    profile: AiSearchProfile,
    active: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    ProfileCardFrame(active = active, onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text("使用中", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                val providerLabel = if (profile.provider == "tavily") "Tavily" else profile.baseUrl
                Text(
                    "$providerLabel · ${if (profile.authStyle == "bearer") "Bearer 认证" else "请求体认证"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassSwitch(checked = active, onCheckedChange = { onToggle() })
        }
    }
    ProfileActions(
        onEdit = onEdit,
        onRename = onRename,
        onDelete = onDelete
    )
}

/** 配置卡片容器：点击整卡切换开关 */
@Composable
private fun ProfileCardFrame(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else null,
        onClick = onClick
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** 操作按钮行：编辑 / 重命名 / 删除 */
@Composable
private fun ProfileActions(
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { onEdit() }) {
            Text("编辑", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = {
            renameInput = ""
            showRenameDialog = true
        }) {
            Text("重命名", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = { showDeleteDialog = true }) {
            Text("删除", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }

    if (showRenameDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("重命名配置") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("新昵称") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        onRename(renameInput)
                        showRenameDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除配置") },
            text = { Text("删除后需要重新填写才能使用，确定删除吗？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
