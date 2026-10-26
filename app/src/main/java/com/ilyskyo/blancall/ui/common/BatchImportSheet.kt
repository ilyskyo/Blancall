// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R

/**
 * 批量导入面板的一条候选项。
 *
 * [imported] 为 true 时表示该篇已存在于背诵列表：面板里显示为「已导入」的灰色锁定勾选，
 * 不可再点选，也不会计入本次导入。
 */
data class BatchImportItem(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val imported: Boolean = false,
)

/**
 * 通用批量导入多选面板（高考素材库首页 / 用户内容包共用）。
 *
 * 支持全选、单独选、多选；已导入条目以灰色勾选锁定；确认后由调用方执行真正的写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportSheet(
    title: String,
    subtitle: String,
    items: List<BatchImportItem>,
    importing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    // 选择状态跟着候选项重建（切换目标包时不能沿用上一包的选择）
    var selected by remember(items) { mutableStateOf(emptySet<String>()) }
    val selectableKeys = remember(items) {
        items.filterNot { it.imported }.mapTo(HashSet()) { it.key }
    }
    val allSelected = selectableKeys.isNotEmpty() && selected.containsAll(selectableKeys)

    GlassModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 20.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        selected = if (allSelected) emptySet() else selectableKeys.toSet()
                    },
                    enabled = selectableKeys.isNotEmpty() && !importing,
                ) {
                    Text(
                        stringResource(
                            if (allSelected) R.string.deselect_all else R.string.select_all
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    pluralStringResource(
                        R.plurals.selected_articles,
                        selected.size,
                        selected.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // 固定高度 + 内部滚动：候选可能有 60 篇，外层面板仍保持可收起的整体高度
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(items, key = { it.key }) { item ->
                    val checked = item.imported || item.key in selected
                    val rowEnabled = !item.imported && !importing
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressClick(enabled = rowEnabled) {
                                selected = if (item.key in selected) {
                                    selected - item.key
                                } else {
                                    selected + item.key
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = rowEnabled,
                        )
                        Spacer(Modifier.width(2.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (item.imported) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.subtitle.isNotBlank()) {
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (item.imported) {
                            Text(
                                stringResource(R.string.western_imported),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest, enabled = !importing) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = selected.isNotEmpty() && !importing,
                ) {
                    Text(
                        if (importing) {
                            stringResource(R.string.content_pack_importing)
                        } else {
                            stringResource(R.string.import_action) + " (${selected.size})"
                        }
                    )
                }
            }
        }
    }
}
