// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
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
 * - 卡片宽高由画布给定且**固定** → 每张卡的根容器一律 `fillMaxSize()` 吃满给定尺寸，
 *   绝不按内容自身高度 wrap 而溢出到卡片外；内容超出时**在卡片内部滚动**。
 * - 标题与滚动区分离：标题固定在卡片顶部、正文占满余下空间并内滚 → 任何尺寸下标题都不会
 *   与内部内容重叠（「最近使用」卡的回落问题即由此消除）。
 * - 三档尺寸自适应（见 [CardMetrics]）：窄卡收紧内边距并隐藏次要信息、矮卡只留主干单行、
 *   高卡内容少时做纵向分布 → 任何 drawable 尺寸下都不出现「半截文字」。
 * - 宿主数据与导航一律以 lambda 传入（不持有 NavController）；
 * - 仅纯色 / 现有 GlassCard 玻璃质感，无任何 gradient。
 */

/** 窄卡阈值：宽度小于该值（典型为 colSpan=1）时收紧内边距、隐藏次要信息 */
private val NARROW_CARD_WIDTH = 168.dp

/**
 * 首页「学习数据 / 全局数据」卡片的数据快照。
 * 由宿主在 HomeScreen 里算好后传入，卡片自身不做统计计算。
 */
data class HomeStatsData(
    /** 累计练习次数 */
    val practices: Int = 0,
    /** 总正确率 0f..1f */
    val rate: Float = 0f,
    /** 累计填空数（全局数据的「累计字数」） */
    val blanks: Int = 0,
    /** 已有文章数 */
    val articleCount: Int = 0
)

/** 高卡阈值：高度达到该值（约 rowSpan≥2）时，内容少则纵向分布，避免全挤在顶部一小块 */
private val TALL_CARD_HEIGHT = 140.dp

/**
 * 矮卡阈值：高度小于该值（= `rowSpan 1`，画布扣掉槽位内缩后约 82dp）时，
 * 卡片可视高度只剩主干一行多一点，只保留主线信息、砍掉全部次要行。
 */
private val SHORT_CARD_HEIGHT = 140.dp

/** 首页卡片统一圆角：编辑态描环按同一曲率绘制，内外圆角一致 */
internal val HOME_CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * 卡片尺寸度量：一次量好宽高，供各卡按「窄（1 列）/ 矮（1 行）/ 高（≥2 行）」三档自适应。
 *
 * 三者由画布给定尺寸推导，互不依赖内容——内容多少只影响卡内是否滚动，
 * 不影响档位，避免「内容一变布局就跳」。
 */
private data class CardMetrics(
    /** 窄卡：宽度不足 [NARROW_CARD_WIDTH]（典型 `colSpan=1`） */
    val compact: Boolean,
    /** 矮卡：高度不足 [SHORT_CARD_HEIGHT]（典型 `rowSpan=1`） */
    val short: Boolean,
    /** 高卡：高度达到 [TALL_CARD_HEIGHT]（典型 `rowSpan≥2`） */
    val tall: Boolean,
    /** 卡片可视高度：列表类卡片据此裁剪条数——宁可少一条，也不要在卡底露出半截内容 */
    val maxHeight: Dp,
) {
    /** 卡片内边距：窄卡 / 矮卡收紧到 10dp，其余 14dp */
    val pad = if (compact || short) 10.dp else 14.dp
}

