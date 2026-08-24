// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.western

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuDivider
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置素材库（可扩展卡片页）。
 *
 * - 本页是底部导航「素材库」tab 的同级根页面，本身【没有返回键】；
 *   用户从底部 tab 进入/切换，与首页/我的文章/数据并列。
 * - 卡片数据可扩展：目前仅有「西方思想」一项，后续新增库只需往 [BUILT_IN_LIBRARIES] 追加。
 * - 点击卡片 → 进入对应库的 WebView 内容页（[LibraryContentPage]）。
 */
@Composable
fun WesternThoughtScreen(navController: NavController) {
    // 仅展示已启用的内置素材库（设置 → 拓展功能 中勾选）
    val enabledLibraries by AppPrefs.builtInLibraryKeysFlow.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "素材库",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),  // 单列：每张卡片横跨整行，与其它根页标题/排版一致
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(BUILT_IN_LIBRARIES.filter { it.id in enabledLibraries }) { lib ->
                LibraryCard(lib = lib) {
                    navController.navigate("philo_content/${lib.id}")
                }
            }
        }
    }
}

/**
 * 单个素材库卡片 — Apple 级横向布局：
 * - 左侧 4dp 竖向 accent 色条
 * - 中间：圆形图标 + 标题 + 副标题 + meta 行
 * - 右侧 chevron `›`
 * - 涟漪按下 + 浅色玻璃底
 * 视觉参考 iOS 备忘录 / App Store 编辑精选。
 */
@Composable
private fun LibraryCard(
    lib: BuiltInLibrary,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val accent = Color(lib.accentColor)

    // 毛玻璃底（与顶栏 / 菜单 0.72 一致）
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)

    // 玻璃卡片描边：浅色用半透明白、深色用半透明亮线，统一为一道细发丝边，
    // 不再使用 Material 阴影（阴影在高亮背景下会露出生硬的"外框 + 内方块"双框感）。
    val hairline = if (isDark) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }

    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下时仅做极轻微的底色加深，不再改变外框/阴影，保持"单一干净玻璃卡"观感。
    val pressedBg = bgColor.copy(alpha = (bgAlpha + 0.06f).coerceAtMost(1f))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .border(1.dp, hairline, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = shape,
        color = if (isPressed) pressedBg else bgColor,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ① 左侧 4dp accent 色条
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accent)
            )
            Spacer(Modifier.width(16.dp))  // 图标与左侧色条拉开

            // ② 圆形图标块
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    kind = AppIconKind.Library,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            // ③ 文本列
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = lib.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = lib.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                // meta 行
                val meta = lib.metaLine.ifBlank { "精选内容 · 持续更新" }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 0.4.sp
                )
            }

            Spacer(Modifier.width(6.dp))

            // ④ 右侧 chevron（不要 paddingEnd 让 chevron 贴边，更 Apple）
            AppIcon(
                kind = AppIconKind.ChevronRight,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 14.dp)
            )
        }
    }
}

/**
 * 素材库内容页（WebView 加载该库首页）。
 *
 * - 站内返回：先退回站内上一页，否则退出到素材库卡片页。
 * - 顶栏右侧 ⌃ 更多菜单（GlassDropdownMenu，美观 Apple 风格）：
 *     ▾ 下载 PDF ─ 弹出二选：仅本页  /  全部页
 *     ▾ 导入到 Blancall 背诵列表 ─ 弹出二选：仅本页  /  全部页
 *     ✓ 练习当前页正文（topbar 胶囊按钮，二级菜单也有快捷项）
 * - 练习正文：仅对 [extractContentBodyFromAssets] 抽取的"内容正文"挖空，
 *   标题 / 引用 / 来源注释 / Box 标题 / 章节封面 / 脚注 等辅助内容不会挖空。
 */
