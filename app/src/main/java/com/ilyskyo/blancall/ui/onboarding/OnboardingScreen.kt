// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.onboarding

import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_DARK
import com.ilyskyo.blancall.ui.common.GLASS_ALPHA_LIGHT
import com.ilyskyo.blancall.ui.theme.AppPrefs
import kotlinx.coroutines.launch

/** 引导页内容（纯离线，不含任何 AI / 联网功能，双版本共用） */
private data class OnboardingPage(
    val icon: AppIconKind,
    val title: String,
    val subtitle: String,
    val features: List<String>
)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = AppIconKind.Home,
        title = "全新首页",
        subtitle = "一屏掌握你的背诵进度",
        features = listOf(
            "顶部搜索：秒查标题、正文与添加日期",
            "「添加」按钮就在搜索栏旁，可随时导入文章",
            "下拉露出品牌栏：logo 与「设置」，设置需下拉可见"
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Inbox,
        title = "导入文章",
        subtitle = "把要背的文本一键变成可练习的文章",
        features = listOf(
            "支持粘贴文本 / 导入文件",
            "自动识别标题与正文",
            "从内部素材库也能直接导入"
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Edit,
        title = "三种背诵模式",
        subtitle = "由浅入深，真正记住",
        features = listOf(
            "句子挖空：看上下文填空",
            "字词挖空：逐词过关",
            "反向默写：看着答案背全文"
        )
    ),
    OnboardingPage(
        icon = AppIconKind.TrackChanges,
        title = "智能复习",
        subtitle = "在遗忘之前，恰到好处地提醒你",
        features = listOf(
            "FSRS 自适应算法编排复习",
            "首页「待复习」任务一目了然",
            "按节奏巩固，记忆更牢"
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Insights,
        title = "数据 & 素材库",
        subtitle = "量化进步，内容常看常新",
        features = listOf(
            "学习数据：练习次数与正确率",
            "错题分析 / 记忆热力图",
            "内置「西方思想」等离线素材库"
        )
    )
)

/**
 * 首次使用引导页 —— 可视化讲解核心背诵功能。
 *
 * 纯离线，不涉及任何 AI / 联网能力，因此完整版与 NoAI 版共用同一套内容，
 * 遵守「双版本同步（AI 相关除外）」规范。
 *
 * @param onFinish 引导完成后的回调（默认置为已看过并返回上一页）
 */
@Composable
fun OnboardingScreen(
    navController: NavController,
    onFinish: (() -> Unit)? = null
) {
    val pages = ONBOARDING_PAGES
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    val finish: () -> Unit = {
        AppPrefs.onboardingSeen = true
        if (onFinish != null) {
            onFinish()
        } else {
            // 引导页恒为压入 home 之上的子页（AppNavigation 恒以 home 为 startDestination），
            // 完成即弹栈归位到 home；栈底 home 不被触碰，锚点永不漂移。
            // 注意：这里绝不使用 popUpTo("home"){inclusive=true}——若 home 已不在栈中
            // 会 no-op 把引导页变成僵尸栈底，导致点「首页」tab 失效。
            navController.popBackStack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AmbientBackground()

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ── 顶部：与「帮助-欢迎使用」页同位同字号的品牌标题 + 右上跳过 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "欢迎使用 Blancall",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "先花几分钟了解基本用法，即可开始背诵",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    onClick = finish,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                ) {
                    Text(
                        "跳过",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // ── 分页内容 ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                OnboardingPageCard(
                    page = pages[pageIndex],
                    pageIndex = pageIndex,
                    pageCount = pages.size,
                    accent = accent,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            // ── 底部：指示点 + 下一步 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { i ->
                        val selected = pagerState.currentPage == i
                        Box(
                            modifier = Modifier
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) accent
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                                .animateContentSize(tween(200))
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    onClick = {
                        if (pagerState.currentPage < pages.size - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            finish()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        if (pagerState.currentPage < pages.size - 1) "下一步"
                        else if (onFinish != null) "下一步"  // 欢迎流程内：后面还有「帮助-欢迎使用」页
                        else "开始使用",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/** 单个引导页：磨砂玻璃卡片式视觉展示 */
@Composable
private fun OnboardingPageCard(
    page: OnboardingPage,
    pageIndex: Int,
    pageCount: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val isDark = isBlancallDark()
    val bgAlpha = if (isDark) GLASS_ALPHA_DARK else GLASS_ALPHA_LIGHT
    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = bgAlpha)
    val shape = RoundedCornerShape(28.dp)

    Box(modifier = modifier) {
        // 磨砂卡片底（已移除顶部高光 verticalGradient 装饰层）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                .background(bgColor)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 视觉图标（玻璃圆底）
                Box(
                    modifier = Modifier
                        .size(124.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(accent.copy(alpha = 0.13f))
                        .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(kind = page.icon, modifier = Modifier.size(58.dp), tint = accent)
                }

                Spacer(Modifier.height(28.dp))

                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = page.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(Modifier.height(20.dp))

                page.features.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(6.dp).clip(CircleShape).background(accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = f,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // 页码角标
        Text(
            text = "${pageIndex + 1}/$pageCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
}