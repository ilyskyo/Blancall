// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.widget.Toast
import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.FadeDialogWindow
import com.ilyskyo.blancall.ui.common.StateSwap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.pressFeedback
import com.ilyskyo.blancall.ui.common.rememberHaptic
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.LiquidGlassPageBar
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ilyskyo.blancall.algorithm.EbbinghausScheduler
import com.ilyskyo.blancall.algorithm.MaskToCloze
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.SentenceSplitter
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.algorithm.TextStatistics
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.StatsRepository
import com.ilyskyo.blancall.data.repository.OneShotCloze
import com.ilyskyo.blancall.data.repository.TagStore
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.common.DeleteConfirmDialog
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.TagChipRow
import com.ilyskyo.blancall.ui.common.toChipUis
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.practice.AdaptiveModePicker
import com.ilyskyo.blancall.ui.practice.PickerSelection
import com.ilyskyo.blancall.ui.tag.TagPickerSheet
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.ArticleViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(navController: NavController, articleId: Long) {
    val articleViewModel: ArticleViewModel = viewModel()
    val context = LocalContext.current
    val resources = LocalResources.current
    // AI 功能开关（关闭时隐藏 AI 入口）
    val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
    var article by remember { mutableStateOf<Article?>(null) }
    // 正文统计只在内容变化时算一次：顶栏主计数与长按弹窗共用同一份口径
    val textStats = remember(article?.content) {
        TextStatistics.analyze(article?.content.orEmpty())
    }
    // 加载失败标记：articleId 无对应文章时展示“文章不存在”而非永久转圈
    var loadFailed by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var readingMode by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    // 顶栏 ⋮ 菜单（编辑 / 删除收进菜单，避免并排按钮挤没标题）
    var showMoreMenu by remember { mutableStateOf(false) }
    var showFullscreenEdit by remember { mutableStateOf(false) }
    var showTextStats by remember { mutableStateOf(false) }
    // 顶栏右侧两行的实测高度：作者行始终占位，标题字号才不会随作者有无跳变
    var headerMetaHeightPx by remember { mutableIntStateOf(0) }
    var practiceButtonRect by remember { mutableStateOf(Rect.Zero) }
    // 预测性返回跟手进度：编辑模式下侧滑返回时驱动编辑界面缩放/淡出动画
    // 用 Animatable 而不是 mutableStateOf —— 手势**取消**时要弹簧回位，
    // 直接写回 0f 会让停在半途的界面跳一下，读起来像丢帧而不是「东西回来了」。
    val editBackProgress = remember { Animatable(0f) }
    // 阅读模式返回跟手进度：侧滑返回时驱动阅读界面缩退淡出
    val readingBackProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // ── 文章标签：编辑态标签行（即时生效）+ 选择面板 ──
    val tagStore = remember { TagStore.getInstance(context.filesDir) }
    val tagData by tagStore.data.collectAsState()
    // 首次进入 priming：IO 读盘 → 发布 StateFlow（跨页面改动即时反映）
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { tagStore.snapshot() } }
    val chipTagsByArticle = remember(tagData) {
        TagOps.tagsByArticle(tagData).mapValues { (_, list) -> list.toChipUis() }
    }
    var showTagPicker by remember { mutableStateOf(false) }

    // 兜底（先注册、优先级低）：PredictiveBackHandler 在个别系统/场景下可能不拦截，
    // 保证编辑/阅读模式下返回键一定能退出当前模式，而不是直接退出页面
    BackHandler(enabled = isEditing) { isEditing = false }
    BackHandler(enabled = readingMode) { readingMode = false }

    // 编辑模式下拦截预测性返回手势（侧滑返回），先退出编辑模式而非直接返回上一页；
    // 手势跟手：进度驱动编辑界面缩小淡出，松手决定退出编辑或回弹
    PredictiveBackHandler(enabled = isEditing) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                // 拖动中逐事件 snapTo：这一帧必须 1:1 跟手，排在补间后面就成「慢半拍」
                editBackProgress.snapTo(backEvent.progress)
            }
            // 手势完成 → 先关闭全屏，再退出编辑
            if (showFullscreenEdit) showFullscreenEdit = false
            else isEditing = false
            // 界面随即整体退场，进度归零不必补间
            editBackProgress.snapTo(0f)
        } catch (e: CancellationException) {
            // 手势取消 → 从半途弹簧回位
            scope.launch { editBackProgress.animateTo(0f, Motion.gestureRecover()) }
        }
    }

    // 沉浸阅读模式：拦截返回手势退出（回到常规详情页）。
    // 用 progressFlow 驱动阅读界面缩退，让「返回」有预测性跟手动画（ReadingModeScreen 内部响应）
    PredictiveBackHandler(enabled = readingMode) { progressFlow ->
        try {
            progressFlow.collect { readingBackProgress.snapTo(it.progress) }
            readingMode = false
            readingBackProgress.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { readingBackProgress.animateTo(0f, Motion.gestureRecover()) }
        }
    }

    LaunchedEffect(articleId) {
        val loaded = articleViewModel.getArticleById(articleId)
        article = loaded
        loadFailed = loaded == null
        loaded?.let {
            editTitle = it.title
            editContent = it.content
            editAuthor = it.author
        }
    }

    // ── 计划状态条：练习次数 / 正确率 / 下次复习 ──
    // 数据源与统计页、任务流完全同源（RecordRepository + FsrsStateStore），不在这里另算一套口径。
    // initialValue 故意是 null：首帧不画，等存储加载完再出现，避免「0 次 → 12 次」的数字跳变。
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()
    val articleRecords = remember(allRecords, articleId) {
        allRecords.filter { it.articleId == articleId }
    }
    val articleStats = remember(articleRecords) { StatsRepository.aggregateArticle(articleRecords) }
    val reviewTemplateId by AppPrefs.reviewTemplateFlow.collectAsState()
    val reviewTemplate = remember(reviewTemplateId) { ReviewTemplate.fromId(reviewTemplateId) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    val planSnapshot by produceState<ArticlePlanState?>(
        initialValue = null,
        articleId, fsrsRevision, articleRecords, articleStats, reviewTemplate,
    ) {
        fsrsStore.awaitLoaded()
        val state = fsrsStore.get(articleId)
        val hasFsrs = state != null && state.reviewCount > 0
        // 旧版/CSV 回灌只有记录、没有 FSRS 状态的文章也算练过
        val hasPractice = hasFsrs || articleRecords.isNotEmpty()
        value = ArticlePlanState(
            practiceCount = articleStats.totalPractices,
            accuracy = if (articleStats.totalBlanks > 0) articleStats.overallRate else null,
            inPlan = fsrsStore.isInPlan(articleId) || articleRecords.isNotEmpty(),
            // 没练过的计划成员：「到期」就是加入时刻（与今日计划、任务流同口径）。
            // 只有旧记录的文章没有 FSRS due，按复习模板兜底，不能缺省成 0。
            due = when {
                hasFsrs -> state!!.due
                else -> fsrsStore.enrolledArticles()[articleId]
                    ?: EbbinghausScheduler.nextReviewAt(reviewTemplate, articleRecords)
                    ?: 0L
            },
            hasPractice = hasPractice,
        )
    }

    // 编辑保存后若清掉了失效的句子记忆状态，明确告诉用户一句 —— 那些句子的学习进度
    // 是真被删了（不是「迁移」），不说就等于静默扣掉用户的数据。消费后归零，避免重复弹。
    val orphanPruned by articleViewModel.orphanStatesPruned.collectAsState()
    LaunchedEffect(orphanPruned) {
        if (orphanPruned > 0) {
            Toast.makeText(
                context,
                resources.getString(com.ilyskyo.blancall.R.string.sentence_states_pruned, orphanPruned),
                Toast.LENGTH_LONG
            ).show()
            articleViewModel.consumeOrphanNotice()
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
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
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
                        val p = editBackProgress.value
                        val s = Motion.backShrinkScale(p)
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
                                articleViewModel.updateArticle(
                                    art.copy(
                                        title = editTitle.trim(),
                                        content = editContent.trim(),
                                        author = editAuthor.trim()
                                    )
                                )
                                // 本地立即赋值以即时反映编辑结果（VM 未暴露当前文章 Flow，故保留本地同步赋值，避免与异步更新竞态时回显旧值）
                                article = art.copy(
                                    title = editTitle.trim(),
                                    content = editContent.trim(),
                                    author = editAuthor.trim()
                                )
                                isEditing = false
                            }
                        },
                        enabled = editTitle.isNotBlank(),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.save), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // 非编辑态：返回键右侧展示标题 +（作者 / 字数 上下堆叠），最右是 ⋮ 菜单（编辑/自定义挖空/删除）。
                    // 标题字号 = 右侧两行总高度（上下边缘与作者/字数对齐）。
                    // 作者行必须始终占位：未填作者时若省掉这一行，右侧只剩一行，标题会突然缩小。
                    val titleFontSize = if (headerMetaHeightPx > 0) {
                        // 标题字号 ≈ 右侧两行总高的 80%：视觉上与两行高度接近但不过大，
                        // 且不把顶栏 Row 撑高（保证作者/字数与按钮中心线对齐）
                        val d = LocalDensity.current
                        (headerMetaHeightPx / (d.density * d.fontScale) * 0.8f).sp
                    } else {
                        MaterialTheme.typography.titleLarge.fontSize
                    }
                    val countText = pluralStringResource(
                        textStats.unit.countPluralRes(),
                        textStats.primaryCount,
                        textStats.primaryCount
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                            .heroElement("article/${art.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = art.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = titleFontSize,
                                lineHeight = titleFontSize
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        ReaderHeaderMeta(
                            author = art.author,
                            countText = countText,
                            onMeasured = { headerMetaHeightPx = it },
                            onOpenStats = { showTextStats = true }
                        )
                    }
                    // ⋮ 三点菜单（顶部栏唯一操作按钮）：编辑 / 自定义挖空 / 删除 ——
                    // 按钮铺在顶部栏会占宽挤没标题（真机反馈），全部操作收进菜单；
                    // 图标（AppIconKind.MoreVert）与弹出菜单（GlassDropdownMenu/GlassMenuItem）
                    // 均复用项目通用组件，样式与其它页面 ⋮ 菜单完全一致。
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            AppIcon(
                                kind = AppIconKind.MoreVert,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                                contentDescription = stringResource(com.ilyskyo.blancall.R.string.more)
                            )
                        }
                        GlassDropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            // 编辑：与原顶部栏「编辑」按钮行为一致（进入编辑态）
                            GlassMenuItem(
                                onClick = { showMoreMenu = false; isEditing = true },
                                label = {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.edit), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            )
                            // 自定义挖空：与原顶部栏按钮行为一致（进入该文章的自定义挖空配置列表）
                            GlassMenuItem(
                                onClick = {
                                    showMoreMenu = false
                                    navController.navigate("custom_cloze_list/${art.id}")
                                },
                                label = {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.custom_cloze), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            )
                            // 删除：与原顶部栏「删除」按钮行为一致（弹原文确认框）
                            GlassMenuItem(
                                onClick = { showMoreMenu = false; showDeleteDialog = true },
                                label = {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.delete), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            )
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
                // 固定分割线：位于滚动区之外，标题下方，不随正文上下滚动
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (isEditing) editBackProgress.value else 1f
                        }
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 正文蓝色卡片与分割线之间的间距（略大，让内容区往下沉一点）
                    Spacer(modifier = Modifier.height(40.dp))
                }

                // ── 阅读内容（可滚动）：编辑模式下供预测性返回手势渐现露出 ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .graphicsLayer {
                            // 编辑模式手势返回：阅读内容随进度从透明渐现（与覆盖层渐隐互补，
                            // 确保下层详情页内容一定可见，而不是露出纯色背景）
                            alpha = if (isEditing) editBackProgress.value else 1f
                        }
                ) {
                    // 正文区顶部留白：让正文蓝色卡片从分割线下方开始（适度下移，避免整屏空洞）
                    Spacer(modifier = Modifier.height(32.dp))

                    // 计划状态条：已练几次 / 正确率 / 下次复习；没进计划时这里就是「加入计划」的入口。
                    // 放正文上方而不是顶栏：顶栏标题字号由右侧两行 meta 的动态测量决定，塞进去会挤压标题。
                    planSnapshot?.let { plan ->
                        ArticlePlanBar(
                            plan = plan,
                            onEnroll = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        fsrsStore.enroll(articleId, System.currentTimeMillis())
                                    }
                                }
                            }
                        )
                    }

                    val readingFontId by com.ilyskyo.blancall.ui.theme.AppPrefs.readingFontIdFlow.collectAsState()
                    val readingFontWeight by com.ilyskyo.blancall.ui.theme.AppPrefs.readingFontWeightFlow.collectAsState()
                    val readingFontFamily = remember(readingFontId, readingFontWeight) {
                        com.ilyskyo.blancall.ui.reader.ReaderFonts.resolveFontFamily(
                            context, readingFontId, readingFontWeight
                        ) ?: FontFamily.Default
                    }
                    val autoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
                    val paragraphs = remember(art.content) {
                        art.content.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
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
                            // edge-to-edge 下 manifest 的 adjustResize 实测不生效（窗口零位移），
                            // imePadding 是把「内容」这一栏抬到键盘之上的唯一手段；放在 background 之后，
                            // 键盘弹起的动画过程中不会在底部露出下层阅读内容。
                            .imePadding()
                            .graphicsLayer {
                                val p = editBackProgress.value
                                val s = Motion.backShrinkScale(p)
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
                                label = { Text(stringResource(com.ilyskyo.blancall.R.string.field_title)) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = editAuthor,
                                onValueChange = { editAuthor = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(com.ilyskyo.blancall.R.string.field_author_optional)) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )

                            // ── 标签行：点击打开选择面板；标签**即时生效**，
                            // 不随正文「保存/取消」回滚（与正文编辑语义解耦，防漏保存）──
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.tag),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                val myTags = chipTagsByArticle[articleId].orEmpty()
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .pressClick { showTagPicker = true }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 选完标签的一瞬间这块从提示文字变成徽标行；原来是硬切，
                                    // 看不出是同一个字段被填上了。两臂各自单根，后面的
                                    // Spacer(weight) 会把淡入期间多出来的宽度吸收掉。
                                    StateSwap(
                                        targetState = myTags.isEmpty(),
                                        label = "readerTagField"
                                    ) { empty ->
                                        if (empty) {
                                            Text(
                                                stringResource(com.ilyskyo.blancall.R.string.tap_to_choose_tags),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        } else {
                                            TagChipRow(tags = myTags, maxChips = 3)
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    AppIcon(
                                        kind = AppIconKind.Add,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = editContent,
                                onValueChange = { editContent = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = { Text(stringResource(com.ilyskyo.blancall.R.string.field_content)) },
                                shape = RoundedCornerShape(10.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    pluralStringResource(com.ilyskyo.blancall.R.plurals.char_count, editContent.length, editContent.length),
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
                                        Text(stringResource(com.ilyskyo.blancall.R.string.fullscreen_edit))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 液态玻璃操作栏：学习统计 / 阅读模式 / 句子卡 / AI / 开始练习
            // 玻璃 = LiquidGlassPageBar（自绘中性光斑采样源，bind 兄弟画布——
            // 不能 bind 页面宿主 pageHost：宿主是玻璃的祖先，PreDraw 反馈循环会致
            // RenderThread 栈溢出闪退；参数对齐导航栏基准（blur 6 / dispersion 0 / 折射 20·70 / 染色 0.04·0.06）
            // 注意：LiquidGlassView 不能条件移除（detach 必崩），故整条**常驻组合**，
            // 编辑/阅读模式下仅 alpha 归零并禁用点击。
            val barVisible = !isEditing && !readingMode
            val barAlpha by animateFloatAsState(
                targetValue = if (barVisible) 1f else 0f,
                animationSpec = MotionFade.number(MotionFade.enter),
                label = "actionBarAlpha"
            )
            val barCornerPx = with(LocalDensity.current) { 24.dp.toPx() }
            val barShape = RoundedCornerShape(barCornerPx)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = barAlpha }
                    .shadow(
                        elevation = 10.dp,
                        shape = barShape,
                        ambientColor = Color.Black.copy(alpha = 0.15f),
                        spotColor = Color.Black.copy(alpha = 0.20f),
                        clip = false
                    )
            ) {
                LiquidGlassPageBar(
                    cornerDp = 24,
                    // 纯透明无色玻璃：关闭色散（消除彩色边缘）、染色压到极低（接近导航栏般通透）
                    dispersion = 0f,
                    tintAlphaLight = 0.04f,
                    tintAlphaDark = 0.06f,
                    modifier = Modifier.matchParentSize()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassActionItem(stringResource(com.ilyskyo.blancall.R.string.reader_stats), Modifier.weight(1f), enabled = barVisible) {
                        navController.navigate("statistics/${art.id}")
                    }
                    GlassActionItem(stringResource(com.ilyskyo.blancall.R.string.reader_mode), Modifier.weight(1f), enabled = barVisible) {
                        readingMode = true
                    }
                    // 句子卡：进入句子卡片大卡片界面（沿用既有 sentence_cards 全局入口路由；
                    // 无触点锚点时浮起转场自动回退默认动画，不会错乱）
                    GlassActionItem(stringResource(com.ilyskyo.blancall.R.string.reader_sentence_cards), Modifier.weight(1f), enabled = barVisible) {
                        navController.navigate("sentence_cards?articleId=${art.id}")
                    }
                    // AI 对话入口（设置中启用 AI 功能后才显示）
                    if (aiEnabled) {
                        // AI 仅 2 字：不参与 weight 均分（否则与 4 字项等宽、胶囊内大片留白——真机反馈）；
                        // 按内容宽度形成小胶囊；其余按钮维持 weight(1f) 均分规则不变
                        GlassActionItem("AI", Modifier.widthIn(min = 52.dp), enabled = barVisible) {
                            navController.navigate("ai?ids=${art.id}")
                        }
                    }
                    GlassActionItem(
                        stringResource(com.ilyskyo.blancall.R.string.reader_start_practice),
                        Modifier
                            .weight(1f)
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                practiceButtonRect = Rect(
                                    pos.x, pos.y,
                                    pos.x + coords.size.width,
                                    pos.y + coords.size.height
                                )
                            },
                        enabled = barVisible,
                        emphasized = true
                    ) {
                        showModePicker = true
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
                    Text(stringResource(com.ilyskyo.blancall.R.string.article_missing), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // ── 沉浸阅读模式：全屏沉浸 + 章节翻页 + 断点续读 ──
    if (readingMode) {
        article?.let { art ->
            // 预测性返回跟手：阅读界面随返回进度缩退（用户偏好的外层缩退效果）
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 与编辑态同一套返回语言：缩放 + 淡出。改造前这里另写了
                        // `translationY = 进度 × 24f`，24 是 px 而非常量密度，
                        // 在 3x 屏上只有 8dp，几乎看不见；换成缩放后进度可感。
                        val p = readingBackProgress.value
                        val s = Motion.backShrinkScale(p)
                        scaleX = s
                        scaleY = s
                        alpha = 1f - p
                    }
            ) {
                ReadingModeScreen(
                    article = art,
                    onExit = { readingMode = false },
                    onPracticeParagraph = { paragraphStart, spans ->
                        // 一次性快照：遮挡配置语义与挖空配置语义不混写（报告明确红线）。
                        // 段落起点 + 段内遮块 → 全文切句区间 → 一次性自定义挖空。
                        val sentenceRanges = MaskToCloze.convert(
                            paragraphStart = paragraphStart,
                            spans = spans,
                            sentences = SentenceSplitter.splitWithPositions(art.content),
                        )
                        if (sentenceRanges.isEmpty()) {
                            Toast.makeText(
                                context,
                                resources.getString(com.ilyskyo.blancall.R.string.reader_practice_section_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val cfg = CustomClozeStore.CustomConfig(
                                id = -1L,
                                name = resources.getString(com.ilyskyo.blancall.R.string.reader_practice_section_name),
                                createdAt = 0L,
                                blanks = sentenceRanges.map {
                                    CustomClozeStore.BlankSpec(it.sentenceIndex, it.a, it.b)
                                },
                                mode = "SENTENCE",
                            )
                            val token = OneShotCloze.put(art.id, cfg)
                            navController.navigate("practice/${art.id}?mode=SENTENCE&oneShot=$token")
                        }
                    },
                    // 「练这一段」面板里的挖空方式：整篇按该方式开练（与底部「开始练习」同一入口）
                    onPracticeMode = { mode ->
                        navController.navigate("practice/${art.id}?mode=${mode.name}")
                    }
                )
            }
        }
    }

    // 删除确认对话框（引用公共组件）
    AnimatedOverlayPresence(target = article.takeIf { showDeleteDialog }) { art ->
        DeleteConfirmDialog(
            title = stringResource(com.ilyskyo.blancall.R.string.confirm_delete_title),
            message = stringResource(com.ilyskyo.blancall.R.string.confirm_delete_message, art.title),
            onConfirm = {
                articleViewModel.deleteArticle(art)
                showDeleteDialog = false
                navController.popBackStack()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    // 顶栏字数详情：长按（或点一下）字数打开，口径与顶栏主计数共用 TextStatistics
    AnimatedOverlayPresence(target = article?.takeIf { showTextStats }) { art ->
        TextStatsDialog(
            articleTitle = art.title,
            stats = textStats,
            onDismiss = { showTextStats = false }
        )
    }

    // 模式选择弹窗：常驻组件，内部状态控制显隐，保证退场动画完整播放
    AdaptiveModePicker(
        visible = showModePicker,
        anchorRect = practiceButtonRect,
        onDismiss = { showModePicker = false },
        onModeSelected = { sel ->
            showModePicker = false
            when (sel) {
                is PickerSelection.Base ->
                    navController.navigate("practice/${articleId}?mode=${sel.mode.name}")
                PickerSelection.Custom ->
                    navController.navigate("custom_cloze_list/${articleId}?pick=true")
            }
        }
    )

    // 标签选择面板（单篇）：标签即时落盘；取消/完成都只收起面板
    LaunchedEffect(showTagPicker, article) {
        // 文章尚未加载完成（极端时序）：收起请求，避免面板在文章到达后突然自己弹出
        if (showTagPicker && article == null) showTagPicker = false
    }
    AnimatedOverlayPresence(target = article.takeIf { showTagPicker }) { target ->
        TagPickerSheet(
            targets = listOf(target),
            onDismiss = { showTagPicker = false },
            onApplied = { showTagPicker = false },
        )
    }

    // ── 全屏编辑对话框 ──
    // 窗口存活条件带一项「退场还在播」：改造前只写 `if (showFullscreenEdit)`，flag 一翻
    // 窗口同帧被拆，于是下面那套 alpha + 14dp 位移只放过入场、退场从未上过屏。
    // 减动效时 animateFloatAsState 收到 MotionFade.alpha 的 snap 分支，alpha 同帧到 0、
    // 条件同帧失效 —— 是「跳过动画」，不是「等一个不会到来的完成信号」。
    FadeDialogWindow(
        visible = showFullscreenEdit,
        onDismissRequest = { showFullscreenEdit = false },
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
                Text(stringResource(com.ilyskyo.blancall.R.string.fullscreen_edit), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showFullscreenEdit = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.done))
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
                    label = { Text(stringResource(com.ilyskyo.blancall.R.string.field_content)) },
                    shape = RoundedCornerShape(10.dp),
                    maxLines = Int.MAX_VALUE
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(com.ilyskyo.blancall.R.plurals.char_count, editContent.length, editContent.length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    }
}

/**
 * 顶栏右侧的作者 + 字数两行。
 *
 * 作者为空时仍画一个透明空格占位：这是「标题字号 = 两行高度」的测量前提，
 * 省掉它会让标题在填写作者前小一号、保存作者后又突然变大。
 * 这里同时接管长按（点一下也可以）打开字数详情，是顶栏上唯一能容纳这个入口的位置。
 */
@Composable
private fun ReaderHeaderMeta(
    author: String,
    countText: String,
    onMeasured: (Int) -> Unit,
    onOpenStats: () -> Unit,
) {
    val authorText = author.trim()
    val hasAuthor = authorText.isNotEmpty()
    val interaction = remember { MutableInteractionSource() }
    val openLabel = stringResource(com.ilyskyo.blancall.R.string.text_stats_open)
    val confirmHaptic = rememberHaptic(HapticTier.Confirm)
    Column(
        modifier = Modifier
            .onGloballyPositioned { onMeasured(it.size.height) }
            .pressFeedback(interaction, PressTier.Plain)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = openLabel,
                onLongClickLabel = openLabel,
                onClick = onOpenStats,
                onLongClick = {
                    confirmHaptic()
                    onOpenStats()
                }
            )
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            // 透明空格只负责撑住行高；读屏不该把「空白作者」念出来
            text = if (hasAuthor) authorText else " ",
            style = MaterialTheme.typography.labelMedium,
            color = if (hasAuthor) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (hasAuthor) Modifier else Modifier.clearAndSetSemantics {}
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 字数详情弹窗：Word 式的一屏统计，主计数与顶栏使用同一个 [TextStatistics] 口径。 */
@Composable
private fun TextStatsDialog(
    articleTitle: String,
    stats: TextStatistics.Stats,
    onDismiss: () -> Unit,
) {
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(com.ilyskyo.blancall.R.string.text_stats_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 大字号/小屏时让内容自己滚，而不是把弹窗撑出屏幕
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = pluralStringResource(
                        stats.unit.countPluralRes(),
                        stats.primaryCount,
                        stats.primaryCount
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = articleTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_chars_with_spaces), stats.charsWithSpaces)
                StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_chars_no_spaces), stats.charsNoSpaces)
                StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_punctuation), stats.punctuation)
                StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_spaces), stats.spaces)
                StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_paragraphs), stats.paragraphs)
                if (stats.digits > 0) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_digits), stats.digits)
                }
                if (stats.han > 0 && stats.unit != TextStatistics.CountUnit.HAN) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_han), stats.han)
                }
                if (stats.kana > 0) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_kana), stats.kana)
                }
                if (stats.hangul > 0) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_hangul), stats.hangul)
                }
                if (stats.letters > 0) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_letters), stats.letters)
                }
                if (stats.nonCjkWords > 0 && stats.unit != TextStatistics.CountUnit.WORD) {
                    StatsRow(stringResource(com.ilyskyo.blancall.R.string.text_stats_words), stats.nonCjkWords)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.ilyskyo.blancall.R.string.close))
            }
        },
        textBottomSpacing = 12.dp
    )
}

