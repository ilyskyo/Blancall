// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.animateColorAsState
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceCloze
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.TagChipRow
import com.ilyskyo.blancall.ui.common.TagChipUi
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 「句子卡片」大卡片界面的视图层（自 SentenceCardScreen 拆出，组织方式对齐 HomeCardViews）。
 * 全部为无状态组件，状态与手势由 SentenceCardScreen 持有与组装。
 */

/**
 * 一张句子卡：来源标题（今日句带角标）+ 来源文章标签 + 句文（内滚居中）+ 底部状态行。
 *
 * [revealed] 控制**回忆面 / 答案面**：只有「已学过但今天还没评」的句子会先给挖空提示
 * （[SentenceCloze]），等用户回忆后再由界面把 [revealed] 置 true 显示整句；
 * 新句（第一次认读）与已评卡（回看）恒为完整显示，不受该参数影响。
 */
@Composable
internal fun SentenceCardFace(
    item: DailySentenceCoordinator.QueueItem,
    state: FsrsEngine.CardState?,
    now: Long,
    /** 来源文章的标签（最多 2 枚 + 「+N」；空列表不占位） */
    tags: List<TagChipUi> = emptyList(),
    /** 回忆面是否已揭晓（仅对「已学未评」的句子有意义） */
    revealed: Boolean = true,
    modifier: Modifier,
) {
    val rated = isRatedToday(state, now)
    // 回忆面 = 已学过（不是第一次）+ 今天还没评 + 这句确实挖得出空。
    // 新句给全文：第一次是"输入"，对陌生句子先考后教是反学习规律的。
    // 「挖不挖得出空」只由句子文本决定，remember 之后列表滚动重组时不再逐帧重算
    val hidesAnything = remember(item.text) { SentenceCloze.hidesAnything(item.text) }
    val recalling = !rated && (state?.reviewCount ?: 0) > 0 && !revealed && hidesAnything
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.title.isNotBlank()) "《${item.title}》" else stringResource(com.ilyskyo.blancall.R.string.sentence_cards),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.isToday) {
                    // 今日句角标：仅队列置顶的当天句子出现
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.today_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TagChipRow(tags = tags, maxChips = 2)
            }
            // 正文区垂直手势的归属（切卡热区扩展的关键）：
            // - 文本溢出需滚动时：正文区滑动 = 滚动文本（保持原行为，不冲突）；
            // - 文本未溢出（绝大多数句子）：禁用它对拖拽的拦截，事件冒泡给外层卡片手势 ⇒
            //   从卡片顶端到正文文字的整段区域都可上下滑切卡，阈值/手感与顶端手势完全一致。
            val textScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(textScrollState, enabled = textScrollState.maxValue > 0),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (recalling) {
                        Text(
                            cueText(item.text),
                            style = MaterialTheme.typography.headlineSmall,
                            lineHeight = 36.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.recall_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.headlineSmall,
                            lineHeight = 36.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Text(
                sentenceCardStatus(state, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (rated) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * 挖空提示的着色版本：占位符压暗到近乎背景，让"该背的字"和"已给的线索"在视觉上分层
 * （同色的一串 □ 会连成一块噪声，读不出哪里是空）。
 */
@Composable
private fun cueText(text: String): AnnotatedString {
    val placeholder = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    return remember(text, placeholder) {
        buildAnnotatedString {
            for (c in SentenceCloze.cue(text)) {
                if (c == SentenceCloze.PLACEHOLDER) withStyle(SpanStyle(color = placeholder)) { append(c) }
                else append(c)
            }
        }
    }
}

/**
 * 评级按钮（圆角 16dp、带效果描述的无障碍语义；[selected] = 选中高亮）。
 *
 * [intervalLabel] 非空时按钮变成两行：评级名 + 这一档预测的下次间隔（Anki 的按钮间隔预览）——
 * 有它用户才知道"点了记住了"到底意味着几天后再见，自评才有校准依据；同时按钮加高到 60dp
 * 容下第二行。为 null 时保持原来的单行 52dp。
 */
@Composable
internal fun RatingButton(
    label: String,
    desc: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier,
    /** 选中高亮：点击即时选中与回看回显共用同一样式（同内容色描边渐显） */
    selected: Boolean = false,
    /** 选中描边色：默认随内容色（深色系）；「记住了」主色底上的白色不可见，单独传黑色拉齐 */
    borderColor: Color = content,
    /** 下次复习间隔文案（如「6 天」）；null = 不显示第二行 */
    intervalLabel: String? = null,
    onClick: () -> Unit,
) {
    // 选中高亮：用描边色渐显（透明 ↔ 描边色过渡，200ms）——
    // 点击即时选中与「回看已评卡回显上次所选」共用同一样式，不引入第二套高亮
    val selectedBorder by animateColorAsState(
        targetValue = if (selected) borderColor else Color.Transparent,
        animationSpec = MotionFade.color(MotionFade.enter),
        label = "ratingSelectedBorder",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(if (intervalLabel != null) 60.dp else 52.dp)
            .semantics { contentDescription = desc },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, selectedBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = content.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 系统黑体（SansSerif）：主操作按钮不用主题的楷体/衬线体（与首页悬浮「完成」按钮同规范）
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
            )
            if (intervalLabel != null) {
                Text(
                    intervalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.SansSerif,
                    color = content.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 「显示答案」主按钮（Anki 的 Show Answer）：回忆面占住评级键的位置，
 * 揭晓之后才让位给三键 —— 先回忆再自评是这套卡片的规矩，按钮本身也在说这句话。
 * 高度与带间隔的两行评级键对齐（60dp），揭晓时底部区不会跳高度。
 */
@Composable
internal fun SentenceRevealButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 字符串先取再进 semantics：semantics 的 lambda 不是 @Composable 上下文，
    // stringResource 直接写在里面编译不过（10-11 轮 i18n 抽取时踩过）。
    val label = stringResource(com.ilyskyo.blancall.R.string.show_answer)
    Button(
        onClick = onClick,
        modifier = modifier
            .height(60.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1,
        )
    }
}

/** 队列为空（无文章 / 无合格句）时的空态 */
@Composable
internal fun SentenceEmptyView(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                kind = AppIconKind.Library,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(com.ilyskyo.blancall.R.string.no_sentences_today),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(com.ilyskyo.blancall.R.string.import_one_first),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("返回", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 完成页入场的错峰级数：图标 / 标题 / 统计 / 按钮。 */
private const val DONE_STAGES = 4

/**
 * 完成页元素的开场：**不改布局**的淡入 + 上滑。
 *
 * 用 graphicsLayer 而不是 AnimatedVisibility：这一列是垂直居中排布的，
 * 而 AnimatedVisibility 在 visible=false 时内容根本不参与布局 —— 逐拍放开会让整列
 * 边出现边重新居中，屏幕上就是「整块往上跳四次」。这里元素始终占位，只有绘制在动。
 *
 * 位移走弹簧、alpha 走补间（词表法则一）。用 [Motion.doneRise] 而不是 [Motion.itemSlide]，
 * 是因为这里要动的是 Dp 而不是 IntOffset；两者刚度同档，两处入场的节奏因此一致。
 */
@Composable
private fun Modifier.doneEnter(open: Boolean): Modifier {
    val fade by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = MotionFade.alpha(MotionFade.itemEnter),
        label = "doneEnterFade",
    )
    val rise by animateDpAsState(
        targetValue = if (open) 0.dp else Motion.doneRiseDp.dp,
        animationSpec = Motion.doneRise(),
        label = "doneEnterRise",
    )
    // 局部名不能就叫 alpha：graphicsLayer 的 lambda 里它会被解析成 GraphicsLayerScope 自己的属性
    return graphicsLayer {
        alpha = fade
        translationY = rise.toPx()
    }
}

/**
 * 完成页统计行里的一项：色点 + 评级名 + 计数。
 *
 * 计数用色点同色加粗：眼睛先认色、再读数，两者必须指向同一个键，
 * 中间不能有一次「这个红点对应哪排字」的辨认。
 */
@Composable
private fun DoneRatingCount(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * 队列评完（或滑到末尾）时的完成页：本次会话评级分布小结 + 未评时提供"继续记忆"
 * + 主动复习「再复习一轮」。
 *
 * 入场分四拍依次放开（图标弹入 → 标题 → 统计 → 按钮），见 [DONE_STAGES]。
 * 编排放在这里而不是外层 StateSwap：状态面切换只负责「这一页淡进来」，
 * 页内四个元素谁先出现是这一页自己的事，两处混在一起就再也调不动其中一处。
 *
 * @param ratedCount   队列中今天已评的句子数（含进入本次界面前已评的）
 * @param againCount   本次会话三键评级分布（忘记 / 不熟 / 记住了）；无会话评级时以 ratedCount 文案兜底
 * @param onReviewAgain 再复习一轮：全量重新排队（含已学过未到期的句子），从头再学
 */
@Composable
internal fun SentenceDoneView(
    ratedCount: Int,
    againCount: Int,
    hardCount: Int,
    goodCount: Int,
    firstUnrated: Int,
    onReviewAgain: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    // 错峰用「第几拍」而不是给每条动画各写一个 delay：错峰讲的是「谁先出现」，
    // 它必须是一处能一眼看完的顺序，而不是散在四个元素里的四个数字。
    // 用 remember 而不是 rememberSaveable：**每次记完一轮都是一件新的事**，入场要重放。
    // 存下来会在「再复习一轮」后第二次走到这里时直接以终态出现 —— 那正是这一页最该有动静的时刻。
    var stage by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // 减动效：直接落终态，不等一个不会到来的节拍
        if (Motion.reduced || stage >= DONE_STAGES) {
            stage = DONE_STAGES
            return@LaunchedEffect
        }
        repeat(DONE_STAGES) { i ->
            if (i > 0) delay(MotionFade.celebrateStep.toLong())
            stage = i + 1
        }
    }
    // 庆祝图标：弹簧弹入（到终点前轻轻过冲一次），落点后补一次极轻的二次回弹。
    // 第二次只有 4%、120ms 一个来回：LowBouncy 的过冲是「弹出来」，这一次是「坐实」——
    // 少了它，图标落在终点的瞬间是「停住」；有了它，是「站稳」。
    val pop = remember { Animatable(Motion.Scale.celebrateFrom) }
    LaunchedEffect(Unit) {
        pop.snapTo(Motion.Scale.celebrateFrom)
        pop.animateTo(1f, Motion.celebratePop())
        pop.animateTo(Motion.Scale.celebrateEcho, MotionFade.number(MotionFade.celebrateEcho))
        pop.animateTo(1f, MotionFade.number(MotionFade.celebrateEcho))
    }
    // 背后那两圈光环：各有一条自己的时间线，第二圈延迟一拍出发、扩散更大、更淡 ——
    // 读起来是一圈涟漪推出去。两圈终点 alpha 都归零，所以减动效（进度直接到 1）
    // 下不留痕 —— 不会出现「关了动画还挂着一圈圈」。
    val halo = remember { Animatable(0f) }
    val halo2 = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        halo.snapTo(0f)
        halo.animateTo(1f, MotionFade.number(MotionFade.celebrateHalo))
    }
    LaunchedEffect(Unit) {
        halo2.snapTo(0f)
        halo2.animateTo(1f, MotionFade.number(MotionFade.celebrateHalo, MotionFade.celebrateHalo2Delay))
    }
    // 三键计数的 count-up：三个数共用一个进度，晚两拍（统计行落位的时刻）起跑，
    // 与这一拍的出现同步到位。减动效下 snap 到 1，直接显示终值。
    val countUp = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        countUp.snapTo(0f)
        countUp.animateTo(1f, MotionFade.number(MotionFade.countUp, MotionFade.celebrateStep * 2))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.doneEnter(stage > 0),
            contentAlignment = Alignment.Center,
        ) {
            // 光环先画（在图标下层）：两圈同一节奏推出去，后环延迟一拍、更大更淡
            val haloProgress = halo.value
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        val s = 1f + Motion.Scale.celebrateHaloSpread * haloProgress
                        scaleX = s
                        scaleY = s
                        alpha = Motion.celebrateHaloAlpha * (1f - haloProgress)
                    }
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            val halo2Progress = halo2.value
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        val s = 1f + Motion.Scale.celebrateHalo2Spread * halo2Progress
                        scaleX = s
                        scaleY = s
                        alpha = Motion.celebrateHalo2Alpha * (1f - halo2Progress)
                    }
                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    kind = AppIconKind.Celebrate,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            // 队列 = 今日 + 到期 + 全部新句：排空即「所有句子都记完了一轮」，而非仅今日句
            stringResource(com.ilyskyo.blancall.R.string.round_complete),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .doneEnter(stage > 1)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(4.dp))
        // 三键评级分布：小色点 + 标签 + count-up 数字（无会话评级时退回总数文案）。
        // 色取饱和三色（忘记=error / 不熟=tertiary / 记住了=primary），与 MistakeBar 的
        // 错误分布条同源 —— 底部三键用 container 浅色底是因为那是大面积按钮，
        // 8dp 小点用浅色会糊。三个数共用一个 count-up 进度齐步走：分布是「一次评完的
        // 统计」，读起来该是一个总结，不是三行各自滚动的数字。
        Column(modifier = Modifier.doneEnter(stage > 2)) {
            if (againCount + hardCount + goodCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoneRatingCount(
                        color = MaterialTheme.colorScheme.error,
                        label = stringResource(com.ilyskyo.blancall.R.string.rate_again),
                        count = (againCount * countUp.value).roundToInt(),
                    )
                    Spacer(Modifier.width(14.dp))
                    DoneRatingCount(
                        color = MaterialTheme.colorScheme.tertiary,
                        label = stringResource(com.ilyskyo.blancall.R.string.rate_hard),
                        count = (hardCount * countUp.value).roundToInt(),
                    )
                    Spacer(Modifier.width(14.dp))
                    DoneRatingCount(
                        color = MaterialTheme.colorScheme.primary,
                        label = stringResource(com.ilyskyo.blancall.R.string.rate_remember),
                        count = (goodCount * countUp.value).roundToInt(),
                    )
                }
            } else {
                val reviewed = (ratedCount * countUp.value).roundToInt()
                Text(
                    // 走 plurals 而不是拼字符串：英文的 "1 sentence / 2 sentences" 必须分叉，
                    // 而拼出来的句子既分不了叉，也不会被任何多语言守卫看到（它是 .kt 里的字面量，
                    // 而棘轮守卫只覆盖「统计页族」那几个文件）。
                    pluralStringResource(
                        com.ilyskyo.blancall.R.plurals.sentence_round_reviewed,
                        reviewed, reviewed
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // 三个按钮同属最后一拍：它们是一组「接下来做什么」，再往下拆拍就散了
        // 主动复习：学完了可以再学一轮（全量重新排队，含已学过未到期的句子）
        Button(
            onClick = onReviewAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .doneEnter(stage > 3),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(com.ilyskyo.blancall.R.string.review_again), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(10.dp))
        if (firstUnrated >= 0) {
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .doneEnter(stage > 3),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("继续记忆", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .doneEnter(stage > 3),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(stringResource(com.ilyskyo.blancall.R.string.back_to_home), style = MaterialTheme.typography.labelLarge)
        }
    }
}
