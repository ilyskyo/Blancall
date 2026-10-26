// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.common.pressFeedback
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.theme.isBlancallDark

/**
 * 导出 / 组卷面板（iOS 表单 sheet 风格）。
 *
 * 旧版把分段筛选、选项、文章列表和按钮全塞进一个不滚动的 AlertDialog，
 * 文章一多列表就在 240dp 处被硬裁掉 —— 下面的条目既看不见也摸不到。
 * 这里按 iOS 的表单 sheet 重排：
 * - 顶部「取消」+ 居中标题（iOS 导航条惯例）；
 * - 胶囊分段控件代替 FilterChip，分组卡片行代替裸 Checkbox；
 * - 文章列表是独立滚动区（最多露 5 行），其余区域固定，
 *   「导出」按钮始终留在拇指够得到的位置。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExportPdfSheet(
    articles: List<Article>,
    preselectIds: Set<Long>,
    onDismiss: () -> Unit,
    onExport: (articles: List<Article>, asCloze: Boolean, maxBlanksPerArticle: Int, includeAnswers: Boolean) -> Unit,
) {
    val isDark = isBlancallDark()
    val sortedArticles = remember(articles) { articles.sortedByDescending { it.updatedAt } }
    val articleIds = remember(sortedArticles) { sortedArticles.mapTo(HashSet()) { it.id } }
    var selectedIds by remember {
        mutableStateOf(
            // 默认选中的必须是列表第一行（最近更新的那篇）：旧实现取的是插入序第一项，
            // 按更新时间排序后它可能落在列表末尾 —— 打开面板看不到任何勾选，
            // 却已经能点「导出」，用户不知道自己会导出哪一篇。
            preselectIds.intersect(articleIds).ifEmpty {
                sortedArticles.firstOrNull()?.id?.let { setOf(it) } ?: emptySet()
            }
        )
    }
    var exportAsCloze by remember { mutableStateOf(false) }
    var maxBlanksPerArticle by remember { mutableStateOf(0) }
    var includeAnswers by remember { mutableStateOf(true) }
    val selectedArticles = remember(sortedArticles, selectedIds) {
        sortedArticles.filter { it.id in selectedIds }
    }
    val allSelected = sortedArticles.isNotEmpty() && selectedIds.size == sortedArticles.size

    val selectHaptic = rememberHaptic(HapticTier.Toggle)
    val blankOptions = listOf(0, 5, 8, 10)
    val blankLabels = blankOptions.map {
        if (it == 0) stringResource(R.string.exam_paper_blank_default) else it.toString()
    }

    // 面板用「分组表底色 + 更亮的行卡片」造出 iOS 设置页的两档层次；
    // 深色下底色沿用 #1C1C1E，卡片抬到 #2C2C2E，否则两组会糊成一片。
    val sheetColor = if (isDark) Color(0xFF1C1C1E) else MaterialTheme.colorScheme.surfaceVariant
    val cardColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    GlassModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetColor,
        // iOS sheet 的圆角明显小于 M3 默认的 28dp；沿用大圆角会被一眼认出是 Android。
        shape = SheetShape,
        dragHandle = { SheetGrabber() },
        // 头部与底部按钮钉住，只有文章列表自己滚（见下方 LazyColumn）。
        contentScrollable = false,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 列表的最大高度 = 面板可用高度 - 固定区（抓手 / 导航条 / 分段 / 说明 / 选择行 / 按钮）。
            // 固定区高度按层级估一个保守值：宁可列表矮一行，也不要内容顶出屏幕被裁掉。
            val availableHeight = if (maxHeight.value.isFinite()) maxHeight else 620.dp
            val reservedHeight = if (exportAsCloze) 440.dp else 300.dp
            val listMaxHeight = (availableHeight - reservedHeight).coerceIn(110.dp, 420.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                SheetHeader(
                    title = stringResource(R.string.export_pdf),
                    onCancel = onDismiss
                )
                Spacer(Modifier.height(8.dp))

                IosSegmentedControl(
                    options = listOf(
                        stringResource(R.string.export_pdf_original),
                        stringResource(R.string.export_pdf_cloze),
                    ),
                    selectedIndex = if (exportAsCloze) 1 else 0,
                    onSelect = { exportAsCloze = it == 1 },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (exportAsCloze) {
                        stringResource(R.string.export_pdf_cloze_desc)
                    } else {
                        stringResource(R.string.export_pdf_original_desc)
                    },
                    style = TextStyleIosCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                AnimatedVisibility(
                    visible = exportAsCloze,
                    enter = expandVertically(Motion.contentSize()) + fadeIn(MotionFade.alpha(MotionFade.enter)),
                    exit = shrinkVertically(Motion.contentSize()) + fadeOut(MotionFade.alpha(MotionFade.exit)),
                ) {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        SectionLabel(stringResource(R.string.exam_paper_blanks_per_article))
                        Spacer(Modifier.height(8.dp))
                        IosSegmentedControl(
                            options = blankLabels,
                            selectedIndex = blankOptions.indexOf(maxBlanksPerArticle).coerceAtLeast(0),
                            onSelect = { maxBlanksPerArticle = blankOptions[it] },
                        )
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = cardColor) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.exam_paper_include_answers),
                                    style = TextStyleIosRow,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                GlassSwitch(
                                    checked = includeAnswers,
                                    onCheckedChange = { includeAnswers = it },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(stringResource(R.string.pick_articles_to_export))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all),
                        style = TextStyleIosSectionAction,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .pressClick(tier = PressTier.Plain) {
                                selectHaptic()
                                selectedIds = if (allSelected) emptySet() else articleIds.toSet()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))

                Surface(shape = RoundedCornerShape(12.dp), color = cardColor) {
                    // 列表是唯一可滚动区：高度随文章数增长，涨到剩余空间就封顶并由自己滚。
                    // 旧版把列表钉死在 240dp，条目一多就被硬裁；这里用 heightIn(max) 而不是
                    // 固定行数 × 行高，短列表不留空白，长列表也能一直翻到底。
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = listMaxHeight)
                    ) {
                        itemsIndexed(sortedArticles, key = { _, article -> article.id }) { index, article ->
                            ExportArticleRow(
                                article = article,
                                selected = article.id in selectedIds,
                                onToggle = {
                                    selectHaptic()
                                    selectedIds = if (article.id in selectedIds) {
                                        selectedIds - article.id
                                    } else {
                                        selectedIds + article.id
                                    }
                                },
                            )
                            if (index != sortedArticles.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                ExportConfirmButton(
                    selectedCount = selectedArticles.size,
                    onClick = {
                        onExport(
                            selectedArticles,
                            exportAsCloze,
                            maxBlanksPerArticle,
                            includeAnswers && exportAsCloze,
                        )
                    }
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 文章行高：标题 + 字数两行；同时用于给列表算固定高度。 */
private val ArticleRowHeight = 58.dp

