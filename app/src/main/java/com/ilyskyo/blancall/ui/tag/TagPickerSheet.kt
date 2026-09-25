// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 标签绑定面板（文章列表批量 / 阅读页单篇共用）。
 *
 * 批量语义（防误删）：每个标签三态——全有=勾选、全无=未选、部分有=混选；
 * **点击混选 = 添加到全部；再点 = 从全部移除；未点击过的标签绝不改动**。
 * 「完成」时对每篇执行 新集合 = 旧集合 ∪ added − removed（[TagStore.applyBatchToggle] 单次落盘）。
 *
 * 内联「新建标签」：只填名称（颜色取默认建议；完整配色可到标签管理页调整）——
 * 刻意不叠第二层 ModalBottomSheet（嵌套面板有遮挡/层叠风险）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    targets: List<Article>,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { TagStore.getInstance(context.filesDir) }
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()

    // 增量语义：added = 应用到全部的目标集合；removed = 从全部移除的目标集合（未点击的标签不出现于两者）
    var added by rememberSaveable { mutableStateOf(LongArray(0)) }
    var removed by rememberSaveable { mutableStateOf(LongArray(0)) }

    // 内联新建状态
    var creating by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var createError by rememberSaveable { mutableStateOf(false) }

    // 清除全部二次确认
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val targetIds = remember(targets) { targets.map { it.id } }
    val tags = data.tags
    val states = remember(targetIds, data) {
        TagOps.pickerStates(targetIds, tags.map { it.id }, data)
    }
    val counts = remember(data) {
        tags.associate { t -> t.id to data.links.values.count { t.id in it } }
    }
    val anyBound = remember(targetIds, data) {
        targets.any { data.links[it.id]?.isNotEmpty() == true }
    }
    val addedSet = added.toSet()
    val removedSet = removed.toSet()

    fun commit(add: Set<Long>, remove: Set<Long>) {
        scope.launch {
            withContext(Dispatchers.IO) { store.applyBatchToggle(targetIds, add, remove) }
            onApplied()
        }
    }

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 20.dp),
        ) {
            Text(
                "选择标签",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (targets.size == 1) "文章《${targets.first().title}》"
                else "已选 ${targets.size} 篇文章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (targets.size > 1) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "勾选 = 添加到全部所选文章，取消勾选 = 从全部移除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (tags.isEmpty()) {
                Text(
                    "还没有标签，先新建一个吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                tags.forEach { tag ->
                    val state = states[tag.id] ?: TagOps.PickerState.NONE
                    val toggleState = when {
                        tag.id in addedSet -> ToggleableState.On
                        tag.id in removedSet -> ToggleableState.Off
                        state == TagOps.PickerState.ALL -> ToggleableState.On
                        state == TagOps.PickerState.MIXED -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val a = added.toMutableSet()
                                val r = removed.toMutableSet()
                                TagOps.togglePickerTag(tag.id, state, a, r)
                                added = a.toLongArray()
                                removed = r.toLongArray()
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = toggleState, onClick = null)
                        Spacer(Modifier.width(4.dp))
                        TagDot(tag = TagChipUi(tag.name, tag.color))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${counts[tag.id] ?: 0} 篇",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── 内联新建（只填名称；颜色取默认建议，完整配色去标签管理页）──
            if (!creating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            creating = true
                            createError = false
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        kind = AppIconKind.Add,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建标签",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        createError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新标签名称（最多 12 字）") },
                    singleLine = true,
                    isError = createError,
                    supportingText = if (createError) {
                        { Text("名称为空、超长或已存在同名标签") }
                    } else {
                        null
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = {
                            creating = false
                            newName = ""
                            createError = false
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val trimmed = newName.trim()
                            val invalid = TagOps.validateName(trimmed, tags) != null
                            if (invalid) {
                                createError = true
                            } else {
                                scope.launch {
                                    val newId = withContext(Dispatchers.IO) {
                                        store.createTag(
                                            trimmed,
                                            ColorOps.suggestTagColor(tags.map { it.color }),
                                        )
                                    }
                                    if (newId == null) {
                                        createError = true
                                    } else {
                                        // 创建成功 → 自动勾选（添加到全部目标）
                                        added = (addedSet + newId).toLongArray()
                                        creating = false
                                        newName = ""
                                    }
                                }
                            }
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("创建")
                    }
                }
                Text(
                    "颜色已自动分配，可在「设置 → 文章标签」中调整",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (anyBound) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("清除全部标签", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { commit(addedSet, removedSet) }) {
                    Text("完成")
                }
            }
        }
    }

    // 清除全部：二次确认（只清绑定，不删标签）
    if (confirmClear) {
        BlancallAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部标签") },
            text = {
                Text(
                    if (targets.size == 1) "将移除这篇文章的全部标签（不会删除标签本身）。"
                    else "将移除这 ${targets.size} 篇文章的全部标签（不会删除标签本身）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    commit(emptySet(), tags.map { it.id }.toSet())
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}
