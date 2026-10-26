// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode

/**
 * 一段可用于「遮挡转练」的正文：全文起点 + 段内已遮区间。
 * 从 ReadingModeScreen 提出来（同包），供练习面板与宿主共用。
 */
internal data class PracticeParagraph(val start: Int, val text: String, val spans: List<IntRange>)

/**
 * 阅读模式「练这一段」面板。
 *
 * 旧实现是个只有标题和空态的 AlertDialog：没遮住任何段落时点开只剩一句
 * 「当前没有可转换的遮挡段」和一个「关闭」，等于没有功能（用户反馈原话）。
 * 现在面板恒有两种真实出口：
 * 1. 有遮挡 → 逐段列出，点一段直接把它遮住的位置变成空位开练（原能力保留）；
 * 2. 无论有没有遮挡 → 三种挖空方式（句子 / 字词 / 反向）都可选，点了立刻进整篇练习。
 *
 * 容器用底部面板而不是弹窗：选项本来就该是「选项卡」的形状，也更符合全 App 的手势语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPracticeSheet(
    visible: Boolean,
    choices: List<PracticeParagraph>,
    /** 遮挡段是否已统计完（false 时显示占位，避免闪一下「还没遮住」的错态） */
    choicesReady: Boolean,
    onDismiss: () -> Unit,
    onPracticeParagraph: (PracticeParagraph) -> Unit,
    onPracticeMode: (BlancallMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AnimatedOverlayPresence(visible = visible) {
        GlassModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, bottom = 28.dp)
            ) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_practice_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_practice_sheet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))

                // ── 出口一：把刚才遮住的段落变成挖空 ──
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_practice_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                when {
                    !choicesReady -> Text(
                        stringResource(com.ilyskyo.blancall.R.string.loading_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    choices.isEmpty() -> Text(
                        stringResource(com.ilyskyo.blancall.R.string.reader_practice_section_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.reader_practice_section_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        choices.forEach { choice ->
                            PracticeChoiceRow(
                                title = choice.text.take(46) + if (choice.text.length > 46) "…" else "",
                                subtitle = stringResource(
                                    com.ilyskyo.blancall.R.string.reader_practice_section_spans,
                                    choice.spans.size
                                ),
                                onClick = { onPracticeParagraph(choice) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── 出口二：整篇按挖空方式练（恒可用，点了直接开练）──
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_practice_mode_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                PracticeChoiceRow(
                    title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_sentence),
                    subtitle = stringResource(com.ilyskyo.blancall.R.string.mode_sentence_line),
                    onClick = { onPracticeMode(BlancallMode.SENTENCE) }
                )
                Spacer(Modifier.height(8.dp))
                PracticeChoiceRow(
                    title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_word),
                    subtitle = stringResource(com.ilyskyo.blancall.R.string.mode_word_line),
                    onClick = { onPracticeMode(BlancallMode.WORD) }
                )
                Spacer(Modifier.height(8.dp))
                PracticeChoiceRow(
                    title = stringResource(com.ilyskyo.blancall.R.string.cloze_mode_reverse),
                    subtitle = stringResource(com.ilyskyo.blancall.R.string.mode_reverse_line),
                    onClick = { onPracticeMode(BlancallMode.REVERSE) }
                )
            }
        }
    }
}

/** 面板里的一行可点选项：标题 + 一行说明，实色卡片与玻璃面板拉出层次 */
@Composable
private fun PracticeChoiceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        // 用本项目统一的按压反馈（缩放 + 亮度，无涟漪）：M3 的 Surface(onClick) 自带
        // indication=ripple，会跟全局「退场涟漪」的取舍打架
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressClick { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}
