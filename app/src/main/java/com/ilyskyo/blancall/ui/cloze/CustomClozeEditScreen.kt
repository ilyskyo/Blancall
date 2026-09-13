// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.cloze

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.BlancallGenerator
import com.ilyskyo.blancall.algorithm.ClozeTokens
import com.ilyskyo.blancall.algorithm.ContentFingerprint
import com.ilyskyo.blancall.algorithm.RangeOps
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.ScrollProgressBadge
import com.ilyskyo.blancall.ui.common.TopBarIconAction
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义挖空模板编辑页（v2：自定义即预览）。
 *
 * 顶部三枚模式 chips 选择这套配置的目标练习模式，编辑与预览实时切换为该模式的空样式：
 * - 📝 句子挖空：点选复句挖复句，选中处渲染为练习同款「[N] ＿＿＿＿」序号空
 * - 🔤 字词挖空：长按循环切换粒度（复句→分句→字词→单字→回到复句），点选区间，渲染高亮空框
 * - ✍️ 反向默写：点选句子加入默写（无炸），选中处渲染为分句卡片「N. 挖一词」
 *
 * 配置按文章保存多套（CustomClozeStore），blanks 记录「句索引 + 句内区间」，
 * 练习时按配置模式确定性构造对应挖空结果，复用既有判分链路。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CustomClozeEditScreen(
    navController: NavController,
    articleId: Long,
    configId: Long = -1L,
    pick: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<Article?>(null) }
    var notFound by remember { mutableStateOf(false) }

    // 目标练习模式（配置与模式绑定）
    var clozeMode by rememberSaveable { mutableStateOf("WORD") }
    var pendingMode by remember { mutableStateOf("WORD") }
    var showModeSwitchConfirm by remember { mutableStateOf(false) }

    // 每句拆分级Level（仅字词模式使用，0..3）
    val levels = remember { mutableStateListOf<Int>() }
    // 每句的选中区间（句内字符 [start, end)）
    val selected = remember { mutableStateMapOf<Int, MutableList<IntRange>>() }
    // 已加载/已保存配置的三要素（拆成基础类型才能随旋转保存恢复）
    var savedId by rememberSaveable { mutableStateOf(if (configId > 0) configId else 0L) }
    var savedName by rememberSaveable { mutableStateOf("") }
    var savedCreatedAt by rememberSaveable { mutableStateOf(0L) }
    // 当前文章已有配置（用于默认命名去重与保存重名校验）
    var existingConfigs by remember { mutableStateOf<List<CustomClozeStore.CustomConfig>>(emptyList()) }
    // 未保存改动：返回（含系统返回）先弹确认，防误触丢失
    var dirty by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // 旋转/进程重建恢复编辑现场：levels+selected 序列化快照（rememberSaveable 自动回放）
    var editorSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshotApplied by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    // 文章内容失配检测：配置锚定的内容指纹与当前文章不一致时警示
    var hashMismatch by remember { mutableStateOf(false) }
    // 撤销/重做：操作前完整快照栈（容量 20）
    val undoStack = remember { mutableStateListOf<ClozeEditSnapshot>() }
    val redoStack = remember { mutableStateListOf<ClozeEditSnapshot>() }
    var savedSnapshot by remember { mutableStateOf<ClozeEditSnapshot?>(null) }
    // 编辑计数：任何编辑操作自增，驱动快照序列化（替代每帧 SideEffect）
    var editSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(articleId, configId) {
        val store = CustomClozeStore.getInstance(navController.context.filesDir)
        val art = withContext(Dispatchers.IO) {
            ArticleRepository(
                navController.context.filesDir.resolve("articles.json").absolutePath
            ).getArticleById(articleId)
        }
        article = art
        if (art == null) {
            notFound = true
            return@LaunchedEffect
        }
        val sentences = SentenceSplitter.split(art.content)
        repeat(sentences.size) { levels.add(0) }
        val cfgs = withContext(Dispatchers.IO) { store.getConfigs(articleId) }
        existingConfigs = cfgs
        // 有快照 = 旋转/重建恢复：现场（可能含未保存编辑）比磁盘新，直接回放
        if (!snapshotApplied && editorSnapshot != null) {
            snapshotApplied = true
            runCatching {
                val o = JSONObject(editorSnapshot!!)
                o.optJSONArray("levels")?.let { lv ->
                    if (lv.length() == levels.size) {
                        levels.clear()
                        for (i in 0 until lv.length()) levels.add(lv.optInt(i, 0))
                    }
                }
                selected.clear()
                o.optJSONObject("sel")?.let { sel ->
                    sel.keys().forEach { key ->
                        val s = key.toIntOrNull() ?: return@forEach
                        val arr = sel.optJSONArray(key) ?: return@forEach
                        val list = selected.getOrPut(s) { mutableStateListOf() }
                        for (j in 0 until arr.length()) {
                            val r = arr.optJSONArray(j) ?: continue
                            if (r.length() >= 2) list.add(r.optInt(0) until r.optInt(1))
                        }
                    }
                }
            }
        } else if (configId > 0) {
            val cfg = cfgs.firstOrNull { it.id == configId }
            if (cfg != null) {
                savedId = cfg.id
                savedName = cfg.name
                savedCreatedAt = cfg.createdAt
                clozeMode = cfg.mode
                cfg.blanks.forEach { b ->
                    if (b.s !in sentences.indices) return@forEach
                    when (cfg.mode) {
                        // 字词模式：区间按保存原样回放（粒度恢复在下方统一处理）
                        "WORD" -> selected.getOrPut(b.s) { mutableStateListOf() }.add(b.a until b.b)
                        // 句子/反向：复句选择，区间即全句
                        else -> selected.getOrPut(b.s) { mutableStateListOf() }.add(0 until sentences[b.s].length)
                    }
                }
                if (cfg.mode == "WORD") {
                    if (cfg.levels.size == sentences.size) {
                        // 保存过粒度：逐句恢复；粒度较粗导致区间切在 token 中间时自动细化对齐
                        for (s in sentences.indices) {
                            val rs = selected[s]?.toList() ?: emptyList()
                            levels[s] = if (rs.isEmpty()) cfg.levels[s]
                            else ClozeTokens.restoreLevel(sentences[s], cfg.levels[s], rs)
                        }
                    } else {
                        // 旧配置无粒度记录：回退原行为（有选中的句子炸到单字级对齐）
                        cfg.blanks.forEach { b ->
                            if (b.s in sentences.indices) {
                                while (levels[b.s] < 3) levels[b.s] = levels[b.s] + 1
                            }
                        }
                    }
                }
                hashMismatch = cfg.contentHash != null && cfg.contentHash != ContentFingerprint.md5Hex(art.content)
                editSeq++
            }
        }
    }

    // 编辑驱动（而非每帧）持久化编辑现场快照：任何编辑 editSeq++，重组后序列化一次
    LaunchedEffect(editSeq) {
        editorSnapshot = runCatching {
            JSONObject().apply {
                put("levels", JSONArray().apply { levels.forEach { put(it) } })
                put("sel", JSONObject().apply {
                    selected.forEach { (s, list) ->
                        if (list.isNotEmpty()) put(s.toString(), JSONArray().apply {
                            list.forEach { r -> put(JSONArray().put(r.first).put(r.last)) }
                        })
                    }
                })
            }.toString()
        }.getOrNull()
    }

    fun selectedSentenceCount(): Int = selected.values.count { it.isNotEmpty() }

    // ── 撤销/重做：操作前完整快照，容量 20；撤销后与保存点快照比较决定 dirty ──
    fun currentSnapshot() = ClozeEditSnapshot(
        clozeMode,
        levels.toList(),
        selected.mapValues { it.value.toList() }
    )

    fun pushUndo() {
        undoStack.add(currentSnapshot())
        if (undoStack.size > 20) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun applySnapshot(s: ClozeEditSnapshot) {
        clozeMode = s.mode
        levels.clear()
        levels.addAll(s.levels)
        selected.clear()
        s.sel.forEach { (k, list) ->
            selected.getOrPut(k) { mutableStateListOf() }.addAll(list)
        }
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

    fun buildConfig(): CustomClozeStore.CustomConfig? {
        val art = article ?: return null
        val sentences = SentenceSplitter.split(art.content)
        val blanks = mutableListOf<CustomClozeStore.BlankSpec>()
        when (clozeMode) {
            // 句子/反向：复句选择
            "SENTENCE", "REVERSE" -> {
                selected.forEach { (s, ranges) ->
                    if (s in sentences.indices && ranges.isNotEmpty()) {
                        blanks.add(CustomClozeStore.BlankSpec(s, 0, sentences[s].length))
                    }
                }
                blanks.sortBy { it.s }
            }
            // 字词：区间合并
            else -> {
                selected.forEach { (s, ranges) ->
                    if (s !in sentences.indices) return@forEach
                    val merged = ranges.sortedBy { it.first }
                        .fold(mutableListOf<IntRange>()) { acc, r ->
                            val last = acc.lastOrNull()
                            if (last != null && r.first <= last.last + 1) {
                                acc[acc.size - 1] = last.first..maxOf(last.last, r.last)
                            } else acc.add(r)
                            acc
                        }
                    merged.forEach { r ->
                        val a = r.first.coerceIn(0, sentences[s].length)
                        val b = (r.last + 1).coerceIn(a + 1, sentences[s].length)
                        blanks.add(CustomClozeStore.BlankSpec(s, a, b))
                    }
                }
                blanks.sortWith(compareBy({ it.s }, { it.a }))
            }
        }
        if (blanks.isEmpty()) return null
        return CustomClozeStore.CustomConfig(
            id = savedId,
            name = savedName.ifBlank { "自定义" },
            createdAt = savedCreatedAt,
            blanks = blanks,
            mode = clozeMode,
            // 记住每句粒度，重进编辑时忠实恢复（B6）；SENTENCE/REVERSE 下全 0 无影响
            levels = levels.toList(),
            // 保存 = 重新锚定到当前文章内容
            contentHash = ContentFingerprint.md5Hex(art.content)
        )
    }

    fun doSave(onDone: (Boolean) -> Unit = {}) {
        val art = article
        val cfg = buildConfig()
        if (art == null || cfg == null) {
            Toast.makeText(context, "请先点选要挖空的句子或字词", Toast.LENGTH_SHORT).show()
            onDone(false)
            return
        }
        // 磁盘写放 IO 线程，完成后回调（pick 流程等保存成功再跳转）
        val snap = currentSnapshot()
        scope.launch {
            val total = cfg.blanks.size
            val newId = withContext(Dispatchers.IO) {
                CustomClozeStore.getInstance(context.filesDir).saveConfig(art.id, cfg)
            }
            savedId = newId
            savedCreatedAt = if (cfg.createdAt > 0) cfg.createdAt else System.currentTimeMillis()
            dirty = false
            savedSnapshot = snap
            Toast.makeText(context, "已保存「${cfg.name}」（$total 个空）", Toast.LENGTH_SHORT).show()
            onDone(true)
        }
    }

    /**
     * 生成当前文章内不重复的默认配置名：解析已有「自定义 N」的最大编号取 max+1；
     * 若该编号仍被占用（历史畸形数据）则继续递增跳过。
     */
    fun nextDefaultName(): String {
        val used = existingConfigs.map { it.name.trim() }.toMutableSet()
        val maxN = existingConfigs
            .mapNotNull { Regex("^自定义\\s*(\\d+)$").find(it.name.trim())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        var n = maxN + 1
        while (used.contains("自定义 $n")) n++
        return "自定义 $n"
    }

    /** 名称是否与当前文章内其他配置重名（排除自身，trim 后全等比较） */
    fun isNameTaken(name: String, excludeId: Long): Boolean =
        existingConfigs.any { it.id != excludeId && it.name.trim() == name }

    /** 保存入口：编辑已有配置沿用原名直存（与遮挡编辑器一致）；新配置弹命名框 */
    fun requestSave() {
        if (savedId > 0L && savedName.isNotBlank()) {
            doSave()
        } else {
            if (savedName.isBlank()) savedName = nextDefaultName()
            showSaveDialog = true
        }
    }

    /** 返回入口：有未保存改动先确认 */
    fun requestBack() {
        if (dirty) showExitConfirm = true else navController.popBackStack()
    }

    BackHandler(enabled = dirty) { requestBack() }

    val blankCount = if (clozeMode == "WORD") {
        selected.values.sumOf { list ->
            list.sortedBy { it.first }
                .fold(0 to Int.MIN_VALUE) { (count, lastEnd), r ->
                    if (r.first <= lastEnd + 1) count to maxOf(lastEnd, r.last)
                    else (count + 1) to r.last
                }.first
        }
    } else {
        selectedSentenceCount()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp)
        ) {
            // ── 顶栏：返回 + 标题 + 保存（常驻右上角，SettingsScreen 同款规范）──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { requestBack() })
                Spacer(Modifier.width(12.dp))
                // 标题 + 计数：计数放进权重列（宽度随空数变化），不会挤动右侧按钮组
                Column(Modifier.weight(1f)) {
                    Text(
                        "自定义挖空",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "$blankCount 个空",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 撤销/重做：固定 36dp 槽位常驻（不可用时置灰），
                // 点撤销后重做一出现也不会挤动位置
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
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        if (savedId > 0L && savedName.isNotBlank()) {
                            // 编辑已有配置：直存；pick 流程保存成功后直接开练
                            doSave { ok ->
                                if (ok && pick) {
                                    navController.navigate("practice/$articleId?configId=$savedId") {
                                        popUpTo("custom_cloze_list/$articleId") { inclusive = true }
                                    }
                                }
                            }
                        } else {
                            if (savedName.isBlank()) savedName = nextDefaultName()
                            showSaveDialog = true
                        }
                    },
                    // 未选任何挖空时置灰（无可保存内容）
                    enabled = article != null && blankCount > 0,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("保存")
                }
            }

            Text(
                article?.title ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
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
            Spacer(Modifier.height(12.dp))

            // ── 模式选择 chips（自定义即预览：切换即改变编辑语义与空样式）──
            ModeChipRow(current = clozeMode, onChange = { next ->
                if (next == clozeMode) return@ModeChipRow
                if (selected.values.any { it.isNotEmpty() }) {
                    pendingMode = next
                    showModeSwitchConfirm = true
                } else {
                    clozeMode = next
                }
            })
            Spacer(Modifier.height(4.dp))
            Text(
                when (clozeMode) {
                    "SENTENCE" -> "点选要挖掉的复句"
                    "REVERSE" -> "点选句子加入反向默写"
                    else -> "点按选中挖空 · 长按切换粒度"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // ── 正文句子列表 ──
            if (notFound) {
                Spacer(Modifier.height(24.dp))
                Text("文章不存在或已被删除", color = MaterialTheme.colorScheme.error)
            } else if (article != null) {
                val sentences = remember(article?.id, article?.content) {
                    SentenceSplitter.split(article!!.content)
                }
                // 全局空序号偏移：按句序累计前文已选空数（预览编号与练习一致）
                var blankOffset = 0
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        items(sentences.size, key = { it }) { sIdx ->
                            val sentence = sentences[sIdx]
                            val ranges = selected[sIdx]?.toList() ?: emptyList()
                            val mergedCount = RangeOps.mergeRanges(ranges).size
                            val offsetBefore = blankOffset
                            blankOffset += mergedCount
                            SentenceEditCard(
                                index = sIdx + 1,
                                sentence = sentence,
                                level = levels.getOrElse(sIdx) { 0 },
                                mode = clozeMode,
                                selectedRanges = ranges,
                                blankIndexOffset = offsetBefore,
                                onToggle = { range ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    pushUndo()
                                    val list = selected.getOrPut(sIdx) { mutableStateListOf() }
                                    val hit = list.firstOrNull { it.first <= range.last && range.first <= it.last }
                                    if (hit != null) list.remove(hit) else list.add(range)
                                    dirty = true
                                    editSeq++
                                },
                                onExplode = {
                                    // 长按循环切换粒度：复句→分句→字词→单字→回到复句（拆碎后可还原）
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    pushUndo()
                                    if (clozeMode == "WORD") levels[sIdx] = (levels[sIdx] + 1) % 4
                                    dirty = true
                                    editSeq++
                                }
                            )
                        }
                    }
                    // 长文滚动进度徽章（>8 句启用）
                    ScrollProgressBadge(
                        listState = listState,
                        total = sentences.size,
                        unit = "句",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 24.dp)
                    )
                }
            }
        }
    }

    // ── 模式切换确认（已选内容非空时）──
    if (showModeSwitchConfirm) {
        BlancallAlertDialog(
            onDismissRequest = { showModeSwitchConfirm = false },
            title = { Text("切换挖空模式？", fontWeight = FontWeight.SemiBold) },
            text = { Text("切换后将清空当前已选的挖空内容。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showModeSwitchConfirm = false
                    pushUndo()
                    selected.clear()
                    levels.replaceAll { 0 }
                    clozeMode = pendingMode
                    dirty = true
                    editSeq++
                }) { Text("清空并切换") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showModeSwitchConfirm = false }) { Text("取消") }
            }
        )
    }

    // ── 保存命名弹窗（新建配置时）──
    if (showSaveDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存自定义挖空", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("给这套配置起个名字，方便在练习模式里选用。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = savedName,
                        onValueChange = { savedName = it },
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
                    // 名称去重：trim 后与其他配置全等则提示并中止保存（不关弹窗，便于改名）
                    val name = savedName.trim().ifBlank { nextDefaultName() }
                    if (isNameTaken(name, savedId)) {
                        Toast.makeText(context, "名称已存在，请换一个", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    savedName = name
                    showSaveDialog = false
                    doSave { ok ->
                        if (ok) {
                            if (pick) {
                                // 来自模式选择的「练一把」流程：保存即开始练习
                                navController.navigate("practice/$articleId?configId=$savedId") {
                                    popUpTo("custom_cloze_list/$articleId") { inclusive = true }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                    }
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
            title = { Text("放弃未保存的挖空？", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "当前编辑的 $blankCount 个空还没有保存，离开后将丢失。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitConfirm = false
                    dirty = false
                    navController.popBackStack()
                }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitConfirm = false }) { Text("继续编辑") }
            }
        )
    }
}

/**
 * 撤销/重做快照：模式 + 每句粒度 + 选中区间完整状态（操作前的现场）。
 */
private data class ClozeEditSnapshot(
    val mode: String,
    val levels: List<Int>,
    val sel: Map<Int, List<IntRange>>
)

/** 模式切换 chips（自定义即预览：三选一） */
@Composable
private fun ModeChipRow(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("SENTENCE" to "📝 句子", "WORD" to "🔤 字词", "REVERSE" to "✍️ 反向").forEach { (m, label) ->
            FilterChip(
                selected = current == m,
                onClick = { onChange(m) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/**
 * 单句编辑卡片：按当前模式渲染编辑语义与预览样式。
 * - SENTENCE / REVERSE：复句点选（无炸），选中之渲染各自模式的空预览
 * - WORD：v1 炸链（level 0-3），选中渲染高亮空框
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SentenceEditCard(
    index: Int,
    sentence: String,
    level: Int,
    mode: String,
    selectedRanges: List<IntRange>,
    blankIndexOffset: Int,
    onToggle: (IntRange) -> Unit,
    onExplode: () -> Unit
) {
    val tokens = when (mode) {
        // 句子/反向模式：单位固定为复句（无炸）
        "SENTENCE", "REVERSE" -> listOf(ClozeTokens.EditToken(sentence, 0 until sentence.length))
        else -> ClozeTokens.tokensFor(sentence, level)
    }
    val merged = RangeOps.mergeRanges(selectedRanges)
    // 长按拆词统一带触感反馈
    val confirmHaptic = rememberConfirmHaptic()

    fun isSelectedRange(range: IntRange): Boolean =
        merged.any { it.first <= range.first && range.last <= it.last }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        Text(
            when (mode) {
                "SENTENCE" -> "第 $index 句"
                "REVERSE" -> "第 $index 句 · 默写单元"
                else -> "第 $index 句 · ${ClozeTokens.levelName(level)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tokens.forEach { token ->
                val selectedNow = isSelectedRange(token.range)
                when {
                    // ── 句子挖空预览：[N] 徽章 + 下划线（SentenceClozeContent 同款）──
                    selectedNow && mode == "SENTENCE" -> {
                        val globalN = blankIndexOffset + merged.indexOfFirst { it.first <= token.range.first && token.range.last <= it.last } + 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "[$globalN]",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "＿".repeat(sentence.length.coerceIn(2, 10)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .combinedClickable(onClick = { onToggle(token.range) })
                                    .padding(horizontal = 2.dp, vertical = 4.dp)
                            )
                        }
                    }
                    // ── 反向默写预览：分句卡片「N. 挖一词」（DictationClauseCard 同款）──
                    selectedNow && mode == "REVERSE" -> {
                        val globalN = blankIndexOffset + merged.indexOfFirst { it.first <= token.range.first && token.range.last <= it.last } + 1
                        Text(
                            "$globalN. " + BlancallGenerator.blankOneWordInClause(sentence),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(onClick = { onToggle(token.range) })
                                .padding(10.dp)
                        )
                    }
                    // ── 字词挖空预览：高亮空框 ──
                    selectedNow -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    RoundedCornerShape(6.dp)
                                )
                                .combinedClickable(onClick = { onToggle(token.range) }, onLongClick = { confirmHaptic(); onExplode() })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "＿".repeat(token.text.length.coerceAtLeast(1).coerceAtMost(6)),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    // ── 未选中原文 token ──
                    else -> {
                        Text(
                            token.text,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { onToggle(token.range) },
                                    onLongClick = { confirmHaptic(); if (mode == "WORD") onExplode() }
                                )
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// token 切分 / 粒度命名 / 区间合并已上移至 algorithm.ClozeTokens 与 algorithm.RangeOps（可单测）

