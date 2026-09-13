// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.import

import android.net.Uri
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import com.ilyskyo.blancall.algorithm.applyFirstLineIndent
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ilyskyo.blancall.util.FileTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

/** 大文件阈值（字符数），超过则提示拆分以避免挖空时卡顿 */
private const val LARGE_FILE_THRESHOLD = 5000

/** 内容硬上限（字符数）：超过此长度拒绝接受，避免 TextField 渲染卡顿/OOM */
private const val MAX_CONTENT_LENGTH = 50000

/** 标题建议/截断长度 */
private const val TITLE_TRUNCATE_LENGTH = 60

@Composable
fun ImportScreen(navController: NavController) {
    val articleViewModel: ArticleViewModel = viewModel()
    val context = LocalContext.current

    var title by remember { mutableStateOf("") }
    // 作者（选填）：与标题一并保存，列表/详情页展示
    var author by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var fileLoaded by remember { mutableStateOf(false) }
    var useFileImport by remember { mutableStateOf(false) }
    // PDF 导入预览：记录用户在该页导入的 PDF，用于「预览 PDF」
    var lastPdfUri by remember { mutableStateOf<Uri?>(null) }
    var hasPdf by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 文档含图片提示：仅文件导入且源文档含图片/嵌入对象时显示
    var imageNotice by remember { mutableStateOf<String?>(null) }
    var showFullscreenInput by remember { mutableStateOf(false) }
    var showLargeFileWarning by remember { mutableStateOf(false) }
    // 保存流程的「进入挖空编辑」意图：标题建议 / 大文件预警弹窗会打断点击流程，
    // 确认后必须沿用最初按钮的意图（否则点「保存并挖空」经弹窗确认会退化为仅保存）
    var pendingOpenClozeEditor by remember { mutableStateOf(false) }
    var titleSuggestionDismissed by remember { mutableStateOf(false) }
    var showTitleSuggestionDialog by remember { mutableStateOf(false) }
    var titleHighlight by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleFocusRequester = remember { FocusRequester() }
    val authorFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    // 当前导入来源的段落是否允许自动首行缩进：粘贴/纯文本=true，PDF/Word 等文档=false。
    // 文件加载时按文件名判定；粘贴模式下恒为 true。
    var lastFileAutoIndent by remember { mutableStateOf(true) }

    /**
     * 统一的保存并退出流程：写入文章 → 通知上一页 → 返回。
     * 对允许缩进的来源（且全局缩进开关开启）在入库前给段落首行补两格缩进，
     * 使阅读与背诵显示一致；PDF/Word 等来源保持原文不动。
     * 包裹 try/catch/finally，失败时回写错误信息并复位 isSaving，避免卡在保存中。
     */
    suspend fun saveAndExit(
        saveTitle: String,
        saveContent: String,
        saveAuthor: String,
        /** true = 保存后直接进入该文章的「自定义挖空」编辑（用挖空页替换导入页，返回时回来源页） */
        openClozeEditor: Boolean = false
    ) {
        val autoIndent = if (useFileImport) lastFileAutoIndent else true
        val contentOut =
            if (autoIndent && AppPrefs.autoIndentEnabled) applyFirstLineIndent(saveContent) else saveContent
        try {
            val articleId = articleViewModel.insertArticleBlocking(saveTitle, contentOut, autoIndent, saveAuthor)
            showLargeFileWarning = false
            errorMessage = null
            navController.previousBackStackEntry?.savedStateHandle?.apply {
                set("articleSaved", true)
                set("savedArticleId", articleId)
            }
            if (openClozeEditor) {
                // 用挖空编辑页替换导入页：从挖空页返回时直接回到来源页（导入已完成，不再回到本页）
                navController.navigate("custom_cloze_edit/$articleId") {
                    popUpTo("import") { inclusive = true }
                }
            } else {
                navController.popBackStack()
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "保存失败"
        } finally {
            isSaving = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        FileTextExtractor.extractTextWithInfo(context, it)
                    }
                    val text = result.text
                    if (text.length > MAX_CONTENT_LENGTH) {
                        Toast.makeText(context, "文本过长（${text.length}字符），已截断至 $MAX_CONTENT_LENGTH 字符，建议拆分导入", Toast.LENGTH_LONG).show()
                        content = text.take(MAX_CONTENT_LENGTH)
                    } else {
                        content = text
                    }
                    fileLoaded = true
                    imageNotice = if (result.hasImages) "文档中的图片无法导入，已仅保留文字内容" else null
                    if (title.isBlank()) {
                        // getFileName 内部走 ContentResolver.query，需在 IO 线程执行
                        val fileName = withContext(Dispatchers.IO) { FileTextExtractor.getFileName(context, it) }
                        if (fileName != null) {
                            title = fileName.substringBeforeLast(".")
                        }
                    }
                    // 记录是否为 PDF —— 导入 PDF 时提供「预览 PDF」
                    val pickedName = withContext(Dispatchers.IO) { FileTextExtractor.getFileName(context, it) }
                    // 依据文件名判断来源是否允许自动首行缩进（PDF/Word 等文档保持原文不动）
                    lastFileAutoIndent = isAutoIndentFile(pickedName)
                    if (pickedName?.lowercase()?.endsWith(".pdf") == true) {
                        lastPdfUri = it
                        hasPdf = true
                    } else {
                        hasPdf = false
                    }
                    errorMessage = null
                } catch (e: Exception) {
                    errorMessage = "文件读取失败: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
        // ── 标题（固定，不参与滚动）──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = { navController.popBackStack() })
            Spacer(Modifier.width(12.dp))
            Text(
                text = "导入文本",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // 可滚动内容区域
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !useFileImport,
                onClick = { useFileImport = false; fileLoaded = false; lastFileAutoIndent = true; imageNotice = null },
                label = { Text("粘贴文本") },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            FilterChip(
                selected = useFileImport,
                onClick = { useFileImport = true; fileLoaded = false },
                label = { Text("文件导入") },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        // 导入 PDF 时提供「预览 PDF」：在 app 内打开该 PDF 原页
        if (hasPdf && lastPdfUri != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val uri = lastPdfUri ?: return@TextButton
                    scope.launch {
                        val f = withContext(Dispatchers.IO) {
                            copyPdfUriToCache(context, uri)
                        }
                        if (f != null) {
                            navController.navigate(
                                "pdf_preview?asset=${Uri.encode(f.absolutePath)}" +
                                    "&title=${Uri.encode(title.ifBlank { "PDF 预览" })}"
                            )
                        }
                    }
                }) {
                    Text("预览 PDF", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 标题框高亮动画：缓缓亮起 → 缓缓消退
        val highlightBorderColor by animateColorAsState(
            targetValue = if (titleHighlight) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outline,
            animationSpec = tween(600)
        )
        val highlightFocusedBorderColor by animateColorAsState(
            targetValue = if (titleHighlight) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.primary,
            animationSpec = tween(600)
        )
        val highlightContainerColor by animateColorAsState(
            targetValue = if (titleHighlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                          else Color.Transparent,
            animationSpec = tween(600)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                // 自适应单行标签：窄屏也永远不折行（折行会把输入框撞高，导致与右侧作者框不等高）
                label = { AdaptiveTextFieldLabel("文章标题") },
                modifier = Modifier
                    .weight(1.6f)
                    .focusRequester(titleFocusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { authorFocusRequester.requestFocus() }),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = highlightFocusedBorderColor,
                    unfocusedBorderColor = highlightBorderColor,
                    focusedContainerColor = highlightContainerColor,
                    unfocusedContainerColor = highlightContainerColor
                )
            )
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                // 自适应单行标签：「作者（选填）」在部分窄屏上放不下会折行、把输入框撞高，导致两框不等高
                label = { AdaptiveTextFieldLabel("作者（选填）") },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(authorFocusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                    // 粘贴文本模式下跳到内容框；文件导入模式下无内容框，忽略
                    if (!useFileImport) contentFocusRequester.requestFocus()
                }),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (useFileImport) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isLoading) "正在解析文件..." else "选择文件")
            }

            if (fileLoaded && content.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text = "已加载: ${content.length} 字符",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            imageNotice?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(MAX_CONTENT_LENGTH) },
                label = { Text("粘贴文本内容") },
                trailingIcon = {
                    IconButton(
                        onClick = { showFullscreenInput = true },
                        modifier = Modifier.semantics { contentDescription = "全屏输入" }
                    ) {
                        AppIcon(
                            kind = AppIconKind.OpenInFull,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(contentFocusRequester)
                    .heightIn(min = 200.dp),
                maxLines = 20
            )
        }

        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

    }

        Spacer(modifier = Modifier.height(12.dp))

        // 底部固定按钮：两个按钮共用同一套校验（标题建议/空内容/空标题/大文件警告两段式）
        fun handleSaveClick(openClozeEditor: Boolean) {
            when {
                title.isBlank() && content.isNotBlank() -> {
                    // 记录意图：确认「使用第一行」后按同一意图保存（仅保存 / 保存并挖空）
                    pendingOpenClozeEditor = openClozeEditor
                    showTitleSuggestionDialog = true
                    titleSuggestionDismissed = false
                    errorMessage = null
                }
                content.isBlank() -> errorMessage = "请输入内容"
                title.isBlank() -> errorMessage = "请输入文章标题"
                content.length > LARGE_FILE_THRESHOLD && !showLargeFileWarning -> {
                    // 记录意图：确认「仍然保存」后按同一意图保存
                    pendingOpenClozeEditor = openClozeEditor
                    showLargeFileWarning = true
                    errorMessage = null
                }
                else -> {
                    if (isSaving) return
                    isSaving = true
                    scope.launch {
                        saveAndExit(title.trim(), content.trim(), author.trim(), openClozeEditor)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { handleSaveClick(openClozeEditor = false) },
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("仅保存")
            }
            OutlinedButton(
                onClick = { handleSaveClick(openClozeEditor = true) },
                modifier = Modifier.weight(1f),
                enabled = !isSaving,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("保存并挖空")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
    }

    // 标题高亮：聚焦标题输入框 + 1秒后恢复
    if (titleHighlight) {
        LaunchedEffect(Unit) {
            titleFocusRequester.requestFocus()
            delay(1000)
            titleHighlight = false
        }
    }

    // 标题建议弹窗
    if (showTitleSuggestionDialog) {
        val firstLine = content.lines().firstOrNull()?.trim()?.take(TITLE_TRUNCATE_LENGTH) ?: ""
        BlancallAlertDialog(
            onDismissRequest = {
                showTitleSuggestionDialog = false
                titleSuggestionDismissed = true
            },
            shape = RoundedCornerShape(28.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 标题图标
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📝", fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "提取标题",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        "是否采用粘贴内容的第一行作为标题？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    // 第一行预览：高亮容器内展示
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            "「$firstLine${if (firstLine.length >= TITLE_TRUNCATE_LENGTH) "…" else ""}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        title = firstLine
                        showTitleSuggestionDialog = false
                        titleSuggestionDismissed = true
                        isSaving = true
                        scope.launch { saveAndExit(firstLine, content.trim(), author.trim(), pendingOpenClozeEditor) }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("使用第一行")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTitleSuggestionDialog = false
                    titleSuggestionDismissed = true
                    titleHighlight = true
                }) {
                    Text("在此输入标题")
                }
            }
        )
    }

    // 大文件预警对话框
    if (showLargeFileWarning) {
        BlancallAlertDialog(
            onDismissRequest = { showLargeFileWarning = false },
            title = { Text("文本较长") },
            text = {
                Text("当前文本共 ${content.length} 字符，篇幅较长。\n在生成挖空练习时，较长的文本可能导致卡顿甚至闪退。\n\n建议将文本拆分后分别导入，或选择较短的文段。\n\n是否仍要保存？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLargeFileWarning = false
                        isSaving = true
                        scope.launch { saveAndExit(title.trim(), content.trim(), author.trim(), pendingOpenClozeEditor) }
                    }
                ) {
                    Text("仍然保存")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLargeFileWarning = false }) {
                    Text("返回修改")
                }
            }
        )
    }

    // 全屏输入对话框
    if (showFullscreenInput) {
        Dialog(
            onDismissRequest = { showFullscreenInput = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            // 入场动画：淡入 + 轻微上移（不整屏 scale，避免露出背景缝隙）。
            // 注：外层是条件式 if(showFullscreenInput)，dismiss 时 Dialog 立即移出组合，退场不播放。
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
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("全屏输入", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showFullscreenInput = false }) {
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
                            value = content,
                            onValueChange = { content = it.take(MAX_CONTENT_LENGTH) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 400.dp),
                            placeholder = { Text("在此粘贴或输入文本...") },
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
        }
    }
}

/** PDF/Word 等富文档扩展名（导入后保持原文，不做首行缩进） */
private val NO_AUTO_INDENT_EXT = setOf("pdf", "doc", "docx", "epub", "rtf", "html", "htm")

/** 根据文件名判断导入来源是否允许自动首行缩进（仅 txt/纯文本/未知 允许） */
private fun isAutoIndentFile(fileName: String?): Boolean {
    if (fileName == null) return true
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext !in NO_AUTO_INDENT_EXT
}

/** 把用户选择的 PDF（content URI）复制到缓存目录，供 PdfPreviewScreen 在 app 内预览 */
private fun copyPdfUriToCache(context: android.content.Context, uri: Uri): File? {
    return try {
        val name = runCatching { FileTextExtractor.getFileName(context, uri) }.getOrNull()
            ?.substringBeforeLast(".")
            ?.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
            ?: "pdf"
        val out = File(context.cacheDir, "pdfview/$name.pdf")
        out.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os -> ins.copyTo(os) }
        }
        out
    } catch (_: Exception) { null }
}

/**
 * 自适应单行输入框标签：**基准样式与 TextField 原生 label 完全一致**（bodyLarge：主题楷体/宋体 16sp），
 * 放得下时保持原样不变；仅在窄屏真的放不下时逐步缩小字号（下限 10sp），保证**永不折行**。
 *
 * 背景：Title / Author 两个输入框同排等高，若 label（如「作者（选填）」）在窄屏上
 * 换行会把该框撑高、与另一个框高度不一致（用户实机反馈）。
 * 注意：基准不能改小——否则放得下的机型上字号/字体会被无端改变（楷体→黑体、变大变小）。
 */
@Composable
private fun AdaptiveTextFieldLabel(text: String) {
    val base = MaterialTheme.typography.bodyLarge
    var fontSize by remember(text) { mutableStateOf(base.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = base.copy(fontSize = fontSize),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > 10f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}
