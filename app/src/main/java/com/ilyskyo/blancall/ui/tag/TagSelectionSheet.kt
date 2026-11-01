// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagDot
import com.ilyskyo.blancall.ui.common.pressClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 只返回标签 id 的选择面板（导入表单 / 批量导入共用）。
 *
 * 与 [TagPickerSheet] 的区别：那个面板绑定的是「已存在的文章」，点了立即落盘；
 * 导入时文章还不存在，只能先把选择留在表单里，等文章写入后再绑定。
 * 视觉/交互沿用同一套：勾选多选、内联新建、完成才返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSelectionSheet(
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { TagStore.getInstance(context.filesDir) }
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.snapshot() }
    }

    var selected by rememberSaveable(
        stateSaver = listSaver<Set<Long>, Long>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(initialSelected) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var createError by rememberSaveable { mutableStateOf(false) }

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 20.dp),
        ) {
            Text(
                stringResource(R.string.import_tags_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.tags_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (data.tags.isEmpty()) {
                    Text(
                        stringResource(R.string.no_tags_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    data.tags.forEach { tag ->
                        val checked = tag.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressClick {
                                    selected = if (checked) {
                                        selected - tag.id
                                    } else {
                                        selected + tag.id
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(4.dp))
                            TagDot(tag = TagChipUi(tag.name, tag.color, tag.id))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            if (!creating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressClick {
                            creating = true
                            createError = false
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        kind = AppIconKind.Add,
                        modifier = Modifier.width(18.dp).height(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.new_tag),
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
                    label = { Text(stringResource(R.string.new_tag_name_hint)) },
                    singleLine = true,
                    isError = createError,
                    supportingText = if (createError) {
                        { Text(stringResource(R.string.tag_create_error)) }
                    } else null,
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
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val trimmed = newName.trim()
                            if (TagOps.validateName(trimmed, data.tags) != null) {
                                createError = true
                            } else {
                                scope.launch {
                                    val newId = withContext(Dispatchers.IO) {
                                        store.createTag(
                                            trimmed,
                                            ColorOps.suggestTagColor(data.tags.map { it.color }),
                                        )
                                    }
                                    if (newId == null) {
                                        createError = true
                                    } else {
                                        selected = selected + newId
                                        creating = false
                                        newName = ""
                                    }
                                }
                            }
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.create))
                    }
                }
                Text(
                    stringResource(R.string.tag_color_auto),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onConfirm(selected.toSet()) }) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}
