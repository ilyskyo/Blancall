// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.DateRange
import com.ilyskyo.blancall.algorithm.RangePreset
import java.time.format.DateTimeFormatter

/**
 * 预设 → 文案资源。数据层（[RangePreset]）只给枚举，文案一律在这里按界面语言取 ——
 * 与徽章、热力图色带同一条规矩：算法层不持有展示文案，否则六语界面上会露简体。
 */
fun rangePresetLabelRes(preset: RangePreset): Int = when (preset) {
    RangePreset.LAST_7 -> R.string.range_7d
    RangePreset.LAST_30 -> R.string.range_30d
    RangePreset.LAST_90 -> R.string.range_90d
    RangePreset.LAST_365 -> R.string.range_365d
    RangePreset.THIS_MONTH -> R.string.range_this_month
    RangePreset.LAST_MONTH -> R.string.range_last_month
    RangePreset.CUSTOM -> R.string.custom
}

/**
 * 时间维度选择器：一个胶囊显示当前区间，点开是预设列表（近 7 天…上月 / 自定义）。
 *
 * 为什么用下拉而不是一排 chip：七个预设平铺会把卡片顶栏撑成两行，而这里的位置在标题右侧、
 * 只放得下一个胶囊；下拉的定位与退场动画由 [GlassDropdownMenu] 统一负责。
 *
 * @param selected     当前预设
 * @param customRange  自定义区间（仅 [RangePreset.CUSTOM] 时有意义），用于在胶囊上直接显示起止日期
 * @param onSelect     选中某个非自定义预设
 * @param onOpenCustom 选中「自定义」——由调用方打开区间选择面板
 * @param defaultPreset 默认档位。与 [onResetToDefault] 同时给出时，菜单底部多一行「恢复默认」——
 *                      时间维度是可反复改的偏好，没有出口的话用户只能靠记忆去猜哪一档才是原来的。
 * @param onResetToDefault 恢复默认：由调用方把预设与自定义区间一起复位
 */
@Composable
fun TimeRangeSelector(
    selected: RangePreset,
    customRange: DateRange?,
    onSelect: (RangePreset) -> Unit,
    onOpenCustom: () -> Unit,
    modifier: Modifier = Modifier,
    defaultPreset: RangePreset? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales.get(0)
    val dayFmt = remember(locale) { DateTimeFormatter.ofPattern("M/d", locale) }
    // 自定义区间直接把「9/12–10/11」写在胶囊上：选完区间后用户需要一眼确认选的是哪一段，
    // 只写「自定义」等于把刚才的操作藏起来。
    val label = if (selected == RangePreset.CUSTOM && customRange != null) {
        "${customRange.start.format(dayFmt)}–${customRange.end.format(dayFmt)}"
    } else {
        stringResource(rangePresetLabelRes(selected))
    }
    val a11y = stringResource(R.string.calendar_range_a11y, label)
    val shape = RoundedCornerShape(50)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .pressClick(tier = PressTier.Container) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = a11y },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(4.dp))
            // ChevronRight 转 90° 当「展开」箭头：图标集里没有 ChevronDown，
            // 而为了一个箭头引入 extended 图标包不划算（见 AppIcon 的取舍说明）。
            AppIcon(
                kind = AppIconKind.ChevronRight,
                modifier = Modifier.size(16.dp).rotate(90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        GlassDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RangePreset.entries.forEach { preset ->
                GlassMenuItem(
                    onClick = {
                        expanded = false
                        if (preset == RangePreset.CUSTOM) onOpenCustom() else onSelect(preset)
                    },
                    label = {
                        Text(
                            stringResource(rangePresetLabelRes(preset)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailing = {
                        if (preset == selected) {
                            AppIcon(
                                kind = AppIconKind.Check,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
            // 恢复默认：只在当前不是默认档时出现 —— 已经是默认还摆一条"恢复默认"，
            // 点了没反应，是比没有更差的状态。
            if (defaultPreset != null && onResetToDefault != null && selected != defaultPreset) {
                GlassMenuDivider()
                GlassMenuItem(
                    onClick = {
                        expanded = false
                        onResetToDefault()
                    },
                    label = {
                        Text(
                            stringResource(R.string.range_reset_default),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailing = {
                        Text(
                            stringResource(rangePresetLabelRes(defaultPreset)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
    }
}
