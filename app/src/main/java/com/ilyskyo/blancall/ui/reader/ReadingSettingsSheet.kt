// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.theme.Macaron
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 阅读设置面板与字体选择行（从 ReadingModeScreen.kt 拆出；纯搬移，行为不变）。
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingSettingsSheet(
    visible: Boolean,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    fontPx: Float,
    onFontChange: (Float) -> Unit,
    lineHeight: Float,
    onLineHeightChange: (Float) -> Unit,
    bgMode: Int,
    onBgModeChange: (Int) -> Unit,
    layoutMode: Int,
    onLayoutModeChange: (Int) -> Unit,
    fontId: String,
    onFontIdChange: (String) -> Unit,
    /** 当前字重（300/400/500/700） */
    fontWeight: Int,
    onFontWeightChange: (Int) -> Unit,
    occlusionEnabled: Boolean,
    onOcclusionEnabledChange: (Boolean) -> Unit,
    occlusionMode: String,
    onOcclusionModeChange: (String) -> Unit,
    occlusionColorIndex: Int,
    onOcclusionColorChange: (Int) -> Unit,
    onOpenMaskConfig: () -> Unit,
    /** 当前「自定义」遮挡配置名（空 = 未选/已删除），选中自定义粒度时显示在 chip 上 */
    maskConfigName: String,
    isDark: Boolean,
    accent: Color
) {
    if (!visible) return
    val context = LocalContext.current

    // ── 字体候选：预置 + 系统字体（IO 线程扫描，避免卡顿）+ 导入字体 ──
    val sysFonts by produceState<List<ReaderFont>>(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) { ReaderFonts.scanSystemFonts() }
    }
    var imported by remember { mutableStateOf(ReaderFonts.listImportedFonts(context)) }
    val allFonts = remember(sysFonts, imported) { ReaderFonts.presets + sysFonts + imported }
    val currentFontName = remember(allFonts, fontId) {
        allFonts.firstOrNull { it.id == fontId }?.name ?: "默认"
    }
    var fontsExpanded by remember { mutableStateOf(false) }

    // 导入字体：系统文件选择器，导入成功后自动选中并刷新列表
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "字体"
        val importedFont = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { ReaderFonts.importFont(context, displayName, it) }
        }.getOrNull() ?: null
        if (importedFont != null) {
            imported = ReaderFonts.listImportedFonts(context)
            onFontIdChange(importedFont.id)
        }
    }

    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (isDark) Color(0x66FFFFFF) else Color(0x33000000)))
        } }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleMedium,
                color = if (isDark) DarkText else PaperWhiteText,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(20.dp))

            // 字号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FormatSize,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "字号 ${fontPx.roundToInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
            }
            Slider(
                value = fontPx,
                onValueChange = onFontChange,
                valueRange = 14f..36f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                )
            )

            Spacer(Modifier.height(8.dp))

            // 行距
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FormatLineSpacing,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "行距 ${(lineHeight * 10).roundToInt() / 10f}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
            }
            Slider(
                value = lineHeight,
                onValueChange = onLineHeightChange,
                valueRange = 1.4f..2.4f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                )
            )

            Spacer(Modifier.height(16.dp))

            // ── 字体：点击展开候选列表（预置 / 系统 / 导入），可从系统选择器导入 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { fontsExpanded = !fontsExpanded }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "字体",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkText else PaperWhiteText
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = currentFontName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDark) DarkSub else PaperWhiteSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = if (isDark) DarkSub else PaperWhiteSub,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = fontsExpanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 4.dp)
                ) {
                    // 预置（FlowRow：加入霞鹜文楷后共 5 个，窄屏自动换行而不是被挤出屏幕）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ReaderFonts.presets.forEach { f ->
                            val selected = fontId == f.id
                            FilterChip(
                                selected = selected,
                                onClick = { onFontIdChange(f.id) },
                                label = { Text(f.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = when {
                                        selected && isDark -> Color(0x334B5563)
                                        selected -> accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                    labelColor = if (isDark) DarkText else PaperWhiteText,
                                    selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected,
                                    borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                    selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    // ── 字重 ──
                    // 霞鹜文楷有三个真实字重（Light/Regular/Medium）；其余字体为常规/加粗两档。
                    // 档位由字体能力决定，因此切换字体时上方会自动收敛到可用档位。
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "字重",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) DarkSub else PaperWhiteSub
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderFonts.weightOptions(ReaderFonts.weightMode(fontId)).forEach { w ->
                            val selected = fontWeight == w
                            FilterChip(
                                selected = selected,
                                onClick = { onFontWeightChange(w) },
                                label = { Text(weightLabel(w)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = when {
                                        selected && isDark -> Color(0x334B5563)
                                        selected -> accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                    labelColor = if (isDark) DarkText else PaperWhiteText,
                                    selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected,
                                    borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                    selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                    // 导入字体（可删除）——置于系统字体上方（用户自定义字体优先展示）
                    if (imported.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "导入字体（${imported.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) DarkSub else PaperWhiteSub
                        )
                        Spacer(Modifier.height(4.dp))
                        imported.forEach { f ->
                            FontPickerRow(
                                name = f.name,
                                selected = fontId == f.id,
                                isDark = isDark,
                                accent = accent,
                                onClick = { onFontIdChange(f.id) },
                                trailing = {
                                    if (fontId != f.id) {
                                        IconButton(
                                            onClick = {
                                                if (ReaderFonts.deleteImportedFont(context, f.id)) {
                                                    imported = ReaderFonts.listImportedFonts(context)
                                                    if (fontId == f.id) onFontIdChange("0")
                                                }
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "删除字体",
                                                tint = if (isDark) Color(0x99FF7A7A) else Color(0xFFD9534F),
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    // 系统字体
                    if (sysFonts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "系统字体（${sysFonts.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) DarkSub else PaperWhiteSub
                        )
                        Spacer(Modifier.height(4.dp))
                        sysFonts.forEach { f ->
                            FontPickerRow(
                                name = f.name,
                                selected = fontId == f.id,
                                isDark = isDark,
                                accent = accent,
                                onClick = { onFontIdChange(f.id) }
                            )
                        }
                    }
                    // 导入入口
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                importLauncher.launch(
                                    arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")
                                )
                            }
                            .padding(vertical = 11.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "导入字体",
                            style = MaterialTheme.typography.bodyMedium,
                            color = accent
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 布局：整篇滚动 / 章节翻页
            Text(
                "布局",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) DarkText else PaperWhiteText
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "整篇滚动", 1 to "章节翻页").forEach { (mode, label) ->
                    val selected = layoutMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onLayoutModeChange(mode) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = when {
                                selected && isDark -> Color(0x334B5563)
                                selected -> accent.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            },
                            labelColor = if (isDark) DarkText else PaperWhiteText,
                            selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                            selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 背景选择（深色模式永远纯黑，浅色项禁用）
            Text(
                "背景",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) DarkText else PaperWhiteText
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0 to "跟随主题", 1 to "米白", 2 to "纯白").forEach { (mode, label) ->
                    val selected = bgMode == mode
                    val enabled = !isDark || mode == 0
                    FilterChip(
                        selected = selected,
                        onClick = { if (enabled) onBgModeChange(mode) },
                        enabled = enabled,
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = when {
                                selected && isDark -> Color(0x334B5563)
                                selected -> accent.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            },
                            labelColor = if (isDark) DarkText else PaperWhiteText,
                            selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = enabled,
                            selected = selected,
                            borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                            selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                        )
                    )
                }
            }
            if (isDark) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "深色模式阅读背景为纯黑，仅支持跟随主题",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkSub
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 背诵遮挡：开关 + 算法子项（AI / 本地） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "背诵遮挡",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDark) DarkText else PaperWhiteText
                    )
                    Text(
                        "点按遮块揭开原文，辅助背诵",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) DarkSub else PaperWhiteSub
                    )
                }
                Spacer(Modifier.weight(1f))
                GlassSwitch(
                    checked = occlusionEnabled,
                    onCheckedChange = { v -> onOcclusionEnabledChange(v) },
                    accent = accent
                )
            }
            // 开启后浮现遮挡粒度子项（短=字词 / 长=复句 / 混合=逐句随机长或短，均为本地算法）
            AnimatedVisibility(visible = occlusionEnabled) {
                Column(Modifier.padding(top = 10.dp)) {
                    // FlowRow：窄屏单行放不下（三 chip + 自定义）时自动换行，避免右侧被裁切
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(
                            "short" to "短遮挡",
                            "long" to "长遮挡",
                            "mixed" to "混合长短遮挡"
                        )
                        options.forEach { (m, label) ->
                            val selected = occlusionMode == m
                            FilterChip(
                                selected = selected,
                                onClick = { onOcclusionModeChange(m) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = when {
                                        selected && isDark -> Color(0x334B5563)
                                        selected -> accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                    labelColor = if (isDark) DarkText else PaperWhiteText,
                                    selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected,
                                    borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                    selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                                )
                            )
                        }
                        // 遮挡自定义入口：与上方三个粒度 chip 完全同款（统一高度/容器色，选中同蓝底），
                        // 切到自定义遮挡粒度并进入配置列表（生效中高亮）
                        val customActive = occlusionMode == "custom"
                        FilterChip(
                            selected = customActive,
                            onClick = {
                                // 选中自定义模式并挂上全屏浮层，随后**立即**移除面板组合。
                                //
                                // 不再等 sheetState.hide() 的动画回调：隐藏动画进行期间，模态窗口
                                // 仍浮在主窗口之上 —— 既会挡住刚挂上的浮层，又可能残留一帧吞掉
                                // 下一次点击，表现为「点自定义后停在阅读界面，要再点一下才进去」。
                                // 浮层是覆盖全屏的不透明页面，直接切换没有观感损失。
                                onOcclusionModeChange("custom")
                                onOpenMaskConfig()
                                onDismiss()
                            },
                            label = {
                                // 配置名回显（单一来源）：有使用中的配置就显示其名称，
                                // 没有则回退默认文案「自定义」——不再分「选中后显示固定文案」
                                // 与「配置名回显」两套逻辑
                                Text(maskConfigName.ifBlank { "自定义" })
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = when {
                                    customActive && isDark -> Color(0x334B5563)
                                    customActive -> accent.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                },
                                labelColor = if (isDark) DarkText else PaperWhiteText,
                                selectedLabelColor = if (isDark) Color(0xFFB9CFF2) else accent
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = customActive,
                                borderColor = if (isDark) Color(0x3DFFFFFF) else Color(0x1F000000),
                                selectedBorderColor = if (isDark) Color(0x66B9CFF2) else accent.copy(alpha = 0.6f)
                            )
                        )
                    }
                    // ── 挡片颜色：马卡龙淡色可选（不透明真正遮住，高度与字形一致）──
                    Spacer(Modifier.height(12.dp))
                    Text("挡片颜色", style = MaterialTheme.typography.labelMedium,
                        color = if (isDark) DarkText else PaperWhiteText)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val maskColors = listOf(
                            Macaron.review().fill, Macaron.continueP().fill, Macaron.info().fill,
                            Macaron.warn().fill, Macaron.lavender().fill, Macaron.neutral().fill
                        )
                        maskColors.forEachIndexed { idx, c ->
                            val sel = occlusionColorIndex == idx
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(c)
                                    .border(
                                        width = if (sel) 2.dp else 1.dp,
                                        color = if (sel) accent else (if (isDark) Color(0x59FFFFFF) else Color(0x33000000)),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable { onOcclusionColorChange(idx) },
                                contentAlignment = Alignment.Center
                            ) {}
                        }
                    }
                }
            }
        }
    }
}


/**
 * 字体列表的单行选择项：选中态用强调色高亮。可选的尾部内容（如删除）。
 */
@Composable
internal fun FontPickerRow(
    name: String,
    selected: Boolean,
    isDark: Boolean,
    accent: Color,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) accent else (if (isDark) DarkText else PaperWhiteText),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}
