// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.widget.Toast
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.cloze.nextDefaultConfigName
import com.ilyskyo.blancall.ui.cloze.shouldPersistEditorSnapshot
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.ContentFingerprint
import com.ilyskyo.blancall.algorithm.MaskSpanOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.ui.common.CoachMark
import com.ilyskyo.blancall.ui.common.CoachShape
import com.ilyskyo.blancall.ui.common.coachAnchor
import com.ilyskyo.blancall.ui.common.rememberCoachAnchor
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.ScrollProgressBadge
import com.ilyskyo.blancall.ui.common.TopBarIconAction
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.combinedPress
import com.ilyskyo.blancall.ui.practice.pinchZoom
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
    val resources = LocalResources.current
    // 触觉栈收并（见 CustomClozeEditScreen 同名注释）：撤销/重做与点选遮挡都是「换一格」= Toggle。
    val toggleHaptic = rememberHaptic(HapticTier.Toggle)
    // 「长按句子拆词」指引的锚点（指向正文列表）
    val bodyAnchor = rememberCoachAnchor()

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
    val customWord = stringResource(com.ilyskyo.blancall.R.string.custom)
    val maskPickFirstMsg = stringResource(com.ilyskyo.blancall.R.string.mask_pick_first)
    val scope = rememberCoroutineScope()
    val paragraphs = remember(article.content) { ReaderOcclusion.splitParagraphs(article.content) }
    // 每段拆分级（遮挡编辑：0=句 1=词 2=字），长按循环
    val levels = remember { mutableStateListOf<Int>() }
    // 已遮挡块（段落索引 + 段内局部区间 + 颜色索引）
    val masks = remember { mutableStateListOf<MaskConfigStore.MaskSpan>() }
    var loaded by remember { mutableStateOf(false) }
    // 当前挡片颜色（顶部选色），与阅读设置挡片颜色互通
    var colorIdx by rememberSaveable { mutableIntStateOf(AppPrefs.readingOcclusionColor) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedId by rememberSaveable { mutableLongStateOf(if (configId > 0) configId else -1L) }
    // 未保存改动标记：有改动时返回（含系统返回）先弹确认，防止误触丢失
    var dirty by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    // 当前文章已有配置（用于默认命名去重与保存重名校验），进页时读一次
    var existingConfigs by remember { mutableStateOf<List<MaskConfigStore.MaskConfig>>(emptyList()) }
    // 编辑已有配置时记住原 createdAt（保存时原样传回，防列表排序跳变）
    var savedCreatedAt by rememberSaveable { mutableLongStateOf(0L) }
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
    // 快照归属的配置 id：回放前校验，防把另一套配置的编辑现场误当作本配置的（用户反馈的“旧配置内容不对”）
    var editorSnapshotFor by rememberSaveable { mutableLongStateOf(-1L) }
    var snapshotApplied by remember { mutableStateOf(false) }
    // 「组合期」捕获的恢复快照（rememberSaveable 的恢复值；新进入时为 null）。
    // 回放判定只认它，**不能读实时 editorSnapshot** —— 本会话 LaunchedEffect(editSeq) 写下的
    // 快照会污染实时值，让「点开已保存配置」被误判成「有现场可回放」，磁盘回填随即整段跳过。
    // 真机 bug：已保存配置点开是空白、名字也丢了；再保存就落成「自定义 2 / 自定义 3」。
    val restoredSnapshot = remember { editorSnapshot }

    if (levels.size != paragraphs.size) {
        levels.clear()
        repeat(paragraphs.size) { levels.add(0) }
    }

    // 编辑驱动（而非每帧）持久化编辑现场快照：任何编辑 editSeq++，重组后序列化一次。
    // ⚠️ editSeq==0（初次组合）必须跳过：那一刻磁盘回填还在 IO 挂起中，写下去的「空现场」
    // 会被当成恢复源 ⇒ 已保存配置永远打不开（同 CustomClozeEditScreen 的真机 bug，见 shouldPersistEditorSnapshot）。
    LaunchedEffect(editSeq) {
        if (!shouldPersistEditorSnapshot(editSeq)) return@LaunchedEffect
        editorSnapshot = runCatching {
            JSONObject().apply {
                put("levels", JSONArray().apply { levels.forEach { put(it) } })
                put("masks", JSONArray().apply {
                    masks.forEach { m -> put(JSONArray().put(m.p).put(m.a).put(m.e).put(m.c)) }
                })
            }.toString()
        }.getOrNull()
        // 记录快照归属：回放时按 configId 校验，跨配置的现场一律作废
        editorSnapshotFor = configId
    }

    LaunchedEffect(article.id, configId) {
        val cfgs = withContext(Dispatchers.IO) { store.getConfigs(article.id) }
        existingConfigs = cfgs
        // 保存目标无条件对齐入口参数：新建（configId<=0）一律重置为 -1 ——
        // 若沿用上一会话/跨进程恢复残留的 savedId>0，requestSave 会跳过命名弹窗、
        // doSave 按旧 id「覆盖更新」，表现为「新建保存后列表里看不到新配置、旧配置被改乱」；
        // 已有配置同样以入口 configId 为准（与列表页的会话 key 双保险）。
        val targetId = if (configId > 0) configId else -1L
        if (savedId != targetId) savedId = targetId
        if (configId > 0) {
            // ① 现场回放：只认「组合期带进来的快照」且归属必须是本配置。
            //    新建（configId<=0）一律从空白起步 —— 新建会话的快照归属也是 -1，与跨会话残留
            //    无法区分，禁止回放可从根上杜绝「点新建却带出旧现场」。
            val snap = restoredSnapshot
            if (snap != null && shouldRestoreMaskSnapshot(configId, snap, editorSnapshotFor, snapshotApplied)) {
                runCatching {
                    val o = JSONObject(snap)
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
                }.onSuccess { snapshotApplied = true }
            }
            // ② 名称 / 创建时间 / 失配警示：现场快照不承载这三项，无论是否回放现场都必须从磁盘取
            //   （真机 bug：跳过这一步 ⇒ 名字空白 ⇒ 保存时按默认编号起名，配置被改成「自定义 N」）；
            //   遮挡区间只在「没有回放现场」时用磁盘值（现场可能含未保存编辑，比磁盘新）。
            cfgs.firstOrNull { it.id == configId }?.let { cfg ->
                editingName = cfg.name
                savedCreatedAt = cfg.createdAt
                hashMismatch = cfg.contentHash != null && cfg.contentHash != ContentFingerprint.md5Hex(article.content)
                if (!snapshotApplied) {
                    masks.clear()
                    masks.addAll(cfg.spans)
                }
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
        toggleHaptic()
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.add(currentSnapshot())
        applySnapshot(snap)
        dirty = if (savedSnapshot != null) currentSnapshot() != savedSnapshot else true
        editSeq++
        toggleHaptic()
    }

    /** 点选切换遮挡：命中已遮块 → 取消；否则移除局部重叠后按当前颜色整块遮上（语义在 MaskSpanOps，可单测） */
    fun toggleMask(p: Int, a: Int, e: Int) {
        toggleHaptic()
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
            Toast.makeText(context, maskPickFirstMsg, Toast.LENGTH_SHORT).show()
            onDone(false)
            return
        }
        val cfg = MaskConfigStore.MaskConfig(
            id = savedId,
            name = editingName.ifBlank { customWord },
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
            // 回读确认真的落盘了：MaskConfigStore 在「配置库读盘失败」时会拒绝写盘以免抹掉
            // 其它文章的配置，那种情况下不能弹「已保存」骗用户（列表里根本没有）。
            val persisted = withContext(Dispatchers.IO) { store.getConfig(article.id, newId) != null }
            if (!persisted) {
                Toast.makeText(
                    context,
                    resources.getString(com.ilyskyo.blancall.R.string.save_failed),
                    Toast.LENGTH_SHORT
                ).show()
                onDone(false)
                return@launch
            }
            savedId = newId
            if (isNew) {
                // 仅新建：标记为本文使用中的配置，并把阅读遮挡切到自定义，保存即见效果
                withContext(Dispatchers.IO) { store.setSelected(article.id, newId) }
                // 只写本文章的阅读设置（按文章独立；原写全局会污染其它文章）
                updateArticleReaderPrefs(
                    ReaderPrefsStore.getInstance(context),
                    article.id
                ) { it.copy(occlusionCustomConfigId = newId, occlusionMode = "custom", occlusionEnabled = true) }
            }
            dirty = false
            savedSnapshot = snap
            Toast.makeText(
                context,
                resources.getQuantityString(com.ilyskyo.blancall.R.plurals.save_mask_toast,
                    spans.size, cfg.name, spans.size),
                Toast.LENGTH_SHORT
            ).show()
            onDone(true)
        }
    }

    /**
     * 生成当前文章内不重复的默认配置名：解析已有「自定义 N」的最大编号取 max+1；
     * 若该编号仍被占用（历史畸形数据）则继续递增跳过。
     */
    // 与自定义挖空共用同一条编号规则（旧盘名都是中文「自定义 N」，换语言后要两种前缀都认）
    fun nextDefaultName(): String = nextDefaultConfigName(existingConfigs.map { it.name }, customWord)

    /** 名称是否与当前文章内其他配置重名（排除自身，trim 后全等比较；新建时 savedId<0 等价于查全部） */
    fun isNameTaken(name: String, excludeId: Long): Boolean =
        existingConfigs.any { it.id != excludeId && it.name.trim() == name }

    /** 保存入口：编辑已有配置一律覆盖保存（名字先按磁盘原值兜底），新建才弹命名框 */
    fun requestSave() {
        if (savedId > 0L) {
            // 名字意外为空时先把本文配置里的原名找回来：旧实现此时去拿 nextDefaultName()，
            // 于是「进编辑 → 保存」一次就把配置改名成 自定义2、自定义3（真机 bug）。
            if (editingName.isBlank()) editingName = existingMaskConfigName(existingConfigs, savedId)
            if (editingName.isNotBlank()) {
                doSave()
            } else {
                editingName = nextDefaultName()
                showSaveDialog = true
            }
            return
        }
        if (editingName.isBlank()) {
            editingName = nextDefaultName()
        }
        showSaveDialog = true
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
                        stringResource(com.ilyskyo.blancall.R.string.mask_config_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = textColor
                    )
                    Text(
                        if (masks.isEmpty()) stringResource(com.ilyskyo.blancall.R.string.mask_none_added)
                        else pluralStringResource(com.ilyskyo.blancall.R.plurals.mask_marked_count, masks.size, masks.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (masks.isEmpty()) subColor else accent
                    )
                }
                // 撤销/重做：固定 36dp 槽位常驻（不可用时置灰），点击后位置不跳动
                TopBarIconAction(
                    kind = AppIconKind.Undo,
                    enabled = undoStack.isNotEmpty(),
                    onClick = { undo() },
                    contentDescription = stringResource(com.ilyskyo.blancall.R.string.undo_desc)
                )
                TopBarIconAction(
                    kind = AppIconKind.Redo,
                    enabled = redoStack.isNotEmpty(),
                    onClick = { redo() },
                    contentDescription = stringResource(com.ilyskyo.blancall.R.string.redo_desc)
                )
                // 清空：同样常驻占位（无遮挡时置灰），否则它一出现/消失也会挤动左侧的撤销/重做
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.clear_masks_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (masks.isEmpty()) subColor.copy(alpha = 0.35f) else subColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedPress(tier = PressTier.Icon, enabled = masks.isNotEmpty(), onClick = { clearAllMasks() })
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { requestSave() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.save))
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
                    stringResource(com.ilyskyo.blancall.R.string.cloze_hash_mismatch),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            // ── 挡片颜色：与阅读设置「挡片颜色」同款马卡龙色板 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(com.ilyskyo.blancall.R.string.mask_color_label),
                style = MaterialTheme.typography.labelMedium, color = textColor)
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
                                .combinedPress(
                                    tier = PressTier.Icon,
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
            Text(stringResource(com.ilyskyo.blancall.R.string.loading_label),
                style = MaterialTheme.typography.bodyMedium, color = subColor,
                modifier = Modifier.padding(horizontal = 26.dp))
        } else if (paragraphs.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(com.ilyskyo.blancall.R.string.mask_no_paragraphs),
                style = MaterialTheme.typography.bodyMedium, color = subColor,
                modifier = Modifier.padding(horizontal = 26.dp))
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 双指捏合调整字号（与阅读页 / 练习页同款手势）：字号写共享的
                    // AppPrefs.readingFont，本页用 readingFontFlow 实时渲染，捏合即所见即所得；
                    // pinchZoom 从不消费单指事件，滚动交给下层 LazyColumn，互不冲突。
                    .pinchZoom { zoom ->
                        AppPrefs.readingFont = (AppPrefs.readingFont * zoom).coerceIn(14f, 36f)
                    }
            ) {
                // 「长按句子拆词」：这是遮挡标注里唯一一个手势，且完全没有可见提示
                CoachMark(
                    coachKey = "mask_long_press_split",
                    anchor = bodyAnchor,
                    title = stringResource(com.ilyskyo.blancall.R.string.coach_mask_long_title),
                    body = stringResource(com.ilyskyo.blancall.R.string.coach_mask_long_body),
                    shape = CoachShape.Card,
                )
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
                        // 首段锚点：长按拆词的指引圈只圈第一段（圈整块列表等于没圈）
                        Box(if (p == 0) Modifier.coachAnchor(bodyAnchor) else Modifier) {
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
                }
                // 长文滚动进度徽章（>8 段启用）
                ScrollProgressBadge(
                    listState = listState,
                    total = paragraphs.size,
                    unit = stringResource(com.ilyskyo.blancall.R.string.unit_paragraphs),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                )
            }
        }
    }

    // ── 保存命名弹窗（与自定义挖空同款交互）──
    AnimatedOverlayPresence(visible = showSaveDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.save_mask_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    TextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(com.ilyskyo.blancall.R.string.custom)) },
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
                    val name = editingName.trim().ifBlank { nextDefaultName() }
                    if (isNameTaken(name, savedId)) {
                        Toast.makeText(context,
                            resources.getString(com.ilyskyo.blancall.R.string.cloze_name_taken), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    editingName = name
                    showSaveDialog = false
                    doSave { ok -> if (ok) onBack() }
                }) { Text(stringResource(com.ilyskyo.blancall.R.string.save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.cancel))
                }
            }
        )
    }

    // ── 未保存改动退出确认 ──
    AnimatedOverlayPresence(visible = showExitConfirm) {
        BlancallAlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.exit_mask_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (masks.isNotEmpty())
                        pluralStringResource(com.ilyskyo.blancall.R.plurals.exit_mask_body, masks.size, masks.size)
                    else stringResource(com.ilyskyo.blancall.R.string.exit_mask_plain_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitConfirm = false
                    dirty = false
                    onBack()
                }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.discard_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.keep_editing_action))
                }
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
 * 现场回放判定（纯函数，可单测）：只有「已有配置」且「组合期带进来的快照确实属于本配置」才回放。
 *
 * 真机 bug 的锚点：旧实现读实时 `editorSnapshot`，本会话 LaunchedEffect(editSeq) 写下的空现场
 * 立刻把它点亮 ⇒ [alreadyApplied] 变 true ⇒ 磁盘回填被跳过 ⇒ 已保存配置打开是空白。
 * 所以判据必须是「组合期」那一份值（[restoredSnapshot]），而不是会话中途被改写的那一份。
 */
