// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.ContentFingerprint
import com.ilyskyo.blancall.algorithm.MaskSpanOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.ScrollProgressBadge
import com.ilyskyo.blancall.ui.common.TopBarIconAction
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.theme.ThemeManager
import com.ilyskyo.blancall.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 遮挡自定义编辑页（阅读模式的遮挡配置编辑器）。
 *
 * 版面与阅读模式的阅读界面完全一致（同一套字号/行距/字体/缩进/背景/挡片圆角），
 * 差异只在交互——直接在正文上标注要遮挡的位置：
 * - 点句子 → 遮住该句（再点取消）；长按句子 → 拆成有空隙的词
 * - 点词 → 遮住该词；长按词 → 拆成单字；点字 → 遮住该字
 * - 长按循环还原：字级长按 → 词级 → 句级（与自定义挖空编辑器同款循环）
 * - 顶部挡片颜色与阅读设置同款马卡龙色板，每次遮挡采用当前选中的颜色（每块可不同）
 *
 * 配置按文章保存多套（MaskConfigStore），spans 记录「段落索引 + 段内区间 + 颜色」，
 * 段落口径与 ReaderOcclusion.splitParagraphs 一致，阅读模式 custom 粒度直接回放。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MaskConfigEditScreen(
    article: Article,
    configId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // ── 主题明暗与阅读排版（与 ReadingModeScreen 同一路由，保证"像阅读界面"）──
    val themeMode by ThemeManager.themeMode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val appBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()
    val fontPx by AppPrefs.readingFontFlow.collectAsState()
    val lineHeight by AppPrefs.readingLineHeightFlow.collectAsState()
    val bgMode by AppPrefs.readingBgModeFlow.collectAsState()
    val fontId by AppPrefs.readingFontIdFlow.collectAsState()
    val autoIndent by AppPrefs.autoIndentEnabledFlow.collectAsState()
    val bgColor = when {
        isDark -> Color(0xFF000000)
        bgMode == 1 -> Color(0xFFF6F0E6)
        bgMode == 2 -> Color.White
        else -> if (appBeige) Color(0xFFF6F0E6) else Color.White
    }
    val textColor = when {
        isDark -> Color(0xFFE8E6E1)
        bgMode == 1 || (bgMode == 0 && appBeige) -> Color(0xFF3A342B)
        else -> Color(0xFF212121)
    }
    val subColor = when {
        isDark -> Color(0xFF8F8D88)
        bgMode == 1 || (bgMode == 0 && appBeige) -> Color(0xFF9A9184)
        else -> Color(0xFF9E9E9E)
    }
    val accent = if (isDark) Color(0xFF7C9ED1) else MaterialTheme.colorScheme.primary
    val fontFamily = remember(context, fontId) {
        // 与阅读正文同字重，保证编辑器里的预览和进入阅读后一致
        ReaderFonts.resolveFontFamily(context, fontId, AppPrefs.readingFontWeight)
            ?: FontFamily.Default
    }
    val indent = autoIndent && article.autoIndent

    // 挡片颜色板：与阅读设置「挡片颜色」完全同款（索引 0-5）
    val maskPalette = listOf(
        Macaron.review().fill, Macaron.continueP().fill, Macaron.info().fill,
        Macaron.warn().fill, Macaron.lavender().fill, Macaron.neutral().fill
    )

    // ── 编辑状态 ──
    val store = remember { MaskConfigStore.getInstance(context.filesDir) }
    val scope = rememberCoroutineScope()
    val paragraphs = remember(article.content) { ReaderOcclusion.splitParagraphs(article.content) }
    // 每段拆分级（遮挡编辑：0=句 1=词 2=字），长按循环
    val levels = remember { mutableStateListOf<Int>() }
    // 已遮挡块（段落索引 + 段内局部区间 + 颜色索引）
    val masks = remember { mutableStateListOf<MaskConfigStore.MaskSpan>() }
    var loaded by remember { mutableStateOf(false) }
    // 当前挡片颜色（顶部选色），与阅读设置挡片颜色互通
    var colorIdx by rememberSaveable { mutableStateOf(AppPrefs.readingOcclusionColor) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedId by rememberSaveable { mutableStateOf(if (configId > 0) configId else -1L) }
    // 未保存改动标记：有改动时返回（含系统返回）先弹确认，防止误触丢失
    var dirty by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    // 新建默认名用（"自定义 N"），进页时读一次
    var configCount by rememberSaveable { mutableStateOf(0) }
    // 编辑已有配置时记住原 createdAt（保存时原样传回，防列表排序跳变）
    var savedCreatedAt by rememberSaveable { mutableStateOf(0L) }
    // 文章内容失配检测：配置锚定的内容指纹与当前文章不一致时警示
    var hashMismatch by remember { mutableStateOf(false) }
    // 撤销/重做：操作前完整快照栈（容量 20）
    val undoStack = remember { mutableStateListOf<MaskSnapshot>() }
    val redoStack = remember { mutableStateListOf<MaskSnapshot>() }
    var savedSnapshot by remember { mutableStateOf<MaskSnapshot?>(null) }
    // 编辑计数：任何编辑操作自增，驱动快照序列化（替代每帧 SideEffect）
    var editSeq by remember { mutableIntStateOf(0) }

    // 旋转/进程重建恢复编辑现场：levels+masks 序列化快照（rememberSaveable 自动回放）
    var editorSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshotApplied by remember { mutableStateOf(false) }

    if (levels.size != paragraphs.size) {
        levels.clear()
        repeat(paragraphs.size) { levels.add(0) }
    }

    // 有快照 = 旋转/重建恢复：现场（可能含未保存编辑）比磁盘新，直接回放
    if (!snapshotApplied && editorSnapshot != null) {
        snapshotApplied = true
        runCatching {
            val o = JSONObject(editorSnapshot!!)
            o.optJSONArray("levels")?.let { lv ->
                if (lv.length() == paragraphs.size) {
                    levels.clear()
                    for (i in 0 until lv.length()) levels.add(lv.optInt(i, 0))
                }
            }
            masks.clear()
            o.optJSONArray("masks")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val r = arr.optJSONArray(j) ?: continue
                    if (r.length() >= 4) {
                        masks.add(
                            MaskConfigStore.MaskSpan(r.optInt(0), r.optInt(1), r.optInt(2), r.optInt(3))
                        )
                    }
                }
            }
        }
    }

    // 编辑驱动（而非每帧）持久化编辑现场快照：任何编辑 editSeq++，重组后序列化一次
    LaunchedEffect(editSeq) {
        editorSnapshot = runCatching {
            JSONObject().apply {
                put("levels", JSONArray().apply { levels.forEach { put(it) } })
                put("masks", JSONArray().apply {
                    masks.forEach { m -> put(JSONArray().put(m.p).put(m.a).put(m.e).put(m.c)) }
                })
            }.toString()
        }.getOrNull()
    }

    LaunchedEffect(article.id, configId) {
        val cfgs = withContext(Dispatchers.IO) { store.getConfigs(article.id) }
        configCount = cfgs.size
        if (configId > 0 && !snapshotApplied) {
            cfgs.firstOrNull { it.id == configId }?.let { cfg ->
                editingName = cfg.name
                savedCreatedAt = cfg.createdAt
                masks.clear()
                masks.addAll(cfg.spans)
                hashMismatch = cfg.contentHash != null && cfg.contentHash != ContentFingerprint.md5Hex(article.content)
                editSeq++
            }
        }
        loaded = true
    }

    // ── 撤销/重做：操作前完整快照，容量 20；撤销后与保存点快照比较决定 dirty ──
    fun currentSnapshot() = MaskSnapshot(levels.toList(), masks.toList())

    fun pushUndo() {
        undoStack.add(currentSnapshot())
        if (undoStack.size > 20) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun applySnapshot(s: MaskSnapshot) {
        levels.clear()
        levels.addAll(s.levels)
        masks.clear()
        masks.addAll(s.masks)
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.add(currentSnapshot())
        applySnapshot(snap)
        dirty = if (savedSnapshot != null) currentSnapshot() != savedSnapshot else true
        editSeq++
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.add(currentSnapshot())
        applySnapshot(snap)
        dirty = if (savedSnapshot != null) currentSnapshot() != savedSnapshot else true
        editSeq++
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 点选切换遮挡：命中已遮块 → 取消；否则移除局部重叠后按当前颜色整块遮上（语义在 MaskSpanOps，可单测） */
    fun toggleMask(p: Int, a: Int, e: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val next = MaskSpanOps.toggle(masks.toList(), p, a, e, colorIdx)
        if (next != masks.toList()) {
            pushUndo()
            masks.clear()
            masks.addAll(next)
            dirty = true
            editSeq++
        }
    }

    /** 清空全部遮挡（编辑中的即时操作，保存前可随时重标；可撤销） */
    fun clearAllMasks() {
        if (masks.isNotEmpty()) {
            pushUndo()
            masks.clear()
            dirty = true
            editSeq++
        }
    }

    /** 合并同段内同色相邻/重叠块，构造保存用 spans（合并语义在 MaskSpanOps，可单测） */
    fun buildSpans(): List<MaskConfigStore.MaskSpan> = MaskSpanOps.merge(masks)

    fun doSave(onDone: (Boolean) -> Unit = {}) {
        val spans = buildSpans()
        if (spans.isEmpty()) {
            Toast.makeText(context, "请先点选要遮挡的句子或字词", Toast.LENGTH_SHORT).show()
            onDone(false)
            return
        }
        val cfg = MaskConfigStore.MaskConfig(
            id = savedId,
            name = editingName.ifBlank { "自定义" },
            createdAt = savedCreatedAt,
            spans = spans,
            // 保存 = 重新锚定到当前文章内容
            contentHash = ContentFingerprint.md5Hex(article.content)
        )
        // 以保存前的 savedId 判断「是否新建」：首次保存后 savedId 已 >0，再存不得重复走新建副作用
        val isNew = savedId <= 0L
        val snap = currentSnapshot()
        // 磁盘写放 IO 线程，完成后回调（返回列表等保存成功再走）
        scope.launch {
            val newId = withContext(Dispatchers.IO) { store.saveConfig(article.id, cfg) }
            savedId = newId
            if (isNew) {
                // 仅新建：标记为本文使用中的配置，并把阅读遮挡切到自定义，保存即见效果
                withContext(Dispatchers.IO) { store.setSelected(article.id, newId) }
                AppPrefs.readingOcclusionCustomConfigId = newId
                AppPrefs.readingOcclusionMode = "custom"
                AppPrefs.readingOcclusionEnabled = true
            }
            dirty = false
            savedSnapshot = snap
            Toast.makeText(context, "已保存「${cfg.name}」（${spans.size} 块遮挡）", Toast.LENGTH_SHORT).show()
            onDone(true)
        }
    }

    /** 保存入口：新配置弹命名框；编辑已有配置沿用原名单直接保存（与自定义挖空一致） */
    fun requestSave() {
        if (savedId > 0L && editingName.isNotBlank()) {
            doSave()
        } else {
            if (editingName.isBlank()) {
                editingName = "自定义 " + (configCount + 1)
            }
            showSaveDialog = true
        }
    }

    /** 返回入口：有未保存改动先确认（含「清空后」场景），防误触丢失 */
    fun requestBack() {
        if (dirty) showExitConfirm = true else onBack()
    }

    // 未保存改动时拦截系统返回
    BackHandler(enabled = dirty) {
        requestBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            // 拦截全部点击：空区域不穿透到底下的阅读界面
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp)
        ) {
            // ── 顶栏：返回 + 标题 + N 块遮挡 + 清空 + 保存 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { requestBack() })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "遮挡自定义",
                        style = MaterialTheme.typography.titleMedium,
                        color = textColor
                    )
                    Text(
                        if (masks.isEmpty()) "未添加遮挡" else "已标 ${masks.size} 块遮挡",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (masks.isEmpty()) subColor else accent
                    )
                }
                // 撤销/重做：固定 36dp 槽位常驻（不可用时置灰），点击后位置不跳动
                TopBarIconAction(
                    kind = AppIconKind.Undo,
                    enabled = undoStack.isNotEmpty(),
                    onClick = { undo() },
                    contentDescription = "撤销"
                )
                TopBarIconAction(
                    kind = AppIconKind.Redo,
                    enabled = redoStack.isNotEmpty(),
                    onClick = { redo() },
                    contentDescription = "重做"
                )
                // 清空：同样常驻占位（无遮挡时置灰），否则它一出现/消失也会挤动左侧的撤销/重做
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (masks.isEmpty()) subColor.copy(alpha = 0.35f) else subColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(enabled = masks.isNotEmpty(), onClick = { clearAllMasks() })
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { requestSave() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("保存")
                }
            }
            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (hashMismatch) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "文章内容已修改，这套配置的标注位置可能不准确",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "点句子遮住 · 长按拆成词 · 点词遮词 · 长按拆成字 · 点字遮字（再长按逐级还原）",
                style = MaterialTheme.typography.labelSmall,
                color = subColor
            )
            Spacer(Modifier.height(10.dp))
            // ── 挡片颜色：与阅读设置「挡片颜色」同款马卡龙色板 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("挡片颜色", style = MaterialTheme.typography.labelMedium, color = textColor)
                Spacer(Modifier.width(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    maskPalette.forEachIndexed { idx, c ->
                        val sel = colorIdx == idx
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
                                .combinedClickable(
                                    onClick = { colorIdx = idx; AppPrefs.readingOcclusionColor = idx }
                                ),
                            contentAlignment = Alignment.Center
                        ) {}
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── 正文：与阅读模式同版式的可点编辑正文 ──
        if (!loaded) {
            Spacer(Modifier.height(24.dp))
            Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = subColor,
                modifier = Modifier.padding(horizontal = 26.dp))
        } else if (paragraphs.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("本文没有可编辑的正文段落", style = MaterialTheme.typography.bodyMedium, color = subColor,
                modifier = Modifier.padding(horizontal = 26.dp))
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy((fontPx * 0.9f).dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 26.dp, end = 26.dp, top = 20.dp, bottom = 96.dp
                    )
                ) {
                    items(paragraphs.size, key = { it }) { p ->
                        val paraText = paragraphs[p].text
                        // 直接从 SnapshotStateList 过滤：任何增删/变色都会触发本项重组
                        val paraMasks = masks.filter { it.p == p }
                        val level = levels.getOrElse(p) { 0 }
                        if (level == 0) {
                            EditableOccludedSentenceParagraph(
                                paraText = paraText,
                                masks = paraMasks,
                                fontPx = fontPx,
                                lineHeight = lineHeight,
                                textColor = textColor,
                                fontFamily = fontFamily,
                                indent = indent,
                                palette = maskPalette,
                                onToggle = { a, e -> toggleMask(p, a, e) },
                                onSplit = {
                                    pushUndo()
                                    levels[p] = 1
                                    editSeq++
                                }
                            )
                        } else {
                            EditableUnitParagraph(
                                paraText = paraText,
                                level = level,
                                masks = paraMasks,
                                fontPx = fontPx,
                                lineHeight = lineHeight,
                                textColor = textColor,
                                fontFamily = fontFamily,
                                palette = maskPalette,
                                onToggle = { a, e -> toggleMask(p, a, e) },
                                onSplit = {
                                    pushUndo()
                                    levels[p] = (levels[p] + 1) % 3
                                    editSeq++
                                }
                            )
                        }
                    }
                }
                // 长文滚动进度徽章（>8 段启用）
                ScrollProgressBadge(
                    listState = listState,
                    total = paragraphs.size,
                    unit = "段",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                )
            }
        }
    }

    // ── 保存命名弹窗（与自定义挖空同款交互）──
    if (showSaveDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存遮挡配置", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "给这套遮挡配置起个名字，保存后在阅读模式遮挡粒度选「自定义」使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        singleLine = true,
                        placeholder = { Text("自定义") },
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        )
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showSaveDialog = false
                    doSave { ok -> if (ok) onBack() }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 未保存改动退出确认 ──
    if (showExitConfirm) {
        BlancallAlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("放弃未保存的遮挡？", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (masks.isNotEmpty()) "当前标注的 ${masks.size} 块遮挡还没有保存，离开后将丢失。"
                    else "你的编辑还未保存（如清空操作），离开后将丢失。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitConfirm = false
                    dirty = false
                    onBack()
                }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitConfirm = false }) { Text("继续编辑") }
            }
        )
    }
}

