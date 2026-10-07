// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ilyskyo.blancall.notification.NotificationHelper
import com.ilyskyo.blancall.notification.ReminderWorker
import com.ilyskyo.blancall.ui.common.ProvideWindowSizeClass
import com.ilyskyo.blancall.ui.navigation.AppNavigation
import com.ilyskyo.blancall.ui.navigation.NavigationDispatcher
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import com.ilyskyo.blancall.ui.theme.BlancallTheme
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.ui.platform.LocalView


class MainActivity : ComponentActivity() {
    /** 通知权限被拒等场景下向用户展示的提示信息（null 表示无提示） */
    private val errorMessage = mutableStateOf<String?>(null)

    /** Android 13+ 通知权限请求 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 权限已授予，调度提醒（WorkManager 未初始化等异常场景不应崩 App）
            safeScheduleNext()
        } else {
            // 权限被拒：提示用户提醒功能将不可用
            errorMessage.value = "提醒功能需要通知权限，请在系统设置中开启后返回应用"
        }
    }

    /**
     * 首帧是否真的画上屏。用 @Volatile 普通字段而不是 Compose 状态：它只被启动屏的条件轮询读取，
     * 不需要（也不应该）触发任何重组。
     */
    @Volatile
    private var firstFrameDrawn = false

    /** 启动屏最长滞留时间：兜底放行，宁可闪一下也不要把用户永久关在启动屏里 */
    private val keepOnScreenMaxMs = 1_500L

    /** 安全调度提醒：捕获 WorkManager 未初始化等异常，避免主流程崩溃 */
    private fun safeScheduleNext() {
        runCatching { ReminderWorker.scheduleNext(this) }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装启动屏：在 setContent 之前调用，保证第一帧即显示与主页一致的底色，消除白屏
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 首帧没画好之前不退场：否则撤掉启动屏之后是一帧空底色，用户读成"闪了两下"。
        // 别把下面那条时限当保险：这个条件只在启动屏自己重绘时被轮询，实测模拟器首帧要 6.9s，
        // 时限 1.5s 早过了画面却仍停在启动屏 —— 真出现"永远停在启动屏"时该查的是首帧为什么没到，
        // 条件轮询救不了那种卡死。时限只在启动屏本来就在动的时候起作用。
        val releaseDeadline = android.os.SystemClock.elapsedRealtime() + keepOnScreenMaxMs
        splash.setKeepOnScreenCondition {
            !firstFrameDrawn && android.os.SystemClock.elapsedRealtime() < releaseDeadline
        }
        // 退场：整屏淡出后再撤掉启动屏。图标是这层视图的子节点，淡整屏就等于淡图标。
        // 不要碰 info.iconView：API 31+ 上它在启动屏没有配置 windowSplashScreenAnimatedIcon 时
        // 直接抛 NullPointerException（崩在 SplashScreenViewProvider$ViewImpl31.getIconView），
        // 不是返回 null —— 实测两版都因此崩在冷启动，runCatching 之外别无他法，索性不用。
        // 减动效下时长为 0：直接淡完 remove，绝不为播动画而延后撤销。
        splash.setOnExitAnimationListener { info ->
            val duration = if (Motion.reduced) 0L else MotionFade.splashExit.toLong()
            info.view.animate().alpha(0f).setDuration(duration)
                .withEndAction { info.remove() }
                .start()
        }

        // ThemeManager / AppPrefs / ReminderPrefs / NotificationHelper
        // 已移至 BlancallApp.onCreate 统一初始化，保证 Worker 进程也可用

        enableEdgeToEdge()

        // 处理通知点击跳转
        handleNotificationIntent(intent)

        setContent {
            // 窗口尺寸类别：随窗口大小实时变化（旋转、分屏、折叠展开均会更新），
            // 供全树按 Compact / Medium / Expanded 决定布局（网格列数、限宽、导航形态）。
            val windowSizeClass = calculateWindowSizeClass(this)

            ProvideWindowSizeClass(windowSizeClass) {
                BlancallTheme {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val msg by errorMessage

                    // 首帧真的上屏之后再放行启动屏。View.post 排在当前这次遍历之后，
                    // 比 LaunchedEffect / SideEffect 更接近"用户已经看见内容"这个事实。
                    val composeView = LocalView.current
                    LaunchedEffect(Unit) { composeView.post { firstFrameDrawn = true } }

                    // errorMessage 变化时弹出 Snackbar
                    LaunchedEffect(msg) {
                        val current = msg ?: return@LaunchedEffect
                        snackbarHostState.showSnackbar(current)
                        errorMessage.value = null
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        AppNavigation()

                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        // 如果提醒已开启，请求通知权限 / 调度提醒
        if (ReminderPrefs.enabled) {
            requestNotificationPermissionIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能在系统设置中重新开启了通知权限，回到 App 时补调度
        if (ReminderPrefs.enabled && hasNotificationPermission()) {
            safeScheduleNext()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * 解析通知点击 Intent，通过 NavigationDispatcher 桥接到 Compose 导航
     */
    private fun handleNotificationIntent(intent: android.content.Intent?) {
        val navAction = intent?.getStringExtra(NotificationHelper.EXTRA_NAV_ACTION) ?: return
        val articleId = intent.getLongExtra(NotificationHelper.EXTRA_ARTICLE_ID, -1L)

        val route = when (navAction) {
            "practice" -> if (articleId > 0) "practice/$articleId" else "home"
            "statistics" -> if (articleId > 0) "statistics/$articleId" else "overview"
            else -> "home"
        }
        NavigationDispatcher.navigate(route)
    }

    /**
     * Android 13+ 需要动态请求通知权限
     */
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // 权限已有，直接调度
                safeScheduleNext()
            }
        } else {
            // Android 12 及以下无需动态权限
            safeScheduleNext()
        }
    }

    /** 当前是否已持有通知权限（Android 12 及以下默认为 true） */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