internal fun shouldRestoreMaskSnapshot(
    configId: Long,
    restoredSnapshot: String?,
    snapshotOwnerId: Long,
    alreadyApplied: Boolean
): Boolean = !alreadyApplied && configId > 0L && restoredSnapshot != null && snapshotOwnerId == configId

/** 本文配置里某套配置的原名（找不到返回空串）：保存前用它兜底，避免把配置改名成默认编号 */
internal fun existingMaskConfigName(configs: List<MaskConfigStore.MaskConfig>, id: Long): String =
    if (id <= 0L) "" else configs.firstOrNull { it.id == id }?.name?.trim().orEmpty()

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
    // 长按拆句 / 拆词统一带触感反馈
    val confirmHaptic = rememberConfirmHaptic()
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
                    onLongPress = { confirmHaptic(); currentSplit() }
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
    // 长按拆句 / 拆词统一带触感反馈
    val confirmHaptic = rememberConfirmHaptic()
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
                        .combinedPress(
                            tier = PressTier.Icon,
                            onClick = { currentToggle(u.start, u.end) },
                            onLongClick = { confirmHaptic(); currentSplit() }
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
                        .combinedPress(
                            tier = PressTier.Icon,
                            onClick = { currentToggle(u.start, u.end) },
                            onLongClick = { confirmHaptic(); currentSplit() }
                        )
                )
            }
        }
    }
}
