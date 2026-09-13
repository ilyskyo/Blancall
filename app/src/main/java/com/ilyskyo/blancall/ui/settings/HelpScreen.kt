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
                            "选择文章后，点「开始练习」，选好模式即可开始背诵。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle("三种入口（任选其一）")
                        Bullet("阅读页底部「开始练习」：先打开文章（阅读页），再开始")
                        Bullet("首页卡片里的「练习」按钮：「最近使用 / 文章卡片 / 待继续」等卡上一步直达")
                        Bullet("首页「继续做」卡片：一键续练未完成的进度（保留原进度）")
                        SubTitle("练习流程")
                        BulletItem(1, "选择文章（可多选 2 篇以上做「跨文混合」）")
                        BulletItem(2, "在模式弹窗中选择练习模式（句子 / 字词 / 反向 / 自定义）")
                        BulletItem(3, "练习中可用顶部「⋮」随时切换模式、挖空策略、开关提示")
                        BulletItem(4, "完成后查看成绩卡，可分享笔记或导出 PDF 试卷")
                        SubTitle("推荐流程")
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
                        Text(
                            "四种模式难度递进：先熟悉结构，再强细节，最后整段还原。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
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
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🧩",
                            title = "自定义挖空",
                            desc = "按你预先圈定的位置挖空 —— 在阅读页将遮挡粒度切为「自定义」并逐句/逐词/逐字标注，练习时选「自定义配置」即可选套用（也可在练习前直接新建编辑）。",
                            footer = "适合：针对性极强的个性化背通"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🔀",
                            title = "跨文混合练习",
                            desc = "在「我的文章」长按进入多选，勾选 2 篇以上后点「开始练习」，多篇文章错开抽空混合背诵。",
                            footer = "适合：阶段总复习、防止单篇依赖上下文"
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoCard(
                            emoji = "🎯",
                            title = "薄弱集训",
                            desc = "在统计页的练习记录中点「复习 ›」，或从「待复习」卡片进入，集中练你曾经错过的位置。",
                            footer = "适合：针对性消灭易错点"
                        )
                    }
                )

                // ── 3. 挖空策略介绍 ──
                HelpSection(
                    emoji = "🎯",
                    title = "挖空策略介绍",
                    expandedContent = {
                        Text(
                            "策略决定「挖哪些位置」，可在练习中随时通过顶部「⋮」切换。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
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
                        SubTitle("挖空粒度（挖多大）")
                        KeyValue("复句", "整句/整个段落区域隐藏，难度最低")
                        KeyValue("分句", "按句子断句隐藏，适合熟悉结构")
                        KeyValue("字词", "只藏关键字词，难度更高")
                        KeyValue("单字", "逐字隐藏，最接近听写式检验")
                        Text(
                            "练习中的「句子 / 字词 / 反向」即对应不同粒度；自定义配置下粒度由你自己的标注决定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                )

                // ── 4. 提示功能 ──
                HelpSection(
                    emoji = "💡",
                    title = "提示功能",
                    expandedContent = {
                        Text(
                            "同一个开关，两种练习强度，可在练习页随时切换。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle("开启提示")
                        Bullet("可以查看部分答案辅助回忆（降难度，适合初学与卡壳时）")
                        Bullet("卡在某一空时用提示过渡，避免长时间卡顿影响节奏")
                        SubTitle("关闭提示")
                        Bullet("完全靠自己回忆，更接近考试环境（提高记忆强度）")
                        Bullet("建议：熟悉后逐步关提示，以真实检验掌握度")
                        SubTitle("如何切换")
                        Bullet("练习页顶部「⋮」菜单 → 提示开关（一键切）")
                    }
                )

                // ── 5. 查看学习数据 ──
                HelpSection(
                    emoji = "📊",
                    title = "查看学习数据",
                    expandedContent = {
                        SubTitle("首页「学习数据」卡片")
                        Bullet("点卡片 → 弹出本机当前累计的学习统计概览")
                        Bullet("弹窗空白处/弹窗外点击 → 进入全局统计页（更详细的数据）")
                        Bullet("点弹窗右上角「✕」→ 仅关闭弹窗，留在首页")
                        SubTitle("首页「全局数据」卡片")
                        Bullet("恒定展示累计概览（文章数 / 练习量 / 正确率等）")
                        SubTitle("「数据」页面里能看到")
                        KeyValue("学习日历", "按天回顾练习量（颜色越深练得越多）")
                        KeyValue("热力图", "长期坚持情况一眼可见")
                        KeyValue("每篇统计", "每篇文章的练习次数、正确率、累计用时")
                        KeyValue("练习记录", "逐次记录可查，点「复习 ›」直接进入薄弱集训")
                    }
                )

                // ── 6. 学习建议 ──
                HelpSection(
                    emoji = "📖",
                    title = "学习建议",
                    expandedContent = {
                        Text(
                            "核心原则：先熟悉结构 → 再强细节 → 最后整段还原；间段复习优于一次性突击。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle("推荐学习流程")
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
                                "开启薄弱优先，重点练习错误内容",
                                "用「待复习」卡片跟进今日到期任务",
                                "考前用「跨文混合 + 反向默写」做总检"
                            )
                        )
                        SubTitle("节奏建议")
                        Bullet("一次练一篇，当天多轮短时胜过一次性长练")
                        Bullet("当天全部正确后，第二天再回头练一次巩固")
                        Bullet("错得多的位置交给「薄弱集训」专项消灭")
                    }
                )

                // ── 7. 操作小贴士（完整操作手册） ──
                HelpSection(
                    emoji = "🛠️",
                    title = "操作小贴士",
                    expandedContent = {
                        Text(
                            "为保持界面简洁，很多操取消了按钮，收进了长按、下拉与编辑态 —— 下面把所有隐藏操作逐条列清。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SubTitle("首页 · 卡片管理")
                        Bullet("长按任意卡片 → 进入编辑态；底部悬浮「完成」退出编辑")
                        Bullet("编辑态拖动卡片 → 换位置；右下角黑弧手柄 → 自由调整卡片大小（拉大拉小均可）")
                        Bullet("编辑态四角按钮：⏳ = 固定位置 / 取消固定；❌ = 删除该卡片")
                        Bullet("编辑态顶部「+」→ 添加卡片面板（待复习 / 继续做 / 最近文章 / 文章卡片 / 学习数据 / 全局数据 / 添加文章 / 自定义配置）")
                        Bullet("「文章卡片」可添加多张（每篇文章一张），默认一行高、内容与「最近使用」同款；最多可拉大到 2 行高（内容少，再大只剩空白）")
                        Bullet("「管理最近文章」入口 → 管理阅读历史（不影响文章本体）")
                        SubTitle("首页 · 下拉顶栏")
                        Bullet("在顶部不断下拉，出现提示条；拉到「松开手指」（有震动）后松手 → 展开品牌顶栏")
                        Bullet("顶栏已展开时再下拉过阈值 → 松手收起顶栏")
                        Bullet("轻轻一扫不会触发收放（只认阈值，防误触）")
                        SubTitle("首页 · 个性化")
                        Bullet("点左上角图标 → 换 Logo（含自定义表情）")
                        Bullet("点副标题文字 → 自定义副标题")
                        SubTitle("我的文章")
                        Bullet("点文章 → 进入阅读页")
                        Bullet("长按 → 多选模式：批量删除 / 勾选 2 篇以上开始跨文混合练习 / 发给 AI")
                        SubTitle("阅读页")
                        Bullet("底部按钮：学习统计 / 阅读模式 / 自定义 / 开始练习（Pro 版另有 AI）")
                        Bullet("「阅读模式」：调字号、行距、字体、字重、配色、遮挡粒度（均衡 / 字词 / 自定义）")
                        Bullet("正文双指捏合 → 直接缩放字号")
                        Bullet("顶部右侧：删除 / 编辑文章")
                        SubTitle("遮挡自定义（阅读页）")
                        Bullet("将遮挡粒度切到「自定义」→ 进入配置列表；点按配置项直接使用，行尾「编辑」进入标注页，长按可重命名 / 删除")
                        Bullet("选中自定义粒度时，按钮上显示当前使用的配置名")
                        Bullet("标注页：点句子 → 遮住整句；长按句子 → 拆成词，点词遮词；长按词 → 再拆成字；再次长按逐级还原")
                        Bullet("标注页双指捏合 → 缩放正文字号（方便精细标词）")
                        SubTitle("练习页")
                        Bullet("顶部「⋮」菜单：切换练习模式 / 挖空策略 / 提示开关 / 沉浸模式 / 段落分层，以及导出 PDF 试卷与分享笔记")
                        Bullet("练习中长按句子或字词 → 切换挖空粒度（复句 → 分句 → 字词 → 单字，循环还原）")
                        Bullet("完成后成绩卡可查看正确率与用时，并可直接分享")
                        SubTitle("自定义挖空（练习时）")
                        Bullet("配置列表：点按进入编辑；长按可开始练习 / 重命名 / 删除；选择器模式下点按直接开练")
                        Bullet("编辑页：点按选中挖空、长按切换粒度（与阅读页标注一致）")
                        Bullet("从导入页「保存并挖空」直达新建 —— 导完文章马上圈定考点")
                        SubTitle("导入文章")
                        Bullet("两种方式：粘贴文本 / 文件导入（txt、PDF、Word 等；PDF 可预览）")
                        Bullet("标题留空时，可一键采用「建议标题」（从正文自动提取）")
                        Bullet("超大文本会提示并截断，建议拆分导入")
                        Bullet("底部两个按钮：「仅保存」= 存好返回；「保存并挖空」= 存好后直接进入该文章的自定义挖空编辑")
                        SubTitle("全局通用")
                        Bullet("所有页面左上角白色圆形按钮 = 返回；也可用系统返回手势 / 返回键")
                        Bullet("阅读页处于标注/编辑状态时，先退编辑再退页面")
                        Bullet("关键操作有震动反馈：下拉过阈值、长按进卡片编辑、拖动 / 缩放卡片、长按菜单等")
                        Bullet("删除文章不可恢复：阅读页右上「删除」或「我的文章」多选批量删除")
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
