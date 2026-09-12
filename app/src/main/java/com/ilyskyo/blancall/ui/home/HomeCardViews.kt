// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.listItemEnter
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 首页画布「卡片内容」层。
 *
 * 画布容器（网格、拖拽、尺寸、大头针）由宿主负责；本文件只负责**按卡片类型渲染内容**，
 * 并通过 [HomeCardContent] 这一个入口分发。设计约束：
 * - 卡片宽高是动态的（画布可改列/行跨度）→ 内容一律 `fillMaxWidth()` + 纵向内滚，
 *   不写死大 padding，小尺寸下靠内部滚动吃下溢出内容；
 * - 宿主数据与导航一律以 lambda 传入（不持有 NavController）；
 * - 仅纯色 / 现有 GlassCard 玻璃质感，无任何 gradient。
 */
@Composable
fun HomeCardContent(
    /** 当前卡片（类型 / refId / 标题快照 / 跨度等） */
    card: HomeLayoutStore.Card,
    /** 全量文章，供「继续练习」按 articleId 反查标题 */
    articles: List<Article>,
    /** 今日待复习（宿主用 FSRS + 记录算好后传入） */
    dueArticles: List<Article>,
    /** 未完成的练习进度（宿主扫描 practice_state_*.json 得到） */
    resumables: List<ResumableItem>,
    /** 最近文章（宿主已过滤 homepage 隐藏项并倒序） */
    recentArticles: List<Article>,
    /** 最近文章日期格式化 */
    dateFormat: SimpleDateFormat,
    /** 需要上报练习按钮位置的文章 id（= 宿主当前的 pendingPracticeArticleId；≤0 表示不上报） */
    anchorArticleId: Long,
    /** 练习按钮位置回调（供模式选择弹窗锚定） */
    onAnchorMeasured: (articleId: Long, rect: Rect) -> Unit,
    /** 点「复习 / 开始练习」→ 宿主打开模式选择弹窗 */
    onPracticeArticle: (articleId: Long) -> Unit,
    /** 点「继续」→ 宿主按 mode 恢复练习 */
    onResumePractice: (item: ResumableItem) -> Unit,
    /** 点最近文章卡 → 打开阅读页 */
    onOpenArticle: (article: Article) -> Unit,
    /** 长按确认「从首页删除」→ 宿主持久化隐藏 */
    onRemoveFromHome: (article: Article) -> Unit,
    /** 「查看全部」→ 宿主跳文章列表 tab */
    onViewAllArticles: () -> Unit,
    /** 点「添加文章」入口卡 → 宿主跳导入页 */
    onAddArticle: () -> Unit,
    /** 点自定义挖空卡 → 宿主跳 `practice/<articleId>?configId=<configId>` */
    onOpenClozeConfig: (articleId: Long, configId: Long) -> Unit,
    /** 点自定义遮挡卡 → 宿主跳阅读页（写状态由本组件完成，见 [CustomMaskCard]） */
    onOpenMaskConfig: (articleId: Long, configId: Long) -> Unit,
    /** 自定义配置的外部修订号：配置被重命名/删除后宿主自增即可触发重新反查 */
    configRevision: Long = 0L,
    modifier: Modifier = Modifier,
) {
    when (card.type) {
        HomeLayoutStore.CardType.DUE -> DueCard(
            dueArticles = dueArticles,
            anchorArticleId = anchorArticleId,
            onAnchorMeasured = onAnchorMeasured,
            onPracticeArticle = onPracticeArticle,
            modifier = modifier,
        )

        HomeLayoutStore.CardType.CONTINUE -> ContinueCard(
            articles = articles,
            resumables = resumables,
            onResumePractice = onResumePractice,
            modifier = modifier,
        )

        HomeLayoutStore.CardType.RECENT -> RecentCard(
            recentArticles = recentArticles,
            dateFormat = dateFormat,
            anchorArticleId = anchorArticleId,
            onAnchorMeasured = onAnchorMeasured,
            onPracticeArticle = onPracticeArticle,
            onOpenArticle = onOpenArticle,
            onRemoveFromHome = onRemoveFromHome,
            onViewAllArticles = onViewAllArticles,
            modifier = modifier,
        )

        HomeLayoutStore.CardType.CUSTOM_CLOZE -> CustomClozeCard(
            card = card,
            configRevision = configRevision,
            onOpenClozeConfig = onOpenClozeConfig,
            modifier = modifier,
        )

        HomeLayoutStore.CardType.CUSTOM_MASK -> CustomMaskCard(
            card = card,
            configRevision = configRevision,
            onOpenMaskConfig = onOpenMaskConfig,
            modifier = modifier,
        )

        HomeLayoutStore.CardType.ADD_ARTICLE -> AddArticleCard(
            onAddArticle = onAddArticle,
            modifier = modifier,
        )
    }
}

