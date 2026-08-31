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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
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
                    "使用帮助",
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
                    title = "开始练习",
                    expandedContent = {
                        Text(
                            "用户选择文章后，点击「开始练习」，选择适合自己的练习模式即可开始背诵。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "推荐流程",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowTag("第一次学习") {
                            Text("句子挖空 → 字词挖空 → 反向默写")
                        }
                        Spacer(Modifier.height(6.dp))
                        FlowTag("复习阶段") {
                            Text("薄弱优先 → 反向默写 → 全覆盖检测")
                        }
                    }
                )

                // ── 2. 练习模式介绍 ──
                HelpSection(
                    emoji = "📋",
                    title = "练习模式介绍",
                    expandedContent = {
                        InfoCard(
                            emoji = "📝",
                            title = "句子挖空",
                            desc = "隐藏部分句子内容，通过上下文回忆完整原文。",
                            footer = "适合：初次学习文章"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔤",
                            title = "字词挖空",
                            desc = "隐藏关键字词，加强易错字和重点内容记忆。",
                            footer = "适合：已熟悉文章，需强化准确率"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "✍️",
                            title = "反向默写",
                            desc = "段落打散默写 — 整段还原",
                            footer = "适合：考前检测和模拟考试"
                        )
                    }
                )

                // ── 3. 挖空策略介绍 ──
                HelpSection(
                    emoji = "🎯",
                    title = "挖空策略介绍",
                    expandedContent = {
                        InfoCard(
                            emoji = "⚖️",
                            title = "均衡",
                            desc = "系统平均分配挖空位置，保持稳定练习难度。适合日常练习。"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🎯",
                            title = "薄弱优先",
                            desc = "优先抽取用户曾经错误的位置进行训练。适合针对性复习。"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔍",
                            title = "全覆盖",
                            desc = "覆盖文章更多内容，全面检测掌握情况。适合阶段测试。"
                        )
                    }
                )

                // ── 4. 提示功能 ──
                HelpSection(
                    emoji = "💡",
                    title = "提示功能",
                    expandedContent = {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "💡",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column {
                                Text(
                                    "开启提示后，可以查看部分答案辅助回忆。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "关闭提示后，可以进行更接近考试环境的训练。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                // ── 5. 查看学习数据 ──
                HelpSection(
                    emoji = "📊",
                    title = "查看学习数据",
                    expandedContent = {
                        Text(
                            "在首页点击「学习数据」卡片，可以弹出本机当前累计的学习统计概览。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "查看全局统计",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "弹窗本身即为入口。在弹窗空白处或弹窗外点击，即可进入全局统计页面，查看更详细的学习数据（如每篇文章的练习次数、正确率、累计用时等）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "关闭弹窗",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点击弹窗右上角的「✕」即可关闭弹窗，停留在首页当前页面。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                // ── 6. 学习建议 ──
                HelpSection(
                    emoji = "📖",
                    title = "学习建议",
                    expandedContent = {
                        Text(
                            "推荐学习流程",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        SuggestionStep(
                            step = "第一次背诵",
                            items = listOf(
                                "使用句子挖空熟悉文章结构",
                                "使用字词挖空强化细节",
                                "使用反向默写检测完整掌握"
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        SuggestionStep(
                            step = "复习",
                            items = listOf(
                                "开启薄弱优先，重点练习错误内容"
                            )
                        )
                    }
                )

                // ── 7. 操作小贴士（隐藏操作与特殊交互说明） ──
                HelpSection(
                    emoji = "🛠️",
                    title = "操作小贴士",
                    expandedContent = {
                        Text(
                            "本应用为保持界面简洁，将部分操作入口收纳在长按与菜单中。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "返回与退出",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "所有页面（设置、帮助、我的文章、统计、阅读、练习等）均提供左上角白色圆形的返回按钮；也可使用系统返回手势（从屏幕边缘右滑）或系统返回键退出。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "阅读页编辑模式下，返回手势会先退出编辑状态；再次返回才退出页面。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "长按触发的操作",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoCard(
                            emoji = "🏠",
                            title = "首页文章卡长按",
                            desc = "将文章从首页隐藏（不会删除，文章仍保留在「我的文章」中）"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📋",
                            title = "我的文章页长按",
                            desc = "进入多选模式：可批量删除，或勾选 2 篇以上进行跨文复习"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🎨",
                            title = "首页图标与副标题点击",
                            desc = "点击首页左上角表情图标可更换 Logo；点击副标题文字可自定义"
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "删除文章",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "阅读页右上角「删除」按钮可删除当前文章；我的文章页多选后可批量删除。删除后无法恢复，请谨慎操作。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "练习页的隐藏入口",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "练习页顶部「⋮」菜单中可切换练习模式、挖空策略、开启提示、沉浸模式、段落分层，以及导出 PDF 试卷和分享笔记。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "统计页练习记录中的「复习 ›」可直接进入该记录的薄弱集训模式。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                // ── 8. AI 使用说明 ──
                HelpSection(
                    emoji = "🤖",
                    title = "AI",
                    expandedContent = {
                        Text(
                            "启用 AI 后，可以把你的文章交给 AI，帮助你解释难点、出练习题、分析易错内容。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "开启与配置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在「设置 → AI → 启用 AI 功能」中开启，并填写 API 地址、API Key 与模型名。支持 OpenAI 兼容接口（DeepSeek / 通义 / GLM / Kimi 等）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "联网搜索：在设置中开启「联网搜索」后，到 tavily.com 免费注册（邮箱即可），控制台获取 API Key（格式 tvly-xxx，免费 1000 次/月），粘贴到「Tavily API Key」输入框即可使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "入口在哪里",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoCard(
                            emoji = "📖",
                            title = "阅读页「AI」按钮",
                            desc = "查看文章时，底部按钮行中点击「AI」，携带当前文章进入对话"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📋",
                            title = "文章列表「发给 AI」",
                            desc = "长按文章进入多选后，点击「发给 AI」，可携带多篇文章跨文对话"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "📋",
                            title = "我的文章页「AI对话记录」",
                            desc = "开启「保存与 AI 的对话」后出现，查看历史对话并继续聊"
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "对话与历史",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "AI 会结合文章内容与你的学习数据（练习次数、正确率、高频易错内容）针对性辅导。长按任意消息可复制单条；对话页顶部「复制对话」可整段复制。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在设置中开启「保存与 AI 的对话」后，每次对话自动保存在本机，可从「我的文章」页的「AI对话记录」入口随时查看历史，点击任意历史会话即可继续对话。关闭后不再保存。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在设置中开启「联网搜索」并填写 Tavily Key 后，提问时会自动联网搜索核验知识，AI 将优先参考搜索到的权威信息作答（可帮助核验背诵内容与考试知识点）。未配置时 AI 仅基于文章与自身知识回答。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "隐私与安全",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "API Key 经系统加密后仅存本机，不会上传。注意：对话时文章内容与学习数据会发送至你所配置的 API 服务。关闭「启用 AI 功能」后，所有 AI 入口将不再显示。",
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
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
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
