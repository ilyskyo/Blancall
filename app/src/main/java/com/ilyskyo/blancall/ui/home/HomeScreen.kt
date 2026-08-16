// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.EbbinghausScheduler
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.appIconKindFromKey
import com.ilyskyo.blancall.ui.common.iconKeyFromKind
import com.ilyskyo.blancall.ui.common.listItemEnter
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.RevealPhase
import com.ilyskyo.blancall.ui.common.TouchRevealHost
import com.ilyskyo.blancall.ui.common.rememberTouchRevealState
import com.ilyskyo.blancall.ui.common.toTouchAnchor
import com.ilyskyo.blancall.ui.ai.AiHistoryScreen
import com.ilyskyo.blancall.ui.list.ListScreen
import com.ilyskyo.blancall.ui.statistics.OverviewScreen
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.runtime.produceState

/** 首页"继续练习"卡片用：从 practice_state_*.json 解析出的未完成进度 */
data class ResumableItem(
    val articleId: Long,
    val mode: String,
    val answered: Int,
    val total: Int,
    val lastTime: Long
)

@Composable
fun HomeScreen(
    navController: NavController,
    // 底部导航模式：隐藏左下角三个入口按钮（入口已迁移到底部导航栏）
    hideEntryButtons: Boolean = false
) {
    val articleViewModel: ArticleViewModel = viewModel()
    // AI 功能开关（左下角 AI 历史入口联动）
    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
    val articles by articleViewModel.articles.collectAsState()
    val context = LocalContext.current
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val allRecords by recordRepo.records.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 首页隐藏的文章（长按"从首页删除"，仅从首页移除，不从文章列表删除）
    val hiddenArticleIds by AppPrefs.hiddenArticleIdsFlow.collectAsState()
    // 按更新时间倒序排列（最近操作过的在前），过滤掉首页隐藏的文章
    val recentArticles = remember(articles, hiddenArticleIds) {
        articles.filter { it.id !in hiddenArticleIds }.sortedByDescending { it.updatedAt }
    }

    // ── 复习检测：FSRS 自适应调度（无 FSRS 状态的文章回退模板间隔）──
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val fsrsStates = remember { fsrsStore.allStates() }
    val dueArticles = remember(articles, allRecords, fsrsStates) {
        // 先一次性按文章分组建索引，避免对每篇文章重复 filter 全表（O(文章×记录) → O(文章+记录)）
        val recordsByArticle = allRecords.groupBy { it.articleId }
        articles.filter { article ->
            val records = recordsByArticle[article.id].orEmpty()
            EbbinghausScheduler.getReviewStatus(fsrsStates[article.id], records) is EbbinghausScheduler.ReviewStatus.DUE
        }
    }

    // ── 继续练习：扫描未完成的练习进度文件（返回首页时重新扫描） ──
    val resumables by produceState<List<ResumableItem>>(emptyList(), articles) {
        if (articles.isEmpty()) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) {
            context.filesDir.listFiles { f ->
                f.name.startsWith("practice_state_") && f.name.endsWith(".json")
            }?.mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    if (json.optString("status") != "IN_PROGRESS") return@mapNotNull null
                    val total = json.optInt("totalBlanks", 0)
                    val answered = json.optInt("answeredCount", 0)
                    if (total - answered <= 0) return@mapNotNull null
                    ResumableItem(
                        articleId = json.optLong("articleId", -1L),
                        mode = json.optString("mode", ""),
                        answered = answered,
                        total = total,
                        lastTime = json.optLong("lastPracticeTime", 0L)
                    )
                } catch (_: Exception) { null }
            }?.sortedByDescending { it.lastTime } ?: emptyList()
        }
    }

    // 从 ImportScreen 保存成功后返回时接收信号
    var showSaveSuccessDialog by remember { mutableStateOf(false) }
    var savedArticleId by remember { mutableStateOf(0L) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val homeIconKey by AppPrefs.homeIconKeyFlow.collectAsState()
    val showHomeEmoji by AppPrefs.showHomeEmojiFlow.collectAsState()
    var showSubtitleEditor by remember { mutableStateOf(false) }
    val subtitle by AppPrefs.subtitleFlow.collectAsState()
    // 模式选择弹窗
    var showModePicker by remember { mutableStateOf(false) }
    var pendingPracticeArticleId by remember { mutableStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    var aiFabRect by remember { mutableStateOf(Rect.Zero) }
    // 触点展开转场：左下角圆形按钮 → 目标页面（触点为源的反向展开动画）
    val revealState = rememberTouchRevealState()
    // 展开状态跨导航保存：进入练习/阅读页返回后仍保持目标页展开（回全局统计/我的文章）
    var revealTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var savedRevealPhase by rememberSaveable { mutableStateOf("Idle") }
    var savedRevealAx by rememberSaveable { mutableStateOf(0f) }
    var savedRevealAy by rememberSaveable { mutableStateOf(0f) }

    // 返回恢复 + 同步展开状态（合并为单一 effect，恢复先于同步执行，
    // 避免 savedRevealPhase 被当前 Idle 状态覆盖导致恢复失效；
    // restore 修改 phase 会重新触发本 effect，二次执行时 phase 已非 Idle，不会重复恢复）
    // 底部导航模式：不恢复触点展开状态（入口按钮已隐藏，无触点可用，恢复会导致首页被目标页覆盖“卡没”）
    LaunchedEffect(revealState.phase, revealState.anchor) {
        if (!hideEntryButtons &&
            revealState.phase == RevealPhase.Idle &&
            savedRevealPhase == RevealPhase.Expanded.name && revealTarget != null
        ) {
            revealState.restore(RevealPhase.Expanded, savedRevealAx, savedRevealAy)
        }
        savedRevealPhase = revealState.phase.name
        revealState.anchor?.let {
            savedRevealAx = it.centerX
            savedRevealAy = it.centerY
        }
    }
    var listFabRect by remember { mutableStateOf(Rect.Zero) }
    var statsFabRect by remember { mutableStateOf(Rect.Zero) }
    // 长按文章卡片 → "从首页删除"选项卡
    var hideFromHomeTarget by remember { mutableStateOf<Article?>(null) }
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        val saved = savedStateHandle?.get<Boolean>("articleSaved") ?: false
        if (saved) {
            savedStateHandle?.remove<Boolean>("articleSaved")
            savedArticleId = savedStateHandle?.get<Long>("savedArticleId") ?: 0L
            savedStateHandle?.remove<Long>("savedArticleId")
            showSaveSuccessDialog = true
        }
    }

    // 保存成功弹窗
    if (showSaveSuccessDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showSaveSuccessDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 成功图标（与提取标题弹窗同规格）
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            kind = AppIconKind.Celebrate,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "保存成功",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "文章已保存，接下来做什么？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    // 主操作：全宽开始练习
                    Button(
                        onClick = {
                            showSaveSuccessDialog = false
                            pendingPracticeArticleId = savedArticleId
                            showModePicker = true
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("开始练习")
                    }
                    Spacer(Modifier.height(10.dp))
                    // 次操作：全宽继续导入
                    OutlinedButton(
                        onClick = {
                            showSaveSuccessDialog = false
                            navController.navigate("import")
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("继续导入")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 图标选择器（精选矢量图标）：复用同款 UI 结构、选中高亮、点击切换、持久化。
    // 移除「自由输入任意 emoji」入口，仅从 AppIconKind 精选集选择（与「铲掉 emoji」目标一致）。
    if (showEmojiPicker) {
        val iconOptions = listOf(
            AppIconKind.Logo, AppIconKind.Celebrate, AppIconKind.Edit, AppIconKind.Inbox,
            AppIconKind.ArrowForward, AppIconKind.OpenInFull, AppIconKind.Check
        )
        BlancallAlertDialog(
            onDismissRequest = { showEmojiPicker = false },
            title = { Text("选择图标") },
            text = {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    iconOptions.forEach { kind ->
                        val selected = kind == appIconKindFromKey(homeIconKey)
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable {
                                    AppPrefs.homeIconKey = iconKeyFromKind(kind)
                                    showEmojiPicker = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(
                                    kind = kind,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showEmojiPicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 副标题编辑
    if (showSubtitleEditor) {
        var editText by remember(subtitle) { mutableStateOf(subtitle) }
        BlancallAlertDialog(
            onDismissRequest = { showSubtitleEditor = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 编辑图标（与提取标题弹窗同规格）
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            kind = AppIconKind.Edit,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "编辑副标题",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { if (it.length <= 30) editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义副标题") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${editText.length}/30",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (editText.length >= 30)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppPrefs.subtitle = editText.ifBlank { subtitle }
                        showSubtitleEditor = false
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleEditor = false }) {
                    Text("取消")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 毛玻璃氛围背景（背景色之上、内容之下）
        AmbientBackground()
        // 内层：首页内容（带安全区 padding），TouchRevealHost 在外层全屏以避免与目标页 statusBarsPadding 双重叠加
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
            Spacer(Modifier.height(20.dp))

            // ── 品牌区：诗意的标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo 图标（可点击换 emoji）：设置中可关闭显示
                    if (showHomeEmoji) {
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .semantics { contentDescription = "应用图标，点击更换" }
                                .clickable { showEmojiPicker = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(
                                    kind = appIconKindFromKey(homeIconKey),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(
                            text = "Blancall",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        // 副标题：单行显示，设备放不下时自适应缩小字号
                        val labelSmallFontSize = MaterialTheme.typography.labelSmall.fontSize
                        var subtitleFontSize by remember(subtitle) {
                            mutableStateOf(labelSmallFontSize)
                        }
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = subtitleFontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp,
                            onTextLayout = { result ->
                                // 溢出时逐步缩小字号（下限 8sp），直到单行放下
                                if (result.hasVisualOverflow && subtitleFontSize.value > 8f) {
                                    subtitleFontSize = (subtitleFontSize.value - 0.5f).sp
                                }
                            },
                            modifier = Modifier.clickable { showSubtitleEditor = true }
                        )
                    }
                }

                GlassButton(
                    onClick = { navController.navigate("import") },
                    modifier = Modifier.height(40.dp)
                ) {
                    Text("添加", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(8.dp))
                GlassButton(
                    onClick = { navController.navigate("settings") },
                    modifier = Modifier
                        .height(40.dp)
                        .semantics { contentDescription = "设置" }
                ) {
                    // 自绘齿轮图标：矢量绘制精确居中，规避文本字形（emoji）偏移问题
                    SettingsGearIcon(color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(18.dp))

            // ── 今日待复习卡片 ──
            if (dueArticles.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "今日待复习（${dueArticles.size}篇）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 全部待复习文章直接列出，无需跳转文章列表
                        dueArticles.forEach { article ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        pendingPracticeArticleId = article.id
                                        showModePicker = true
                                    }
                                    .onGloballyPositioned { coords ->
                                        if (article.id == pendingPracticeArticleId) {
                                            val pos = coords.positionInWindow()
                                            practiceButtonRect = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    article.title,
                                    // 与「继续练习」一致：文章名用楷体（Serif）
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "开始复习 →",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── 继续练习卡片：恢复上次未完成的练习进度 ──
            if (resumables.isNotEmpty()) {
                val articleMap = remember(articles) { articles.associateBy { it.id } }
                // 标题最大宽度限制：不超过屏幕宽度的 2/3，超出以省略号截断
                val maxTitleWidth = (LocalConfiguration.current.screenWidthDp * 2 / 3).dp
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                    ) {
                        Text(
                            "继续练习（${resumables.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.height(2.dp))
                        // 全部未完成练习都展示（首页可滚动，不受篇幅限制）
                        resumables.forEach { item ->
                            val art = articleMap[item.articleId] ?: return@forEach
                            val modeLabel = when (item.mode) {
                                "SENTENCE" -> "句子挖空"
                                "WORD" -> "字词挖空"
                                "REVERSE" -> "反向默写"
                                else -> "练习"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navController.navigate("practice/${item.articleId}?resume=true")
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        art.title,
                                        // 文章名用宋体（Serif），与全局标题风格一致；宽度超限时省略号截断
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = maxTitleWidth)
                                    )
                                    Text(
                                        "$modeLabel · 剩余 ${item.total - item.answered}/${item.total}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f)
                                    )
                                }
                                // 可点击视觉提示：右缘留白避免贴边，轻微下移使视觉居中于两行之间
                                Text(
                                    "继续 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 16.dp)
                                        .offset(y = 2.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── 全局统计入口：用 remember(allRecords) 包裹，避免每次重组重算 ──
            val (totalPractices, totalCorrect, totalBlanks, overallRate) = remember(allRecords) {
                val tp = allRecords.size
                val tc = allRecords.sumOf { it.correctCount }
                val tb = allRecords.sumOf { it.totalBlanks }
                val or = if (tb > 0) tc.toFloat() / tb else 0f
                HomeStats(tp, tc, tb, or)
            }
            // 学习数据卡片关闭状态：以练习次数为 key，做新练习后自动恢复显示
            var statsCardDismissed by remember(totalPractices) { mutableStateOf(false) }
            if (totalPractices > 0 && !statsCardDismissed) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate("overview") },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("学习数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${totalPractices}次练习 · 正确率 ${(overallRate * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                            )
                        }
                        // 固定 64dp 宽 Box + Text 居中，让"查看详情"和"继续"的文字中央在同一对称轴
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .padding(end = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "查看详情",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        // 叉号关闭按钮：点击后本次会话不再显示，左下角统计入口仍可进入
                        IconButton(
                            onClick = { statsCardDismissed = true },
                            modifier = Modifier.semantics { contentDescription = "关闭学习数据卡片" }
                        ) {
                            AppIcon(
                                kind = AppIconKind.Close,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── 最近文章 ──
            if (recentArticles.isEmpty()) {
                // 空状态：居中引导（首页主体为滚动容器，weight 不生效，用固定高度模拟居中，与文章列表页空态同风格）
                Box(
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(
                            kind = AppIconKind.Inbox,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "还没有文章",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点击右上角「添加」导入第一篇",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { navController.navigate("import") },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("导入第一篇文章")
                        }
                    }
                }
            } else {
                Text(
                    "最近文章",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                val isWide = LocalConfiguration.current.screenWidthDp >= 600
                // 页面可滚动：底部预留左下角按钮组高度（两个按钮 + 间距 + 边距），
                // 保证滚动到底部时按钮不遮挡最后一张卡片；文章数按屏幕高度分档 3/4/5
                val screenHeightDp = LocalConfiguration.current.screenHeightDp
                val maxRecentItems = when {
                    screenHeightDp >= 1000 -> 5
                    screenHeightDp >= 820 -> 4
                    else -> 3
                }
                val visibleRecent = recentArticles.take(maxRecentItems)

                if (isWide) {
                    // 两列固定布局（每行 2 张），不可滚动，底部预留按钮组空间
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 120.dp)
                    ) {
                        visibleRecent.chunked(2).forEach { rowArticles ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowArticles.forEach { article ->
                                    val index = visibleRecent.indexOf(article)
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = listItemEnter(index),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        HomeArticleCard(
                                            article = article,
                                            dateFormat = dateFormat,
                                            onClick = { navController.navigate("reader/${article.id}") },
                                            onLongClick = { hideFromHomeTarget = article },
                                            onPractice = {
                                                pendingPracticeArticleId = article.id
                                                showModePicker = true
                                            },
                                            practiceModifier = if (article.id == pendingPracticeArticleId) Modifier.onGloballyPositioned { coords ->
                                                val pos = coords.positionInWindow()
                                                practiceButtonRect = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                                            } else Modifier
                                        )
                                    }
                                }
                            }
                        }
                        if (recentArticles.size > maxRecentItems) {
                            TextButton(
                                onClick = { navController.navigate("list") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("查看全部 (${recentArticles.size} 篇)")
                            }
                        }
                    }
                } else {
                    // 单列固定布局，不可滚动，底部预留按钮组空间
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 120.dp)
                    ) {
                        visibleRecent.forEach { article ->
                            val index = visibleRecent.indexOf(article)
                            AnimatedVisibility(
                                visible = true,
                                enter = listItemEnter(index)
                            ) {
                                HomeArticleCard(
                                    article = article,
                                    dateFormat = dateFormat,
                                    onClick = { navController.navigate("reader/${article.id}") },
                                    onLongClick = { hideFromHomeTarget = article },
                                    onPractice = {
                                        pendingPracticeArticleId = article.id
                                        showModePicker = true
                                    },
                                    practiceModifier = if (article.id == pendingPracticeArticleId) Modifier.onGloballyPositioned { coords ->
                                        val pos = coords.positionInWindow()
                                        practiceButtonRect = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                                    } else Modifier
                                )
                            }
                        }
                        if (recentArticles.size > maxRecentItems) {
                            TextButton(
                                onClick = { navController.navigate("list") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("查看全部 (${recentArticles.size} 篇)")
                            }
                        }
                    }
                }
            }
        }
    }

        // 左下角：入口按钮组（AI 历史 + 我的文章 + 全局统计），均使用触点展开转场。
        // 位置固定：屏幕左下方，比屏幕底部稍上移一点（不与底部贴死）
        // 底部导航模式（hideEntryButtons）：隐藏入口按钮，入口已迁移到底部导航栏
        if (!hideEntryButtons) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // AI 历史对话入口（启用 AI 且开启「保存与 AI 的对话」时才显示）
            val aiHistoryEnabled by AppPrefs.aiHistoryEnabledFlow.collectAsState()
            if (aiEnabled && aiHistoryEnabled) {
                GlassEntryButton(
                    label = "AI",
                    onClick = {
                        // 与文章/数据一致：触点为源的反向展开动画
                        revealTarget = "ai_history"
                        revealState.expand(aiFabRect.toTouchAnchor())
                    },
                    modifier = Modifier
                        .onGloballyPositioned { aiFabRect = it.boundsInWindow() }
                        .semantics { contentDescription = "AI 历史对话" }
                )
            }
            // 玻璃入口按钮：与右上角 GlassButton 同款磨砂质感，文字 + 主色短横线
            GlassEntryButton(
                label = "文章",
                onClick = {
                    // 触点为源的反向展开：点击位置 = 展开起点
                    revealTarget = "list"
                    revealState.expand(listFabRect.toTouchAnchor())
                },
                modifier = Modifier
                    .onGloballyPositioned { listFabRect = it.boundsInWindow() }
                    .semantics { contentDescription = "查看全部文章" }
            )
            GlassEntryButton(
                label = "数据",
                onClick = {
                    revealTarget = "overview"
                    revealState.expand(statsFabRect.toTouchAnchor())
                },
                modifier = Modifier
                    .onGloballyPositioned { statsFabRect = it.boundsInWindow() }
                    .semantics { contentDescription = "全局学习统计" }
            )
        }
        } // hideEntryButtons 条件闭合
        } // 内层首页内容 Box 闭合

        // 触点展开转场宿主：全屏渲染目标页面（目标页自己处理 statusBarsPadding，避免双重叠加）
        TouchRevealHost(
            state = revealState,
            target = {
                when (revealTarget) {
                    // 触点展开方式进入：返回键/系统返回手势均通过 collapse 收起（popBackStack 在栈底无效）
                    "list" -> ListScreen(navController, onBack = { revealState.collapse() })
                    "overview" -> OverviewScreen(navController, onBack = { revealState.collapse() })
                    "ai_history" -> AiHistoryScreen(navController, onBack = { revealState.collapse() })
                }
            }
        )
    }

    // 长按"从首页删除"选项卡
    hideFromHomeTarget?.let { target ->
        BlancallAlertDialog(
            onDismissRequest = { hideFromHomeTarget = null },
            title = { Text("从首页删除") },
            text = {
                Column {
                    Text("「${target.title}」将从首页最近文章中移除。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("可在「我的文章」中继续查看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPrefs.hideArticleFromHome(target.id)
                    hideFromHomeTarget = null
                }) { Text("从首页删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { hideFromHomeTarget = null }) { Text("取消") }
            }
        )
    }

    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect,
        onDismiss = { showModePicker = false },
        onModeSelected = { mode ->
            showModePicker = false
            if (pendingPracticeArticleId > 0) {
                navController.navigate("practice/${pendingPracticeArticleId}?mode=${mode.name}")
            }
        }
    )
}

@Composable
private fun HomeArticleCard(
    article: Article,
    dateFormat: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPractice: () -> Unit,
    practiceModifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            // 收敛表面语言：对齐兄弟 M3 卡片（扁平 + 0.5dp 描边 + elevation=0），
            // 移除原先 1.dp 抬升高光，消除「三种卡片语言混用」。
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${article.content.length} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = article.content.take(50).replace("\n", " "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFormat.format(Date(article.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Button(
                    onClick = onPractice,
                    modifier = practiceModifier.height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("开始练习", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * 玻璃入口按钮（左下角「文章 / 数据」）：与右上角 GlassButton 同款磨砂质感，
 * 半透明底 + 顶部高光 + 细描边 + 大圆角，文字 + 底部主色短横线，克制的品牌感入口。
 */
@Composable
private fun GlassEntryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val highlightColor = if (isDark) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    val bgColor = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA_DARK)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = GLASS_ALPHA_LIGHT)
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(bgColor)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 顶部高光：玻璃上缘反光（matchParentSize 不参与测量，不会撑大按钮）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to highlightColor,
                            0.5f to Color.Transparent
                        )
                    )
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * 自绘设置齿轮图标（Canvas 矢量绘制，遵循主流设计系统规范）：
 * - 描边：2px 基准（18dp 图标取 1.5dp），线段末端直角（Butt）
 * - 圆角：齿外端 1px 圆角、根部直角（"外柔内刚"）
 * - 角度：8 齿 45° 间隔（15° 倍数，与栅格 45° 辅助线平行）
 * 几何居中，规避文本字形（emoji）在不同设备的渲染偏移。
 */
@Composable
private fun SettingsGearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val c = center
        val stroke = 1.4.dp.toPx()                    // 描边宽度（14dp 尺寸等比）
        val radius = size.minDimension * 0.38f        // 环半径（描边中心线）
        val toothW = 2.9.dp.toPx()                    // 齿宽
        val toothH = radius * 0.42f                    // 齿长（从环外缘伸出）
        val teeth = 8                                 // 45° 间隔
        val toothRadius = 0.9.dp.toPx()                // 齿外端圆角（外柔内刚：外圆内方）

        // 齿：Path 绘制，外端 1px 圆角、根部直角
        for (i in 0 until teeth) {
            rotate(degrees = (360f / teeth) * i, pivot = c) {
                val x0 = c.x - toothW / 2f
                val x1 = c.x + toothW / 2f
                val yTop = c.y - radius - stroke / 2f - toothH   // 外端
                val yBottom = c.y - radius - stroke / 2f         // 根部（环外缘）
                val path = Path().apply {
                    moveTo(x0, yBottom)
                    lineTo(x0, yTop + toothRadius)
                    quadraticTo(x0, yTop, x0 + toothRadius, yTop)
                    lineTo(x1 - toothRadius, yTop)
                    quadraticTo(x1, yTop, x1, yTop + toothRadius)
                    lineTo(x1, yBottom)
                    close()
                }
                drawPath(path, color)
            }
        }
        // 环：直角线段末端（Butt），规范描边
        drawCircle(
            color = color,
            radius = radius,
            center = c,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // 中心轴点
        drawCircle(color = color, radius = 1.1.dp.toPx(), center = c)
    }
}

/**
 * 首页全局统计聚合结果（供 remember 解构使用）。
 */
private data class HomeStats(
    val totalPractices: Int,
    val totalCorrect: Int,
    val totalBlanks: Int,
    val overallRate: Float
)
