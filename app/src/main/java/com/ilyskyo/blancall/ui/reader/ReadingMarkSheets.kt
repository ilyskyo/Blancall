// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilyskyo.blancall.data.repository.ReadingMark
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import java.text.DateFormat
import java.util.Date

/** 列表行：一条记录 + 摘录（摘录由调用方从正文里切，存储层只存坐标） */
internal data class ReadingMarkRow(
    val mark: ReadingMark,
    val quote: String,
    /** 摘录在段内被裁剪过（前面用省略号），用于显示时补一个前导省略号 */
    val clipped: Boolean = false
)

/**
 * 批注编辑对话框。
 *
 * 引用原文放在输入框上面 —— 用户多半是「读到这里想到点什么」，先看到自己划的那句话
 * 才写得出批注。已有记录时底部多一个删除（删除划线/批注是同一个动作）。
 */
@Composable
internal fun NoteEditorDialog(
    visible: Boolean,
    quote: String,
    initialNote: String,
    /** 传 null = 新建（不显示删除） */
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(visible, quote, initialNote) { mutableStateOf(initialNote) }
    AnimatedOverlayPresence(visible = visible) {
        BlancallAlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.reader_note_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    // 引用：这条批注挂在哪句话上
                    if (quote.isNotBlank()) {
                        Text(
                            text = quote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = {
                            Text(
                                stringResource(com.ilyskyo.blancall.R.string.reader_note_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (canDelete) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Text(
                                stringResource(com.ilyskyo.blancall.R.string.delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(text.trim()) }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.cancel))
                }
            }
        )
    }
}

/**
 * 「划线与批注」列表。
 *
 * ## 为什么不用 ModalBottomSheet
 * 入口在阅读设置面板里，点它要同时做两件事：收起设置面板、打开列表。
 * 两个 M3 ModalBottomSheet 的窗口在同一次组合里一个销毁一个创建时，新窗口不会出现
 * （实测了三种时序：同时、延时 260ms、只在面板关闭后再开 —— 列表都没出来；
 * 只有"面板不动"时列表正常）。改用 Dialog 承载：它是独立 window，与 sheet 的
 * 窗口生命周期完全解耦，且遮罩 + 底部卡片的观感与 sheet 一致。
 *
 * 入口放在设置面板里而不是顶栏：顶栏已挤着返回/标题/练习/设置四个控件，
 * 再加一个必然压缩标题；划线列表是"读完回头整理"的动作，本来就属于设置这一档。
 */
@Composable
internal fun ReadingMarksSheet(
    visible: Boolean,
    rows: List<ReadingMarkRow>,
    onDismiss: () -> Unit,
    onJump: (ReadingMarkRow) -> Unit,
    onDelete: (ReadingMarkRow) -> Unit
) {
    if (!visible) return
    val isDark = isBlancallDark()
    // 入场：从底部滑上来（退场交给 Dialog 关闭，不做假动画）
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        // 只让下面的 slideIn 决定入场；系统窗口动画会叠成第二次位移
        com.ilyskyo.blancall.ui.common.SuppressDialogWindowAnimation()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                // 点遮罩关闭（卡片自己会拦住卡内点击）
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = entered,
                // 走动效词表（裸 tween 会被 MotionVocabularyTest 拦下）：
                // 进头像"一层从底部被推上来"，出街同样走词表，减动效时自动坍缩
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.pageEnter()) +
                    fadeIn(MotionFade.alpha(MotionFade.enter)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.pageExit()) +
                    fadeOut(MotionFade.alpha(MotionFade.exit))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(if (isDark) Color(0xFF1C1C1E) else Color.White)
                        .navigationBarsPadding()
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 26.dp)
                ) {
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.reader_marks_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (rows.isEmpty()) {
                            stringResource(com.ilyskyo.blancall.R.string.reader_marks_empty)
                        } else {
                            stringResource(com.ilyskyo.blancall.R.string.reader_marks_count, rows.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    if (rows.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rows, key = { it.mark.id }) { row ->
                                MarkRow(
                                    row = row,
                                    onClick = { onJump(row) },
                                    onDelete = { onDelete(row) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkRow(
    row: ReadingMarkRow,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val m = row.mark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pressClick { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (row.clipped) "…${row.quote}" else row.quote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (m.hasNote) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = m.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatMarkTime(if (m.updatedAt > 0) m.updatedAt else m.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.width(6.dp))
            // 删除：一个文字按钮（不用图标，避免在这个尺寸下糊）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pressClick { onDelete() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 时间按系统 locale 的短格式显示（不新增文案，也不假设 12/24 小时制） */
private fun formatMarkTime(ts: Long): String {
    if (ts <= 0L) return ""
    return try {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
    } catch (_: Exception) {
        ""
    }
}
