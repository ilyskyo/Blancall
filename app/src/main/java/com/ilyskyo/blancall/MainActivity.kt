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
import com.ilyskyo.blancall.ui.navigation.AppNavigation
import com.ilyskyo.blancall.ui.navigation.NavigationDispatcher
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import com.ilyskyo.blancall.ui.theme.BlancallTheme


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

    /** 安全调度提醒：捕获 WorkManager 未初始化等异常，避免主流程崩溃 */
    private fun safeScheduleNext() {
        runCatching { ReminderWorker.scheduleNext(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装启动屏：在 setContent 之前调用，保证第一帧即显示与主页一致的底色，消除白屏
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // ThemeManager / AppPrefs / ReminderPrefs / NotificationHelper
        // 已移至 BlancallApp.onCreate 统一初始化，保证 Worker 进程也可用

        enableEdgeToEdge()

        // 处理通知点击跳转
        handleNotificationIntent(intent)

        setContent {
            BlancallTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val msg by errorMessage

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