@Composable
private fun StatsRow(label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 主计数单位 → 本地化的数量词；四个单位各有一条 plurals，俄语的量档由资源自己管。 */
private fun TextStatistics.CountUnit.countPluralRes(): Int = when (this) {
    TextStatistics.CountUnit.HAN -> com.ilyskyo.blancall.R.plurals.text_count_han
    TextStatistics.CountUnit.KANA -> com.ilyskyo.blancall.R.plurals.text_count_kana
    TextStatistics.CountUnit.WORD -> com.ilyskyo.blancall.R.plurals.text_count_word
    TextStatistics.CountUnit.CHARACTER -> com.ilyskyo.blancall.R.plurals.text_count_char
}

/**
 * 玻璃操作栏内的单个操作项：透明底、无涟漪克制反馈，文字居中自适应缩放。
 * @param emphasized true 时文字用主色（「开始练习」主 CTA）。
 */
@Composable
private fun GlassActionItem(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val pillSrc = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // 顶端玻璃胶囊：涟漪换成统一的缩放＋亮度（Container 档），
            // clip 仍在 clickable 之前 —— 指示层要跟着圆角裁，否则角上冒直角灰块。
            .pressFeedback(pillSrc, PressTier.Container)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = pillSrc,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            AdaptiveButtonLabel(text)
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

// ────────────────────────────────────────────────
// 计划状态条（文章详情页）
// ────────────────────────────────────────────────

/**
 * 状态条要展示的最小事实集。
 *
 * [hasPractice] 与 [inPlan] 必须分开：练过的文章自动算在计划里，只看 [inPlan]
 * 无法区分「还没开始」和「练过几次」这两种完全不同的文案。
 */
private data class ArticlePlanState(
    val practiceCount: Int,
    val accuracy: Float?,
    val inPlan: Boolean,
    val due: Long,
    val hasPractice: Boolean,
)

/**
 * 一行计划状态：左边「练了多少 / 练得怎么样」，右边「什么时候该再练」。
 *
 * 刻意不做成卡片：正文卡片已经是一块面，上面再叠一块面会抢正文的视觉重量。
 * 这一行只需要「扫一眼」，所以用 labelMedium + 低对比色；唯一的强调留给逾期（error 色）
 * 和「加入计划」（accent 文字 + 细描边）。
 */
@Composable
private fun ArticlePlanBar(
    plan: ArticlePlanState,
    onEnroll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 到期文案只要日级精度：进页面时取一次，不为它挂一个每秒重组的时间源
    val now = remember { System.currentTimeMillis() }
    val leftText = if (plan.hasPractice) {
        val base = pluralStringResource(
            com.ilyskyo.blancall.R.plurals.plan_bar_practices, plan.practiceCount, plan.practiceCount
        )
        val rate = plan.accuracy
        if (rate != null) {
            base + " · " + stringResource(
                com.ilyskyo.blancall.R.string.plan_bar_accuracy, (rate * 100f).roundToInt()
            )
        } else {
            base
        }
    } else {
        stringResource(com.ilyskyo.blancall.R.string.plan_bar_not_started)
    }
    val dueText = planDueLabel(plan, now)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = leftText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 左侧让出空间：右侧的到期文案/加入按钮永远完整可见，长数字只省略左侧
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        if (dueText == null) {
            PlanEnrollChip(onClick = onEnroll)
        } else {
            Text(
                text = dueText,
                style = MaterialTheme.typography.labelMedium,
                color = planDueColor(plan.due, now),
                maxLines = 1
            )
        }
    }
}

/** 到期文案；返回 null 表示「还没进计划」，由调用方换成加入按钮 */
@Composable
private fun planDueLabel(plan: ArticlePlanState, now: Long): String? {
    if (!plan.inPlan && !plan.hasPractice) return null
    // 没练过：这是计划开始时间，不是下一次复习
    val isNew = !plan.hasPractice
    return when (StudyFlow.phase(plan.due, false, now)) {
        StudyFlow.Phase.OVERDUE -> stringResource(
            if (isNew) com.ilyskyo.blancall.R.string.plan_bar_start_overdue
            else com.ilyskyo.blancall.R.string.plan_bar_overdue
        )
        StudyFlow.Phase.TODAY -> stringResource(
            if (isNew) com.ilyskyo.blancall.R.string.plan_bar_start_today
            else com.ilyskyo.blancall.R.string.plan_bar_due_today
        )
        StudyFlow.Phase.TOMORROW -> stringResource(
            if (isNew) com.ilyskyo.blancall.R.string.plan_bar_start_tomorrow
            else com.ilyskyo.blancall.R.string.plan_bar_due_tomorrow
        )
        else -> {
            // THIS_WEEK / LATER（PAUSED 在传 false 时不会出现，落到这里也无害）
            val days = ceil((plan.due - now).toDouble() / StudyFlow.MILLIS_PER_DAY)
                .toInt().coerceAtLeast(2)
            if (isNew) {
                pluralStringResource(
                    com.ilyskyo.blancall.R.plurals.plan_bar_start_days, days, days
                )
            } else {
                pluralStringResource(
                    com.ilyskyo.blancall.R.plurals.plan_bar_due_days, days, days
                )
            }
        }
    }
}

@Composable
private fun planDueColor(due: Long, now: Long): Color =
    when (StudyFlow.phase(due, false, now)) {
        StudyFlow.Phase.OVERDUE -> MaterialTheme.colorScheme.error
        StudyFlow.Phase.TODAY -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * 「加入计划」：详情页唯一「不练习也能进计划」的入口。
 *
 * 用细描边胶囊而不是实心按钮 —— 本页的主 CTA 是底部那颗「开始练习」，
 * 这里再放一颗实心按钮会和它抢注意力。
 */
@Composable
private fun PlanEnrollChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            // 项目统一手感：关掉 Material 涟漪，走 pressFeedback 的缩放反馈
            .pressClick { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(com.ilyskyo.blancall.R.string.plan_bar_enroll),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}