/** 由画布给定的卡片尺寸推导度量：窄 / 矮 / 高三档互不依赖内容，内容多少只影响卡内是否滚动 */
private fun BoxWithConstraintsScope.CardMetrics(): CardMetrics = CardMetrics(
    compact = maxWidth < NARROW_CARD_WIDTH,
    short = maxHeight < SHORT_CARD_HEIGHT,
    tall = maxHeight >= TALL_CARD_HEIGHT,
    maxHeight = maxHeight,
)

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
    /** 学习数据 / 全局数据卡片的统计快照（由宿主算好传入） */
    stats: HomeStatsData = HomeStatsData(),
    /** 点学习数据 / 全局数据卡 → 宿主跳统计页 */
    onOpenStats: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 卡片尺寸由画布固定。这里统一量一次实际宽高，供各卡做「窄 / 矮 / 高」三档自适应；
    // 各卡再以 fillMaxSize() 吃满这个固定尺寸，绝不按内容 wrap（那正是内容溢出卡外的根因）。
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val m = CardMetrics()
        val cardModifier = Modifier.fillMaxSize()

        when (card.type) {
            HomeLayoutStore.CardType.DUE -> DueCard(
                dueArticles = dueArticles,
                anchorArticleId = anchorArticleId,
                onAnchorMeasured = onAnchorMeasured,
                onPracticeArticle = onPracticeArticle,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.CONTINUE -> ContinueCard(
                articles = articles,
                resumables = resumables,
                onResumePractice = onResumePractice,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.RECENT -> RecentCard(
                recentArticles = recentArticles,
                dateFormat = dateFormat,
                anchorArticleId = anchorArticleId,
                onAnchorMeasured = onAnchorMeasured,
                onPracticeArticle = onPracticeArticle,
                onOpenArticle = onOpenArticle,
                onViewAllArticles = onViewAllArticles,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.ARTICLE -> ArticleCard(
                article = articles.firstOrNull { it.id == card.refId },
                dateFormat = dateFormat,
                anchorArticleId = anchorArticleId,
                onAnchorMeasured = onAnchorMeasured,
                onPracticeArticle = onPracticeArticle,
                onOpenArticle = onOpenArticle,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.CUSTOM_CLOZE -> CustomClozeCard(
                card = card,
                configRevision = configRevision,
                onOpenClozeConfig = onOpenClozeConfig,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.CUSTOM_MASK -> CustomMaskCard(
                card = card,
                configRevision = configRevision,
                onOpenMaskConfig = onOpenMaskConfig,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.ADD_ARTICLE -> AddArticleCard(
                onAddArticle = onAddArticle,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.STATS -> StatsCard(
                stats = stats,
                onOpenStats = onOpenStats,
                m = m,
                modifier = cardModifier,
            )

            HomeLayoutStore.CardType.GLOBAL_STATS -> GlobalStatsCard(
                stats = stats,
                onOpenStats = onOpenStats,
                m = m,
                modifier = cardModifier,
            )
        }
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
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.review()
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = hue.fill,
    ) {
        // 外层 Column 不滚动：标题固定在顶部、正文吃满余下空间并内滚 → 标题永不与内容重叠
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(m.pad),
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
                    compact = m.compact,
                    short = m.short,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Spacer(Modifier.height(if (m.short) 0.dp else if (m.compact) 2.dp else 4.dp))
                CardBody(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = if (m.tall && dueArticles.size <= 3) {
                        Arrangement.SpaceEvenly
                    } else {
                        Arrangement.Top
                    },
                ) {
                    dueArticles.forEach { article ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPracticeArticle(article.id) }
                                .then(practiceAnchorModifier(article.id, anchorArticleId, onAnchorMeasured))
                                .padding(vertical = if (m.short) 2.dp else if (m.compact) 3.dp else 5.dp),
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
                                // 矮卡（1 行高）只留标题：作者行会把两行内容顶出卡片可视区
                                if (article.author.isNotBlank() && !m.short) {
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
                                maxLines = 1,
                                modifier = Modifier.padding(start = if (m.compact) 6.dp else 8.dp),
                            )
                        }
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
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.continueP()
    val articleMap = remember(articles) { articles.associateBy { it.id } }
    // 先解析出真正能渲染的行（文章可能已被删除），据此决定纵向分布与标题数量
    val rows = remember(resumables, articleMap) {
        resumables.mapNotNull { item -> articleMap[item.articleId]?.let { item to it } }
    }
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = hue.fill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(m.pad),
        ) {
            CardHeader(
                dotColor = hue.accent,
                title = if (rows.isEmpty()) "待继续完成"
                else "待继续完成（${rows.size}）",
            )
            if (rows.isEmpty()) {
                CardEmptyState(
                    icon = AppIconKind.TrackChanges,
                    title = "没有进行中的练习",
                    subtitle = "挑一篇文章开始练一把",
                    accent = hue.accent,
                    compact = m.compact,
                    short = m.short,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Spacer(Modifier.height(if (m.short) 0.dp else if (m.compact) 2.dp else 4.dp))
                CardBody(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = if (m.tall && rows.size <= 3) {
                        Arrangement.SpaceEvenly
                    } else {
                        Arrangement.Top
                    },
                ) {
                    rows.forEach { (item, art) ->
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
                                .padding(vertical = if (m.short) 2.dp else if (m.compact) 3.dp else 5.dp),
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
                                // 矮卡（1 行高）只留标题，副行会把内容顶出可视区
                                if (!m.short) {
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
                            }
                            Text(
                                "继续",
                                style = MaterialTheme.typography.labelSmall,
                                color = hue.accent,
                                maxLines = 1,
                                modifier = Modifier.padding(start = if (m.compact) 6.dp else 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// RECENT —— 最近文章
// ══════════════════════════════════════════════════════════════════

@Composable
private fun RecentCard(
    recentArticles: List<Article>,
    dateFormat: SimpleDateFormat,
    anchorArticleId: Long,
    onAnchorMeasured: (Long, Rect) -> Unit,
    onPracticeArticle: (Long) -> Unit,
    onOpenArticle: (Article) -> Unit,
    onViewAllArticles: () -> Unit,
    m: CardMetrics,
    modifier: Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(m.pad),
        ) {
            // 列表条数：按可用高度裁剪，宁可少一条也不在卡底露出半截文章行
            // （旧版 forEach 全量铺，超出部分被硬裁，2 行高卡会留一条切了一半的正文）
            val rowH = if (m.short) 28.dp else if (m.compact) 92.dp else 100.dp
            val gap = if (m.short) 4.dp else if (m.compact) 6.dp else 8.dp
            val titleH = 20.dp + if (m.short) 4.dp else if (m.compact) 6.dp else 8.dp
            // 4dp 安全余量：行高是估算值，宁可保守
            val avail = m.maxHeight - m.pad * 2 - titleH - 4.dp
            val full = (((avail + gap) / (rowH + gap)).toInt()).coerceAtLeast(1)
            val shown = recentArticles.take(full)

            // 标题行：「查看全部」入口挂在标题右侧，不占列表行高（列表区本来就紧），
            // 有溢出时才出现——任何尺寸下都能看到入口，不必挤掉一整篇文章行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 2.dp,
                        bottom = if (m.short) 4.dp else if (m.compact) 6.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近使用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (recentArticles.size > shown.size) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看全部 ${recentArticles.size} 篇 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onViewAllArticles)
                            .padding(horizontal = 4.dp),
                    )
                }
            }

            if (recentArticles.isEmpty()) {
                CardEmptyState(
                    icon = AppIconKind.Inbox,
                    title = "还没有文章",
                    subtitle = "点「添加文章」导入第一篇",
                    accent = MaterialTheme.colorScheme.primary,
                    compact = m.compact,
                    short = m.short,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                CardBody(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = if (m.tall && shown.size <= 3) {
                        Arrangement.SpaceEvenly
                    } else {
                        Arrangement.Top
                    },
                ) {
                    shown.forEachIndexed { index, article ->
                        AnimatedVisibility(visible = true, enter = listItemEnter(index)) {
                            RecentArticleRow(
                                article = article,
                                dateFormat = dateFormat,
                                compact = m.compact,
                                dense = m.short,
                                onClick = { onOpenArticle(article) },
                                onPractice = { onPracticeArticle(article.id) },
                                practiceModifier = practiceAnchorModifier(
                                    articleId = article.id,
                                    anchorArticleId = anchorArticleId,
                                    onAnchorMeasured = onAnchorMeasured,
                                ),
                            )
                        }
                        Spacer(Modifier.height(gap))
                    }
                }
            }
        }
    }
}

/**
 * 最近文章行。
 *
 * [dense]（真·单行）：标题 + 字符数 + 「练习」按钮同排；
 * [slim]（超紧凑三行）：给「文章卡片」的矮卡用 —— 标题+字符数 / 摘要 / 日期+小按钮，
 * 间距减半，完整信息也能塞进 1 行高的卡里（用户要求「小小的、像图 2 这样」）。
 * 两者都 false 时为标准三行（标题+字符数 / 摘要 / 日期 + 按钮）。
 */
@Composable
private fun RecentArticleRow(
    article: Article,
    dateFormat: SimpleDateFormat,
    compact: Boolean,
    dense: Boolean,
    onClick: () -> Unit,
    onPractice: () -> Unit,
    practiceModifier: Modifier,
    slim: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(
                start = if (compact || slim) 10.dp else 12.dp,
                end = if (compact || slim) 10.dp else 12.dp,
                // slim：上窄下宽 —— 按钮离卡底略远一点，视觉更平衡（用户反馈「太靠着底」）
                top = if (dense) 1.dp else if (slim) 3.dp else if (compact) 8.dp else 10.dp,
                bottom = if (dense) 1.dp else if (slim) 6.dp else if (compact) 8.dp else 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            // 窄卡下隐藏「作者·字符数」次要信息，把宽度让给标题（避免标题被挤没）
            if (!compact && !dense) {
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
                    modifier = Modifier.weight(0.6f, fill = false),
                )
            }
            // dense（矮卡）：字符数 + 练习按钮与标题同排 —— 整行只有一行高
            if (dense) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${article.content.length} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                PracticeButton(compact = true, dense = true, onPractice = onPractice, modifier = practiceModifier)
            }
        }

        if (!dense) {
            if (slim) {
                // 超紧凑三行：摘要 + 日期/按钮（间距压到最小，适应 1 行高的卡）
                Spacer(Modifier.height(1.dp))
                Text(
                    text = article.content.take(50).replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateFormat.format(Date(article.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                    // 按钮用标准尺寸（compact 30dp）—— 矮卡里也和其它卡一样大（用户反馈「练习键可以大的」）
                    PracticeButton(
                        compact = true,
                        onPractice = onPractice,
                        modifier = practiceModifier,
                    )
                }
            } else {
                Spacer(Modifier.height(2.dp))

                Text(
                    text = article.content.take(50).replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(if (compact) 4.dp else 6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateFormat.format(Date(article.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                    PracticeButton(compact = compact, onPractice = onPractice, modifier = practiceModifier)
                }
            }
        }
    }
}

/** 「开始练习 / 练习」按钮（最近文章卡专用，矮卡收成小尺寸 / 极矮卡再收一档） */
@Composable
private fun PracticeButton(
    compact: Boolean,
    onPractice: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    Button(
        onClick = onPractice,
        modifier = modifier.height(if (dense) 24.dp else if (compact) 30.dp else 34.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(
            horizontal = if (dense) 8.dp else if (compact) 10.dp else 12.dp,
            vertical = 0.dp,
        ),
    ) {
        Text(
            if (compact) "练习" else "开始练习",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

// ══════════════════════════════════════════════════════════════════
// ARTICLE —— 文章卡片（单独固定某一篇）
// ══════════════════════════════════════════════════════════════════

/**
 * 文章卡片：把某一篇具体文章单独放成一张卡（与「最近文章」不同，内容不随最近打开变化）。
 *
 * 内容**完全复用「最近使用」卡里的文章行**（[RecentArticleRow]，同款排版）：
 * - 标准/高卡：标题 + 字符数 / 摘要 / 日期 + 开始练习；
 * - 矮卡（1 行高）：标题 + 字符数 + 练习按钮单行收拢；
 * - 点行体→ 打开阅读；点练习→ 模式选择弹窗（按钮位置上报锚点）；
 * - 文章已被删除（article == null）→ 空态提示，长按进编辑态可删除本卡。
 */
@Composable
private fun ArticleCard(
    article: Article?,
    dateFormat: SimpleDateFormat,
    anchorArticleId: Long,
    onAnchorMeasured: (Long, Rect) -> Unit,
    onPracticeArticle: (Long) -> Unit,
    onOpenArticle: (Article) -> Unit,
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.warn()
    // 卡面保持默认素净底色（不再用蜜桃色整卡铺底——用户反馈「黄色太丑」，
    // 与「最近使用」观感一致；hue 仅用于空态图标点缀）
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
    ) {
        if (article == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.pad),
            ) {
                CardHeader(dotColor = hue.accent, title = "文章卡片")
                CardEmptyState(
                    icon = AppIconKind.Inbox,
                    title = "文章已不存在",
                    subtitle = "长按卡片可删除这张卡",
                    accent = hue.accent,
                    compact = m.compact,
                    short = m.short,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        } else {
            // 与「最近使用」卡内的文章行同款内容；矮卡（1 行高）用 slim 紧凑三行 ——
            // 用户要求「卡片小小的、内容像图 2 那样完整」
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (m.short) 2.dp else m.pad),
                verticalArrangement = Arrangement.Center,
            ) {
                RecentArticleRow(
                    article = article,
                    dateFormat = dateFormat,
                    compact = m.compact,
                    dense = false,
                    slim = m.short,
                    onClick = { onOpenArticle(article) },
                    onPractice = { onPracticeArticle(article.id) },
                    practiceModifier = practiceAnchorModifier(
                        articleId = article.id,
                        anchorArticleId = anchorArticleId,
                        onAnchorMeasured = onAnchorMeasured,
                    ),
                )
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
    m: CardMetrics,
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
        m = m,
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
    m: CardMetrics,
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
        m = m,
        modifier = modifier,
    )
}

/**
 * 自定义配置卡（挖空 / 遮挡共用外观）：配置名 + 明细 + 「点击进入」提示；
 * 反查不到（配置已被删除）时展示淡色「配置已失效」且不可点击。
 *
 * 内容整体在卡片内 **垂直居中**（高卡不空、矮卡内滚），文字均带上限与省略号。
 */
@Composable
private fun CustomConfigCardFrame(
    title: String,
    detail: String?,
    dotColor: Color,
    accent: Color,
    containerColor: Color,
    onClick: (() -> Unit)?,
    m: CardMetrics,
    modifier: Modifier,
) {
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = containerColor,
        onClick = onClick,
    ) {
        if (m.short) {
            // 矮卡（1 行高，可视约 82dp）：压成单行 —— 圆点 + 名称 + 明细，
            // 纵排的「点击进入」会把标题顶出卡外，只剩半截文字。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.pad),
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
                Spacer(Modifier.width(8.dp))
                Text(
                    detail ?: "配置已失效",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (detail == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    },
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            CardBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.pad),
                verticalArrangement = Arrangement.Center,
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
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (detail == null) {
                    Text(
                        "配置已失效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                        maxLines = 1,
                    )
                }
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
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.info()
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = hue.fill,
        onClick = onAddArticle,
    ) {
        if (m.short) {
            // 矮卡（1 行高）：单行横排 —— 图标 + 标题 + 「导入」动作提示
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.pad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardIconBadge(
                    kind = AppIconKind.Add,
                    accent = hue.accent,
                    boxSize = if (m.compact) 26.dp else 30.dp,
                    iconSize = if (m.compact) 15.dp else 17.dp,
                )
                Spacer(Modifier.width(if (m.compact) 6.dp else 8.dp))
                Text(
                    "添加文章",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = hue.accent,
                    maxLines = 1,
                )
            }
        } else {
            CardBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardIconBadge(
                        kind = AppIconKind.Add,
                        accent = hue.accent,
                        boxSize = if (m.compact) 30.dp else 36.dp,
                        iconSize = if (m.compact) 17.dp else 20.dp,
                    )
                    Spacer(Modifier.width(if (m.compact) 8.dp else 10.dp))
                    Column {
                        Text(
                            "添加文章",
                            style = if (m.compact) MaterialTheme.typography.bodySmall
                            else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!m.compact) {
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
    }
}

// ══════════════════════════════════════════════════════════════════
// STATS —— 学习数据（练习摘要，点进统计页）
// ══════════════════════════════════════════════════════════════════

@Composable
private fun StatsCard(
    stats: HomeStatsData,
    onOpenStats: () -> Unit,
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.lavender()
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = hue.fill,
        onClick = onOpenStats,
    ) {
        if (m.short) {
            // 矮卡（1 行高，可视约 82dp）：横排一行 —— 徽标 + 标题 + 摘要。
            // 纵排三行会被裁掉一半（用户反馈「不同尺寸内容显示异常」）。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.pad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardIconBadge(
                    kind = AppIconKind.Insights,
                    accent = hue.accent,
                    boxSize = if (m.compact) 26.dp else 30.dp,
                    iconSize = if (m.compact) 15.dp else 17.dp,
                )
                Spacer(Modifier.width(if (m.compact) 6.dp else 8.dp))
                Text(
                    "学习数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    statsSummary(stats, m.compact),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            // 高卡（≥2 行高，short 已在上方拦下）：图标 + 标题 + 大号练习次数 + 指标副行。
            // 与「全局数据」的数据行列表拉开差异：这里是「大数字」版式，旧版三行居中小字号会让 2 行高卡显得空。
            CardBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.pad),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CardIconBadge(
                    kind = AppIconKind.Insights,
                    accent = hue.accent,
                    boxSize = if (m.compact) 32.dp else 38.dp,
                    iconSize = if (m.compact) 17.dp else 20.dp,
                )
                Text(
                    "学习数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 还没练习过时显示「还没有练习记录」，避免「0 次 · 0%」的视觉空旷与错愕
                if (stats.practices == 0) {
                    Text(
                        "还没有练习记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${stats.practices}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = hue.accent,
                                maxLines = 1,
                            )
                            Text(
                                " 次练习",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                        Text(
                            "正确率 ${(stats.rate * 100).toInt()}% · 累计 ${stats.blanks} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "查看详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = hue.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 学习数据矮卡摘要：窄卡只留练习次数，避免与标题挤成省略号 */
private fun statsSummary(stats: HomeStatsData, compact: Boolean): String = when {
    stats.practices == 0 -> "暂无记录"
    compact -> "${stats.practices} 次"
    else -> "${stats.practices} 次 · ${(stats.rate * 100).toInt()}%"
}

// ══════════════════════════════════════════════════════════════════
// GLOBAL_STATS —— 全局数据（累计统计）
// ══════════════════════════════════════════════════════════════════

@Composable
private fun GlobalStatsCard(
    stats: HomeStatsData,
    onOpenStats: () -> Unit,
    m: CardMetrics,
    modifier: Modifier,
) {
    val hue = Macaron.neutral()
    GlassCard(
        modifier = modifier,
        shape = HOME_CARD_SHAPE,
        containerColor = hue.fill,
        onClick = onOpenStats,
    ) {
        if (m.short) {
            // 矮卡（1 行高）：横排一行，与「学习数据」卡同构；纵排数据行会被裁到只剩一行
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.pad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardIconBadge(
                    kind = AppIconKind.Insights,
                    accent = hue.accent,
                    boxSize = if (m.compact) 26.dp else 30.dp,
                    iconSize = if (m.compact) 15.dp else 17.dp,
                )
                Spacer(Modifier.width(if (m.compact) 6.dp else 8.dp))
                Text(
                    "全局数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    globalStatsSummary(stats, m.compact),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            CardBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(m.pad),
                verticalArrangement = if (m.tall) Arrangement.SpaceEvenly else Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardIconBadge(
                        kind = AppIconKind.Insights,
                        accent = hue.accent,
                        boxSize = if (m.compact) 26.dp else 32.dp,
                        iconSize = if (m.compact) 15.dp else 18.dp,
                    )
                    Spacer(Modifier.width(if (m.compact) 6.dp else 8.dp))
                    Text(
                        "全局数据",
                        style = if (m.compact) MaterialTheme.typography.bodySmall
                        else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(if (m.compact) 4.dp else 6.dp))
                // 空态：避免「0 次 / 0% / 0 字 / 0 篇」堆一排看起来空且困惑
                if (stats.practices == 0) {
                    Text(
                        "还没有全局练习数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    GlobalStatRow("练习总数", "${stats.practices} 次", dense = m.compact)
                    GlobalStatRow("平均正确率", "${(stats.rate * 100).toInt()}%", dense = m.compact)
                    // 窄卡只留两行，避免文字被挤成省略号
                    if (!m.compact) GlobalStatRow("累计填空", "${stats.blanks} 字", dense = false)
                    if (m.tall && !m.compact) {
                        GlobalStatRow("覆盖文章", "${stats.articleCount} 篇", dense = false)
                    }
                }
            }
        }
    }
}

/** 全局数据矮卡摘要：窄卡只留累计填空数，避免与标题挤成省略号 */
private fun globalStatsSummary(stats: HomeStatsData, compact: Boolean): String = when {
    stats.practices == 0 -> "暂无数据"
    compact -> "${stats.blanks} 字"
    else -> "${stats.blanks} 字 · ${stats.articleCount} 篇"
}

@Composable
private fun GlobalStatRow(label: String, value: String, dense: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (dense) 1.dp else 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════════════
// 共用小件
// ══════════════════════════════════════════════════════════════════

/**
 * 卡片正文区：**先吃满调用方给定的空间，再纵向内滚**。
 *
 * - 内容不足一屏时按 [verticalArrangement] 排布（高卡可 `SpaceEvenly` / `Center` 填空，不显得空）；
 * - 内容超出一屏时在卡片内部滚动，绝不画出卡片外。
 *
 * 调用方需传入带确定高度的 modifier：`fillMaxSize()`（直接铺满卡片）或
 * `fillMaxWidth().weight(1f)`（标题下方占满余下空间）。
 */
@Composable
private fun CardBody(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** 各功能卡统一的圆形浅色徽标：柔和底色 + 同色系图标 */
@Composable
private fun CardIconBadge(
    kind: AppIconKind,
    accent: Color,
    boxSize: Dp,
    iconSize: Dp,
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(kind = kind, modifier = Modifier.size(iconSize), tint = accent)
    }
}

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
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 精致空态：柔和圆形图标底 + 主文案 + 一行淡色副文案。
 * 供「待复习」「继续练习」「最近文章」三种卡的零数据场景复用。
 *
 * 通过内滚 + [Arrangement.Center] 实现「空间富余时垂直居中、空间不足时卡内滚动」：
 * 图标永远完整可见（不会出现被切一半），小卡下自动缩小图标并隐藏副文案。
 */
@Composable
private fun CardEmptyState(
    icon: AppIconKind,
    title: String,
    subtitle: String,
    accent: Color,
    compact: Boolean,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    if (short) {
        // 矮卡（1 行高）：横排单行 —— 小图标 + 主文案；纵向堆叠会把两者顶出可视区
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(kind = icon, modifier = Modifier.size(14.dp), tint = accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val boxSize = if (compact) 38.dp else 52.dp
    val iconSize = if (compact) 19.dp else 26.dp
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                kind = icon,
                modifier = Modifier.size(iconSize),
                tint = accent,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Text(
            title,
            style = if (compact) MaterialTheme.typography.bodySmall
            else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 窄卡空间紧张：只留主文案，避免副文案把空态挤到滚动
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
