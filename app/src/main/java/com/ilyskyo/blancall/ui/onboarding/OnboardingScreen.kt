// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import com.ilyskyo.blancall.ui.common.Motion
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
import androidx.compose.ui.res.stringResource
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
    // 顶层表存不了 stringResource（不在组合上下文）⇒ 存资源号，渲染点再解析
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @StringRes val features: List<Int>
)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = AppIconKind.Home,
        title = com.ilyskyo.blancall.R.string.ob_home_title,
        subtitle = com.ilyskyo.blancall.R.string.ob_home_subtitle,
        features = listOf(
            com.ilyskyo.blancall.R.string.ob_home_f1,
            com.ilyskyo.blancall.R.string.ob_home_f2,
            com.ilyskyo.blancall.R.string.ob_home_f3
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Inbox,
        title = com.ilyskyo.blancall.R.string.ob_import_title,
        subtitle = com.ilyskyo.blancall.R.string.ob_import_subtitle,
        features = listOf(
            com.ilyskyo.blancall.R.string.ob_import_f1,
            com.ilyskyo.blancall.R.string.ob_import_f2,
            com.ilyskyo.blancall.R.string.ob_import_f3
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Edit,
        title = com.ilyskyo.blancall.R.string.ob_modes_title,
        subtitle = com.ilyskyo.blancall.R.string.ob_modes_subtitle,
        features = listOf(
            com.ilyskyo.blancall.R.string.ob_modes_f1,
            com.ilyskyo.blancall.R.string.ob_modes_f2,
            com.ilyskyo.blancall.R.string.ob_modes_f3
        )
    ),
    OnboardingPage(
        icon = AppIconKind.TrackChanges,
        title = com.ilyskyo.blancall.R.string.ob_review_title,
        subtitle = com.ilyskyo.blancall.R.string.ob_review_subtitle,
        features = listOf(
            com.ilyskyo.blancall.R.string.ob_review_f1,
            com.ilyskyo.blancall.R.string.ob_review_f2,
            com.ilyskyo.blancall.R.string.ob_review_f3
        )
    ),
    OnboardingPage(
        icon = AppIconKind.Insights,
        title = com.ilyskyo.blancall.R.string.ob_data_title,
        subtitle = com.ilyskyo.blancall.R.string.ob_data_subtitle,
        features = listOf(
            com.ilyskyo.blancall.R.string.ob_data_f1,
            com.ilyskyo.blancall.R.string.ob_data_f2,
            com.ilyskyo.blancall.R.string.ob_data_f3
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
                        stringResource(com.ilyskyo.blancall.R.string.ob_welcome_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.ob_welcome_subtitle),
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
                        stringResource(com.ilyskyo.blancall.R.string.ob_skip),
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
                                .animateContentSize(Motion.contentSize())
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
                        if (pagerState.currentPage < pages.size - 1 || onFinish != null)
                            stringResource(com.ilyskyo.blancall.R.string.ob_next)   // 欢迎流程内：后面还有「帮助-欢迎使用」页
                        else stringResource(com.ilyskyo.blancall.R.string.ob_start_using),
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
                    text = stringResource(page.title),
                    style = MaterialTheme.typography.headlineSmall,
                    // 字重红线：headline* 只能 SemiBold（设备楷体无 Bold 面会回退黑体）
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(page.subtitle),
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
                            text = stringResource(f),
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