/**
 * 撤销/重做快照：粒度 + 遮挡块完整状态（操作前的现场）。
 */
private data class MaskSnapshot(
    val levels: List<Int>,
    val masks: List<MaskConfigStore.MaskSpan>
)

/**
 * 句级（level 0）可编辑遮挡段落：渲染与阅读模式 OccludedParagraph 完全同款
 * （同字号/行距/缩进/挡片圆角与高度对齐），点遮块取消遮挡，点分句按当前颜色遮住，长按拆词。
 */
@Composable
private fun EditableOccludedSentenceParagraph(
    paraText: String,
    masks: List<MaskConfigStore.MaskSpan>,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    indent: Boolean,
    palette: List<Color>,
    onToggle: (Int, Int) -> Unit,
    onSplit: () -> Unit
) {
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentMasks = rememberUpdatedState(masks)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentSplit by rememberUpdatedState(onSplit)
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { 8.dp.toPx() }

    Text(
        text = paraText,
        fontSize = fontPx.sp,
        lineHeight = (fontPx * lineHeight).sp,
        color = textColor,
        fontFamily = fontFamily,
        style = TextStyle(textIndent = if (indent) TextIndent(firstLine = 2.em) else TextIndent()),
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                // 先画原文再画遮块——与阅读模式遮挡同一绘制方式，块贴合字形、不透明
                drawContent()
                val l = layoutState.value ?: return@drawWithContent
                for (m in currentMasks.value) {
                    val color = palette[m.c.coerceIn(0, palette.lastIndex)]
                    for (r in rangeRects(l, m.a, m.e)) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(r.left, r.top),
                            size = Size(r.width, r.height),
                            cornerRadius = CornerRadius(cornerRadiusPx)
                        )
                    }
                }
            }
            .pointerInput(paraText) {
                detectTapGestures(
                    onTap = { pos ->
                        val l = layoutState.value ?: return@detectTapGestures
                        val hit = currentMasks.value.firstOrNull { m ->
                            rangeRects(l, m.a, m.e).any { it.contains(pos) }
                        }
                        if (hit != null) {
                            currentToggle(hit.a, hit.e)
                        } else {
                            val offset = l.getOffsetForPosition(pos)
                            val clause = ReaderOcclusion.clauseRanges(paraText)
                                .firstOrNull { offset >= it.start && offset < it.end }
                            if (clause != null) currentToggle(clause.start, clause.end)
                        }
                    },
                    onLongPress = { currentSplit() }
                )
            },
        onTextLayout = { l -> layoutState.value = l }
    )
}