@Composable
fun LibraryContentPage(
    navController: NavController,
    libraryId: String
) {
    val lib = BUILT_IN_LIBRARIES.firstOrNull { it.id == libraryId } ?: BUILT_IN_LIBRARIES.first()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 非官方第三方素材免责提示：每个库首次进入弹一次（gaokao / western 各自记忆）
    var showDisclaimer by remember { mutableStateOf(
        (lib.id == "gaokao" && !AppPrefs.isDisclaimerSeen("gaokao")) ||
            (lib.id == "western" && !AppPrefs.isDisclaimerSeen("western"))
    ) }

    if (showDisclaimer) {
        BlancallAlertDialog(
            onDismissRequest = { AppPrefs.markDisclaimerSeen(lib.id); showDisclaimer = false },
            title = { Text("提示") },
            text = {
                Text(
                    when (lib.id) {
                        "gaokao" -> "该内容非 Blancall 官方制作，它提供的一切联系信息都与 Blancall 无关。"
                        else -> "该素材库内容由AI汇总整理，不保证内容完全正确。请勿用作论文引用依据、考试答题模板等。它不可替代深度阅读，亦不可作为权威解读。请务必将其视为对话的起点，而非思考的终点。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { AppPrefs.markDisclaimerSeen(lib.id); showDisclaimer = false }) { Text("知道了") }
            }
        )
    }

    // 一级菜单：⋮ 三点点
    var showMenu by remember { mutableStateOf(false) }
    // 当前人物 ID（由 JS / onPageFinished 更新）
    var currentPerson by remember { mutableStateOf("") }
    // 当前正在浏览的小节 id（如 sec-2），由 JS 滚动监听上报；空串=整篇
    var currentSection by remember { mutableStateOf("") }
    // 目录页滚动位置：进入篇目预览前保存，返回时恢复（不回到默认顶部）
    var savedScrollY by rememberSaveable { mutableStateOf(0) }
    // 顶栏「练习」按钮：先弹出练习模式选择选项卡，选定后再导入并进入，不默认任何模式
    var showPracticePicker by remember { mutableStateOf(false) }
    var pendingPracticePerson by remember { mutableStateOf("") }
    var pendingPracticeText by remember { mutableStateOf("") }
    var pendingPracticeLabel by remember { mutableStateOf("") }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val accent = Color(lib.accentColor)

    val onBack: () -> Unit = {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else navController.popBackStack()
    }
    // 预测性返回手势：只用 PredictiveBackHandler（Android<13 自动退化为普通返回回调），
    // 不加普通 BackHandler——否则会压制系统侧滑返回的跟手动画。
    // 有站内历史则 WebView 站内返回，否则退出到素材库卡片页；手势取消则回弹不离开。
    PredictiveBackHandler { progressFlow ->
        try {
            progressFlow.collect { }
            onBack()
        } catch (_: CancellationException) {
            // 手势取消 → 保持当前页
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                text = lib.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 练习正文按钮（仅在进入具体人物页后显示，index / concept-map 页不显示）
            if (currentPerson.isNotEmpty() && currentPerson != "index" && currentPerson != "concept-map") {
                val secNum = currentSection.removePrefix("sec-")
                val practiceLabel = if (currentSection.isNotBlank()) "练习 · 第${secNum}节" else "练习 · 全文"
                Surface(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable {
                            // 练习「当前正在看的那一页」——有章节则只取该节，否则取整篇；
                            // 先弹出模式选择选项卡，用户选定后再导入并进入对应练习（不默认模式）。
                            val target = resolvePracticeTarget(context, currentPerson, currentSection)
                            val text = extractContentBodyFromAssets(
                                context,
                                target.assetPath,
                                if (target.isSection) currentSection else ""
                            )
                            if (text.isNotBlank()) {
                                pendingPracticePerson = currentPerson
                                pendingPracticeText = text
                                pendingPracticeLabel = target.scopeLabel
                                showPracticePicker = true
                            }
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = accent.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = practiceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // 三点点菜单按钮 + 一级 GlassDropdownMenu（与 PracticeScreen 顶栏 ⋮ 完全一致）
            Box {
                IconButton(onClick = { showMenu = true }) {
                    M3Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                GlassDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    // 分组标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "操作",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            letterSpacing = 1.5.sp
                        )
                        if (currentPerson.isNotEmpty() && currentPerson != "index") {
                            Text(
                                text = currentPerson,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    GlassMenuDivider()

                    // 仅在进入了具体人物页时显示操作（仅西方思想库有该入口）
                    if (currentPerson.isNotEmpty() && currentPerson != "index" && currentPerson != "concept-map") {
                        // === 下载 PDF（直接下载当前人物，无二级菜单） ===
                        GlassMenuItem(
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.OpenInFull,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("下载 PDF", fontWeight = FontWeight.Medium) },
                            onClick = {
                                showMenu = false
                                if (currentPerson.isNotBlank()) {
                                    openAssetPdf(context, "file:///android_asset/philo/$currentPerson.pdf")
                                }
                            }
                        )
                        // === 全文导入到 Blancall 背诵列表（导入后提示已导入，不进入练习） ===
                        GlassMenuItem(
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.Check,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("全文导入背诵列表", fontWeight = FontWeight.Medium) },
                            onClick = {
                                showMenu = false
                                if (currentPerson.isNotBlank()) {
                                    val text = extractContentBodyFromAssets(context, "philo/${currentPerson}.html")
                                    if (text.isNotBlank()) {
                                        scope.launch {
                                            val articleId = importPhiloToBlancall(
                                                context, currentPerson, text, currentPerson
                                            )
                                            if (articleId > 0) {
                                                Toast.makeText(context, "已导入", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        GlassMenuDivider()
                        // （练习当前页的功能已并入顶栏「练习 · 第X节」按钮，菜单不再重复放，避免同一功能多入口）
                    } else {
                        // index / concept-map 页提示：按库类型给对应的引导文案
                        val guideText = if (lib.id == "gaokao") "请进入某一篇文章" else "请进入某位思想家素材页"
                        GlassMenuItem(
                            leadingIcon = {
                                AppIcon(
                                    kind = AppIconKind.Library,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    guideText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = { showMenu = false }
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // ============== 二级菜单（"本页" / "全部页" 二选一） ==============
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                        }
                        // 记录滚动位置：返回本页时恢复，避免目录回到顶部
                        setOnScrollChangeListener { _, _, scrollY, _, _ -> savedScrollY = scrollY }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val uri = Uri.parse(url)

                                // philomenu: scheme — 三点点菜单交互（post 到主线程修改 Compose state）
                                if (uri.scheme == "philomenu") {
                                    when (uri.host) {
                                        "update_person" -> {
                                            val person = uri.getQueryParameter("person") ?: ""
                                            // section 可选：当前正在阅读的小节（如 sec-2）。
                                            // 仅当 person 一致时才更新 section，避免不同页串味。
                                            val section = uri.getQueryParameter("section") ?: ""
                                            mainHandler.post {
                                                if (person.isNotBlank()) {
                                                    currentPerson = person
                                                    currentSection = section
                                                } else if (section.isNotBlank()) {
                                                    currentSection = section
                                                }
                                            }
                                        }
                                        "download_pdf", "import_blancall" -> {
                                            mainHandler.post { showMenu = true }
                                        }
                                    }
                                    return true
                                }

                                // PDF 文件：改为在 app 内预览（PdfPreviewScreen 渲染每一页，不再跳外部查看器）
                                if (url.endsWith(".pdf", ignoreCase = true)) {
                                    val assetPath = url.removePrefix("file:///android_asset/")
                                    navController.navigate(
                                        "pdf_preview?asset=${Uri.encode(assetPath)}" +
                                            "&title=${Uri.encode(currentPerson)}"
                                    )
                                    return true
                                }
                                return false
                            }

                            // onPageFinished：在 WebView 内跳转到人物页时，提取当前页文件名以更新 currentPerson
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url ?: return
                                // 返回本页时恢复目录滚动位置（从篇目预览返回后仍在原处）
                                if (savedScrollY > 0) view?.scrollTo(0, savedScrollY)
                                // 从 URL 中提取文件名（如 weber.html / arendt.html）
                                val fileName = url.substringAfterLast("/").substringBefore(".html")
                                // 仅「具体人物页」才算进入可操作页；index（库首页）与
                                // concept-map（思维导图）都不应出现下载/导入/练习入口。
                                if (fileName.isNotBlank() && fileName != "index" && fileName != "concept-map") {
                                    mainHandler.post {
                                        currentPerson = fileName
                                        // 切到新人物时清空当前小节，避免沿用上一人物的 section
                                        currentSection = ""
                                    }
                                }
                            }
                        }
                        loadUrl("file:///android_asset/${lib.assetPath}")
                    }.also { webView = it }
                },
                update = {}
            )

            // 二级菜单已移除：下载 PDF / 全文导入背诵列表 直接在一级菜单执行
        }
    }

    // 顶栏「练习」：先弹出练习模式选择选项卡，选定后再导入并进入对应练习
    AdaptiveModePicker(
        visible = showPracticePicker,
        anchorRect = null,
        onDismiss = { showPracticePicker = false },
        onModeSelected = { mode ->
            showPracticePicker = false
            if (pendingPracticeText.isNotBlank()) {
                scope.launch {
                    val articleId = importPhiloToBlancall(
                        context,
                        pendingPracticePerson,
                        pendingPracticeText,
                        pendingPracticeLabel
                    )
                    if (articleId > 0) {
                        navController.navigate("practice/${articleId}?mode=${mode.name}")
                    }
                }
            }
            pendingPracticeText = ""
        }
    )
}

/**
 * 练习 / 导入目标解析结果。
 * - [assetPath]：要抽取正文的 HTML 路径（可能是整篇人物，也可能是某一 sec-* 小节）。
 * - [scopeLabel]：展示用标签，如 "韦伯 · 第2节" 或 "韦伯 · 全文"。
 * - [isSection]：本次是否只取单节（用于标题后缀，避免多篇文章重名覆盖）。
 */
private data class PracticeTarget(
    val assetPath: String,
    val scopeLabel: String,
    val isSection: Boolean
)

/**
 * 根据当前人物 + 当前浏览小节，解析本次「练习 / 导入」应取的内容范围。
 * - 用户在某一小节停留（currentSection 非空且合法）→ 只取该节（练习当前正文）。
 * - 否则 → 取整篇人物（导入全文）。
 */
private fun resolvePracticeTarget(
    context: Context,
    personId: String,
    sectionId: String
): PracticeTarget {
    val basePath = "philo/${personId}.html"
    val validSection = if (sectionId.isNotBlank() && sectionId.startsWith("sec-")) {
        // 校验该小节确实存在于 HTML 中，避免 JS 上报脏值导致抽空
        val guard = Regex("""id="${Regex.escape(sectionId)}"""")
        try {
            val html = context.assets.open(basePath).bufferedReader().use { it.readText() }
            guard.containsMatchIn(html)
        } catch (_: Exception) {
            false
        }
    } else {
        false
    }
    return if (validSection) {
        // sec-N → 第N节（用于背诵列表标题，便于区分整篇与单节导入）
        val secNum = sectionId.removePrefix("sec-")
        PracticeTarget(
            assetPath = basePath,
            scopeLabel = "${personId} · 第${secNum}节",
            isSection = true
        )
    } else {
        PracticeTarget(
            assetPath = basePath,
            scopeLabel = personId,
            isSection = false
        )
    }
}

/**
 * 从 assets/philo/xxx.html 提取"内容正文"纯文本。
 *
 * @param sectionId 可选。传入某个小节 id（如 "sec-2"）时，只提取该小节内的正文；
 *                  传空串则提取整篇人物的全部正文。
 *
 * 只挖真正的【正文】—— `<section class="section">` 内的 `<p>` 与 `<li>`。
 * 排除（不挖）：
 *   - 封面（.section.cover / .cover h1/subtitle）
 *   - 章节标题（h2.sec-head / h3 / h4）
 *   - 目录列表（.toc-list）
 *   - 来源注释段落（.src-note）
 *   - Box 标题（.box-title）
 *   - 引用 cite（blockquote cite / cite）
 *   - 章节脚注（.footer-note / .col-title / dt / dd 中的 label）
 *
 * 这是用户明确要求"只挖正文，不挖标题、引用等辅助内容"。
 */
private fun extractContentBodyFromAssets(
    context: Context,
    assetPath: String,
    sectionId: String = ""
): String {
    return try {
        val html = context.assets.open(assetPath).bufferedReader().use { it.readText() }

        // 若指定了小节，先裁切到该小节：保留 `<section id="sec-N">...</section>` 片段。
        val scoped = if (sectionId.isNotBlank()) {
            val re = Regex("""<section[^>]*id="${Regex.escape(sectionId)}"[\s\S]*?</section>""")
            re.find(html)?.value ?: html
        } else {
            html
        }

        // 1) 先剔除所有"辅助内容区"：把这些区段的 HTML 替换成占位符，避免误抓
        val masked = scoped
            .replace(Regex("""<section[^>]*class="[^"]*\bsection-cover\b[^"]*"[\s\S]*?</section>"""), "")
            .replace(Regex("""<section[^>]*id="sec-contents"[\s\S]*?</section>"""), "")
            .replace(Regex("""<aside[^>]*class="[^"]*\btoc\b[^"]*"[\s\S]*?</aside>"""), "")
            // .box-title 是 box 内的小标题，挖空会破坏 box 标签，放在内容里提取后过滤掉
            // .src-note / .footer-note 是来源/脚注，整体移除
            .replace(Regex("""<p[^>]*class="[^"]*\bsrc-note\b[^"]*"[\s\S]*?</p>"""), "")
            .replace(Regex("""<div[^>]*class="[^"]*\bfooter-note\b[^"]*"[\s\S]*?</div>"""), "")
            .replace(Regex("""<blockquote[^>]*>[\s\S]*?</blockquote>"""), "")

        // 2) 在剩余 HTML 里抓所有 <p>/<li> 段落
        val raw = Regex("""<(?:p|li)[^>]*>([\s\S]*?)</(?:p|li)>""")
            .findAll(masked)
            .map { it.groupValues[1] }
            .map { it.replace(Regex("""<[^>]+>"""), "") }
            .map {
                it.replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .trim()
            }
            // 3) 过滤：必须有实际中文正文（去掉"速览卡"标签、空标签等）
            .filter { para -> para.length >= 12 && hasChineseContent(para) }
            .toList()

        raw.joinToString("\n")
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

/** 判断字符串是否包含"真正的中文正文"——避开速览卡标签、人物定位简介等元信息。 */
private fun hasChineseContent(s: String): Boolean {
    // 至少 3 个汉字 + 含的/在/是/了/有/和 等中文常用字（确保不是英文界面标签）
    val cnCount = s.count { c -> c.code in 0x4E00..0x9FFF }
    if (cnCount < 6) return false
    return true
}

/**
 * 将素材库人物页正文导入为 Article。
 * 必须在协程作用域中调用（click handler → rememberCoroutineScope）。
 *
 * @param filePersonId 用于读取 HTML 的人物文件名基（如 "weber"），与 section 无关。
 * @param articleTitle 写入 Article 的标题（可带小节后缀，如 "韦伯 · 第2节"），
 *                      便于在背诵列表里区分整篇与单节导入。
 */
private suspend fun importPhiloToBlancall(
    context: Context,
    filePersonId: String,
    content: String,
    articleTitle: String
): Long = withContext(Dispatchers.IO) {
    try {
        // 读取人物标题（从 HTML <title>，取人名部分）
        val html = context.assets.open("philo/${filePersonId}.html").bufferedReader()
            .use { it.readText() }
        val personName = Regex("""<title>([^<]+)""").find(html)?.groupValues?.getOrNull(1)
            ?.substringBefore("·")
            ?.trim()
            ?: filePersonId.replaceFirstChar { it.uppercaseChar() }

        // 若调用方已传带后缀的标题（如含"· 第N节"），则直接用；否则用纯人名。
        val title = if (articleTitle.isNotBlank() && articleTitle != filePersonId) {
            articleTitle
        } else {
            personName
        }

        val repo = ArticleRepository.getInstance(
            context.filesDir.resolve("articles.json").absolutePath
        )
        val article = Article(title = title, content = content)
        repo.insert(article)
    } catch (e: Exception) {
        e.printStackTrace()
        -1L
    }
}

/**
 * 把 assets/philo/xxx.pdf 拷贝到缓存目录，经 FileProvider 授权后交给系统 PDF 查看器打开。
 */
private fun openAssetPdf(context: Context, assetUrl: String) {
    val assetPath = assetUrl.removePrefix("file:///android_asset/")
    val fileName = assetPath.substringAfterLast("/")
    if (fileName.isBlank()) return
    val outFile = File(context.cacheDir, "philo/$fileName")
    outFile.parentFile?.mkdirs()
    try {
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * 素材库注册表（可扩展）：新增库只需在此追加一项，并放入对应 assets 目录。
 */
data class BuiltInLibrary(
    val id: String,
    val title: String,
    val subtitle: String,
    /** assets 下首页相对路径，如 "philo/index.html" */
    val assetPath: String,
    val accentColor: Long,
    /** 卡片底部 meta 行，如 "20 位思想家 · 60+ 核心概念 · 11.6 万字" */
    val metaLine: String = "",
    /** meta 行右侧小标签（可选），如 "高考素材" */
    val metaTag: String = "高考素材"
)

private val BUILT_IN_LIBRARIES = listOf(
    BuiltInLibrary(
        id = "western",
        title = "西方思想",
        subtitle = "现代西方思想史 · 20 位思想家高考素材",
        assetPath = "philo/index.html",
        accentColor = 0xFF1F3A5F,
        metaLine = "20 位思想家 · 60+ 核心概念 · 11.6 万字",
        metaTag = "高考素材"
    ),
    BuiltInLibrary(
        id = "gaokao",
        title = "高考必背篇目",
        subtitle = "高考语文必背 60 篇",
        assetPath = "gaokao/index.html",
        accentColor = 0xFF7A3B2E,
        metaLine = "60 篇 · 文言文 20 · 诗词曲 40",
        metaTag = "高考素材"
    )
)
