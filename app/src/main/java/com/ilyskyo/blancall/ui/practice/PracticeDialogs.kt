// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.BlancallGenerator


// ========== 未完成提交确认弹窗 ==========

@Composable
internal fun IncompleteSubmitDialog(
    unfilledCount: Int,
    onContinue: () -> Unit,
    onSubmitPartial: () -> Unit
) {
    BlancallAlertDialog(
        onDismissRequest = onContinue,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        title = {
            Text(
                "还有 ${unfilledCount} 个空未完成",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    "你还有部分内容没有完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "是否保存当前进度并提交？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        HintRow("只批改已完成的部分")
                        Spacer(Modifier.height(4.dp))
                        HintRow("未填写内容不会计入错误统计")
                        Spacer(Modifier.height(4.dp))
                        HintRow("当前练习进度会被保存，下次可以继续")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmitPartial,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存并提交")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinue,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("继续填写")
            }
        }
    )
}


@Composable
internal fun HintRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            kind = AppIconKind.Check,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


// ========== PDF 导出（F8）==========

@Composable
internal fun ExportPdfDialog(
    onDismiss: () -> Unit,
    onExport: (includeAnswer: Boolean) -> Unit
) {
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出PDF试卷") },
        text = {
            Column {
                Text("选择导出格式：", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onExport(false) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📝 无答案版（纯试卷）")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onExport(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📋 带答案版（末尾附答案）")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


// ═══════════════════════════════════════════
//  反向默写（段落打散默写）
// ═══════════════════════════════════════════

/** 构建反向默写的展示文本：把打乱顺序的挖空分句编号列出，作为默写线索 */
internal fun buildDictationDisplayText(dictation: BlancallGenerator.DictationResult): String {
    return buildString {
        appendLine("【默写线索 · 分句已打乱顺序并挖空】")
        dictation.shuffledClauses.forEach { sh ->
            appendLine("${sh.displayOrder + 1}. ${sh.displayText}")
        }
    }.trimEnd()
}
