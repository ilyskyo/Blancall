// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.navigation

import android.app.Activity
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ilyskyo.blancall.ui.common.BottomNavBar
import com.ilyskyo.blancall.ui.home.HomeScreen
import com.ilyskyo.blancall.ui.import.ImportScreen
import com.ilyskyo.blancall.ui.list.ListScreen
import com.ilyskyo.blancall.ui.ai.AiConfigScreen
import com.ilyskyo.blancall.ui.ai.AiHistoryScreen
import com.ilyskyo.blancall.ui.ai.AiProfileEditScreen
import com.ilyskyo.blancall.ui.ai.AiScreen
import com.ilyskyo.blancall.ui.practice.PracticeScreen
import com.ilyskyo.blancall.ui.reader.ReaderScreen
import com.ilyskyo.blancall.ui.settings.SettingsScreen
import com.ilyskyo.blancall.ui.settings.HelpScreen
import com.ilyskyo.blancall.ui.western.WesternThoughtScreen
import com.ilyskyo.blancall.ui.western.LibraryContentPage
import com.ilyskyo.blancall.ui.western.PdfPreviewScreen
import com.ilyskyo.blancall.ui.statistics.OverviewScreen
import com.ilyskyo.blancall.ui.statistics.StatisticsScreen
import com.ilyskyo.blancall.ui.onboarding.OnboardingScreen
import com.ilyskyo.blancall.ui.search.SearchScreen
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.SectionMode

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val predictiveBack by AppPrefs.predictiveBackFlow.collectAsState()
    // 内置素材库：启用任一库后底部导航栏追加「素材库」入口（支持多库扩展）
    val enabledLibraries by AppPrefs.builtInLibraryKeysFlow.collectAsState()
    // 当前路由（用于底部导航栏高亮）
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    // 首次使用引导页：首次启动展示一次，设置里也可重看。
    // 引导页是"压入 home 之上的子页面"，完成后 popBackStack() 归位——
    // 不能把它当作 startDestination，否则 findStartDestination() 锚点漂移，
    // 会导致点「首页」tab 时 home 不在返回栈里、被当子页压到当前页之上。
    val onboardingSeen by AppPrefs.onboardingSeenFlow.collectAsState()
    var onboardingLaunched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!onboardingSeen && !onboardingLaunched) {
            onboardingLaunched = true
            navController.navigate("onboarding")
        }
    }

    // 底部导航栏根页面：home/list/overview 固定，开启内置素材库时追加 philo
    val rootRoutes = listOf("home", "list", "overview") +
        if (enabledLibraries.isNotEmpty()) listOf("philo") else emptyList()
    // 按「根 tab 归属」匹配，覆盖各根页面的直接子路由（如 statistics/xxx、philo_content/xxx），
    // 保证进入子页面时底部导航栏仍可见且高亮正确，用户可随时点其它 tab 跳出。
    val currentTab = when {
        currentRoute == "home" || currentRoute?.startsWith("home/") == true -> 0
        currentRoute == "list" || currentRoute?.startsWith("list/") == true -> 1
        currentRoute == "overview" || currentRoute?.startsWith("statistics/") == true -> 2
        // 仅素材库卡片页（philo，纯 Compose）显示底部导航栏；
        // WebView 内容页（philo_content）不归 tab——隐藏玻璃条，避免液态玻璃
        // 持续折射 WebView 导致的闪烁（与 settings/reader 子页同策略）
        currentRoute == "philo" -> 3
        else -> -1
    }
    // 首页不再显示左下角入口按钮（入口已迁移到底部导航栏，导航栏固定启用）

    // tab 切换：回到根页面栈，避免子页面残留
    fun selectTab(index: Int) {
        // 不做 route==currentRoute 判断：navigateToTab 幂等（同页也安全），
        // 避免 currentRoute 匹配异常时误拦「点回当前 tab / 首页」
        val route = rootRoutes.getOrNull(index) ?: return
        navController.navigateToTab(route)
    }

    // tab 根页返回＝退出应用：4 个根页面真正平级，返回键不退回上一 tab、也不回启动页
    val navContext = LocalContext.current
    BackHandler(enabled = currentRoute in rootRoutes) {
        val activity = navContext as? Activity
        if (activity != null) activity.finish()
        else navController.popBackStack()
    }

    // ── 处理通知点击跳转 ──
    // 合并为单一消费：collect 即消费，避免双重导航；
    // Channel(CONFLATED) 保证冷启动期间投递的路由也能在收集开始后被收到，
    // 且消费后即从缓冲移除，配置变更（旋转）不会重复触发。
    LaunchedEffect(Unit) {
        NavigationDispatcher.pendingRoute.collect { route ->
            navController.navigate(route) {
                popUpTo("home") { inclusive = false }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // 导航过渡动画
    // 原则：enableEdgeToEdge() 已提供系统级预测性返回动画，
    // Compose 只做辅助淡出，避免双动画叠加导致返回变慢。
    // ══════════════════════════════════════════════════════

    // enterTransition：前进导航 → 新页面从右侧滑入
    val enterSlide: (AnimatedContentTransitionScope<*>.() -> EnterTransition) = if (predictiveBack) {
        { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
          fadeIn(tween(200)) }
    } else {
        { EnterTransition.None }
    }

    // exitTransition：前进导航 → 旧页面向右滑出（快速，不加缩放）
    val exitSlide: (AnimatedContentTransitionScope<*>.() -> ExitTransition) = if (predictiveBack) {
        { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(200)) }
    } else {
        { ExitTransition.None }
    }

    // popExitTransition：系统返回 / 预测性返回 → 当前页面退出
    // 只做快速淡出，因为 enableEdgeToEdge() 已处理系统级滑动+缩放动画
    val popExitSlide: (AnimatedContentTransitionScope<*>.() -> ExitTransition) = if (predictiveBack) {
        { fadeOut(tween(200)) }
    } else {
        { ExitTransition.None }
    }

    // popEnterTransition：系统返回 → 上一页从左侧滑入
    val popEnterSlide: (AnimatedContentTransitionScope<*>.() -> EnterTransition) = if (predictiveBack) {
        { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(250)) }
    } else {
        { EnterTransition.None }
    }

    // 底部导航模式：根页面 tab 切换用极短渐隐（crossfade）。
    // 原本 EnterTransition.None 会让 AnimatedContent 在切换瞬间新旧两页同帧叠加残留（首页⇄我的文章时最易
    // 看出“Blancall”标题残影）；极短淡入淡出让旧页明确淡出、新页淡入，杜绝同帧残影且观感依旧利索。
    val noneTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition) = { fadeIn(tween(100)) }
    val noneExitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition) = { fadeOut(tween(100)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
    // 页面容器：专用 FrameLayout（内含 ComposeView 渲染 NavHost）。
    // 作为液态玻璃导航栏的采样源——玻璃悬浮其上、非其子视图，
    // 因此能实时折射页面内容且不会形成折射自身的反馈循环
    // （与阅读模式「玻璃 bind 正文容器」同一架构，两者均已真机验证）。
    // 嵌套 ComposeView 组合没有默认 SaveableStateRegistry，透传外层以保证旋转后导航栈可恢复。
    val outerRegistry = LocalSaveableStateRegistry.current
    var pageHost by remember { mutableStateOf<FrameLayout?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).also { fl ->
                pageHost = fl
                fl.addView(
                    ComposeView(ctx).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        setContent {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides outerRegistry) {
                            NavHost(
        navController = navController,
        // 恒以 home 为导航栈底（四个根 tab 平级、无上下级）：首启引导页是
        // 压入 home 之上的子页（见上方 LaunchedEffect），完成后 popBackStack 归位。
        // 若以 onboarding 为 startDestination，findStartDestination/popUpTo 锚点
        // 会永久漂移成 onboarding，导致「点首页 tab 回不去/出现预测性返回手势」。
        startDestination = "home"
    ) {
        composable(
            "home",
            enterTransition = noneTransition,
            exitTransition = noneExitTransition,
            popExitTransition = noneExitTransition,
            popEnterTransition = noneTransition
        ) {
            HomeScreen(navController)
        }

        composable(
            "import",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            ImportScreen(navController)
        }

        composable(
            "list",
            enterTransition = noneTransition,
            exitTransition = noneExitTransition,
            popExitTransition = noneExitTransition,
            popEnterTransition = noneTransition
        ) {
            ListScreen(navController)
        }

        composable(
            route = "reader/{articleId}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            ReaderScreen(navController, articleId)
        }

        composable(
            route = "practice/{articleId}?mode={mode}&resume={resume}&sectionMode={sectionMode}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType },
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("resume") {
                    type = NavType.StringType
                    defaultValue = "false"
                },
                navArgument("sectionMode") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            val modeStr = backStackEntry.arguments?.getString("mode") ?: ""
            val resumeStr = backStackEntry.arguments?.getString("resume") ?: "false"
            val sectionModeStr = backStackEntry.arguments?.getString("sectionMode") ?: ""
            val initialMode = try { BlancallMode.valueOf(modeStr) } catch (_: IllegalArgumentException) { null }
            val initialSectionMode = try { SectionMode.valueOf(sectionModeStr) } catch (_: IllegalArgumentException) { null }
            PracticeScreen(
                navController, listOf(articleId), initialMode,
                resume = resumeStr == "true",
                initialSectionMode = initialSectionMode
            )
        }

        composable(
            route = "cross/{articleIds}",
            arguments = listOf(
                navArgument("articleIds") { type = NavType.StringType }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val idsStr = backStackEntry.arguments?.getString("articleIds") ?: ""
            val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) {
                // ids 为空时不进入练习（避免 PracticeScreen 永远"准备中"），返回首页
                LaunchedEffect(Unit) {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            } else {
                PracticeScreen(navController, ids)
            }
        }

        composable(
            "overview",
            enterTransition = noneTransition,
            exitTransition = noneExitTransition,
            popExitTransition = noneExitTransition,
            popEnterTransition = noneTransition
        ) {
            OverviewScreen(navController)
        }

        composable(
            "settings",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            SettingsScreen(navController)
        }

        // 首页搜索页（搜索标题 / 正文 / 添加日期）
        composable(
            "search",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            SearchScreen(navController)
        }

        // 首次使用引导页（首启自动进入；设置里可从「帮助」重看）
        composable(
            "onboarding",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            OnboardingScreen(navController)
        }

        composable(
            "help",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            HelpScreen(navController)
        }

        // 内置素材库卡片页（底部「素材库」tab 进入，同级根页面，无返回键）
        // 与 home/list/overview 一样：底部导航模式下 tab 切换无动画，直接出现
        composable(
            "philo",
            enterTransition = noneTransition,
            exitTransition = noneExitTransition,
            popExitTransition = noneExitTransition,
            popEnterTransition = noneTransition
        ) {
            WesternThoughtScreen(navController)
        }

        // 素材库内容页（点卡片进入对应库 WebView）
        composable(
            route = "philo_content/{libraryId}",
            arguments = listOf(
                navArgument("libraryId") { type = NavType.StringType }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getString("libraryId") ?: "western"
            LibraryContentPage(navController, libraryId)
        }

        // 内置 PDF 预览页（点开素材库单篇 PDF 在 app 内预览）
        composable(
            route = "pdf_preview?asset={asset}&title={title}",
            arguments = listOf(
                navArgument("asset") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val asset = backStackEntry.arguments?.getString("asset") ?: ""
            val title = backStackEntry.arguments?.getString("title") ?: ""
            PdfPreviewScreen(navController, asset, title.takeIf { it.isNotBlank() })
        }

        composable(
            route = "statistics/{articleId}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            StatisticsScreen(navController, articleId)
        }

        composable(
            route = "ai?ids={ids}",
            arguments = listOf(
                navArgument("ids") { type = NavType.StringType; defaultValue = "" }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val idsStr = backStackEntry.arguments?.getString("ids") ?: ""
            val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) {
                // ids 为空时不进入对话页（避免空上下文），直接返回
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            } else {
                AiScreen(navController, ids)
            }
        }

        // AI 历史对话列表（?section=analysis 时只显示训练分析）
        composable(
            "ai_history?section={section}",
            arguments = listOf(
                navArgument("section") {
                    type = NavType.StringType
                    defaultValue = "all"
                }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { entry ->
            AiHistoryScreen(
                navController,
                initialSection = entry.arguments?.getString("section") ?: "all"
            )
        }

        // AI 历史对话详情（继续对话）
        composable(
            route = "ai_resume/{sessionId}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            AiScreen(navController, resumeSessionId = sessionId)
        }

        // AI 配置管理（对话配置 + 联网搜索配置，开关式选择）
        composable(
            "ai_config",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            AiConfigScreen(navController)
        }

        // AI 配置新建 / 编辑（type: chat / search；id 为空 = 新建）
        composable(
            route = "ai_profile_edit/{type}?id={id}",
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                // 注意：StringType 非空，可选参数用空串默认值（null 会导致启动闪退）
                navArgument("id") { type = NavType.StringType; defaultValue = "" }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: "chat"
            val id = backStackEntry.arguments?.getString("id")?.ifBlank { null }
            AiProfileEditScreen(navController, type = type, profileId = id)
        }
    } // close NavHost
    } // close CompositionLocalProvider
    } // close setContent（NavHost 渲染进页面容器）
    } // close ComposeView.apply
    ) // close fl.addView
    } // close FrameLayout.also
    } // close factory
    ) // close AndroidView（页面容器，玻璃采样源）

        // ── 底部导航栏（设置中开启后显示，仅在三个根页面） ──
        // 悬浮于页面容器之上（Box z 上层），玻璃折射采样「页面实时内容」。
        // 进入/离开根 tab 页面时使用下滑+淡出动画（避免闪现消失）。
        AnimatedVisibility(
            visible = currentTab >= 0,
            enter = slideInVertically(
                animationSpec = tween(320, easing = LinearOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight }
            ) + fadeIn(
                animationSpec = tween(250, delayMillis = 80)
            ),
            exit = slideOutVertically(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> fullHeight }
            ) + fadeOut(
                animationSpec = tween(220)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomNavBar(
                currentTab = currentTab,
                onSelect = { selectTab(it) },
                showLibraryTab = enabledLibraries.isNotEmpty(),
                host = pageHost
            )
        }
    } // close Box
}

/**
 * 底部导航根页面【平级切换】：四个 tab 都以「home」为唯一锚点（不依赖
 * findStartDestination——start 恒定是 home，物理上也就是栈底）。
 * - popUpTo("home") 先把当前页上方的一切弹回 home（saveState 保留子页状态），
 *   保证栈底唯一、home 永不重复入栈；
 * - 点「首页」时 home 一定是当前栈顶 ⇒ launchSingleTop 命中（同页不重入），
 *   直接把用户带回栈底本身——不会产生可返回的子页，也就没有预测性返回手势。
 *
 * 返回键的"平级＝退出"语义由 [BackHandler] 在根页拦截实现（见 AppNavigation）。
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        // 根 tab 平级切换：不保存/恢复页面状态。
        // saveState/restoreState 会让上一页（如首页品牌栏）在状态恢复后残留子组合，
        // 造成“首页⇄我的文章”切换时标题残影重叠；去掉后切走即彻底销毁页面，无残留。
        popUpTo("home") {
            inclusive = false
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}
