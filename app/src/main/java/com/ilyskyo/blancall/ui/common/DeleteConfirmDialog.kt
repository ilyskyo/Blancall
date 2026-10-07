// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 删除确认对话框（公共组件）。
 *
 * 统一 ListScreen 与 ReaderScreen 的删除确认样式：红色“删除”按钮 + “取消”按钮。
 *
 * @param title     对话框标题
 * @param message   提示正文
 * @param onConfirm 确认删除回调
 * @param onDismiss 取消/关闭回调
 */
@Composable
fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 删除是全 app 唯一的「收不回去」，回执用 Destructive 档（35ms/255 重击）：
    // 确认之后东西就没了，那一下要让手指记住「刚才是我按的」。
    val confirmHaptic = rememberHaptic(HapticTier.Destructive)
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = {
                    confirmHaptic()
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
