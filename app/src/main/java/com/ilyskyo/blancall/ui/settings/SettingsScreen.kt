// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.ilyskyo.blancall.ui.theme.isBlancallDark
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
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassSwitch
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
    // 首页副标题编辑弹窗（首页品牌栏收起时也可从这里修改）
    var showSubtitleDialog by remember { mutableStateOf(false) }
    val homeSubtitle by AppPrefs.subtitleFlow.collectAsState()

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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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
                        GlassSwitch(
                            checked = lightBeige,
                            onCheckedChange = { AppPrefs.lightBeigeBackgroundEnabled = it }
                        )
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    // ── 首页副标题（点击编辑；首页品牌栏收起时也能从这里修改）──
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showSubtitleDialog = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("首页副标题", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("首页品牌栏下方的标语，点击编辑",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("编辑 ›", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }

                    // 内置素材库已移至下方「拓展功能」分组
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 主题色 ──
            Text("主题色", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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
                    val isDark = isBlancallDark()
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
                                    AppIcon(
                                        kind = AppIconKind.Check,
                                        tint = accentColor,
                                        modifier = Modifier.size(14.dp)
                                    )
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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
                        Text("启用后，可在确认返回前预览目标界面",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassSwitch(
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
                        Text("顶栏显示图标", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("开启该功能后，首页左上角显示图标",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassSwitch(
                        checked = showHomeEmoji,
                        onCheckedChange = { AppPrefs.showHomeEmoji = it }
                    )
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // 段落首行缩进开关（导入时给未缩进段落补两格；关闭后不再新增缩进）
                val autoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("段落首行缩进", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("开启后，导入纯文本/粘贴文章时自动为每段首行自动缩进；PDF、Word 等文档保持原样",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp))
                    }
                    GlassSwitch(
                        checked = autoIndentEnabled,
                        onCheckedChange = { AppPrefs.autoIndentEnabled = it }
                    )
                }

            }

            Spacer(Modifier.height(8.dp))

            // ── AI（独立分组：母项开关 + 子项设置，层级一目了然） ──
            Text("AI", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                // AI 开关（关闭后所有 AI 入口隐藏）
                val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 AI 功能", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassSwitch(
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

                        // 子项统一收纳进嵌套玻璃面板（backdrop=false 避免双重模糊）：
                        // 半透明染色 + 1dp 细描边延续全 App 的 Apple 玻璃语言，与「启用 AI 功能」母项形成从属层次
                        val isDark = isBlancallDark()
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface,
                            containerAlpha = if (isDark) 0.90f else 0.85f,
                            backdrop = false
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Text("AI 功能设置", style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp))

                                // ── 使用AI挖空（关闭后挖空走本地算法，采集页只采策略与段落）──
                                val useAiCloze by AppPrefs.useAiClozeFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("使用AI挖空", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    GlassSwitch(
                                        checked = useAiCloze,
                                        onCheckedChange = { AppPrefs.useAiCloze = it }
                                    )
                                }

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
                                // 保存与 AI 的对话开关（开启后在「我的文章」页显示历史入口）
                                val aiHistoryEnabled by AppPrefs.aiHistoryEnabledFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("保存与 AI 的对话", style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text("开启后对话自动保存在本机，在「我的文章」页可查看历史对话",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 16.dp))
                                    }
                                    GlassSwitch(
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
                                        Text("开启后，在对话时AI可以联网搜索信息",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    GlassSwitch(
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
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 复习 ──
            Text("记忆方案", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                val templateId by AppPrefs.reviewTemplateFlow.collectAsState()
                val templates = listOf(ReviewTemplate.SPRINT, ReviewTemplate.STANDARD, ReviewTemplate.DEEP)
                Column(Modifier.padding(4.dp)) {
                    templates.forEachIndexed { idx, template ->
                        val isSelected = template.id == templateId
                        OptionRow(
                            label = template.name,
                            description = when (template.id) {
                                "sprint" -> "复习更勤快"
                                "deep" -> "间隔更长"
                                else -> "均衡节奏"
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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
                    GlassSwitch(
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
                    GlassSwitch(
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Blancall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text("Fill the blank, recall the knowledge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("挖空记忆助手 · by ilyskyo",
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

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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

    // ── 首页副标题编辑弹窗 ──
    if (showSubtitleDialog) {
        var editText by remember(homeSubtitle) { mutableStateOf(homeSubtitle) }
        BlancallAlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text("编辑首页副标题") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        // 默认副标题 38 字 > 旧 30 字限制，编辑会被拦截；取消限制
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("自定义副标题") },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 直接保存（允许清空：清空即移除副标题）
                        AppPrefs.subtitle = editText
                        showSubtitleDialog = false
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleDialog = false }) { Text("取消") }
            }
        )
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
                AppIcon(
                    kind = AppIconKind.Check,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
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

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
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
                GlassSwitch(
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
