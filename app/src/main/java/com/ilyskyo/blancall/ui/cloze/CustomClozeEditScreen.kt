// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.cloze

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义挖空模板编辑页（v2：自定义即预览）。
 *
 * 顶部三枚模式 chips 选择这套配置的目标练习模式，编辑与预览实时切换为该模式的空样式：
 * - 📝 句子挖空：点选复句挖复句，选中处渲染为练习同款「[N] ＿＿＿＿」序号空
 * - 🔤 字词挖空：长按逐级炸碎（分句→字词→单字），点选区间，渲染高亮空框
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
    var editingConfig by remember { mutableStateOf<CustomClozeStore.CustomConfig?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    LaunchedEffect(articleId, configId) {
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
        if (configId > 0) {
            val cfg = CustomClozeStore.getInstance(navController.context.filesDir)
                .getConfigs(articleId).firstOrNull { it.id == configId }
            if (cfg != null) {
                editingConfig = cfg
                saveName = cfg.name
                clozeMode = cfg.mode
                cfg.blanks.forEach { b ->
                    if (b.s !in sentences.indices) return@forEach
                    when (cfg.mode) {
                        // 字词模式：区间对齐 token 边界需炸到单字级
                        "WORD" -> {
                            while (levels[b.s] < 3) levels[b.s] = levels[b.s] + 1
                            selected.getOrPut(b.s) { mutableStateListOf() }.add(b.a until b.b)
                        }
                        // 句子/反向：复句选择，区间即全句
                        else -> selected.getOrPut(b.s) { mutableStateListOf() }.add(0 until sentences[b.s].length)
                    }
                }
            }
        }
    }

    fun selectedSentenceCount(): Int = selected.values.count { it.isNotEmpty() }

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
            id = editingConfig?.id ?: 0L,
            name = saveName.ifBlank { editingConfig?.name ?: "" },
            createdAt = editingConfig?.createdAt ?: 0L,
            blanks = blanks,
            mode = clozeMode
        )
    }

    fun doSave() {
        val art = article ?: return
        val cfg = buildConfig()
        if (cfg == null) {
            Toast.makeText(context, "请先点选要挖空的句子或字词", Toast.LENGTH_SHORT).show()
            return
        }
        val total = cfg.blanks.size
        val newId = CustomClozeStore.getInstance(context.filesDir).saveConfig(art.id, cfg)
        Toast.makeText(context, "已保存「${cfg.name}」（$total 个空）", Toast.LENGTH_SHORT).show()
        editingConfig = cfg.copy(id = newId)
    }

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
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "自定义挖空",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$blankCount 个空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        if (editingConfig != null) {
                            doSave()
                            if (pick) {
                                // 来自模式选择的「练一把」流程：保存即开始练习
                                navController.navigate("practice/$articleId?configId=${editingConfig?.id ?: -1L}") {
                                    popUpTo("custom_cloze_list/$articleId") { inclusive = true }
                                }
                            }
                        } else {
                            saveName = "自定义 " + (CustomClozeStore.getInstance(context.filesDir)
                                .getConfigs(articleId).size + 1)
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
                    "SENTENCE" -> "点选要挖掉的复句（选中即预览句子挖空样式）"
                    "REVERSE" -> "点选句子加入反向默写（练习时逐分句挖一词打乱还原）"
                    else -> "点按选中挖空 · 长按逐级拆碎（分句→字词→单字）"
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(sentences.size, key = { it }) { sIdx ->
                        val sentence = sentences[sIdx]
                        val ranges = selected[sIdx]?.toList() ?: emptyList()
                        val mergedCount = mergeRanges(ranges).size
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
                                val list = selected.getOrPut(sIdx) { mutableStateListOf() }
                                val hit = list.firstOrNull { it.first <= range.last && range.first <= it.last }
                                if (hit != null) list.remove(hit) else list.add(range)
                            },
                            onExplode = {
                                if (clozeMode == "WORD" && levels[sIdx] < 3) levels[sIdx] = levels[sIdx] + 1
                            }
                        )
                    }
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
                    selected.clear()
                    levels.replaceAll { 0 }
                    clozeMode = pendingMode
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
                        value = saveName,
                        onValueChange = { saveName = it },
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
                    doSave()
                    if (pick) {
                        // 来自模式选择的「练一把」流程：保存即开始练习
                        navController.navigate("practice/$articleId?configId=${editingConfig?.id ?: -1L}") {
                            popUpTo("custom_cloze_list/$articleId") { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 合并相邻/重叠区间 */
private fun mergeRanges(ranges: List<IntRange>): List<IntRange> =
    ranges.sortedBy { it.first }
        .fold(mutableListOf<IntRange>()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                acc[acc.size - 1] = last.first..maxOf(last.last, r.last)
            } else acc.add(r)
            acc
        }

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
        "SENTENCE", "REVERSE" -> listOf(EditToken(sentence, 0 until sentence.length))
        else -> tokensFor(sentence, level)
    }
    val merged = mergeRanges(selectedRanges)

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
                else -> "第 $index 句 · ${levelName(level)}"
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
                                .combinedClickable(onClick = { onToggle(token.range) }, onLongClick = onExplode)
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
                                    onLongClick = { if (mode == "WORD") onExplode() }
                                )
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** token：文本 + 句内字符区间 */
private data class EditToken(val text: String, val range: IntRange)

