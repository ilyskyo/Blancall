// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.DateRange
import com.ilyskyo.blancall.algorithm.dayOfWeekAtRow
import com.ilyskyo.blancall.algorithm.monthCells
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * 自定义时间区间选择面板（玻璃底部面板）。
 *
 * 交互：点一个开始日、再点一个结束日；两端已选满时再点一下就是**重开一段**。
 * 这个「重开」是刻意的：如果第二次点击总是当成「改结束日」，用户想换一段更早的区间时
 * 就必须先取消再重来，而面板里并没有取消单端的入口。
 *
 * 未来的日子一律不可点（还没有数据，能选就会得到一张全空的图）；
 * 大屏并排两个月，手机一次一个月 —— 与 [LocalIsLargeScreen] 的既有档位一致。
 *
 * @param initial 打开时的区间（通常是当前生效的区间，用户多半只微调一端）
 * @param today   今天；由调用方传入而不是读时钟，便于测试且跨午夜能跟着换
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerSheet(
    initial: DateRange,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (DateRange) -> Unit
) {
    val locale = LocalConfiguration.current.locales.get(0)
    val firstDay = remember(locale) { java.time.temporal.WeekFields.of(locale).firstDayOfWeek }
    val weekdayLabels = remember(locale, firstDay) {
        (0 until 7).map { dayOfWeekAtRow(it, firstDay).getDisplayName(TextStyle.NARROW, locale) }
    }
    val monthFmt = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val dayFmt = remember(locale) { DateTimeFormatter.ofPattern("yyyy-MM-dd", locale) }
    val twoMonths = LocalIsLargeScreen
    val haptic = rememberHaptic(HapticTier.Toggle)

    var start by remember { mutableStateOf<LocalDate?>(initial.start) }
    var end by remember { mutableStateOf<LocalDate?>(initial.end) }
    // 翻月锚点从区间末端起：用户打开面板第一眼要看到自己刚选的结束日
    var anchor by remember { mutableStateOf(YearMonth.from(initial.end)) }
    val currentMonth = remember(today) { YearMonth.from(today) }
    val months = if (twoMonths) listOf(anchor, anchor.plusMonths(1)) else listOf(anchor)

    val onDayClick: (LocalDate) -> Unit = { day ->
        haptic()
        // 局部非空变量收敛，而不是靠外层的 start == null 判断兜住 start!!
        val s = start
        if (s == null || end != null) {
            start = day
            end = null
        } else {
            // 两端反着点也要得到合法区间：DateRange.of 负责交换，与「先点晚、再点早」等价
            val r = DateRange.of(s, day)
            start = r.start
            end = r.end
        }
    }

    GlassModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.range_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.range_pick_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            // 已选两端：面板里必须有一个「我选了哪两天」的确定回显，否则只能靠格子上的圆点认
            Row(verticalAlignment = Alignment.CenterVertically) {
                RangeEndpoint(
                    label = stringResource(R.string.date_range_start),
                    value = start?.format(dayFmt),
                    modifier = Modifier.weight(1f)
                )
                AppIcon(
                    kind = AppIconKind.ArrowForward,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RangeEndpoint(
                    label = stringResource(R.string.date_range_end),
                    value = end?.format(dayFmt),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonthArrow(
                    kind = AppIconKind.ChevronRight,
                    rotateDeg = 180f,
                    a11y = stringResource(R.string.calendar_prev_month)
                ) { anchor = anchor.minusMonths(1) }
                Text(
                    months.joinToString(" · ") { it.format(monthFmt) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                MonthArrow(
                    kind = AppIconKind.ChevronRight,
                    rotateDeg = 0f,
                    a11y = stringResource(R.string.calendar_next_month),
                    enabled = anchor < currentMonth
                ) { anchor = anchor.plusMonths(1) }
            }

            Spacer(Modifier.height(8.dp))
            if (twoMonths) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    months.forEach { ym ->
                        MonthGrid(
                            yearMonth = ym,
                            firstDayOfWeek = firstDay,
                            weekdayLabels = weekdayLabels,
                            today = today,
                            start = start,
                            end = end,
                            onDayClick = onDayClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                MonthGrid(
                    yearMonth = anchor,
                    firstDayOfWeek = firstDay,
                    weekdayLabels = weekdayLabels,
                    today = today,
                    start = start,
                    end = end,
                    onDayClick = onDayClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                // 用局部非空变量收敛，而不是靠 `enabled` 这层 UI 状态兜住 `!!`：
                // 前者是编译器能验的；后者只依赖「按钮禁用时不会触发 onClick」这个约定，
                // 将来谁把 enabled 去掉（改成常亮、或换成自定义点击层），`!!` 就会当场 NPE。
                val s = start
                val e = end
                TextButton(
                    onClick = { if (s != null && e != null) onConfirm(DateRange.of(s, e)) },
                    enabled = s != null && e != null
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 单端回显：小标题 + 日期（未选时为破折号） */
@Composable
private fun RangeEndpoint(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 翻月箭头：圆形图标按钮，读屏靠 [a11y] 说话（图标本身不带语义） */
@Composable
private fun MonthArrow(
    kind: AppIconKind,
    rotateDeg: Float,
    a11y: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.pressClick(tier = PressTier.Icon, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = a11y
                // 禁用态必须显式说出来：只把颜色调淡，读屏用户完全不知道这个箭头点不动
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        AppIcon(
            kind = kind,
            modifier = Modifier.size(18.dp).rotate(rotateDeg),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}

/** 一个月的月历：星期表头 + 6×7 格 */
@Composable
private fun MonthGrid(
    yearMonth: YearMonth,
    firstDayOfWeek: DayOfWeek,
    weekdayLabels: List<String>,
    today: LocalDate,
    start: LocalDate?,
    end: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        monthCells(yearMonth, firstDayOfWeek).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                today = today,
                                start = start,
                                end = end,
                                onClick = onDayClick
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单日格子：两端实心圆、区间内浅色带、区间外透明；未来日期不可点 */
@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    start: LocalDate?,
    end: LocalDate?,
    onClick: (LocalDate) -> Unit
) {
    val enabled = !date.isAfter(today)
    val isEdge = date == start || date == end
    val inBand = start != null && end != null && date in start..end && !isEdge
    val bg = when {
        isEdge -> MaterialTheme.colorScheme.primary
        inBand -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val textColor = when {
        isEdge -> MaterialTheme.colorScheme.onPrimary
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(if (isEdge) CircleShape else RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (enabled) {
                    Modifier.pressClick(tier = PressTier.Icon) { onClick(date) }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isEdge) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor
        )
    }
}
