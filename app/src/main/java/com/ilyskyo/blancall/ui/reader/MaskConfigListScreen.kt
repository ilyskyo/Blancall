// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 遮挡自定义配置列表页（阅读模式内浮层，按文章保存）。
 *
 * - 「新建」→ 空白遮挡编辑器
 * - 点按配置 → 编辑器（回填已保存的遮挡，可继续修改）
 * - 长按配置 → 菜单：使用 / 重命名 / 删除
 *   「使用」= 标记为本文使用中的配置，并把阅读遮挡粒度切到「自定义」，返回即生效。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MaskConfigListScreen(
    articleId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onNew: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MaskConfigStore.getInstance(context.filesDir) }
    var configs by remember { mutableStateOf<List<MaskConfigStore.MaskConfig>>(emptyList()) }
    var selectedId by remember { mutableLongStateOf(-1L) }
    var articleTitle by remember { mutableStateOf("") }

    // 菜单/对话框状态
    var menuForId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<MaskConfigStore.MaskConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<MaskConfigStore.MaskConfig?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun reload() {
        // 磁盘读放 IO 线程
        scope.launch {
            val cfgs = withContext(Dispatchers.IO) { store.getConfigs(articleId) }
            val sel = withContext(Dispatchers.IO) { store.getSelected(articleId) }
            configs = cfgs
            selectedId = sel
        }
    }

    LaunchedEffect(articleId) {
        reload()
        val art = withContext(Dispatchers.IO) {
            runCatching {
                ArticleRepository(context.filesDir.resolve("articles.json").absolutePath)
                    .getArticleById(articleId)
            }.getOrNull()
        }
        articleTitle = art?.title ?: ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            // 拦截全部点击：空区域不穿透到底下的阅读界面
            .pointerInput(Unit) { detectTapGestures { } }
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
                BackButton(onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    "遮挡自定义",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onNew,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("新建")
                }
            }
            Spacer(Modifier.height(4.dp))
            if (articleTitle.isNotBlank()) {
                Text(
                    articleTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "点按配置进入编辑 · 长按使用 / 重命名 / 删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (configs.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "还没有遮挡配置\n点这里新建，直接在正文上标注要遮住的句子或字词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                        .combinedClickable(onClick = onNew)
                        .padding(16.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(configs.size, key = { configs[it].id }) { idx ->
                        val cfg = configs[idx]
                        val inUse = cfg.id == selectedId
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (inUse) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .combinedClickable(
                                        onClick = { onEdit(cfg.id) },
                                        onLongClick = { menuForId = cfg.id }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🫥", fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cfg.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${cfg.spans.size} 块遮挡" + if (inUse) " · 使用中" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (inUse) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
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
                                        // 标记使用 + 遮挡粒度切到自定义 + 确保遮挡开关打开（磁盘写放 IO 线程）
                                        scope.launch {
                                            withContext(Dispatchers.IO) { store.setSelected(articleId, cfg.id) }
                                            AppPrefs.readingOcclusionCustomConfigId = cfg.id
                                            AppPrefs.readingOcclusionMode = "custom"
                                            AppPrefs.readingOcclusionEnabled = true
                                            Toast.makeText(context, "已使用「${cfg.name}」", Toast.LENGTH_SHORT).show()
                                            onBack()
                                        }
                                    },
                                    label = { Text("使用", style = MaterialTheme.typography.bodyMedium) }
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
                    renameTarget = null
                    if (renameText.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.saveConfig(articleId, cfg.copy(name = renameText.trim())) }
                            reload()
                        }
                    }
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
                    "确定删除「${deleteTarget!!.name}」（${deleteTarget!!.spans.size} 块遮挡）？删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val target = deleteTarget ?: return@TextButton
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteConfig(articleId, target.id) }
                        // 删除「使用中」配置时同步清掉 AppPrefs 指向，阅读页 custom 粒度自然回退为无遮挡
                        if (AppPrefs.readingOcclusionCustomConfigId == target.id) {
                            AppPrefs.readingOcclusionCustomConfigId = -1L
                        }
                        reload()
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}
