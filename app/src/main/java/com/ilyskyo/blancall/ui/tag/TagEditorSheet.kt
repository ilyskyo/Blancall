// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet

/**
 * 标签编辑面板（新建 / 编辑共用）：名称输入 + [TagColorPicker]（色环 + 套装）+ 保存。
 *
 * - 名称校验走 [TagOps.validateName]（空 / 超 12 字 / 忽略大小写重名），
 *   首次输入或点保存后显示错误；
 * - 新建时颜色默认 [ColorOps.suggestTagColor]（避开既有标签颜色、不含主题主色）；
 * - 删除不在本面板（管理页长按菜单删除），避免误触。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorSheet(
    initialTag: Tag?,
    existingTags: List<Tag>,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialTag?.name ?: "") }
    var color by rememberSaveable {
        mutableIntStateOf(
            initialTag?.color ?: ColorOps.suggestTagColor(existingTags.map { it.color })
        )
    }
    var touched by rememberSaveable { mutableStateOf(false) }

    val error = TagOps.validateName(name, existingTags, excludeId = initialTag?.id ?: -1L)
    val showError = touched && error != null

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 20.dp),
        ) {
            Text(
                if (initialTag == null) "新建标签" else "编辑标签",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    touched = true
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称（最多 12 字）") },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text(errorText(error!!)) }
                } else {
                    null
                },
                shape = RoundedCornerShape(10.dp),
            )

            Spacer(Modifier.height(14.dp))

            TagColorPicker(
                color = color,
                previewName = name.trim().ifBlank { "标签" },
                onColorChange = { color = it },
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        if (error == null) {
                            onSave(name.trim(), color)
                        } else {
                            touched = true
                        }
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (initialTag == null) "创建" else "保存")
                }
            }
        }
    }
}

/** 名称错误文案 */
internal fun errorText(error: TagOps.NameError): String = when (error) {
    TagOps.NameError.BLANK -> "名称不能为空"
    TagOps.NameError.TOO_LONG -> "名称最多 12 个字"
    TagOps.NameError.DUPLICATE -> "已存在同名标签"
}