/** iOS sheet 圆角：明显小于 M3 默认的 28dp，才不会一眼看出是 Android 面板。 */
private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** iOS 填充按钮的圆角（system borderedProminent ≈ 14pt，不是胶囊）。 */
private val IosButtonShape = RoundedCornerShape(14.dp)

private val TextStyleIosTitle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    lineHeight = 22.sp,
)
private val TextStyleIosAction = TextStyleIosTitle.copy(fontWeight = FontWeight.Normal)

private val TextStyleIosRow = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 20.sp,
)

private val TextStyleIosCaption = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

private val TextStyleIosSection = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

private val TextStyleIosSectionAction = TextStyleIosSection.copy(
    fontWeight = FontWeight.Medium,
)

/** iOS 顶部抓手：36×5 的短横条，不承担任何读屏语义（关闭仍走返回手势 / 取消）。 */
@Composable
private fun SheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        )
    }
}

/** 导航条：取消在左、标题绝对居中（两侧宽度不对称时标题也不偏）。 */
@Composable
private fun SheetHeader(title: String, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = TextStyleIosTitle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.cancel),
            style = TextStyleIosAction,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .pressClick(tier = PressTier.Plain, onClick = onCancel)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TextStyleIosSection,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * iOS 胶囊分段控件：灰色轨道 + 白色滑块 + 弹性滑动。
 *
 * 用 selectableGroup + RadioButton 语义，读屏会播报「已选中」；
 * 滑块走 graphicsLayer 平移（只重绘，不逐帧重排文字），与 GlassSwitch 的滑块同一套做法。
 */
@Composable
private fun IosSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val isDark = isBlancallDark()
    val trackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFF767680).copy(alpha = 0.12f)
    val thumbColor = if (isDark) Color(0xFF636366) else Color.White
    val thumbShape = RoundedCornerShape(7.dp)
    val haptic = rememberHaptic(HapticTier.Toggle)
    val selectedTextColor = MaterialTheme.colorScheme.onSurface
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(trackColor)
            .padding(2.dp)
            .selectableGroup()
    ) {
        val cellWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = cellWidth * selectedIndex.toFloat(),
            animationSpec = Motion.toggleTravel(),
            label = "segmentThumb",
        )
        Box(
            modifier = Modifier
                .width(cellWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = thumbOffset.toPx() }
                .shadow(elevation = 2.dp, shape = thumbShape, clip = false)
                .background(thumbColor, thumbShape)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val src = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressFeedback(src, tier = PressTier.Plain, layerShape = thumbShape)
                        .selectable(
                            selected = selected,
                            interactionSource = src,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = {
                                if (!selected) {
                                    haptic()
                                    onSelect(index)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val color by animateColorAsState(
                        targetValue = if (selected) selectedTextColor else unselectedTextColor,
                        animationSpec = MotionFade.color(MotionFade.enter),
                        label = "segmentLabelColor",
                    )
                    Text(
                        text = label,
                        style = TextStyleIosSection.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** 文章行：整行可点，右侧是 iOS 多选圆勾；取消选中时只剩一圈细描边。 */
@Composable
private fun ExportArticleRow(
    article: Article,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ArticleRowHeight)
            .pressFeedback(src, tier = PressTier.Plain)
            .toggleable(
                value = selected,
                interactionSource = src,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = article.title,
                style = TextStyleIosRow,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.char_count,
                    article.content.length,
                    article.content.length,
                ),
                style = TextStyleIosSection,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        SelectionMark(selected)
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = MotionFade.color(MotionFade.enter),
        label = "selectFill",
    )
    val ring by animateColorAsState(
        targetValue = if (selected) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        animationSpec = MotionFade.color(MotionFade.enter),
        label = "selectRing",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            AppIcon(
                kind = AppIconKind.Check,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 底部整宽胶囊主按钮；无选中时降为灰色禁用态，而不是把按钮藏起来。 */
@Composable
private fun ExportConfirmButton(selectedCount: Int, onClick: () -> Unit) {
    val enabled = selectedCount > 0
    val label = if (selectedCount > 1) {
        stringResource(R.string.exam_paper_start, selectedCount)
    } else {
        stringResource(R.string.export_action)
    }
    val bg by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        },
        animationSpec = MotionFade.color(MotionFade.enter),
        label = "exportButtonBg",
    )
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .pressFeedback(
                src,
                tier = PressTier.Container,
                enabled = enabled,
                layerShape = IosButtonShape,
                hapticTier = HapticTier.Confirm,
            )
            .clip(IosButtonShape)
            .background(bg)
            .clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyleIosTitle,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}
