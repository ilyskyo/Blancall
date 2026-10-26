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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
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
import com.ilyskyo.blancall.ui.common.DonationInviteRow
import com.ilyskyo.blancall.ui.theme.AppPrefs
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
 * [revealed] 控制**回忆面 / 答案面**：今天还没评的句子会先给挖空提示（[SentenceCloze]），
 * 等用户回忆后再由界面把 [revealed] 置 true 显示整句；新句（第一次认读）默认也给全文，
 * 只有用户打开「首次也遮挡」([clozeNewEnabled]) 时才跟着遮；今天已评的卡（回看）恒为完整显示。
 * 揭晓是**同一段文字换可见度**（横条退、藏住的字进，见 [ClozeCueText] 的 progress），
 * 不是把这一块换成另一块 —— 换卡时进度按 `item.key` 立即归零，防漏答案。
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
    /** 是否启用挖空遮挡（用户偏好，见 AppPrefs.sentenceClozeEnabled）：关掉 = 整句直出 */
    clozeEnabled: Boolean = true,
    /** 首次见到的句子（没评过级的新句）是否也遮挡（见 AppPrefs.sentenceClozeNewEnabled）：关 = 新句先给全文 */
    clozeNewEnabled: Boolean = false,
    modifier: Modifier,
){
    val context = LocalContext.current
    val rated = isRatedToday(state, now)
    // 「挖不挖得出空」只由句子文本决定，remember 之后列表滚动重组时不再逐帧重算
    val hidesAnything = remember(item.text) { SentenceCloze.hidesAnything(item.text) }
    // 是不是「回忆面」= 今天还没评 + 用户开着遮挡 + 这句确实挖得出空 + 这句不是第一次见。
    // 「第一次见」默认给全文：先认读再回忆是学习规律；但用户可以在 ⋮ 菜单里打开
    // 「首次也遮挡」——那时新句也走正反面（先挖空，点「显示答案」读全文）。
    // **刻意不含 `!revealed`**：揭晓是这张卡内部的一段过渡（见 revealAnim），不是"换一个面"——
    // 把它算进来，揭晓那一帧整块会被换成另一段布局，那正是要治的硬切。
    val recallFace = clozeEnabled && !rated && hidesAnything &&
        ((state?.reviewCount ?: 0) > 0 || clozeNewEnabled)
    // 揭晓进度（0 = 只给线索，1 = 整句）：驱动"空位横条退场 + 被藏住的字入场"，全程零重排。
    // 按 [item.key] remember ⇒ 换卡的那一帧就是 0；换卡时再 snap 一次兜底，
    // 否则上一张的进度会以"答案正在淡出"的形式挂在新卡上（等于替他漏答案）。
    val revealAnim = remember(item.key) { Animatable(0f) }
    LaunchedEffect(item.key, revealed) {
        if (revealed) revealAnim.animateTo(1f, MotionFade.number(MotionFade.answerReveal))
        else revealAnim.snapTo(0f)
    }
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
                    if (recallFace) {
                        // 揭晓 = 同一句话换可见度：横条退、藏住的字进，排版一步不动
                        ClozeCueText(
                            text = item.text,
                            progress = { revealAnim.value },
                            style = MaterialTheme.typography.headlineSmall,
                            lineHeight = 36.sp,
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
                sentenceCardStatus(context, state, now),
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
 * 挖空提示的渲染（遮挡视觉规范 v1），外加「揭晓」的过程动画。
 *
 * **空位不是方块，是"填空线"。** 旧实现把每个被藏的字换成 `□` 再压暗到 35%，
 * 一句里连排七八个方框，读起来是一块噪声：既不像句子，也不像题，只剩"被涂黑了"的感觉。
 * 单字方框还会让读者去数框，而数框没有记忆价值。
 *
 * 规范：
 * 1. 形状：圆角横条（高 2dp、圆角 1dp），不用描边、不加底色块；
 * 2. 宽度：取该字字宽的 62%，最小 3dp —— 汉字接近满宽，西文字母自然收窄；
 * 3. 颜色：正文色 18% 透明度；恒弱于正文与提示文字，不抢目光；
 * 4. 位置：贴着该字的字形底部（距字底留 6% 字高的缝），看起来像一道填空下划线；
 * 5. 每个空位独立一条：条与条之间自然留出 38% 字宽，**一眼能数出还差几个字**；
 * 6. 首字与标点保持正文样式 —— 它们是线索，不是要被藏起来的内容；
 * 7. 行高、换行、对齐与整句完全一致，遮挡不改变排版。
 *
 * 实现：**原文照排**，被藏住的字只是先把颜色设成透明（**宽度与换行仍由排版引擎正常计算**），
 * 再在 `drawBehind` 里按这些字的布局框画横条。不用逐字 Composable 或 FlowRow ——
 * 那会让中英混排的字距、行高、居中全部失控，而这里排版仍是系统文本引擎的原生结果。
 * 用原文而不是 [SentenceCloze.cue] 生成的 `□` 串，规范第 7 条才真的成立：
 * `□` 只是"宽度接近汉字"，混排时两者字宽并不相等，拿它排版会把断行位置挪掉。
 *
 * 揭晓（2026-10-18）：点「显示答案」不再是把这一块换成另一块，而是**同一句话的两种可见度**——
 * 横条不透明度 18% → 0、被藏住的字 `Color.Transparent` → 正文色，两件事由 [progress] 同一条进度反向走。
 * 做法是同一份原文叠两层（[base] = 露出的字、[answer] = 藏住的字），两层字符串与样式完全一致
 * ⇒ 逐字框对得上，揭晓全程零重排。进度只在绘制域里读（`drawBehind` / `graphicsLayer`），
 * 所以逐帧只是重画，既不重排版也不重组合。
 *
 * @param progress 0 = 只给线索（横条在、藏住的字不可见），1 = 整句揭晓（横条退净、字全亮）。
 */
@Composable
internal fun ClozeCueText(
    text: String,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    lineHeight: TextUnit = 36.sp,
    textAlign: TextAlign = TextAlign.Center,
) {
    // 哪几个字要遮：cue 与原文逐字符同长同序（见 SentenceCloze.cue），按 index 直接对齐
    val hidden = remember(text) {
        val cue = SentenceCloze.cue(text)
        BooleanArray(text.length) { i -> i < cue.length && cue[i] == SentenceCloze.PLACEHOLDER }
    }
    val base = remember(text, hidden) {
        buildAnnotatedString {
            text.forEachIndexed { i, c ->
                // 透明而非删除：字符的推进宽度必须留着，否则换行位置会整体前移
                if (hidden[i]) withStyle(SpanStyle(color = Color.Transparent)) { append(c) }
                else append(c)
            }
        }
    }
    val answer = remember(text, hidden) {
        buildAnnotatedString {
            text.forEachIndexed { i, c ->
                if (hidden[i]) append(c)
                else withStyle(SpanStyle(color = Color.Transparent)) { append(c) }
            }
        }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    // 用普通可变槽而不是 State：测量（onTextLayout）一定早于绘制（drawBehind），
    // 写在这里读取时已经有值，首帧就能画出横条，不会先闪一帧"缺字"。
    val layoutSlot = remember(text) { arrayOfNulls<TextLayoutResult>(1) }
    Box(
        modifier = modifier.drawBehind {
            val res = layoutSlot[0] ?: return@drawBehind
            val barH = 2.dp.toPx()
            val barR = barH / 2f
            val minW = 3.dp.toPx()
            // 横条跟着答案反向退场：进度 1 = 退净，正好是字全亮的那一刻
            val color = ink.copy(alpha = 0.18f * (1f - progress()))
            for (i in hidden.indices) {
                if (!hidden[i]) continue
                val box = res.getBoundingBox(i)
                if (box.width <= 0f) continue
                val w = (box.width * 0.62f).coerceAtLeast(minW)
                // 贴字形底部（box.bottom ≈ 基线 + descent），距字底留 6% 字高的缝：
                // 用行高定位会落在字身中部、压住笔画；用字符框底部才既在字下方、又同一行齐平。
                val top = box.bottom - barH - box.height * 0.06f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(box.center.x - w / 2f, top),
                    size = Size(w, barH),
                    cornerRadius = CornerRadius(barR, barR),
                )
            }
        },
    ) {
        // 两层同串同式 ⇒ 排版逐字一致，永远对得上（见上方注释）
        Text(
            text = base,
            style = style,
            lineHeight = lineHeight,
            color = ink,
            textAlign = textAlign,
            onTextLayout = { layoutSlot[0] = it },
        )
        Text(
            text = answer,
            style = style,
            lineHeight = lineHeight,
            color = ink,
            textAlign = textAlign,
            modifier = Modifier.graphicsLayer { alpha = progress() },
        )
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
            Text(stringResource(com.ilyskyo.blancall.R.string.back), style = MaterialTheme.typography.labelLarge)
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
    // 累计完成一轮（赞赏邀请的门槛依据）。用 remember 而非 rememberSaveable：
    // 「再复习一轮」后应当再算一轮，而 rememberSaveable 会让第二次进来时不再累加。
    // 代价是旋转屏幕可能多计一次 —— 门槛是 5，多算一次无实质影响。
    LaunchedEffect(Unit) { AppPrefs.noteDonationCompletion() }

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
                Text(stringResource(com.ilyskyo.blancall.R.string.continue_memory), style = MaterialTheme.typography.labelLarge)
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
        // 完成态赞赏入口：一行字，不是按钮（理由见 DonationInvite.kt 顶部注释）。
        // 间距写在传入的 modifier 里而不是外面另加 Spacer —— 闸门没放行时该组件整体不渲染，
        // Spacer 若在外面会留下 20dp 空洞。它属于「已经做完了」的余韵，不与三个按钮同拍强调。
        DonationInviteRow(
            slot = "sentence_done",
            modifier = Modifier
                .doneEnter(stage > 3)
                .padding(top = 8.dp)
        )
    }
}