// ══════════════════════════════════════════════════════════════════
// DUE —— 今日待复习
// ══════════════════════════════════════════════════════════════════

@Composable
private fun DueCard(
    dueArticles: List<Article>,
    anchorArticleId: Long,
    onAnchorMeasured: (Long, Rect) -> Unit,
    onPracticeArticle: (Long) -> Unit,
    modifier: Modifier,
) {
    val hue = Macaron.review()
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        containerColor = hue.fill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            CardHeader(
                dotColor = hue.accent,
                title = if (dueArticles.isEmpty()) "要复习的任务"
                else "要复习的任务（${dueArticles.size}篇）",
            )
            if (dueArticles.isEmpty()) {
                CardEmptyState(
                    icon = AppIconKind.Check,
                    title = "今日已没有要复习的",
                    subtitle = "明天再来看看",
                    accent = hue.accent,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                dueArticles.forEach { article ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPracticeArticle(article.id) }
                            .then(practiceAnchorModifier(article.id, anchorArticleId, onAnchorMeasured))
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                article.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (article.author.isNotBlank()) {
                                Text(
                                    article.author.trim(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            "复习",
                            style = MaterialTheme.typography.labelSmall,
                            color = hue.accent,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// CONTINUE —— 待继续完成
// ══════════════════════════════════════════════════════════════════

@Composable
private fun ContinueCard(
    articles: List<Article>,
    resumables: List<ResumableItem>,
    onResumePractice: (ResumableItem) -> Unit,
    modifier: Modifier,
) {
    val hue = Macaron.continueP()
    val articleMap = remember(articles) { articles.associateBy { it.id } }
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        containerColor = hue.fill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            CardHeader(
                dotColor = hue.accent,
                title = if (resumables.isEmpty()) "待继续完成"
                else "待继续完成（${resumables.size}）",
            )
            if (resumables.isEmpty()) {
                CardEmptyState(
                    icon = AppIconKind.TrackChanges,
                    title = "没有进行中的练习",
                    subtitle = "挑一篇文章开始练一把",
                    accent = hue.accent,
                )
            } else {
                Spacer(Modifier.height(2.dp))
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
                            .clickable { onResumePractice(item) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                art.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (art.author.isNotBlank()) append(art.author.trim()).append(" · ")
                                    append(modeLabel).append(" · 剩余 ")
                                    append(item.total - item.answered).append("/").append(item.total)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "继续",
                            style = MaterialTheme.typography.labelSmall,
                            color = hue.accent,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// RECENT —— 最近文章
// ══════════════════════════════════════════════════════════════════

/** 最近文章超过该数量时，卡片内提供一个「查看全部」入口 */
private const val RECENT_VIEW_ALL_THRESHOLD = 5

@Composable
private fun RecentCard(
    recentArticles: List<Article>,
    dateFormat: SimpleDateFormat,
    anchorArticleId: Long,
    onAnchorMeasured: (Long, Rect) -> Unit,
    onPracticeArticle: (Long) -> Unit,
    onOpenArticle: (Article) -> Unit,
    onRemoveFromHome: (Article) -> Unit,
    onViewAllArticles: () -> Unit,
    modifier: Modifier,
) {
    // 长按"从首页删除"弹窗：目标文章由卡片内部状态承载，确认后回调宿主持久化
    var hideTarget by remember { mutableStateOf<Article?>(null) }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                "最近使用",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )

            if (recentArticles.isEmpty()) {
                CardEmptyState(
                    icon = AppIconKind.Inbox,
                    title = "还没有文章",
                    subtitle = "点「添加文章」导入第一篇",
                    accent = MaterialTheme.colorScheme.primary,
                )
            } else {
                recentArticles.forEachIndexed { index, article ->
                    AnimatedVisibility(visible = true, enter = listItemEnter(index)) {
                        RecentArticleRow(
                            article = article,
                            dateFormat = dateFormat,
                            onClick = { onOpenArticle(article) },
                            onLongClick = { hideTarget = article },
                            onPractice = { onPracticeArticle(article.id) },
                            practiceModifier = practiceAnchorModifier(
                                articleId = article.id,
                                anchorArticleId = anchorArticleId,
                                onAnchorMeasured = onAnchorMeasured,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (recentArticles.size > RECENT_VIEW_ALL_THRESHOLD) {
                    TextButton(
                        onClick = onViewAllArticles,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看全部（${recentArticles.size} 篇）")
                    }
                }
            }
        }
    }

    hideTarget?.let { target ->
        BlancallAlertDialog(
            onDismissRequest = { hideTarget = null },
            title = { Text("从首页删除") },
            text = {
                Column {
                    Text(
                        "「${target.title}」将从首页最近文章中移除。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可在「我的文章」中继续查看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    hideTarget = null
                    onRemoveFromHome(target)
                }) { Text("从首页删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { hideTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RecentArticleRow(
    article: Article,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPractice: () -> Unit,
    practiceModifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    if (article.author.isNotBlank()) append(article.author.trim()).append(" · ")
                    append(article.content.length).append(" 字符")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = article.content.take(50).replace("\n", " "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dateFormat.format(Date(article.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Button(
                onClick = onPractice,
                modifier = practiceModifier.height(34.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text("开始练习", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// CUSTOM_CLOZE —— 自定义挖空（一张卡 = 一套配置）
// ══════════════════════════════════════════════════════════════════

@Composable
private fun CustomClozeCard(
    card: HomeLayoutStore.Card,
    configRevision: Long,
    onOpenClozeConfig: (Long, Long) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val fallbackTitle = card.title
    val resolved by produceState<ConfigLookup>(
        initialValue = ConfigLookup.Loading,
        card.refId,
        configRevision,
    ) {
        value = withContext(Dispatchers.IO) { resolveCloze(context.filesDir, card.refId) }
    }
    val hue = Macaron.lavender()
    val found = resolved as? ConfigLookup.Found
    val onClick: (() -> Unit)? = if (found == null) null else {
        { onOpenClozeConfig(found.articleId, card.refId) }
    }

    CustomConfigCardFrame(
        title = found?.name ?: fallbackTitle.ifBlank { "自定义挖空" },
        detail = found?.detail,
        dotColor = hue.accent,
        accent = hue.accent,
        containerColor = hue.fill,
        onClick = onClick,
        modifier = modifier,
    )
}

// ══════════════════════════════════════════════════════════════════
// CUSTOM_MASK —— 自定义遮挡（一张卡 = 一套配置）
// ══════════════════════════════════════════════════════════════════

@Composable
private fun CustomMaskCard(
    card: HomeLayoutStore.Card,
    configRevision: Long,
    onOpenMaskConfig: (Long, Long) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fallbackTitle = card.title
    val resolved by produceState<ConfigLookup>(
        initialValue = ConfigLookup.Loading,
        card.refId,
        configRevision,
    ) {
        value = withContext(Dispatchers.IO) { resolveMask(context.filesDir, card.refId) }
    }
    val hue = Macaron.info()
    val found = resolved as? ConfigLookup.Found
    // 与 MaskConfigListScreen「使用」一致：标记选中配置 + 遮挡粒度切自定义 + 打开遮挡开关，再进阅读页
    val onClick: (() -> Unit)? = if (found == null) null else {
        {
            scope.launch {
                val store = MaskConfigStore.getInstance(context.filesDir)
                withContext(Dispatchers.IO) { store.setSelected(found.articleId, card.refId) }
                AppPrefs.readingOcclusionCustomConfigId = card.refId
                AppPrefs.readingOcclusionMode = "custom"
                AppPrefs.readingOcclusionEnabled = true
                onOpenMaskConfig(found.articleId, card.refId)
            }
        }
    }

    CustomConfigCardFrame(
        title = found?.name ?: fallbackTitle.ifBlank { "自定义遮挡" },
        detail = found?.detail,
        dotColor = hue.accent,
        accent = hue.accent,
        containerColor = hue.fill,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 自定义配置卡（挖空 / 遮挡共用外观）：配置名 + 明细 + 「点击进入」提示；
 * 反查不到（配置已被删除）时展示淡色「配置已失效」且不可点击。
 */
@Composable
private fun CustomConfigCardFrame(
    title: String,
    detail: String?,
    dotColor: Color,
    accent: Color,
    containerColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        containerColor = containerColor,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (detail == null) {
                Text(
                    "配置已失效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            } else {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "点击进入",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// ADD_ARTICLE —— 添加文章入口
// ══════════════════════════════════════════════════════════════════

@Composable
private fun AddArticleCard(
    onAddArticle: () -> Unit,
    modifier: Modifier,
) {
    val hue = Macaron.info()
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        containerColor = hue.fill,
        onClick = onAddArticle,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(hue.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        kind = AppIconKind.Add,
                        modifier = Modifier.size(20.dp),
                        tint = hue.accent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "添加文章",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        "导入新文章开始练习",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// 共用小件
// ══════════════════════════════════════════════════════════════════

/** 卡片标题行：同族圆点 + 标题（与既有待复习 / 继续练习卡一致） */
@Composable
private fun CardHeader(dotColor: Color, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 精致空态：柔和圆形图标底 + 主文案 + 一行淡色副文案，居中、留白舒适。
 * 供「待复习」「继续练习」「最近文章」三种卡的零数据场景复用。
 */
@Composable
private fun CardEmptyState(
    icon: AppIconKind,
    title: String,
    subtitle: String,
    accent: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                kind = icon,
                modifier = Modifier.size(26.dp),
                tint = accent,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 练习按钮位置上报修饰符：仅当该行/卡正是宿主当前 pending 的文章时挂上，
 * 与 HomeScreen 原有 `practiceModifier` 行为一致——弹窗据此锚定。
 */
private fun practiceAnchorModifier(
    articleId: Long,
    anchorArticleId: Long,
    onAnchorMeasured: (Long, Rect) -> Unit,
): Modifier =
    if (articleId == anchorArticleId) {
        Modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            onAnchorMeasured(
                articleId,
                Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height),
            )
        }
    } else {
        Modifier
    }

// ══════════════════════════════════════════════════════════════════
// 自定义配置反查（只读；不改 store）
// ══════════════════════════════════════════════════════════════════

/** 配置反查结果：加载中 / 已失效 / 命中（含所属文章 id、名称、明细） */
private sealed class ConfigLookup {
    object Loading : ConfigLookup()
    object NotFound : ConfigLookup()
    data class Found(val articleId: Long, val name: String, val detail: String) : ConfigLookup()
}

/**
 * 按 configId 反查自定义挖空配置所属文章。
 *
 * store 未提供「按 configId 反查文章」的方法，故此处直接读全量 JSON 再查（只读，不写盘）。
 * 主文件损坏时回读 .bak，与 store 的读盘语义保持一致。
 */
private fun resolveCloze(filesDir: File, configId: Long): ConfigLookup {
    if (configId <= 0L) return ConfigLookup.NotFound
    val root = readJsonWithBackup(filesDir, "custom_cloze.json") ?: return ConfigLookup.NotFound
    val articles = root.optJSONObject("articles") ?: return ConfigLookup.NotFound
    val keys = articles.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val arr = articles.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != configId) continue
            val articleId = key.toLongOrNull() ?: return ConfigLookup.NotFound
            val name = o.optString("name", "").ifBlank { "自定义挖空" }
            val blankCount = o.optJSONArray("blanks")?.length() ?: 0
            val mode = clozeModeLabel(o.optString("mode", "WORD"))
            return ConfigLookup.Found(articleId, name, "$blankCount 空 · $mode")
        }
    }
    return ConfigLookup.NotFound
}

/** 按 configId 反查自定义遮挡配置所属文章（读全量 JSON，只读） */
private fun resolveMask(filesDir: File, configId: Long): ConfigLookup {
    if (configId <= 0L) return ConfigLookup.NotFound
    val root = readJsonWithBackup(filesDir, "mask_config.json") ?: return ConfigLookup.NotFound
    val articles = root.optJSONObject("articles") ?: return ConfigLookup.NotFound
    val keys = articles.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val arr = articles.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != configId) continue
            val articleId = key.toLongOrNull() ?: return ConfigLookup.NotFound
            val name = o.optString("name", "").ifBlank { "自定义遮挡" }
            val spanCount = o.optJSONArray("spans")?.length() ?: 0
            return ConfigLookup.Found(articleId, name, "$spanCount 块遮挡")
        }
    }
    return ConfigLookup.NotFound
}

/** 读 JSON：主文件优先，损坏回读同名 .bak；都不可用返回 null */
private fun readJsonWithBackup(filesDir: File, name: String): JSONObject? {
    val main = File(filesDir, name)
    if (main.exists()) {
        try {
            return JSONObject(main.readText())
        } catch (_: Exception) {
            // 落到备份分支
        }
    }
    val bak = File(filesDir, name + ".bak")
    if (bak.exists()) {
        try {
            return JSONObject(bak.readText())
        } catch (_: Exception) {
            // 备份也不可用
        }
    }
    return null
}

private fun clozeModeLabel(mode: String): String = when (mode) {
    "SENTENCE" -> "句子挖空"
    "WORD" -> "字词挖空"
    "REVERSE" -> "反向默写"
    else -> "练习"
}
