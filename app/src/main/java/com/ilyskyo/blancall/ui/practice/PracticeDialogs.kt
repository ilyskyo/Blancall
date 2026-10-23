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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
                pluralStringResource(com.ilyskyo.blancall.R.plurals.unfilled_title, unfilledCount, unfilledCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.unfilled_line1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.unfilled_line2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        HintRow(stringResource(com.ilyskyo.blancall.R.string.unfilled_hint1))
                        Spacer(Modifier.height(4.dp))
                        HintRow(stringResource(com.ilyskyo.blancall.R.string.unfilled_hint2))
                        Spacer(Modifier.height(4.dp))
                        HintRow(stringResource(com.ilyskyo.blancall.R.string.unfilled_hint3))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmitPartial,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(com.ilyskyo.blancall.R.string.save_and_submit))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onContinue,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(com.ilyskyo.blancall.R.string.keep_filling))
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
        title = { Text(stringResource(com.ilyskyo.blancall.R.string.export_pdf_paper)) },
        text = {
            Column {
                Text(stringResource(com.ilyskyo.blancall.R.string.export_pick_format), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onExport(false) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.export_no_answer))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onExport(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.export_with_answer))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
        }
    )
}


// ═══════════════════════════════════════════
//  反向默写（段落打散默写）
// ═══════════════════════════════════════════

/** 构建反向默写的展示文本：把打乱顺序的挖空分句编号列出，作为默写线索 */
internal fun buildDictationDisplayText(
    dictation: BlancallGenerator.DictationResult,
    header: String,
): String {
    return buildString {
        appendLine(header)
        dictation.shuffledClauses.forEach { sh ->
            appendLine("${sh.displayOrder + 1}. ${sh.displayText}")
        }
    }.trimEnd()
}
