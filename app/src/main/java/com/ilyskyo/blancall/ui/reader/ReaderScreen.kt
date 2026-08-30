// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.DeleteConfirmDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(navController: NavController, articleId: Long) {
    val articleViewModel: ArticleViewModel = viewModel()
    val context = LocalContext.current
    // AI 功能开关（关闭时隐藏 AI 入口）
    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
    var article by remember { mutableStateOf<Article?>(null) }
    // 加载失败标记：articleId 无对应文章时展示“文章不存在”而非永久转圈
    var loadFailed by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var readingMode by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showFullscreenEdit by remember { mutableStateOf(false) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    // 预测性返回跟手进度：编辑模式下侧滑返回时驱动编辑界面缩放/淡出动画
    var editBackProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 兜底（先注册、优先级低）：PredictiveBackHandler 在个别系统/场景下可能不拦截，
    // 保证编辑/阅读模式下返回键一定能退出当前模式，而不是直接退出页面
    BackHandler(enabled = isEditing) { isEditing = false }
    BackHandler(enabled = readingMode) { readingMode = false }

    // 编辑模式下拦截预测性返回手势（侧滑返回），先退出编辑模式而非直接返回上一页；
    // 手势跟手：进度驱动编辑界面缩小淡出，松手决定退出编辑或回弹
    PredictiveBackHandler(enabled = isEditing) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                editBackProgress = backEvent.progress
            }
            // 手势完成 → 先关闭全屏，再退出编辑
            if (showFullscreenEdit) showFullscreenEdit = false
            else isEditing = false
            editBackProgress = 0f
        } catch (e: CancellationException) {
            // 手势取消 → 回弹
            editBackProgress = 0f
        }
    }

    // 沉浸阅读模式：拦截返回手势退出（回到常规详情页）
    PredictiveBackHandler(enabled = readingMode) { progressFlow ->
        try {
            progressFlow.collect { }
            readingMode = false
        } catch (e: CancellationException) {
            // 手势取消 → 保持阅读模式
        }
    }

    LaunchedEffect(articleId) {
        val loaded = articleViewModel.getArticleById(articleId)
        article = loaded
        loadFailed = loaded == null
        loaded?.let {
            editTitle = it.title
            editContent = it.content
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
        // 顶部导航（沉浸阅读模式下隐藏）
        if (!readingMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 编辑模式：取消编辑/保存按钮也跟随预测性返回手势
                //（透明度随进度 1→0 逐渐变透明，露出下层阅读内容）
                .then(
                    if (isEditing) Modifier.graphicsLayer {
                        val p = editBackProgress
                        val s = 1f - 0.08f * p
                        scaleX = s
                        scaleY = s
                        alpha = 1f - p
                    } else Modifier
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左：返回按钮；编辑模式下点击 = 退出编辑（与系统返回手势一致），非编辑态返回上一页
            BackButton(onClick = {
                if (isEditing) isEditing = false else navController.popBackStack()
            })

            article?.let { art ->
                if (isEditing) {
                    // 编辑模式下右侧仅保留「保存」（取消编辑已由返回键承担），与顶端按钮同款磨砂玻璃风格
                    GlassButton(
                        onClick = {
                            if (editTitle.isNotBlank()) {
                                articleViewModel.updateArticle(art.copy(title = editTitle.trim(), content = editContent.trim()))
                                // 本地立即赋值以即时反映编辑结果（VM 未暴露当前文章 Flow，故保留本地同步赋值，避免与异步更新竞态时回显旧值）
                                article = art.copy(title = editTitle.trim(), content = editContent.trim())
                                isEditing = false
                            }
                        },
                        enabled = editTitle.isNotBlank(),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("删除", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                        GlassButton(
                            onClick = { isEditing = true },
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("编辑", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(8.dp))

        article?.let { art ->
            // 内容区：底层始终渲染阅读内容（编辑模式下供预测性返回手势露出）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // ── 阅读内容（可滚动）：编辑模式下供预测性返回手势渐现露出 ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .graphicsLayer {
                            // 编辑模式手势返回：阅读内容随进度从透明渐现（与覆盖层渐隐互补，
                            // 确保下层详情页内容一定可见，而不是露出纯色背景）
                            alpha = if (isEditing) editBackProgress else 1f
                        }
                ) {
                    Text(
                        text = art.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "${art.content.length} 字符",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(16.dp))

                    val readingFontId by com.ilyskyo.blancall.ui.theme.AppPrefs.readingFontIdFlow.collectAsState()
                    val readingFontFamily = remember(readingFontId) {
                        com.ilyskyo.blancall.ui.reader.ReaderFonts.resolveFontFamily(context, readingFontId) ?: FontFamily.Default
                    }
                    val autoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
                    val paragraphs = remember(art.content) {
                        art.content.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            paragraphs.forEachIndexed { index, para ->
                                if (index > 0) Spacer(Modifier.height(10.dp))
                                Text(
                                    text = para,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        textIndent = if (autoIndentEnabled && art.autoIndent)
                                            TextIndent(firstLine = 2.em) else TextIndent()
                                    ),
                                    fontFamily = readingFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // ── 编辑覆盖层：不透明背景 + 跟手动画
                //（透明度随进度 1→0 渐变，下层阅读内容逐渐显现）──
                if (isEditing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .graphicsLayer {
                                val p = editBackProgress
                                val s = 1f - 0.08f * p
                                scaleX = s
                                scaleY = s
                                alpha = 1f - p
                            }
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = editTitle,
                                onValueChange = { editTitle = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("标题") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = editContent,
                                onValueChange = { editContent = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = { Text("内容") },
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${editContent.length} 字符",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { showFullscreenEdit = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AppIcon(
                                            kind = AppIconKind.OpenInFull,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("全屏编辑")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isEditing && !readingMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.navigate("statistics/${art.id}") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        AdaptiveButtonLabel("学习统计")
                    }
                    OutlinedButton(
                        onClick = { readingMode = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        AdaptiveButtonLabel("阅读模式")
                    }
                    // AI 对话入口（设置中启用 AI 功能后才显示）
                    if (aiEnabled) {
                        OutlinedButton(
                            onClick = { navController.navigate("ai?ids=${art.id}") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            AdaptiveButtonLabel("AI")
                        }
                    }
                    Button(
                        onClick = { showModePicker = true },
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                practiceButtonRect = Rect(
                                    pos.x, pos.y,
                                    pos.x + coords.size.width,
                                    pos.y + coords.size.height
                                )
                            },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        AdaptiveButtonLabel("开始练习")
                    }
                }
            }
        } ?: run {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // 加载失败（文章不存在）时给出提示，而非永久转圈
                if (loadFailed) {
                    Text("文章不存在", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // ── 沉浸阅读模式：全屏沉浸 + 章节翻页 + 断点续读 ──
    if (readingMode) {
        article?.let { art ->
            ReadingModeScreen(
                article = art,
                onExit = { readingMode = false },
                // AI 遮挡：仅在 AI 开关开启时注入桥接实现（未开启/未配置时阅读页自动隐藏 AI 选项）
                aiOcclusion = if (aiEnabled) remember { ProAiOcclusionBridge() } else null
            )
        }
    }

    // 删除确认对话框（引用公共组件）
    if (showDeleteDialog) {
        article?.let { art ->
            DeleteConfirmDialog(
                title = "确认删除",
                message = "确定要删除「${art.title}」吗？\n删除后无法恢复。",
                onConfirm = {
                    articleViewModel.deleteArticle(art)
                    showDeleteDialog = false
                    navController.popBackStack()
                },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }

    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect,
        onDismiss = { showModePicker = false },
        onModeSelected = { mode ->
            showModePicker = false
            navController.navigate("practice/${articleId}?mode=${mode.name}")
        }
    )

    // 全屏编辑对话框
    if (showFullscreenEdit) {
        Dialog(
            onDismissRequest = { showFullscreenEdit = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            // 入场动画：淡入 + 轻微上移（不整屏 scale，避免露出背景缝隙）。
            // 注：外层是条件式 if(showFullscreenEdit)，dismiss 时 Dialog 立即移出组合，退场不播放。
            var fsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { fsVisible = true }
            val fsAlpha by animateFloatAsState(targetValue = if (fsVisible) 1f else 0f, animationSpec = tween(180), label = "fsFade")
            val fsOffset by animateDpAsState(targetValue = if (fsVisible) 0.dp else 14.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "fsSlide")
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fsAlpha; translationY = fsOffset.toPx() },
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("全屏编辑", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showFullscreenEdit = false }) {
                            Text("完成")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = editContent,
                            onValueChange = { editContent = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 400.dp),
                            label = { Text("内容") },
                            shape = RoundedCornerShape(10.dp),
                            maxLines = Int.MAX_VALUE
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${editContent.length} 字符",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    }
}

/**
 * 自适应单行按钮文字：正常显示时保持默认字号不变，
 * 空间不足时才逐步缩小字号（下限 8sp），保证永不换行、不超出按钮边框。
 */
@Composable
private fun AdaptiveButtonLabel(text: String) {
    val style = MaterialTheme.typography.labelLarge
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = style.copy(fontSize = fontSize),
        onTextLayout = { result ->
            // 溢出时逐步缩小字号，直到能单行放下
            if (result.hasVisualOverflow && fontSize.value > 8f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}