private fun levelName(level: Int): String = when (level) {
    0 -> "复句"
    1 -> "分句"
    2 -> "字词"
    else -> "单字"
}

/**
 * 按级Level切分 token：
 * - 0 复句
 * - 1 分句：按句内标点切，标点跟随前一个 token
 * - 2 字词：连续汉字每 2 字一块（末尾余 1 字自成一tok），英文单词整体
 * - 3 单字：每个汉字一个 token，英文单词整体
 * 标点（非中文非字母字符）始终并入前一个 token，避免单独的标点空。
 */
private fun tokensFor(sentence: String, level: Int): List<EditToken> {
    if (sentence.isEmpty()) return emptyList()
    if (level <= 0) return listOf(EditToken(sentence, 0 until sentence.length))

    val out = mutableListOf<EditToken>()
    var i = 0
    val n = sentence.length

    fun isChinese(c: Char) = c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'
    fun isPunct(c: Char) = !isChinese(c) && !c.isLetter()

    if (level == 1) {
        var start = 0
        while (i < n) {
            val c = sentence[i]
            if (isPunct(c)) {
                i++
                while (i < n && isPunct(sentence[i])) i++
                out.add(EditToken(sentence.substring(start, i), start until i))
                start = i
            } else i++
        }
        if (start < n) out.add(EditToken(sentence.substring(start), start until n))
        return if (out.size <= 1) listOf(EditToken(sentence, 0 until sentence.length)) else out
    }

    val chunk = if (level == 2) 2 else 1
    var tokenStart = 0
    while (i < n) {
        val c = sentence[i]
        when {
            isChinese(c) -> {
                var runEnd = i
                while (runEnd < n && isChinese(sentence[runEnd])) runEnd++
                var s = i
                while (s < runEnd) {
                    val e = minOf(s + chunk, runEnd)
                    out.add(EditToken(sentence.substring(s, e), s until e))
                    s = e
                }
                i = runEnd
                tokenStart = i
            }
            c.isLetter() -> {
                var runEnd = i
                while (runEnd < n && sentence[runEnd].isLetter() && !isChinese(sentence[runEnd])) runEnd++
                out.add(EditToken(sentence.substring(i, runEnd), i until runEnd))
                i = runEnd
                tokenStart = i
            }
            else -> {
                i++
                while (i < n && isPunct(sentence[i])) i++
                val end = i
                if (out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    out.add(EditToken(last.text + sentence.substring(last.range.last + 1, end), last.range.first until end))
                } else {
                    out.add(EditToken(sentence.substring(tokenStart, end), tokenStart until end))
                }
            }
        }
    }
    return out
}
