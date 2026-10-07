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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ilyskyo.blancall.ui.common.BottomNavBar
import com.ilyskyo.blancall.ui.common.LocalIsLargeScreen
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.NavBarAutoHide
import com.ilyskyo.blancall.ui.common.NavRail
import com.ilyskyo.blancall.ui.common.NavRailWidth
import com.ilyskyo.blancall.ui.common.HeroEntry
import com.ilyskyo.blancall.ui.common.HeroTransitionHost
import com.ilyskyo.blancall.ui.home.HomeScreen
import com.ilyskyo.blancall.ui.home.SentenceCardScreen
import com.ilyskyo.blancall.ui.import.ImportScreen
import com.ilyskyo.blancall.ui.list.ListScreen
import com.ilyskyo.blancall.ui.ai.AiConfigScreen
import com.ilyskyo.blancall.ui.ai.AiHistoryScreen
import com.ilyskyo.blancall.ui.ai.AiProfileEditScreen
import com.ilyskyo.blancall.ui.ai.AiScreen
import com.ilyskyo.blancall.ui.practice.PracticeScreen
import com.ilyskyo.blancall.ui.cloze.CustomClozeEditScreen
import com.ilyskyo.blancall.ui.cloze.CustomClozeListScreen
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
import com.ilyskyo.blancall.ui.tag.TagManagerScreen
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.viewmodel.BlancallMode
import com.ilyskyo.blancall.ui.viewmodel.SectionMode

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
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
        // 点击**当前 tab**（如在首页点「首页」）：直接忽略，不做任何导航。
        // ⚠️ 原实现放任 navigateToTab 走一遍（依赖 launchSingleTop “幂等”），
        // 真机实测同 tab 点击仍会出现一次「白色遮挡刷新」（页面重建/转场中间帧）；
        // 同 tab 点击本就不应有任何视觉变化，直接短路最稳。
        if (index == currentTab) return
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
    //
    // 方向语义（极易写反，已对 AnimatedContent 源码核对）：
    // SlideDirection 说的是**内容移动的方向**，不是「从哪一侧来」——
    //   Left  = 内容向左移 = 进场页从右边缘来 / 退场页往左边缘去
    //   Right = 内容向右移 = 进场页从左边缘来 / 退场页往右边缘去
    // 于是规范配对是：前进两个都用 Left（两页一起向左走，像一张纸被推走），
    // 返回两个都用 Right（两页一起向右走）。
    //
    // 改造前这里是 exitSlide=Right、popEnter=Left、popExit 只有淡出 ——
    // 三处方向互相矛盾，返回时播放的其实是一套「前进」的动效语言。
    //
    // 视差：跟随方向的那一页走满幅（它要把对面完全盖住/让开），
    // 另一页只走 Motion.slideParallax，读起来像「一层被掀开」而不是两页对撞。
    //
    // 位移一律弹簧、alpha 一律 tween（参数见 Motion / MotionFade）：
    // 进场页用 NoBouncy，因为滑动终点是 x=0，任何过冲都会推过终点、
    // 在 trailing 边缘露出窗口底色，在最整宽的页面滑动上表现为一次白闪。
    //
    // 跟手不用自己写：NavHost 内部用 SeekableTransitionState + PredictiveBackHandler
    // 把下面声明的 pop 转场直接 seek 到手势进度。前提是没有自装的 BackHandler
    // 抢掉这次手势 —— 各页只在「返回有别的含义」时才注册（见各页的 enabled 条件）。
    //
    // 已核 activity-compose 1.10.1 源码：NavHost 跑在嵌套 ComposeView 里也能拿到分发器，
    // 因为 LocalOnBackPressedDispatcherOwner.current 会依次回退
    // 「provides → view tree → LocalContext」，故无需再显式 provides 一次。
    // ══════════════════════════════════════════════════════

    // 前进导航：新页从右边缘推入
    val enterSlide: (AnimatedContentTransitionScope<*>.() -> EnterTransition) = {
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Left,
            Motion.pageEnter()
        )
    }

    // 前进导航：旧页向左让位（只走视差量，末尾补一点淡出免得在左缘留一条残留）
    val exitSlide: (AnimatedContentTransitionScope<*>.() -> ExitTransition) = {
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Left,
            Motion.pageExit(),
            targetOffset = { (it * Motion.slideParallax).toInt() }
        ) + fadeOut(MotionFade.alpha(MotionFade.exit))
    }

    // 返回：当前页向右推出（走满幅，下面的页面才露得干净）
    val popExitSlide: (AnimatedContentTransitionScope<*>.() -> ExitTransition) = {
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Right,
            Motion.pageExit()
        )
    }

    // 返回：上一页从左边缘回位（视差量 + 淡入补足）
    val popEnterSlide: (AnimatedContentTransitionScope<*>.() -> EnterTransition) = {
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Right,
            Motion.pageEnter(),
            initialOffset = { (it * Motion.slideParallax).toInt() }
        ) + fadeIn(MotionFade.alpha(MotionFade.enter))
    }

    // 底部导航模式：根页面 tab 切换用极短渐隐（crossfade）。
    // 原本 EnterTransition.None 会让 AnimatedContent 在切换瞬间新旧两页同帧叠加残留（首页⇄我的文章时最易
    // 看出“Blancall”标题残影）；极短淡入淡出让旧页明确淡出、新页淡入，杜绝同帧残影且观感依旧利索。
    // 这不是动效而是「防残影」，所以刻意保持比任何入场动画都短，且进出等长。
    val noneTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition) =
        { fadeIn(MotionFade.alpha(MotionFade.tabCross)) }
    val noneExitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition) =
        { fadeOut(MotionFade.alpha(MotionFade.tabCross)) }

    // ── 大屏适配：宽度 ≥600dp（平板竖屏 / 折叠屏展开 / 手机与平板横屏）改用侧边导航栏 ──
    // 依据 Material 3 规范：Compact(<600) 底栏 / Medium(600–839) 侧栏 / Expanded(≥840) 侧栏。
    // 横屏用侧栏的实质理由：横屏竖向空间稀缺而横向富余，底栏会再切掉 64dp。
    val isLargeScreen = LocalIsLargeScreen

    // 页面级「临时收起底栏」请求（长按操作栏 / 编辑态贴底显示时不遮挡；
    // 滚动驱动收起见 rememberAutoHideNavBarOnScroll）
    val navBarAutoHidden by NavBarAutoHide.hidden.collectAsState()

    // 页面容器引用必须先声明：NavRail 与底栏都要用它做玻璃折射采样源，
    // 而它在下方 AndroidView 的 factory 里才被赋值（首次组合时仍为 null，
    // 玻璃侧有「等 host 就绪再 bind」的轮询逻辑兜住）。
    val outerRegistry = LocalSaveableStateRegistry.current
    var pageHost by remember { mutableStateOf<FrameLayout?>(null) }

    // 侧栏让位宽度（跨 composition 桥接）：
    // NavHost 运行在独立 ComposeView 组合里读不到外层状态，用 remember 的
    // MutableState 实例作桥——AndroidView.update 每帧写入最新值，NavHost 的
    // padding 读取它（railWidthState.value），旋转/进出全屏时实时更新。
    val railWidthState = remember { mutableStateOf(0.dp) }
    val railReserved = if (isLargeScreen && currentTab >= 0) NavRailWidth else 0.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
    // 页面容器：专用 FrameLayout（内含 ComposeView 渲染 NavHost），**铺满整个窗口**——
    // 侧栏悬浮其上，采样区域（左缘 76dp 条）落在容器内（页面内容由 NavHost 的
    // start padding 让位），玻璃方能折射到真实像素。
    // 作为液态玻璃导航栏的采样源——玻璃悬浮其上、非其子视图，
    // 因此能实时折射页面内容且不会形成折射自身的反馈循环
    // （与阅读模式「玻璃 bind 正文容器」同一架构，两者均已真机验证）。
    // 嵌套 ComposeView 组合没有默认 SaveableStateRegistry，透传外层以保证旋转后导航栈可恢复。
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        update = { railWidthState.value = railReserved },
        factory = { ctx ->
            FrameLayout(ctx).also { fl ->
                pageHost = fl
                fl.addView(
                    ComposeView(ctx).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        setContent {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides outerRegistry) {
                            // 全窗背景层：给「侧栏让位区」（NavHost padding 之外）也铺上页面底色——
                            // 侧栏玻璃按同坐标采样 pageHost，若无此层，让位区采样为透明/空
                            // ⇒ 玻璃「不取色」（真机反馈：「没有向下取色」「我的文章页导航栏不取色」）。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                            // 侧栏让位区的「氛围底」：纯色上液态玻璃无内容可折射 = 不液态
                            // （真机反馈）。在让位条（左 76dp）铺柔和的色彩层次（竖向色阶
                            // + 两个色斑），为玻璃的折射形变提供可见纹理；屏幕上它只在
                            // 侧栏下方露出（窄屏/子页被页面背景完全覆盖，无副作用）。
                            if (isLargeScreen && currentTab >= 0) {
                                val ambientAccent = MaterialTheme.colorScheme.primary
                                Box(
                                    modifier = Modifier
                                        .width(NavRailWidth)
                                        .fillMaxHeight()
                                        .drawBehind {
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    listOf(
                                                        ambientAccent.copy(alpha = 0.12f),
                                                        Color.Transparent,
                                                        ambientAccent.copy(alpha = 0.06f)
                                                    )
                                                )
                                            )
                                            drawCircle(
                                                brush = Brush.radialGradient(
                                                    listOf(
                                                        ambientAccent.copy(alpha = 0.16f),
                                                        Color.Transparent
                                                    ),
                                                    center = Offset(size.width * 0.5f, size.height * 0.22f),
                                                    radius = size.height * 0.35f
                                                ),
                                                center = Offset(size.width * 0.5f, size.height * 0.22f),
                                                radius = size.height * 0.35f
                                            )
                                            drawCircle(
                                                brush = Brush.radialGradient(
                                                    listOf(
                                                        ambientAccent.copy(alpha = 0.12f),
                                                        Color.Transparent
                                                    ),
                                                    center = Offset(size.width * 0.5f, size.height * 0.78f),
                                                    radius = size.height * 0.30f
                                                ),
                                                center = Offset(size.width * 0.5f, size.height * 0.78f),
                                                radius = size.height * 0.30f
                                            )
                                        }
                                )
                            }
                            HeroTransitionHost {
                            NavHost(
        // 大屏时给悬浮侧栏让位（内容整体右移 76dp；窄屏为 0 不加边距）
        modifier = Modifier.padding(start = railWidthState.value),
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
            HeroEntry(this) {
                HomeScreen(navController)
            }
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
            HeroEntry(this) {
                ListScreen(navController)
            }
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
            HeroEntry(this) {
                ReaderScreen(navController, articleId)
            }
        }

        // 自定义挖空配置列表页（新建 / 点击编辑或直接开练 / 长按开始练习·重命名·删除）
        // pick=true：来自模式选择「练一把」流程，点配置直接开始练习
        composable(
            route = "custom_cloze_list/{articleId}?pick={pick}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType },
                navArgument("pick") {
                    type = NavType.StringType
                    defaultValue = "false"
                }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            val pick = backStackEntry.arguments?.getString("pick") == "true"
            CustomClozeListScreen(navController, articleId, pick)
        }

        // 自定义挖空模板编辑页（按文章保存多套配置）；pick=true：保存后直接开始练习
        composable(
            route = "custom_cloze_edit/{articleId}?configId={configId}&pick={pick}",
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType },
                navArgument("configId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("pick") {
                    type = NavType.StringType
                    defaultValue = "false"
                }
            ),
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            val configId = backStackEntry.arguments?.getLong("configId") ?: -1L
            val pick = backStackEntry.arguments?.getString("pick") == "true"
            CustomClozeEditScreen(navController, articleId, configId, pick)
        }

        composable(
            route = "practice/{articleId}?mode={mode}&resume={resume}&sectionMode={sectionMode}&configId={configId}",
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
                },
                navArgument("configId") {
                    type = NavType.LongType
                    defaultValue = -1L
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
                initialSectionMode = initialSectionMode,
                initialConfigId = backStackEntry.arguments?.getLong("configId") ?: -1L
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
            HeroEntry(this) {
                SearchScreen(navController)
            }
        }

        // 文章标签管理页（设置 → 内容管理 → 文章标签）
        composable(
            "tag_manager",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) {
            TagManagerScreen(navController)
        }

        // 句子卡片大卡片界面（首页小卡片点入；华为堆叠形态：划卡 + 三键评级）
        composable(
            "sentence_cards",
            enterTransition = enterSlide,
            exitTransition = exitSlide,
            popExitTransition = popExitSlide,
            popEnterTransition = popEnterSlide
        ) { backStackEntry ->
            HeroEntry(this) {
                SentenceCardScreen(navController)
            }
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

                            }    } // close Box（全窗背景层——侧栏让位区的取样像素来源）
    } // close CompositionLocalProvider
    } // close setContent（NavHost 渲染进页面容器）
    } // close ComposeView.apply
    ) // close fl.addView
    } // close FrameLayout.also
    } // close factory
    ) // close AndroidView（页面容器，玻璃采样源）

    // 侧边导航栏（仅大屏）：**悬浮**在页面容器之上（左缘）。
    // ⚠️ 两条不可动摇的设计约束：
    //  1) 必须悬浮而非并排：液态玻璃库按「屏幕同坐标」采样 bind 的容器——并排时侧栏
    //     位置落在页面容器之外，采样越界 ⇒ 玻璃只剩兜底底色（真机：全屏/分屏「纯白」）。
    //  2) 必须**声明在 AndroidView 之后**（绘制在上层）：侧栏与铺满全窗的页面容器重叠，
    //     若声明在前会被容器盖住（真机回归：横屏「导航栏直接不显示」——全窗背景层
    //     遮住了侧栏）。玻璃采样不受 z 序影响（库对 bound view 做离屏绘制，非屏幕抓取）。
    if (isLargeScreen && currentTab >= 0) {
        NavRail(
            modifier = Modifier.align(Alignment.TopStart),
            currentTab = currentTab,
            onSelect = { selectTab(it) },
            showLibraryTab = enabledLibraries.isNotEmpty(),
            host = pageHost
        )
    }

        // ── 底部导航栏（仅窄屏；大屏由左侧 NavRail 承接） ──
        // 悬浮于页面容器之上（Box z 上层），玻璃折射采样「页面实时内容」。
        // 进入/离开根 tab 页面时使用下滑+淡出动画（避免闪现消失）。
        // 大屏下 visible 恒为 false：AnimatedVisibility 会走完整下滑出场动画，
        // 因此旋转/折叠展开时是「底栏滑走、侧栏出现」的自然交接，而非硬切。
        // ⚠️ 必须与页面容器**并列在 Box 里**，不能和 weight 子项同处 Row：
        // 真机反馈——自由窗口（小窗）下底栏 fillMaxWidth 参与 Row 测量、
        // 抢走全部宽度，页面容器被压成 0 宽 ⇒「只有导航栏、页面完全空白」。
        // navBarAutoHidden：页面请求临时收起（长按多选 / 首页编辑态 / 滚动前进）——
        // 走同一条下滑出场动画，退出状态自动滑回。
        AnimatedVisibility(
            visible = currentTab >= 0 && !isLargeScreen && !navBarAutoHidden,
            // 进出等长等谱：改造前是 320/300 两套时长、入场淡入还多带 80ms 延迟，
            // 收起与弹出的节奏对不上，快速滚动时底栏会「闪一下再走」。
            // 位移交给弹簧（Motion.sheetSlide，NoBouncy：底栏终点贴屏幕边，过冲会露出底缝），
            // 淡入淡出仍是等长 tween。
            enter = slideInVertically(
                animationSpec = Motion.sheetSlide(),
                initialOffsetY = { fullHeight -> fullHeight }
            ) + fadeIn(
                animationSpec = MotionFade.alpha(MotionFade.enter)
            ),
            exit = slideOutVertically(
                animationSpec = Motion.sheetSlide(),
                targetOffsetY = { fullHeight -> fullHeight }
            ) + fadeOut(
                animationSpec = MotionFade.alpha(MotionFade.enter)
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
