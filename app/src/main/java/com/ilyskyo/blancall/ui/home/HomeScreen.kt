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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.EbbinghausScheduler
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.AutoHideNavBarOnFlag
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GridMaxWidth
import com.ilyskyo.blancall.ui.common.LocalIsLargeScreen
import com.ilyskyo.blancall.ui.common.NavBarAutoHide
import com.ilyskyo.blancall.ui.common.navigateReveal
import com.ilyskyo.blancall.ui.common.rememberAutoHideNavBarOnScroll
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.homeGridColumns
import com.ilyskyo.blancall.ui.navigation.navigateToTab
import com.ilyskyo.blancall.ui.reader.updateArticleReaderPrefs
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.practice.PickerSelection
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
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

    // 「添加卡片」底部面板状态外提：与阅读设置面板同款写法，
    // 保证程序化收起（点「完成」）与再次展开的动画行为一致。
    val addCardSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 「管理最近文章」底部面板（编辑态入口）：同款外提状态
    val manageArticlesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManageArticlesSheet by remember { mutableStateOf(false) }
    // 「选择文章」底部面板（添加卡片 → 文章卡片）：点选一篇文章单独成一张卡
    val articlePickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showArticlePickerSheet by remember { mutableStateOf(false) }

    // ── 首页滚动手势（无顶栏）：下拉揭示品牌区 ──
    val homeScrollState = rememberScrollState()
    // 下拉位移（px）：滚到顶再继续下拉 → 内容整体**跟手**下移，顶部露出空白区显示
    // 「Blancall + 副标题」；**松手立即回弹**。Animatable：拖动 snapTo 跟手、松手 animateTo 回弹。
    val homePullOffset = remember { Animatable(0f) }
    val homePullScope = rememberCoroutineScope()
    val homePullMaxPx = with(LocalDensity.current) { HOME_PULL_MAX.toPx() }
    // 回弹防重入：松手兜底（指针抬起监听）与 onPreFling 可能同时触发
    val homePullSettling = remember { AtomicBoolean(false) }

    /** 松手立即回弹复位（跟手结束就缩回去）。多处触发点统一入口。 */
    fun settleHomePull() {
        if (homePullOffset.value <= 0.5f) return
        if (!homePullSettling.compareAndSet(false, true)) return
        homePullScope.launch {
            try {
                homePullOffset.animateTo(
                    0f,
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            } finally {
                // ⚠️ 必须 finally：回弹动画被新的拖动 snapTo/复位抢占时 animateTo 会抛
                // CancellationException，若直接跟一句 set(false) 会被跳过 —— 标志永久停在
                // true，之后所有回弹被拦死（真机：下拉后彻底不再收回）。
                homePullSettling.set(false)
            }
        }
    }

    val homePullConnection = remember(homeScrollState, homePullMaxPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy > 0f && homeScrollState.value <= 0f) {
                    // 滚到顶再继续下拉：内容跟手下移（阻尼渐重——越拉越沉、行程封顶）
                    homePullScope.launch {
                        val damping = 1f -
                            (homePullOffset.value / homePullMaxPx).coerceIn(0f, 1f) * 0.65f
                        homePullOffset.snapTo(
                            (homePullOffset.value + dy * damping).coerceIn(0f, homePullMaxPx)
                        )
                    }
                    // 消费本段下拉：内容由上面自行下移，不触发系统过度滚动光效
                    return Offset(0f, dy)
                }
                if (dy != 0f && homePullOffset.value > 0f) {
                    // 任何其它滚动（含回滚）立即复位
                    homePullScope.launch { homePullOffset.snapTo(0f) }
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                // 快速下拉松手（有惯性）走这里回弹；慢松手无 fling 时由指针抬起监听
                // （见内容列上的 Final pass 监听）兜底 —— 旧实现只靠这里，慢松手会卡在露出态。
                settleHomePull()
                return Velocity.Zero
            }
        }
    }

    // ── 首页卡片布局状态（提到外层函数作用域：悬浮「完成」按钮与学习数据弹窗也要用）──
    val homeLayoutStore = remember { HomeLayoutStore.getInstance(context.filesDir) }
    var homeCards by remember { mutableStateOf(homeLayoutStore.getCards()) }
    var cardEditMode by remember { mutableStateOf(false) }

    // 长按卡片 = 「长按操作」：自动收起悬浮底部导航栏（与「我的文章」多选同一机制），
    // 底部空间让给悬浮「完成」按钮；退出编辑态自动恢复，页面销毁时自动兜底释放。
    // ⚠️ 必须用 AutoHideNavBarOnFlag（flag 快照配对）：手写 DisposableEffect 在 onDispose
    // 读委托状态会读到已复位的 false、漏释放 ⇒ 底栏不再恢复（见 NavBarAutoHide 文档）。
    AutoHideNavBarOnFlag(NavBarAutoHide.KEY_HOME_CARD_EDIT, cardEditMode)
    val navBarAutoHidden by NavBarAutoHide.hidden.collectAsState()
    // 滚动驱动的导航栏自动收起：内容前进时导航栏让位、回滚时恢复（首页不再需要避让留白）
    val navBarScrollConn = rememberAutoHideNavBarOnScroll()
    var showAddCardSheet by remember { mutableStateOf(false) }
    // 学习数据弹窗显隐（悬浮于底部导航栏之上，不随内容滚动）
    var showStatsPopup by remember { mutableStateOf(false) }
    val homeScope = rememberCoroutineScope()
    fun persistHomeCards(newCards: List<HomeLayoutStore.Card>) {
        homeCards = newCards
        homeScope.launch { withContext(Dispatchers.IO) { homeLayoutStore.saveCards(newCards) } }
    }

    // ── 全局统计：用 remember(allRecords) 包裹，避免每次重组重算 ──
    val (totalPractices, totalCorrect, totalBlanks, overallRate) = remember(allRecords) {
        val tp = allRecords.size
        val tc = allRecords.sumOf { it.correctCount }
        val tb = allRecords.sumOf { it.totalBlanks }
        val or = if (tb > 0) tc.toFloat() / tb else 0f
        HomeStats(tp, tc, tb, or)
    }
    val homeStats = remember(totalPractices, totalBlanks, overallRate, articles.size) {
        HomeStatsData(
            practices = totalPractices,
            rate = overallRate,
            blanks = totalBlanks,
            articleCount = articles.size
        )
    }

    // 首页是否已有「学习数据」卡片：有则不再弹悬浮学习数据（同一信息不出现两遍）
    val hasStatsCard = homeCards.any { it.type == HomeLayoutStore.CardType.STATS }
    // 做完一次**新**练习才弹（无卡片时）。两重保障避免误弹：
    //  ① 基线持久化到 AppPrefs（跨进程）——冷启动后 remember/进程内单例都会重置；
    //  ② 先 awaitLoaded() 再比对——记录是异步读盘的，先见空列表再变真实值，直接比对会把历史练习当成「刚做完」。
    LaunchedEffect(Unit) {
        recordRepo.awaitLoaded()
        if (homeCards.any { it.type == HomeLayoutStore.CardType.STATS }) return@LaunchedEffect
        val practices = recordRepo.records.value.size
        val baseline = AppPrefs.statsPopupBaseline()
        if (baseline >= 0 && practices > baseline) showStatsPopup = true
        AppPrefs.setStatsPopupBaseline(practices)
    }
    // 已有「学习数据」卡片（信息已在首页常驻）/ 进入编辑态：收起弹窗
    // （编辑态收起还为了避免与悬浮「完成」按钮叠在一起）
    LaunchedEffect(hasStatsCard, cardEditMode) {
        if (hasStatsCard || cardEditMode) showStatsPopup = false
    }
    // 弹出后自动收起（点详情 / 关闭可提前收起）
    LaunchedEffect(showStatsPopup) {
        if (showStatsPopup) {
            delay(STATS_POPUP_AUTO_DISMISS_MS)
            showStatsPopup = false
        }
    }

    // 首页隐藏的文章（长按"从首页删除"，仅从首页移除，不从文章列表删除）
    val hiddenArticleIds by AppPrefs.hiddenArticleIdsFlow.collectAsState()
    // 按更新时间倒序排列（最近操作过的在前），过滤掉首页隐藏的文章
    val recentArticles = remember(articles, hiddenArticleIds) {
        articles.filter { it.id !in hiddenArticleIds }.sortedByDescending { it.updatedAt }
    }

    // ── 文章标签：首页「最近使用 / 文章卡片」徽标展示 ──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow（跨页面改动即时反映）
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val chipTagsByArticle = remember(tagData) {
        TagOps.tagsByArticle(tagData).mapValues { (_, list) -> list.toChipUis() }
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

    // ── 句子卡片（每日一句）：到期优先抽句、同日幂等落盘 ──
    // 抽句与状态推导全部在 IO 线程；随文章加载与 FSRS 状态加载完成自动刷新
    // （produceState 换 key 会取消上一轮，setTodayIfAbsent 保证同日只抽一次不重复落盘）
    val sentenceStore = remember { SentenceCardStore.getInstance(context.filesDir) }
    val sentenceUi by produceState<SentenceDailyUi?>(null, articles, fsrsStates) {
        if (articles.isEmpty()) {
            value = null
            return@produceState
        }
        fsrsStore.awaitLoaded()
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val states = fsrsStore.allSentenceStates()
            val snapshot = DailySentenceCoordinator.ensureToday(sentenceStore, articles, states, now)
                ?: return@withContext null
            val title = articles.firstOrNull { it.id == snapshot.articleId }?.title
                ?.takeIf { it.isNotBlank() } ?: snapshot.title
            val pendingDue = states.count { (k, s) -> k != snapshot.key && FsrsEngine.isDue(s, now) }
            SentenceDailyUi(
                text = snapshot.text,
                articleTitle = title,
                statusLine = sentenceStatusLine(states[snapshot.key], now),
                pendingDueCount = pendingDue,
            )
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
    var savedArticleId by remember { mutableLongStateOf(0L) }
    val subtitle by AppPrefs.subtitleFlow.collectAsState()
    // 模式选择弹窗
    var showModePicker by remember { mutableStateOf(false) }
    var pendingPracticeArticleId by remember { mutableLongStateOf(0L) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    // 弹窗锚点来源：true=点击练习按钮（行位置上报有效）；false=「保存成功→开始练习」
    // （没有按钮锚点，居中弹出；此时行位置上报会污染锚点——其所在行可能在屏幕外）
    var anchorFromButton by remember { mutableStateOf(false) }
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
            textBottomSpacing = 0.dp,
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
                    // 右上角：x 关闭（+ 新建已删，与「继续导入」按钮功能重复）
                    IconButton(onClick = { showSaveSuccessDialog = false }) {
                        AppIcon(
                            kind = AppIconKind.Close,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = "关闭"
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
                                // 清除上次练习按钮的陈旧锚点并停用行锚点上报：本路径居中弹出
                                // （沿用旧锚点会把选项卡弹到上次那篇文章的位置，可能在屏幕外）
                                anchorFromButton = false
                                practiceButtonRect = Rect.Zero
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

    // （图标选择与副标题编辑弹窗已收归设置页：首页顶栏移除后由设置页承担编辑入口）

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
        // ── 下拉揭示层：内容跟手下移后露出的顶部空白区（位于内容层之下，被内容盖住）──
        // 高度 = 当前下拉位移；品牌文字从顶部渐显，随下拉逐步完整露出。
        val homePullPx = homePullOffset.value
        if (homePullPx > 0.5f) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { homePullPx.toDp() })
                    .clipToBounds()
                    .graphicsLayer { alpha = (homePullPx / homePullMaxPx).coerceIn(0f, 1f) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Blancall",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 内容宽上限：窄屏保持 600dp；宽屏放宽到 [GridMaxWidth]。（大屏自适应列数
                // 需要更宽画布，上限过窄会把卡片拉成 600dp 级扁条——真机反馈。）
                .widthIn(max = if (LocalIsLargeScreen) GridMaxWidth else 600.dp)
                // 下拉跟手：内容整体下移，顶部露出品牌揭示区（揭示层在其下层绘制）
                .graphicsLayer { translationY = homePullOffset.value },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 松手兜底：嵌套滚动在「无速度慢松手」时不会派发 fling 阶段回调，
                    // 仅靠 onPreFling 复位会漏（真机：下拉后卡在露出态）——
                    // 这里在 Final pass **仅观察、不消费**：任何指针全部抬起/手势取消
                    // 且仍处于下拉位移状态时，立即回弹缩回。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                                if (e.changes.none { it.pressed }) settleHomePull()
                            }
                        }
                    }
                    .nestedScroll(homePullConnection)
                    .nestedScroll(navBarScrollConn)
                    .verticalScroll(homeScrollState)
                    .padding(horizontal = 20.dp)
            ) {
            Spacer(Modifier.height(12.dp))

            // ── 搜索栏：常驻显示；右侧为「设置」入口（原「添加」键位）──
            // 首页不再有「添加」键：添加卡片由画布「＋」卡片承接，导入可从设置/文章列表页进入。
            HomeSearchBar(
                onSearch = { navController.navigate("search") },
                onSettings = { navController.navigate("settings") },
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(18.dp))

            // ── 首页卡片画布：网格布局，支持拖动换位 / 拉伸缩放 / 大头针固定 / 长按进入编辑态 ──
            // （布局状态与统计都已提到函数作用域，原因：悬浮层也要读）
            // 列数随可用宽度自适应（大屏多列；手机 2 列不变）——按画布实际内容宽折算，
            // 不依赖窗口宽近似（会多算侧栏宽度）。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val hcGridWidth = if (maxWidth.value.isFinite()) maxWidth.value else 0f
            HomeCardCanvas(
                columns = homeGridColumns(hcGridWidth),
                cards = homeCards,
                editMode = cardEditMode,
                onCardsChange = { persistHomeCards(it) },
                onDeleteCard = { card -> persistHomeCards(homeCards.filterNot { it.id == card.id }) },
                onPinToggle = { card, row, col ->
                    persistHomeCards(
                        homeCards.map { c ->
                            when {
                                c.id != card.id -> c
                                c.pinned -> c.copy(pinned = false, lockRow = -1, lockCol = -1)
                                else -> c.copy(pinned = true, lockRow = row, lockCol = col)
                            }
                        }
                    )
                },
                onEditConfig = { card ->
                    homeScope.launch {
                        when (card.type) {
                            HomeLayoutStore.CardType.CUSTOM_CLOZE -> {
                                val aid = withContext(Dispatchers.IO) {
                                    findClozeConfigArticle(context.filesDir, card.articleId, card.refId)
                                }
                                if (aid != null) {
                                    navController.navigate("custom_cloze_edit/$aid?configId=${card.refId}")
                                }
                            }
                            HomeLayoutStore.CardType.CUSTOM_MASK -> {
                                val aid = withContext(Dispatchers.IO) {
                                    findMaskConfigArticle(context.filesDir, card.articleId, card.refId)
                                }
                                if (aid != null) {
                                    withContext(Dispatchers.IO) {
                                        MaskConfigStore.getInstance(context.filesDir).setSelected(aid, card.refId)
                                        // 只写目标文章的阅读设置（按文章独立；原写全局会污染其它文章）
                                        updateArticleReaderPrefs(
                                            ReaderPrefsStore.getInstance(context),
                                            aid
                                        ) { it.copy(occlusionCustomConfigId = card.refId, occlusionMode = "custom", occlusionEnabled = true) }
                                    }
                                    navController.navigate("reader/$aid")
                                }
                            }
                            else -> Unit
                        }
                    }
                },
                onAddCard = { showAddCardSheet = true },
                // 编辑态「管理最近文章」入口：一篇文章都没有时不展示该按钮
                onManageArticles = if (recentArticles.isEmpty()) null else {
                    { showManageArticlesSheet = true }
                },
                // 长按卡片进编辑态：交给画布内统一的手势实现（避免内层行级手势消费长按）
                onLongPressCard = { cardEditMode = true },
                modifier = Modifier.fillMaxWidth(),
                cardContent = { card ->
                    // 编辑态淡化卡面：角上的白钮必然会压住标题一角，内容淡下去后
                    // 视觉重心落在「白钮 + 点亮描环」上，不会显得内容被压坏
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(alpha = if (cardEditMode) 0.5f else 1f)
                    ) {
                        HomeCardContent(
                            card = card,
                            articles = articles,
                            tagsByArticle = chipTagsByArticle,
                            dueArticles = dueArticles,
                            resumables = resumables,
                            recentArticles = recentArticles,
                            dateFormat = dateFormat,
                            anchorArticleId = pendingPracticeArticleId,
                            onAnchorMeasured = { id, rect ->
                                // 仅「点击练习按钮」路径消费行位置作为锚点；
                                // 「保存成功→开始练习」不上报（避免锚点被改成屏幕外的行位置）
                                if (anchorFromButton) {
                                    pendingPracticeArticleId = id
                                    practiceButtonRect = rect
                                }
                            },
                            onPracticeArticle = { id ->
                                anchorFromButton = true
                                pendingPracticeArticleId = id
                                showModePicker = true
                            },
                            onResumePractice = { item ->
                                navController.navigate("practice/${item.articleId}?resume=true")
                            },
                            onOpenArticle = { article, anchor ->
                                navController.navigateReveal("reader/${article.id}", anchor)
                            },
                            onViewAllArticles = { navController.navigateToTab("list") },
                            onAddArticle = { navController.navigate("import") },
                            onOpenClozeConfig = { articleId, configId ->
                                navController.navigate("practice/$articleId?configId=$configId")
                            },
                            onOpenMaskConfig = { articleId, _, anchor ->
                                navController.navigateReveal("reader/$articleId", anchor)
                            },
                            stats = homeStats,
                            onOpenStats = { navController.navigateToTab("overview") },
                            sentenceUi = sentenceUi,
                            onOpenSentenceCard = { anchor ->
                                navController.navigateReveal("sentence_cards", anchor)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            )
            } // close BoxWithConstraints（画布列数测量）
            // 底部只留呼吸间距：导航栏改为「滚动/长按自动收起」（滚到底时已让位），
            // 不再需要 96/168dp 的避让留白（用户要求：取消一切为导航栏预留的底距）。
            Spacer(Modifier.height(16.dp))

            // ── 添加卡片：由画布顶部「+」唤起，列出未加入的系统卡与全部自定义配置 ──
            if (showAddCardSheet) {
                val systemAddable = listOf(
                    Triple(HomeLayoutStore.CardType.DUE, "待复习", "今日需要复习的文章"),
                    Triple(HomeLayoutStore.CardType.CONTINUE, "继续做", "未完成的练习进度"),
                    Triple(HomeLayoutStore.CardType.RECENT, "最近文章", "最近打开过的文章"),
                    Triple(HomeLayoutStore.CardType.ARTICLE, "文章卡片", "把某一篇文章单独放成一张卡"),
                    Triple(HomeLayoutStore.CardType.SENTENCE, "句子卡片", "每天一句 · 间隔复习"),
                    Triple(HomeLayoutStore.CardType.STATS, "学习数据", "练习次数与正确率"),
                    Triple(HomeLayoutStore.CardType.GLOBAL_STATS, "全局数据", "累计统计概览"),
                    Triple(HomeLayoutStore.CardType.ADD_ARTICLE, "添加文章", "导入新文章的入口")
                ).filter { (t, _, _) ->
                    // 文章卡片可以放多张（每篇一张）：只要还有未添加的文章就始终可选
                    if (t == HomeLayoutStore.CardType.ARTICLE) {
                        articles.any { a ->
                            homeCards.none { it.id == HomeLayoutStore.articleCardId(a.id) }
                        }
                    } else {
                        homeCards.none { it.type == t }
                    }
                }
                val customAddable by produceState<List<HomeLayoutStore.Card>>(emptyList(), homeCards, articles) {
                    value = withContext(Dispatchers.IO) {
                        collectAddableCustomCards(
                            context.filesDir,
                            homeCards.map { it.id }.toSet(),
                            articles.map { it.id }.toSet()
                        )
                    }
                }
                HomeAddCardSheet(
                    sheetState = addCardSheetState,
                    systemAddable = systemAddable,
                    customAddable = customAddable,
                    onDismiss = { showAddCardSheet = false },
                    onAddSystem = { type ->
                        if (type == HomeLayoutStore.CardType.ARTICLE) {
                            // 文章卡片先选文章：收起当前面板，弹出选择文章面板
                            showAddCardSheet = false
                            showArticlePickerSheet = true
                        } else {
                            val cardId = when (type) {
                                HomeLayoutStore.CardType.DUE -> HomeLayoutStore.CARD_ID_DUE
                                HomeLayoutStore.CardType.CONTINUE -> HomeLayoutStore.CARD_ID_CONTINUE
                                HomeLayoutStore.CardType.RECENT -> HomeLayoutStore.CARD_ID_RECENT
                                HomeLayoutStore.CardType.SENTENCE -> HomeLayoutStore.CARD_ID_SENTENCE
                                HomeLayoutStore.CardType.STATS -> HomeLayoutStore.CARD_ID_STATS
                                HomeLayoutStore.CardType.GLOBAL_STATS -> HomeLayoutStore.CARD_ID_GLOBAL_STATS
                                else -> HomeLayoutStore.CARD_ID_ADD
                            }
                            persistHomeCards(homeCards + HomeLayoutStore.Card(cardId, type))
                        }
                    },
                    onAddCustom = { c -> persistHomeCards(homeCards + c) },
                )
            }
            Spacer(Modifier.height(14.dp))

        }
    }

        } // 内层首页内容 Box 闭合

        // ── 悬浮层：不随首页内容滚动（底栏可见时自动落在其上方）──

        // 编辑态底栏已自动收起（见 NavBarAutoHide）：底距从「栏上安全距」收缩为贴底小距，
        // 用动画过渡避免按钮跳变；统计弹窗（非编辑态，底栏可见）仍按安全底距避让。
        val doneButtonBottom by animateDpAsState(
            targetValue = if (navBarAutoHidden) 16.dp else FLOATING_BOTTOM_PADDING,
            label = "doneButtonBottom",
        )

        // ① 编辑态「完成」：旧版内联在画布下方，要滚到底才能看到、还会被导航栏遮住
        AnimatedVisibility(
            visible = cardEditMode,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = doneButtonBottom)
                .navigationBarsPadding(),
        ) {
            Button(
                onClick = { cardEditMode = false },
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
            ) {
                // 系统黑体（SansSerif）：主操作按钮，不用主题的楷体/衬线体
                Text(
                    "完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.SansSerif,
                )
            }
        }

        // ② 学习数据弹窗：做完一次新练习、且首页没有「学习数据」卡片时弹出（有卡片则不弹，信息不重复）
        AnimatedVisibility(
            visible = showStatsPopup && !cardEditMode,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = FLOATING_BOTTOM_PADDING)
                .navigationBarsPadding(),
        ) {
            StatsPopupCard(
                practices = totalPractices,
                rate = overallRate,
                blanks = totalBlanks,
                onViewDetails = {
                    showStatsPopup = false
                    navController.navigateToTab("overview")
                },
                onDismiss = { showStatsPopup = false },
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
            )
        }

    }

    // ── 「选择文章」底部面板：添加卡片 → 文章卡片，点选一篇文章单独成一张卡 ──
    if (showArticlePickerSheet) {
        HomeArticlePickerSheet(
            sheetState = articlePickerSheetState,
            articles = articles,
            isAdded = { a -> homeCards.any { it.id == HomeLayoutStore.articleCardId(a.id) } },
            onDismiss = { showArticlePickerSheet = false },
            onPick = { a ->
                val newId = HomeLayoutStore.articleCardId(a.id)
                if (homeCards.none { it.id == newId }) {
                    persistHomeCards(
                        homeCards + HomeLayoutStore.Card(
                            id = newId,
                            type = HomeLayoutStore.CardType.ARTICLE,
                            refId = a.id,
                            // 默认一行高：「小小的」紧凑形态（slim 行完整展示标题/字符数/摘要/日期/练习）
                            rowSpan = 1,
                            title = a.title,
                        )
                    )
                }
                showArticlePickerSheet = false
            },
        )
    }

    // ── 「管理最近文章」底部面板：编辑态入口，逐条从首页移除（仅首页隐藏，不删文章）──
    // 原「长按文章行 → 从首页删除」已下线：它与「长按卡片进编辑态」互相打架（用户反馈「一些卡片长按无效」）
    if (showManageArticlesSheet) {
        val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
        GlassModalBottomSheet(
            onDismissRequest = { showManageArticlesSheet = false },
            sheetState = manageArticlesSheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            ) {
                Text(
                    "管理最近文章",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "从首页移除后不再出现在「最近使用」卡片里，文章本身不会被删除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxPanelHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (recentArticles.isEmpty()) {
                        Text(
                            "首页已经没有可移除的文章了",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        recentArticles.forEach { article ->
                            ManageArticleRow(
                                title = article.title,
                                subtitle = buildString {
                                    if (article.author.isNotBlank()) {
                                        append(article.author.trim()).append(" · ")
                                    }
                                    append(dateFormat.format(Date(article.updatedAt)))
                                },
                                onRemove = { AppPrefs.hideArticleFromHome(article.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { showManageArticlesSheet = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("完成", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        // Rect.Zero = 无有效锚点 → 传 null 走屏幕中心伪锚点（居中弹出）
        anchorRect = practiceButtonRect.takeIf { it != Rect.Zero },
        onDismiss = { showModePicker = false },
        onModeSelected = { sel ->
            showModePicker = false
            if (pendingPracticeArticleId > 0) {
                when (sel) {
                    is PickerSelection.Base ->
                        navController.navigate("practice/${pendingPracticeArticleId}?mode=${sel.mode.name}")
                    PickerSelection.Custom ->
                        navController.navigate("custom_cloze_list/${pendingPracticeArticleId}?pick=true")
                }
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
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val container = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)
    val shape = RoundedCornerShape(14.dp)

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
                .clickable { onSearch() },
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
        // 设置按钮（占据原「添加」键的位置与尺寸）：点击进入设置页。
        // 首页不再有「添加」键——添加卡片由画布「＋」卡片承接。
        GlassButton(
            onClick = { onSettings() },
            modifier = Modifier
                .height(46.dp)
                .semantics { contentDescription = "设置" }
        ) {
            SettingsGearIcon(color = MaterialTheme.colorScheme.onSurface)
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

// ── 首页卡片系统辅助 ──

/**
 * 「添加卡片」底部面板：沿用阅读设置面板（ReadingSettingsSheet）的原生底部弹出样式。
 *
 * - [sheetState] 由 [HomeScreen] 外提，程序化收起 / 再次展开的动画行为与阅读设置一致；
 * - 面板高度不超过屏幕 55%，条目多时在面板内部滚动；
 * - 命名 / 类型说明 + 右侧「添加」动作，排版对齐阅读设置面板的行风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeAddCardSheet(
    sheetState: SheetState,
    systemAddable: List<Triple<HomeLayoutStore.CardType, String, String>>,
    customAddable: List<HomeLayoutStore.Card>,
    onDismiss: () -> Unit,
    onAddSystem: (HomeLayoutStore.CardType) -> Unit,
    onAddCustom: (HomeLayoutStore.Card) -> Unit,
) {
    // 面板内容区最大高度：不超过屏幕大半，超出部分内部滚动
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                "添加卡片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                systemAddable.forEach { (type, label, desc) ->
                    HomeAddCardRow(label = label, desc = desc) { onAddSystem(type) }
                }
                if (customAddable.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "自定义配置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    customAddable.forEach { c ->
                        HomeAddCardRow(
                            label = c.title.ifBlank { "自定义配置" },
                            desc = if (c.type == HomeLayoutStore.CardType.CUSTOM_CLOZE) {
                                "自定义挖空"
                            } else {
                                "自定义遮挡"
                            }
                        ) { onAddCustom(c) }
                    }
                }
            }
        }
    }
}

/**
 * 「选择文章」面板：把某一篇文章单独添加为首页卡片。
 * 与「添加卡片」同一套原生底部弹出样式（拖拽手柄 + 标题 + 内部滚动列表）；
 * 已添加过的文章显示「已添加」且不可再点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeArticlePickerSheet(
    sheetState: SheetState,
    articles: List<Article>,
    isAdded: (Article) -> Boolean,
    onDismiss: () -> Unit,
    onPick: (Article) -> Unit,
) {
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
        ) {
            Text(
                "选择文章",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "选中的文章会单独成为一张卡片，随时可以开始练习。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                if (articles.isEmpty()) {
                    Text(
                        "还没有文章，先去导入一篇吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    articles.forEach { a ->
                        val added = isAdded(a)
                        HomeArticlePickRow(
                            title = a.title,
                            subtitle = buildString {
                                if (a.author.isNotBlank()) append(a.author.trim()).append(" · ")
                                append(a.content.length).append(" 字符")
                            },
                            added = added,
                        ) { if (!added) onPick(a) }
                    }
                }
            }
        }
    }
}

/** 「选择文章」面板里的一行：标题 + 副信息 + 右侧「添加 / 已添加」 */
@Composable
private fun HomeArticlePickRow(
    title: String,
    subtitle: String,
    added: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !added, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (added) "已添加" else "添加",
            style = MaterialTheme.typography.labelMedium,
            color = if (added) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.primary
            },
            maxLines = 1
        )
    }
}

/** 「添加卡片」面板里的一行可选项：名称 + 类型说明 + 右侧「添加」动作 */
@Composable
private fun HomeAddCardRow(label: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "添加",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

/** 「管理最近文章」面板里的一行：标题 + 副信息 + 右侧「移除」（仅首页隐藏，不删文章） */
@Composable
private fun ManageArticleRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = onRemove) {
            Text(
                "移除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 反查挖空配置所属文章（编辑入口）。
 * 卡片自带 articleId 时按双键精确命中（不跨文章、不串篇）；旧卡（无 articleId）退化为全库扫描兼容。
 * 读盘语义（含主文件损坏回读 .bak）统一收敛到 [CustomClozeStore]，与卡片反查/添加面板保持一致。
 */
private fun findClozeConfigArticle(filesDir: java.io.File, articleId: Long, configId: Long): Long? =
    runCatching { CustomClozeStore.getInstance(filesDir).findArticleIdByConfigId(configId, articleId) }.getOrNull()

/** 反查遮挡配置所属文章（与 [findClozeConfigArticle] 同语义） */
private fun findMaskConfigArticle(filesDir: java.io.File, articleId: Long, configId: Long): Long? =
    runCatching { MaskConfigStore.getInstance(filesDir).findArticleIdByConfigId(configId, articleId) }.getOrNull()

/**
 * 收集「尚未加入画布」的自定义挖空 / 遮挡卡片（供添加卡片弹窗使用）。
 *
 * 卡片 id 与 articleId 均带上所属文章：不同文章的同 configId 配置可同时添加且互不串篇；
 * [validArticleIds] 过滤掉已删除文章的孤儿配置。旧格式卡片 id（无 articleId）仍视为已加入，避免重复添加。
 */
private fun collectAddableCustomCards(
    filesDir: java.io.File,
    taken: Set<String>,
    validArticleIds: Set<Long>
): List<HomeLayoutStore.Card> {
    val out = mutableListOf<HomeLayoutStore.Card>()
    // 自定义挖空
    runCatching {
        CustomClozeStore.getInstance(filesDir).listAll().forEach { (aid, cfg) ->
            if (aid !in validArticleIds) return@forEach
            val id = HomeLayoutStore.clozeCardId(aid, cfg.id)
            if (id in taken || "cloze:${cfg.id}" in taken) return@forEach
            out += HomeLayoutStore.Card(
                id = id,
                type = HomeLayoutStore.CardType.CUSTOM_CLOZE,
                refId = cfg.id,
                articleId = aid,
                colSpan = 1,
                rowSpan = 1,
                title = cfg.name
            )
        }
    }
    // 自定义遮挡
    runCatching {
        MaskConfigStore.getInstance(filesDir).listAll().forEach { (aid, cfg) ->
            if (aid !in validArticleIds) return@forEach
            val id = HomeLayoutStore.maskCardId(aid, cfg.id)
            if (id in taken || "mask:${cfg.id}" in taken) return@forEach
            out += HomeLayoutStore.Card(
                id = id,
                type = HomeLayoutStore.CardType.CUSTOM_MASK,
                refId = cfg.id,
                articleId = aid,
                colSpan = 1,
                rowSpan = 1,
                title = cfg.name
            )
        }
    }
    return out
}

// ── 首页悬浮层（底部导航栏之上）──

/** 悬浮元素的安全底距 = 导航栏高(64dp) + 栏底距(14dp) + 间距(12dp)，再叠加 navigationBarsPadding() */
private val FLOATING_BOTTOM_PADDING = 90.dp

/** 学习数据弹窗自动收起延时（点详情 / 关闭可提前） */
private const val STATS_POPUP_AUTO_DISMISS_MS = 6000L

/** 下拉揭示区最大行程：滚到顶再继续下拉这么多即到顶（跟手 + 阻尼渐重）。 */
private val HOME_PULL_MAX = 140.dp

/**
 * 「学习数据」悬浮弹窗：仅当用户**刚做完一次练习**且首页没有学习数据卡片时弹出。
 * 固定悬浮于底部导航栏之上（见 [FLOATING_BOTTOM_PADDING]），不随内容滚动。
 */
@Composable
private fun StatsPopupCard(
    practices: Int,
    rate: Float,
    blanks: Int,
    onViewDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        onClick = onViewDetails,
    ) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "学习数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        kind = AppIconKind.Close,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = "关闭"
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(end = 6.dp)) {
                StatMetric("$practices", "累计练习", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatMetric("${(rate * 100).toInt()}%", "正确率", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatMetric("$blanks", "累计填空", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onViewDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("查看详情", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 弹窗内的单个数值指标：大号数字 + 小字说明 */
@Composable
private fun StatMetric(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}
