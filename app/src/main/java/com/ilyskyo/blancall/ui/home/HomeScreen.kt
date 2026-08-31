// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.appIconKindFromKey
import com.ilyskyo.blancall.ui.common.iconKeyFromKind
import com.ilyskyo.blancall.ui.navigation.navigateToTab
import com.ilyskyo.blancall.ui.common.listItemEnter
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
) {
    val articleViewModel: ArticleViewModel = viewModel()
    val articles by articleViewModel.articles.collectAsState()
    val context = LocalContext.current
    val recordRepo = remember { RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath) }
    val allRecords by recordRepo.records.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 搜索栏右侧「添加」按钮实测宽度；供品牌栏「设置」按钮等宽对齐
    var addButtonWidth by remember { mutableStateOf(0.dp) }

    // ── 首页顶部品牌头部：默认收起（仅搜索框），下拉(滚到顶再拉)时展开 ──
    val homeScrollState = rememberScrollState()
    // 展开比例 0..1（0=收起，仅显示搜索框；1=全开，显示 logo+导入+设置）
    val brandProgress = remember { Animatable(if (AppPrefs.homeBrandExpanded) 1f else 0f) }
    // 品牌栏下拉固定状态：true=展开固定，false=收起；每次下拉松手即切换，不依赖松手位置
    // 状态持久化到 AppPrefs：一旦展开，跨页面(如前往设置再返回)保持展开，直到用户再次下拉/上滑手动收起
    var brandExpanded by remember { mutableStateOf(AppPrefs.homeBrandExpanded) }
    // 品牌栏(logo+设置)全展开高度；搜索栏常驻不折叠，故无需计入头部展开预算
    val brandHeight = 72.dp
    val headerScope = rememberCoroutineScope()
    // 品牌栏拉满所需的「下拉行程」（约 2.2 倍品牌栏高度），行程比把手宽裕、不会一下瞬满
    val brandPullPx = with(LocalDensity.current) { (brandHeight * 2.2f).toPx() }
    // 品牌栏开合用带回弹的弹簧动画（略 overshoot，收尾更有弹性）
    val bounceSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val topBarConnection = remember(homeScrollState, brandProgress, headerScope, bounceSpring, brandPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // 滚到顶再继续下拉：
                //  - 未展开：over-scroll 实时转为头部展开（带递进阻尼的跟手）
                //  - 已拉满：再次下拉 → 回弹收起（toggle）
                if (dy > 0f && homeScrollState.value <= 0f) {
                    // 拖拽跟手：随进度递增阻力，越接近拉满越费劲（收/展状态由松手 toggle 决定）
                    headerScope.launch {
                        val damped = dy / brandPullPx * (1f - brandProgress.value * 0.35f)
                        brandProgress.snapTo((brandProgress.value + damped).coerceIn(0f, 1f))
                    }
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 品牌栏展开/收起完全由「再次下拉」toggle 控制，滚动内容不自动收起
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (homeScrollState.value <= 0f && brandProgress.value > 0f) {
                    headerScope.launch {
                        // 已完全展开时，再由「再次下拉」toggle 收起；尚未拉满(或刚松手)的同一次下拉
                        // 直接送满并固定——避免松手后自动再收起来，保证只有再次下拉或上滑才会收回。
                        if (brandProgress.value >= 0.99f) {
                            brandExpanded = !brandExpanded
                            AppPrefs.homeBrandExpanded = brandExpanded
                            if (brandExpanded) {
                                brandProgress.animateTo(1f, bounceSpring)
                            } else {
                                brandProgress.animateTo(0f, tween(durationMillis = 280))
                            }
                        } else {
                            brandExpanded = true
                            AppPrefs.homeBrandExpanded = true
                            brandProgress.animateTo(1f, bounceSpring)
                        }
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

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
    // FSRS 状态后台加载：首帧先渲染，加载完成后刷新，避免同步读文件卡顿
    var fsrsStates by remember { mutableStateOf(fsrsStore.allStates()) }
    LaunchedEffect(Unit) {
        fsrsStore.awaitLoaded()
        fsrsStates = fsrsStore.allStates()
    }
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
                    Spacer(Modifier.weight(1f))
                    // 右上角：+ 新建（继续导入）
                    IconButton(
                        onClick = {
                            showSaveSuccessDialog = false
                            navController.navigate("import")
                        }
                    ) {
                        AppIcon(
                            kind = AppIconKind.Add,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 右上角：x 关闭
                    IconButton(onClick = { showSaveSuccessDialog = false }) {
                        AppIcon(
                            kind = AppIconKind.Close,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    // 主操作 + 次操作：同一行并排
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 开始练习
                        Button(
                            onClick = {
                                showSaveSuccessDialog = false
                                pendingPracticeArticleId = savedArticleId
                                showModePicker = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("开始练习")
                        }
                        // 继续导入
                        OutlinedButton(
                            onClick = {
                                showSaveSuccessDialog = false
                                navController.navigate("import")
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("继续导入")
                        }
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
                        // 默认副标题 38 字 > 旧 30 字限制，编辑会被拦截；取消限制
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义副标题") },
                        shape = RoundedCornerShape(12.dp)
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
        // 内层：首页内容（带安全区 padding），外层全屏 Box 承载 AmbientBackground 氛围背景
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
                    .nestedScroll(topBarConnection)
                    .verticalScroll(homeScrollState)
                    .padding(horizontal = 20.dp)
            ) {
            Spacer(Modifier.height(4.dp))

            // ── 品牌栏：logo + 设置，默认收起；下拉(滚到顶再拉)时滑出。
            //    此处不再放「添加」按钮（搜索栏右侧已有），避免重复 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(brandHeight * brandProgress.value)
                    .clipToBounds()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(brandHeight),
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
                                    if (result.hasVisualOverflow && subtitleFontSize.value > 8f) {
                                        subtitleFontSize = (subtitleFontSize.value - 0.5f).sp
                                    }
                                },
                                modifier = Modifier.clickable { showSubtitleEditor = true }
                            )
                        }
                    }
                    GlassButton(
                        onClick = { navController.navigate("settings") },
                        modifier = Modifier
                            // 与搜索栏右侧「添加」按钮等宽对齐
                            .width(if (addButtonWidth > 0.dp) addButtonWidth else 44.dp)
                            .height(40.dp)
                            .semantics { contentDescription = "设置" }
                    ) {
                        SettingsGearIcon(color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // ── 搜索栏：常驻显示（不参与折叠），右侧「添加」直导入 ──
            HomeSearchBar(
                onSearch = { navController.navigate("search") },
                onAdd = { navController.navigate("import") },
                onAddWidthMeasured = { addButtonWidth = it }
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(18.dp))

            // ── 今日待复习卡片 ──
            if (dueArticles.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    // 清新马卡龙：樱花粉淡彩卡面 + 同族柔和点缀
                    containerColor = Macaron.review().fill
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(Macaron.review().accent, RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "要复习的任务（${dueArticles.size}篇）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
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
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        article.title,
                                        // 与「继续练习」一致：文章名用楷体（Serif）
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 作者放标题下方的副行，避免与右侧「复习」动作挤成一行（曾误读成"苏洵复习"）
                                    if (article.author.isNotBlank()) {
                                        Text(
                                            article.author.trim(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Text(
                                    "复习",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Macaron.review().accent
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
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    // 清新马卡龙：薄荷绿淡彩卡面 + 同族柔和点缀
                    containerColor = Macaron.continueP().fill
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(Macaron.continueP().accent, RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "待继续完成（${resumables.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = maxTitleWidth)
                                    )
                                    Text(
                                        buildString {
                                            if (art.author.isNotBlank()) append(art.author.trim()).append(" · ")
                                            append(modeLabel).append(" · 剩余 ")
                                            append(item.total - item.answered).append("/").append(item.total)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                    )
                                }
                                // 右缘与「复习」对齐：去掉额外 end 留白与下移，使两卡右侧按钮同轴对称
                                Text(
                                    "继续",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Macaron.continueP().accent,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
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
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    onClick = { navController.navigateToTab("overview") }
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth().height(390.dp),
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
                    "最近使用",
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
                                onClick = { navController.navigateToTab("list") },
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
                                onClick = { navController.navigateToTab("list") },
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

        } // 内层首页内容 Box 闭合
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

/**
 * 首页搜索栏：磨砂玻璃质感，点击进入搜索页；右侧「添加」按钮直达导入。
 * 常驻显示，不随下拉折叠；实测「添加」宽度上报给品牌栏「设置」按钮等宽对齐。
 */
@Composable
private fun HomeSearchBar(
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    onAddWidthMeasured: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val container = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)
    val shape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 搜索框占位：点击进入独立搜索页
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                .background(container)
                .clickable(onClick = onSearch),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    kind = AppIconKind.SearchHint,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                // 提示字：紧贴搜索符号（点击整框进入搜索页）
                Text(
                    text = "搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // 添加按钮（实测宽度上报，供品牌栏「设置」按钮等宽对齐）
        GlassButton(
            onClick = onAdd,
            modifier = Modifier
                .height(46.dp)
                .onGloballyPositioned {
                    onAddWidthMeasured(with(density) { it.size.width.toDp() })
                }
        ) {
            Text(
                text = "添加",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
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
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        }
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
                    text = buildString {
                        if (article.author.isNotBlank()) append(article.author.trim()).append("  ·  ")
                        append(article.content.length.toString()).append(" 字符")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
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
