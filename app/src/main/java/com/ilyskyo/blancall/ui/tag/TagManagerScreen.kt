// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.tag

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.TagChipUi
import com.ilyskyo.blancall.ui.common.TagDot
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 行高 56dp + 行距 8dp（拖动换位的步长基准，固定常量保证算法与视觉一致） */
private val TAG_ROW_HEIGHT = 56.dp
private val TAG_ROW_GAP = 8.dp

/**
 * 文章标签管理页：新建 / 编辑（名称 + 色环/套装配色）/ 删除（级联解绑 + 二次确认）/
 * 拖动排序（右侧手柄直接拖，数组顺序即展示顺序，松手即持久化）/
 * 长按行 → 菜单（跨文复习 / 删除）。
 *
 * 数据实时来自 [TagStore.data]（跨页面改动即时反映）；首次进入在 IO 线程 priming 一次。
 */
@Composable
fun TagManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { TagStore.getInstance(context.filesDir) }
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = rememberConfirmHaptic()

    // 首次进入 priming（IO 读盘 → 发布 StateFlow）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.snapshot() }
    }

    // 跨文复习快捷入口需要文章实况（标签下文章 id 与现存文章对账）
    val articleViewModel: ArticleViewModel = viewModel()
    val articles by articleViewModel.articles.collectAsState()

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tag?>(null) }
    var deleteTarget by remember { mutableStateOf<Tag?>(null) }
    // 管理该标签下的文章（勾选列表）；null = 未打开
    var managing by remember { mutableStateOf<Tag?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    // ── 拖动排序状态（行高固定：目标位 = 起点 + round(位移 / 步长)）──
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowStepPx = with(LocalDensity.current) { (TAG_ROW_HEIGHT + TAG_ROW_GAP).toPx() }
    // 拖动回调在首个组合期被 pointerInput 捕获：用 rememberUpdatedState 保证读到最新标签表
    val latestTags by rememberUpdatedState(data.tags)

    fun handleDrop() {
        val id = draggingId
        draggingId = null
        val dy = dragOffset
        dragOffset = 0f
        if (id == null) return
        val list = latestTags
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + (dy / rowStepPx).roundToInt()).coerceIn(0, list.lastIndex)
        if (to == from) return
        val orderedIds = TagOps.reorderByMove(list, from, to).map { it.id }
        haptic()
        scope.launch { withContext(Dispatchers.IO) { store.reorderTags(orderedIds) } }
    }

    /** 某标签的绑定篇数与跨文复习 id 集合（与现存文章对账） */
    fun boundArticleIds(tagId: Long): List<Long> =
        articles.filter { data.links[it.id]?.contains(tagId) == true }.map { it.id }

    // 每标签绑定篇数一次算好（避免逐行重复 O(标签 × 文章) 过滤）
    val countsByTag = remember(data, articles) {
        data.tags.associate { t ->
            t.id to articles.count { a -> data.links[a.id]?.contains(t.id) == true }
        }
    }

    // 滚动状态提升到条件外：拖动期间仅卸下滚动修饰符，滚动位置不丢
    val listScrollState = rememberScrollState()

    fun crossReview(tag: Tag) {
        val ids = boundArticleIds(tag.id)
        if (ids.size < 2) {
            Toast.makeText(context, "该标签下不足 2 篇文章，无法跨文复习", Toast.LENGTH_SHORT).show()
        } else {
            navController.navigate("cross/${ids.joinToString(",")}")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            // ── 顶栏：返回 + 标题 + 新建 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    "文章标签",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    onClick = { creating = true },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        "新建",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (data.tags.isEmpty()) {
                // ── 空态 ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(
                        kind = AppIconKind.Tag,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有标签",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "标签为文章分类，并以彩色徽标显示在卡片上",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { creating = true }, shape = RoundedCornerShape(10.dp)) {
                        Text("新建第一个标签")
                    }
                }
            } else {
                Text(
                    "共 ${data.tags.size} 个标签",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // 拖动期间临时卸下滚动，避免与换位手势抢指针
                        .then(
                            if (draggingId == null) Modifier.verticalScroll(listScrollState)
                            else Modifier
                        ),
                ) {
                    data.tags.forEach { tag ->
                        val isDragging = draggingId == tag.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f),
                        ) {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(TAG_ROW_HEIGHT)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationY = dragOffset
                                            alpha = 0.92f
                                        }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                backdrop = false,
                                onClick = { editing = tag },
                                onLongClick = { menuFor = tag.id },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 14.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TagDot(tag = TagChipUi(tag.name, tag.color), size = 12.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        tag.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${countsByTag[tag.id] ?: 0} 篇",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                    // 管理该标签下的文章：勾选添加 / 取消勾选移出（同一入口兼顾增删）
                                    Text(
                                        "管理",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { managing = tag }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                    // 拖动手柄：直接拖（无需长按），与长按菜单手势互不冲突。
                                    // 拖拽用「越过 slop 才接管」的手动模式（见 dragHandleAfterSlop 注释）：
                                    // detectDragGestures 在滚动手势 + 卡面 combinedClickable 的组合环境下
                                    // 会因事件「已被消费」而静默中止，表现为「手柄拖不动、无任何反应」（真机反馈）。
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .pointerInput(tag.id) {
                                                dragHandleAfterSlop(
                                                    onStart = {
                                                        draggingId = tag.id
                                                        dragOffset = 0f
                                                    },
                                                    onDelta = { d -> dragOffset += d.y },
                                                    onEnd = { handleDrop() },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.DragHandle,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                            // 长按菜单锚点（相对本行定位）
                            GlassDropdownMenu(
                                expanded = menuFor == tag.id,
                                onDismissRequest = { menuFor = null },
                            ) {
                                GlassMenuItem(
                                    onClick = {
                                        menuFor = null
                                        editing = tag
                                    },
                                    label = { Text("编辑名称与颜色") },
                                )
                                GlassMenuItem(
                                    onClick = {
                                        menuFor = null
                                        crossReview(tag)
                                    },
                                    label = { Text("跨文复习") },
                                )
                                GlassMenuDivider()
                                GlassMenuItem(
                                    onClick = {
                                        menuFor = null
                                        deleteTarget = tag
                                    },
                                    label = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                )
                            }
                        }
                        Spacer(Modifier.height(TAG_ROW_GAP))
                    }
                    // 底部留白：最后一行不被导航条/屏幕边缘压迫
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }

    // ── 新建 / 编辑面板 ──
    if (creating) {
        TagEditorSheet(
            initialTag = null,
            existingTags = data.tags,
            onDismiss = { creating = false },
            onSave = { name, color ->
                scope.launch { withContext(Dispatchers.IO) { store.createTag(name, color) } }
                creating = false
            },
        )
    }
    editing?.let { target ->
        TagEditorSheet(
            initialTag = target,
            existingTags = data.tags,
            onDismiss = { editing = null },
            onSave = { name, color ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        store.renameTag(target.id, name)
                        store.recolorTag(target.id, color)
                    }
                }
                editing = null
            },
        )
    }

    // ── 删除二次确认（级联解绑）──
    deleteTarget?.let { target ->
        val boundCount = data.links.values.count { target.id in it }
        BlancallAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除标签") },
            text = {
                Text(
                    if (boundCount > 0) {
                        "删除标签「${target.name}」？已绑定的 $boundCount 篇文章将取消该标签（不会删除文章）。此操作无法撤销。"
                    } else {
                        "删除标签「${target.name}」？此操作无法撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { store.deleteTag(target.id) } }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // ── 管理该标签下的文章：勾选=绑定，取消勾选=移出（单次落盘，见 setTagArticles）──
    managing?.let { tag ->
        // 初始勾选态：当前已绑定的文章（打开时快照一次，会话内以本地状态为准）
        var checked by remember(tag.id) {
            mutableStateOf(
                articles.filter { data.links[it.id]?.contains(tag.id) == true }
                    .map { it.id }
                    .toSet()
            )
        }
        BlancallAlertDialog(
            onDismissRequest = { managing = null },
            title = { Text("管理「${tag.name}」的文章", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "勾选的文章将绑定该标签，取消勾选则移出（不删除文章）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (articles.isEmpty()) {
                        Text(
                            "还没有文章",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                        ) {
                            items(articles, key = { it.id }) { article ->
                                val on = article.id in checked
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            checked = if (on) checked - article.id else checked + article.id
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 整行可点：勾选框只做展示（onCheckedChange = null），避免双重响应
                                    Checkbox(checked = on, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            article.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${article.content.length} 字符",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = tag
                    val selection = checked
                    managing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.setTagArticles(target.id, selection) }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { managing = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 手柄拖拽（归一化）：越过 touch slop 才「接管」手势 —— 与首页画布编辑态拖动同一模式。
 *
 * 不用 `detectDragGestures` 的原因（真机反馈「手柄拖不动 / 无任何反应」）：
 * 其内部滑差等待（awaitPointerSlopOrCancellation）在事件流中出现「已被消费」的
 * 事件时会**静默中止**（返回 null，不回调任何 onDrag*）；而本手柄处于
 * verticalScroll + 卡面 combinedClickable 的双重手势环境里，事件先于手柄被
 * 其它节点的滑差判定消费时，拖拽就会无声失败。手动模式只读 positionChange 增量、
 * 过 slop 前绝不消费、过 slop 后自行消费，不依赖任何内部取消语义，稳定可拖。
 *
 * 未过 slop 前完全不消费事件：卡片点击/长按菜单不受影响。
 */
private suspend fun PointerInputScope.dragHandleAfterSlop(
    onStart: () -> Unit,
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var acc = Offset.Zero
        var started = false
        val touchSlop = viewConfiguration.touchSlop
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!started) {
                    acc += delta
                    if (acc.getDistance() > touchSlop) {
                        started = true
                        change.consume()
                        onStart()
                        onDelta(acc)
                    }
                } else {
                    change.consume()
                    onDelta(delta)
                }
            }
        } finally {
            // 手势被系统取消（多指抢占等）时也要收尾，否则拖动预览态会卡住
            if (started) onEnd()
        }
    }
}
