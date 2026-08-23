// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 底部导航栏（设置中可开关）：三个根页面入口——首页 / 我的文章 / 数据。
 *
 * 与全 app 的毛玻璃语言统一：半透明 surface 染色（深色更实、浅色更透）+ 顶部发丝线，
 * 去掉 Material2 式硬投影；图标 + 文字组合，选中态以主色着色（Apple tab bar 风），
 * 不再使用飘浮小横线。背景延伸至系统导航条（小白条）之后，避免底部留白。
 */
@Composable
fun BottomNavBar(
    currentTab: Int,
    onSelect: (Int) -> Unit,
    showLibraryTab: Boolean = false
) {
    val baseTabs = listOf(
        "首页" to AppIconKind.Home,
        "我的文章" to AppIconKind.Articles,
        "数据" to AppIconKind.Insights,
    )
    val tabs = if (showLibraryTab) baseTabs + ("素材库" to AppIconKind.Library) else baseTabs
    val isDark = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    // 半透明染色：深色下更实（毛玻璃感弱但对比足），浅色下更透，让氛围光斑微透
    val bgAlpha = if (isDark) 0.94f else 0.90f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor.copy(alpha = bgAlpha))
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, (label, kind) ->
                val selected = index == currentTab
                val tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AppIcon(kind = kind, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint
                    )
                }
            }
        }
    }
}
