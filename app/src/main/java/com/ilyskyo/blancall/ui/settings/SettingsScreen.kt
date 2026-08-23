// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.notification.ReminderWorker
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.theme.AccentPresets
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderFrequency
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import com.ilyskyo.blancall.ui.theme.ThemeManager
import com.ilyskyo.blancall.ui.theme.ThemeMode

@Composable
fun SettingsScreen(navController: NavController) {
    val themeMode by ThemeManager.themeMode.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
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
            Text("设置", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 外观 ──
            Text("外观", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(4.dp)) {
                    // 浅色米黄底色开关（深色模式始终纯黑，不受此开关影响）
                    val lightBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()

                    // 主题模式：固定 3 行列表（与米黄底色开关无关）
                    OptionRow(
                        label = "浅色模式",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.LIGHT) }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    OptionRow(
                        label = "深色模式",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.DARK) }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    OptionRow(
                        label = "跟随系统",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.SYSTEM) }
                    )

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("米黄底色", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("开启后，在浅色模式下软件界面使用米黄底色，不影响深色模式。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = lightBeige,
                            onCheckedChange = { AppPrefs.lightBeigeBackgroundEnabled = it }
                        )
                    }

                    // 内置素材库已移至下方「拓展功能」分组
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 主题色 ──
            Text("主题色", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                val accentIndex by AppPrefs.accentColorFlow.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 根据系统深色模式选择 primaryDark/primaryLight，保证深色下对比度
                    val isDark = isSystemInDarkTheme()
                    AccentPresets.forEachIndexed { index, preset ->
                        val isSelected = index == accentIndex
                        val accentColor = if (isDark) preset.primaryDark else preset.primaryLight
                        Surface(
                            onClick = { AppPrefs.accentColorIndex = index },
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(44.dp),
                            border = if (isSelected)
                                BorderStroke(2.5.dp, accentColor)
                            else
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier.size(24.dp),
                                    shape = RoundedCornerShape(50),
                                    color = accentColor,
                                    content = {}
                                )
                                if (isSelected) {
                                    Text("✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentColor,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 特性 ──
            Text("特性", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                // 改用 AppPrefs 的 Flow collectAsState，与持久化值保持单一数据源
                val predictiveBack by AppPrefs.predictiveBackFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("预测性返回手势", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("返回时有滑动缩放动画，关闭后使用淡入淡出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = predictiveBack,
                        onCheckedChange = { AppPrefs.predictiveBackEnabled = it }
                    )
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // 首页表情图标显示开关
                val showHomeEmoji by AppPrefs.showHomeEmojiFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("首页显示表情图标", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("开启该功能后，首页左上角显示表情图标",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = showHomeEmoji,
                        onCheckedChange = { AppPrefs.showHomeEmoji = it }
                    )
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // ── AI 学习助手开关（关闭后所有 AI 入口隐藏） ──
                val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 AI 功能", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("开启后可在阅读页与文章列表中与 AI 对话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = aiEnabled,
                        onCheckedChange = { AppPrefs.aiEnabled = it }
                    )
                }

                // AI 配置管理（仅开启时显示）：多配置 + 开关式选择
                androidx.compose.animation.AnimatedVisibility(visible = aiEnabled) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        HorizontalDivider(
                            Modifier.padding(bottom = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )

                        // ── AI 对话配置入口（点击进入配置管理页） ──
                        val chatProfiles by AiConfigStore.chatProfilesFlow.collectAsState()
                        val activeChatId by AiConfigStore.activeChatIdFlow.collectAsState()
                        val activeChat = chatProfiles.find { it.id == activeChatId }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("ai_config") }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("对话配置", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (activeChat != null) "已开启「${activeChat.name}」" else "当前还未选择",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeChat != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("管理 ›", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (chatProfiles.isEmpty()) {
                            OutlinedButton(
                                onClick = { navController.navigate("ai_profile_edit/chat") },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("点击创建配置信息", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        // 保存与 AI 的对话开关（开启后首页出现 AI 历史入口）
                        val aiHistoryEnabled by AppPrefs.aiHistoryEnabledFlow.collectAsState()
                        Row(
                            Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("保存与 AI 的对话", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text("开启后对话自动保存在本机，首页左下角出现「AI」入口可查看历史",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 16.dp))
                            }
                            Switch(
                                checked = aiHistoryEnabled,
                                onCheckedChange = { AppPrefs.aiHistoryEnabled = it }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        // 联网搜索开关（开启后提问自动联网搜索核验）
                        val aiSearchEnabled by AppPrefs.aiSearchEnabledFlow.collectAsState()
                        Row(
                            Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("联网搜索", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text("提问时自动联网搜索核验知识",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = aiSearchEnabled,
                                onCheckedChange = { AppPrefs.aiSearchEnabled = it }
                            )
                        }

                        // ── 联网搜索配置入口（点击进入配置管理页） ──
                        val searchProfiles by AiConfigStore.searchProfilesFlow.collectAsState()
                        val activeSearchId by AiConfigStore.activeSearchIdFlow.collectAsState()
                        val activeSearch = searchProfiles.find { it.id == activeSearchId }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("ai_config") }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("搜索配置", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (activeSearch != null) "已开启「${activeSearch.name}」" else "当前还未选择",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeSearch != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("管理 ›", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (searchProfiles.isEmpty()) {
                            OutlinedButton(
                                onClick = { navController.navigate("ai_profile_edit/search") },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("点击创建配置信息", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 复习 ──
            Text("复习", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                val templateId by AppPrefs.reviewTemplateFlow.collectAsState()
                val templates = listOf(ReviewTemplate.SPRINT, ReviewTemplate.STANDARD, ReviewTemplate.DEEP)
                Column(Modifier.padding(4.dp)) {
                    templates.forEachIndexed { idx, template ->
                        val isSelected = template.id == templateId
                        OptionRow(
                            label = template.name,
                            description = when (template.id) {
                                "sprint" -> "目标留存率 85% · 复习更勤快"
                                "deep" -> "目标留存率 95% · 间隔更长更省"
                                else -> "目标留存率 90% · 均衡节奏（默认）"
                            },
                            selected = isSelected,
                            onClick = { AppPrefs.reviewTemplateId = template.id }
                        )
                        if (idx < templates.size - 1) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 学习提醒 ──
            Text("学习提醒", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            ReminderSettingsCard()

            Spacer(Modifier.height(8.dp))

            // ── 帮助 ──
            Text("帮助", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Surface(
                    onClick = { navController.navigate("onboarding") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎓", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text("使用引导", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text("→", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Surface(
                    onClick = { navController.navigate("help") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📖", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text("帮助与说明", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text("→", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 拓展功能 ──
            Text("拓展功能", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                // ── 内置素材库：多库各自可启用，未来可继续追加 ──
                Text("内置素材库", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                val enabledSet by AppPrefs.builtInLibraryKeysFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("西方思想", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Switch(
                        checked = "western" in enabledSet,
                        onCheckedChange = { AppPrefs.setLibraryEnabled("western", it) }
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("高考必背篇目", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Switch(
                        checked = "gaokao" in enabledSet,
                        onCheckedChange = { AppPrefs.setLibraryEnabled("gaokao", it) }
                    )
                }
                // 预留更多库：在此 Row 之后追加即可（如化学句子大全）
            }

            Spacer(Modifier.height(8.dp))

            // ── 关于 ──
            Text("关于", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Blancall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text("Fill the blank, recall the knowledge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("智能挖空记忆助手 · by ilyskyo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("导入文本 · 自动挖空 · FSRS 智能复习",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 赞赏区 ──
            Text("赞赏区·本软件完全免费", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("求求了，如果可以的话，请扫码支持一下我 🥹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text("哪怕一分钱，也是坚持更新下去的动力",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Image(
                        painter = painterResource(id = R.drawable.appreciation_qr),
                        contentDescription = "赞赏二维码",
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .aspectRatio(1f),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // ── 版本号 ──
            val context = LocalContext.current
            val versionName = remember {
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: ""
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Blancall v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ========== 学习提醒设置卡片 ==========

@Composable
private fun ReminderSettingsCard() {
    val context = LocalContext.current
    val enabled by ReminderPrefs.enabledFlow.collectAsState()
    val hour by ReminderPrefs.hourFlow.collectAsState()
    val minute by ReminderPrefs.minuteFlow.collectAsState()
    val goalMinutes by ReminderPrefs.goalMinutesFlow.collectAsState()
    val frequency by ReminderPrefs.frequencyFlow.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }
    // 自定义目标分钟数输入
    var showCustomMinutesDialog by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(4.dp)) {
            // ── 开关行 ──
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("开启提醒", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (enabled) "已开启 · ${String.format("%02d:%02d", hour, minute)}"
                        else "关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { newEnabled ->
                        ReminderPrefs.enabled = newEnabled
                        if (newEnabled) {
                            (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
                        } else {
                            ReminderWorker.cancel(context)
                        }
                    }
                )
            }

            // ── 展开设置（仅开启时显示）──
            androidx.compose.animation.AnimatedVisibility(visible = enabled) {
                Column {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 提醒时间
                    Surface(
                        onClick = { showTimePicker = true },
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("提醒时间", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    String.format("%02d:%02d", hour, minute),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("▸", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 每日学习目标
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("每日学习目标", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 20).forEach { mins ->
                                FilterChip(
                                    selected = goalMinutes == mins,
                                    onClick = { ReminderPrefs.goalMinutes = mins },
                                    label = { AdaptiveChipLabel("${mins}分钟") },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                            // 自定义目标分钟数：已自定义时按钮显示实际分钟数
                            FilterChip(
                                selected = goalMinutes !in listOf(5, 10, 20),
                                onClick = {
                                    customMinutesInput = if (goalMinutes in listOf(5, 10, 20)) "" else goalMinutes.toString()
                                    showCustomMinutesDialog = true
                                },
                                label = {
                                    AdaptiveChipLabel(
                                        if (goalMinutes in listOf(5, 10, 20)) "自定义" else "${goalMinutes}分钟"
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 提醒频率
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("提醒频率", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReminderFrequency.entries.forEach { freq ->
                                FilterChip(
                                    selected = frequency == freq,
                                    onClick = { ReminderPrefs.frequency = freq },
                                    label = { Text(freq.label, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 时间选择弹窗 ──
    if (showTimePicker) {
        TimePickerDialog(
            currentHour = hour,
            currentMinute = minute,
            onConfirm = { h, m ->
                ReminderPrefs.hour = h
                ReminderPrefs.minute = m
                showTimePicker = false
                ReminderWorker.scheduleNext(context)
            },
            onDismiss = { showTimePicker = false }
        )
    }

    // ── 自定义目标分钟数输入对话框 ──
    if (showCustomMinutesDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomMinutesDialog = false },
            title = { Text("自定义每日学习时长") },
            text = {
                OutlinedTextField(
                    value = customMinutesInput,
                    onValueChange = { input ->
                        customMinutesInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text("分钟数") },
                    suffix = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customMinutesInput.toIntOrNull() != null,
                    onClick = {
                        customMinutesInput.toIntOrNull()?.let { ReminderPrefs.goalMinutes = it.coerceIn(1, 600) }
                        showCustomMinutesDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMinutesDialog = false }) { Text("取消") }
            }
        )
    }
}

// ========== 简易时间选择弹窗 ==========

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimePickerDialog(
    currentHour: Int,
    currentMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 以 currentHour/currentMinute 为 key，避免外部时间变化时输入框仍显示旧值
    var hourText by remember(currentHour) { mutableStateOf(currentHour.toString().padStart(2, '0')) }
    var minuteText by remember(currentMinute) { mutableStateOf(currentMinute.toString().padStart(2, '0')) }

    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text("设置提醒时间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 手动输入区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("时", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { v ->
                                if (v.length <= 2 && v.all { it.isDigit() }) {
                                    hourText = v
                                }
                            },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            placeholder = { Text("0-23", style = MaterialTheme.typography.bodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    Text(" : ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("分", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = minuteText,
                            onValueChange = { v ->
                                if (v.length <= 2 && v.all { it.isDigit() }) {
                                    minuteText = v
                                }
                            },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            placeholder = { Text("0-59", style = MaterialTheme.typography.bodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 快速预设（FlowRow 自动换行，避免挤压叠压）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "早上 8:00" to Pair(8, 0),
                        "中午 12:30" to Pair(12, 30),
                        "下午 6:00" to Pair(18, 0),
                        "晚上 8:00" to Pair(20, 0),
                        "晚上 9:00" to Pair(21, 0)
                    ).forEach { (label, time) ->
                        val h = hourText.toIntOrNull() ?: -1
                        val m = minuteText.toIntOrNull() ?: -1
                        val isActive = h == time.first && m == time.second
                        AssistChip(
                            onClick = {
                                hourText = time.first.toString().padStart(2, '0')
                                minuteText = time.second.toString().padStart(2, '0')
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = (hourText.toIntOrNull() ?: currentHour).coerceIn(0, 23)
                    val m = (minuteText.toIntOrNull() ?: currentMinute).coerceIn(0, 59)
                    onConfirm(h, m)
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 自适应单行文字：放不下时自动缩小字号，保证永远排在同一行（不换行）。
 * 用于选项卡/FilterChip 的 label，避免不同设备/字号下文字被挤成两行。
 */
@Composable
private fun AdaptiveChipLabel(text: String) {
    val style = MaterialTheme.typography.labelSmall
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = style.copy(fontSize = fontSize),
        onTextLayout = { result ->
            // 溢出时逐步缩小字号（下限 8sp），直到能单行放下
            if (result.hasVisualOverflow && fontSize.value > 8f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}