/**
 * 词/字级（level 1/2）可编辑遮挡段落：单元以 FlowRow 呈现（词块之间留空隙，
 * 即"拆开"的视觉），点单元遮挡/取消，长按进入下一级（词→字→还原复句）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditableUnitParagraph(
    paraText: String,
    level: Int,
    masks: List<MaskConfigStore.MaskSpan>,
    fontPx: Float,
    lineHeight: Float,
    textColor: Color,
    fontFamily: FontFamily,
    palette: List<Color>,
    onToggle: (Int, Int) -> Unit,
    onSplit: () -> Unit
) {
    val units = remember(paraText, level) { ReaderOcclusion.editUnits(paraText, level) }
    val currentMasks = rememberUpdatedState(masks)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentSplit by rememberUpdatedState(onSplit)
    val textStyle = TextStyle(
        fontSize = fontPx.sp,
        lineHeight = (fontPx * lineHeight).sp,
        fontFamily = fontFamily
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        units.forEach { u ->
            val masked = currentMasks.value.firstOrNull { it.a <= u.start && it.e >= u.end }
            if (masked != null) {
                // 遮挡块：同尺寸占位文本透明 + 色块背景（保持换行流式布局不变）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette[masked.c.coerceIn(0, palette.lastIndex)])
                        .combinedClickable(
                            onClick = { currentToggle(u.start, u.end) },
                            onLongClick = { currentSplit() }
                        )
                ) {
                    Text(
                        paraText.substring(u.start, u.end),
                        style = textStyle,
                        color = Color.Transparent
                    )
                }
            } else {
                Text(
                    paraText.substring(u.start, u.end),
                    style = textStyle,
                    color = textColor,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { currentToggle(u.start, u.end) },
                            onLongClick = { currentSplit() }
                        )
                )
            }
        }
    }
}
