// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.BackButton

@Composable
fun HelpScreen(navController: NavController) {
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
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // 顶部返回按钮 + 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.help_001),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 1. 开始练习 ──
                HelpSection(
                    emoji = "🚀",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_002),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_003),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_004))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_005))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_006))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_007))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_008))
                        BulletItem(1, stringResource(com.ilyskyo.blancall.R.string.help_009))
                        BulletItem(2, stringResource(com.ilyskyo.blancall.R.string.help_010))
                        BulletItem(3, stringResource(com.ilyskyo.blancall.R.string.help_011))
                        BulletItem(4, stringResource(com.ilyskyo.blancall.R.string.help_012))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_013))
                        FlowTag(stringResource(com.ilyskyo.blancall.R.string.help_014)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.help_015))
                        }
                        Spacer(Modifier.height(6.dp))
                        FlowTag(stringResource(com.ilyskyo.blancall.R.string.help_016)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.help_017))
                        }
                    }
                )

                // ── 句子卡片（每日一句） ──
                HelpSection(
                    emoji = "💬",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_018),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_019),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_020))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_021))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_022))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_023))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_024))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_025))
                    }
                )

                // ── 2. 练习模式介绍 ──
                HelpSection(
                    emoji = "📋",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_026),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_027),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📝",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_028),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_029),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_030)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔤",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_031),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_032),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_033)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "✍️",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_034),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_035),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_036)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🧩",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_037),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_038),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_039)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔀",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_040),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_041),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_042)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🎯",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_043),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_044),
                            footer = stringResource(com.ilyskyo.blancall.R.string.help_045)
                        )
                    }
                )

                // ── 3. 挖空策略介绍 ──
                HelpSection(
                    emoji = "🎯",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_046),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_047),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "⚖️",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_048),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_049)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🎯",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_050),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_051)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔍",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_052),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_053)
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_054))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_055), stringResource(com.ilyskyo.blancall.R.string.help_056))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_057), stringResource(com.ilyskyo.blancall.R.string.help_058))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_059), stringResource(com.ilyskyo.blancall.R.string.help_060))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_061), stringResource(com.ilyskyo.blancall.R.string.help_062))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_063),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                )

                // ── 4. 提示功能 ──
                HelpSection(
                    emoji = "💡",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_064),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_065),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_066))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_067))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_068))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_069))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_070))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_071))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_072))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_073))
                    }
                )

                // ── 5. 查看学习数据 ──
                HelpSection(
                    emoji = "📊",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_074),
                    expandedContent = {
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_075))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_076))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_077))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_078))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_079))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_080))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_081))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_082), stringResource(com.ilyskyo.blancall.R.string.help_083))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_084), stringResource(com.ilyskyo.blancall.R.string.help_085))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_086), stringResource(com.ilyskyo.blancall.R.string.help_087))
                        KeyValue(stringResource(com.ilyskyo.blancall.R.string.help_088), stringResource(com.ilyskyo.blancall.R.string.help_089))
                    }
                )

                // ── 6. 学习建议 ──
                HelpSection(
                    emoji = "📖",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_090),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_091),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_092))
                        SuggestionStep(
                            step = stringResource(com.ilyskyo.blancall.R.string.help_093),
                            items = listOf(
                                stringResource(com.ilyskyo.blancall.R.string.help_094),
                                stringResource(com.ilyskyo.blancall.R.string.help_095),
                                stringResource(com.ilyskyo.blancall.R.string.help_096)
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        SuggestionStep(
                            step = stringResource(com.ilyskyo.blancall.R.string.help_097),
                            items = listOf(
                                stringResource(com.ilyskyo.blancall.R.string.help_098),
                                stringResource(com.ilyskyo.blancall.R.string.help_099),
                                stringResource(com.ilyskyo.blancall.R.string.help_100)
                            )
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_101))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_102))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_103))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_104))
                    }
                )

                // ── 7. 操作小贴士（完整操作手册） ──
                HelpSection(
                    emoji = "🛠️",
                    title = stringResource(com.ilyskyo.blancall.R.string.help_105),
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_106),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_107))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_108))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_109))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_110))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_111))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_112))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_113))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_114))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_115))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_116))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_117))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_118))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_119))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_120))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_121))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_122))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_123))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_124))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_125))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_126))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_127))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_128))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_129))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_130))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_131))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_132))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_133))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_134))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_135))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_136))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_137))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_138))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_139))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_140))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_141))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_142))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_143))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_144))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_145))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_146))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_147))
                        SubTitle(stringResource(com.ilyskyo.blancall.R.string.help_148))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_149))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_150))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_151))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_152))
                        Bullet(stringResource(com.ilyskyo.blancall.R.string.help_153))
                    }
                )

                // ── 8. AI 使用说明 ──
                HelpSection(
                    emoji = "🤖",
                    title = "AI",
                    expandedContent = {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_154),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_155),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_156),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_157),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_158),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoCard(
                            emoji = "📖",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_159),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_160)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📋",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_161),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_162)
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📋",
                            title = stringResource(com.ilyskyo.blancall.R.string.help_163),
                            desc = stringResource(com.ilyskyo.blancall.R.string.help_164)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_165),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_166),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_167),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_168),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_169),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.help_170),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── 可折叠帮助区块 ──

@Composable
private fun HelpSection(
    emoji: String,
    title: String,
    expandedContent: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            // 标题栏（可点击展开/折叠）
            Surface(
                onClick = { expanded = !expanded },
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(emoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 展开内容（带动画）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(Motion.contentSize()) +
                    fadeIn(MotionFade.alpha(MotionFade.enter)),
                exit = shrinkVertically(Motion.contentSize()) +
                    fadeOut(MotionFade.alpha(MotionFade.exit))
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp
                    )
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                    expandedContent()
                }
            }
        }
    }
}

// ── 子组件 ──

/** 小节标题（带左色条，内容分点清晰） */
@Composable
private fun SubTitle(text: String) {
    Row(
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 分点条目：• 宽松行距，适合逐条阅读 */
@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 带序号的分点条目：① / ② / … */
@Composable
private fun BulletItem(index: Int, text: String) {
    val mark = "①②③④⑤⑥⑦⑧⑨⑩".getOrNull(index - 1)?.toString() ?: "$index."
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            mark,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 参数行：左侧标签（加粗）+ 右侧说明 */
@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowTag(
    label: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            // 内部子内容由调用方传入，使用本地默认色（这里提供 onSurfaceVariant 作暗色模式保险色）
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                content()
            }
        }
    }
}

@Composable
private fun InfoCard(
    emoji: String,
    title: String,
    desc: String,
    footer: String? = null
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                emoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (footer != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        footer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionStep(
    step: String,
    items: List<String>
) {
    Column {
        Text(
            step,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
