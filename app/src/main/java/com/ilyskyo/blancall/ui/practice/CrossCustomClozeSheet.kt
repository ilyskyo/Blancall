// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 跨文复习「自定义挖空」配置选择浮层：**按文章分别勾选**要应用的挖空配置
 * （如 A 文章选第 1 套、B 文章选第 8 套，各自独立）。
 *
 * - 每篇文章单选一套配置（点已选中的 chip = 取消选择）；
 * - 未选择的文章不参与挖空（合理默认：避免自动错配用户没指定的配置）；
 * - 没有任何可应用标注时由调用方（VM 应用失败）提示，不进入空白练习；
 * - 确认后回调 [onStart]（articleId → 配置），由调用方应用并进入练习。
 *
 * @param articles 参与跨文复习的文章（articleId to 标题，按混合来源顺序）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CrossCustomClozeSheet(
    articles: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onStart: (Map<Long, CustomClozeStore.CustomConfig>) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CustomClozeStore.getInstance(context.filesDir) }
    // 每篇文章的可选配置（IO 读盘；空 = 该文章暂无配置，不参与挖空）
    var configsByArticle by remember { mutableStateOf<Map<Long, List<CustomClozeStore.CustomConfig>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    // 每篇文章选中的配置 id（存在 = 选中）
    val selected = remember { mutableStateMapOf<Long, Long>() }

    LaunchedEffect(articles) {
        val loaded = withContext(Dispatchers.IO) {
            articles.associate { (id, _) -> id to store.getConfigs(id) }
        }
        configsByArticle = loaded
        loading = false
    }

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 20.dp),
        ) {
            Text(
                "自定义挖空",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "按文章分别勾选要应用的挖空配置；未选择的文章不挖空",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (loading) {
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    articles.forEach { (articleId, title) ->
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        val cfgs = configsByArticle[articleId].orEmpty()
                        if (cfgs.isEmpty()) {
                            Text(
                                "该文章暂无挖空配置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                cfgs.forEach { cfg ->
                                    val sel = selected[articleId] == cfg.id
                                    FilterChip(
                                        selected = sel,
                                        // 点已选中的 chip = 取消选择（便于纠正误选）
                                        onClick = {
                                            if (sel) selected.remove(articleId)
                                            else selected[articleId] = cfg.id
                                        },
                                        label = {
                                            Text(
                                                cfg.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        ),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        // 解析为「文章 → 配置对象」；期间被删除/取消失效的条目自然剔除
                        val map = selected.mapNotNull { (articleId, configId) ->
                            configsByArticle[articleId]
                                ?.firstOrNull { it.id == configId }
                                ?.let { articleId to it }
                        }.toMap()
                        if (map.isNotEmpty()) onStart(map)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (selected.isEmpty()) "开始练习"
                        else "开始练习（${selected.size} 篇）",
                    )
                }
            }
        }
    }
}
