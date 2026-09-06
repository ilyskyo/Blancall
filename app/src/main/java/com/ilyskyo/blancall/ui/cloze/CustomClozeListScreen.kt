// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.cloze

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.data.repository.ArticleRepository
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义挖空配置列表页（按文章）。
 *
 * - 「新建」→ 空白编辑器
 * - 点按配置 → 编辑器（回填已保存的挖空状态，可继续修改）
 * - 长按配置 → 菜单：开始练习 / 重命名 / 删除
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomClozeListScreen(
    navController: NavController,
    articleId: Long
) {
    val context = LocalContext.current
    val store = remember { CustomClozeStore.getInstance(context.filesDir) }
    var configs by remember { mutableStateOf<List<CustomClozeStore.CustomConfig>>(emptyList()) }
    var articleTitle by remember { mutableStateOf("") }
    var loadFailed by remember { mutableStateOf(false) }

    // 菜单/对话框状态
    var menuForId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<CustomClozeStore.CustomConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomClozeStore.CustomConfig?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun reload() {
        configs = store.getConfigs(articleId)
    }

    LaunchedEffect(articleId) {
        val art = withContext(Dispatchers.IO) {
            ArticleRepository(navController.context.filesDir.resolve("articles.json").absolutePath)
                .getArticleById(articleId)
        }
        if (art == null) {
            loadFailed = true
        } else {
            articleTitle = art.title
        }
        configs = store.getConfigs(articleId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp)
        ) {
            // ── 顶栏：返回 + 标题 + 新建 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "自定义挖空",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { navController.navigate("custom_cloze_edit/$articleId") },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("新建")
                }
            }

            Text(
                articleTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "点按配置进入编辑 · 长按开始练习 / 重命名 / 删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (loadFailed) {
                Text("文章不存在或已被删除", color = MaterialTheme.colorScheme.error)
            } else if (configs.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "还没有自定义配置\n点右上角「新建」创建第一套",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
                ) {
                    items(configs.size, key = { configs[it].id }) { idx ->
                        val cfg = configs[idx]
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .combinedClickable(
                                        onClick = {
                                            navController.navigate("custom_cloze_edit/$articleId?configId=${cfg.id}")
                                        },
                                        onLongClick = { menuForId = cfg.id }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎯", fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cfg.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${cfg.blanks.size} 个空 · " + when (cfg.mode) {
                                            "SENTENCE" -> "句子挖空"
                                            "REVERSE" -> "反向默写"
                                            else -> "字词挖空"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // 长按菜单（锚定在配置卡片上）
                            GlassDropdownMenu(
                                expanded = menuForId == cfg.id,
                                onDismissRequest = { menuForId = null }
                            ) {
                                GlassMenuItem(
                                    onClick = {
                                        menuForId = null
                                        navController.navigate("practice/$articleId?configId=${cfg.id}")
                                    },
                                    label = { Text("开始练习", style = MaterialTheme.typography.bodyMedium) }
                                )
                                GlassMenuItem(
                                    onClick = {
                                        menuForId = null
                                        renameTarget = cfg
                                        renameText = cfg.name
                                    },
                                    label = { Text("重命名", style = MaterialTheme.typography.bodyMedium) }
                                )
                                GlassMenuItem(
                                    onClick = {
                                        menuForId = null
                                        deleteTarget = cfg
                                    },
                                    label = { Text("删除", style = MaterialTheme.typography.bodyMedium) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 重命名弹窗 ──
    if (renameTarget != null) {
        BlancallAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名配置", fontWeight = FontWeight.SemiBold) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val cfg = renameTarget ?: return@TextButton
                    if (renameText.isNotBlank()) {
                        store.saveConfig(articleId, cfg.copy(name = renameText.trim()))
                        reload()
                    }
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // ── 删除确认弹窗 ──
    if (deleteTarget != null) {
        BlancallAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除配置", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定删除「${deleteTarget!!.name}」（${deleteTarget!!.blanks.size} 个空）？删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    store.deleteConfig(articleId, deleteTarget!!.id)
                    reload()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}
