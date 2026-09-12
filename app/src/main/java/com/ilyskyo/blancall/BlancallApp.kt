// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.notification.NotificationHelper
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import com.ilyskyo.blancall.ui.theme.ThemeManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * 自定义 Application：统一在进程启动时初始化全局单例偏好。
 *
 * 关键点：ReminderWorker 可能由 WorkManager 在独立进程调度，
 * 若不在每个进程入口初始化 ReminderPrefs，将导致 prefs 未初始化、
 * 提醒读取失败而永久失效。这里在 onCreate 中统一初始化，
 * 保证主进程与 Worker 进程都能拿到可用的 Prefs。
 */
class BlancallApp : Application() {

    // 保存系统默认崩溃处理器（设置自己的之后 get 会返回自己，必须先保存）
    private val originalUncaughtHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()

        // 开发期主线程 IO / 资源泄漏检测：仅 debug 生效（penaltyLog 只打日志，不改变发布行为）
        // 用 ApplicationInfo 标志判断而非 BuildConfig：AGP 8 默认不生成 BuildConfig，无需为此开启 buildConfig 特性
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // 全局崩溃日志：写入 filesDir/crash.log，闪退后可从文件定位真实根因
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash.log")
                // 上限 256KB：崩溃日志无上限会随长期使用持续膨胀、白占用户存储。
                // 超限时只保留最近一半内容（崩溃路径上读这点数据代价可接受）。
                val maxBytes = 256 * 1024L
                if (logFile.length() > maxBytes) {
                    val tail = logFile.readText().takeLast((maxBytes / 2).toInt())
                    logFile.writeText("(较早日志已截断)\n$tail")
                }
                logFile.appendText(
                    "\n=== ${System.currentTimeMillis()} ===\n线程: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable) + "\n"
                )
            } catch (_: Exception) { }
            // 保持系统默认崩溃行为（进程终止）
            originalUncaughtHandler?.uncaughtException(thread, throwable)
        }

        // 主题偏好（StateFlow 初始值需要从磁盘读取）
        ThemeManager.init(this)

        // pdfbox-android 资源加载器：glyphlist 等字体映射资源打包在 assets 中，
        // 必须先注册 AssetManager，否则首次文本提取会因 GlyphList not found 崩溃
        try {
            PDFBoxResourceLoader.init(this)
        } catch (_: Exception) { }
        // 应用偏好（predictiveBack / accentColor / emoji 等）
        AppPrefs.init(this)
        // AI 对话 / 联网搜索 多配置存储（SharedPreferences + JSON，Key 以 SecurePrefs 密文落盘）
        AiConfigStore.init(this)
        // FSRS 智能调度：按「复习频率」设置加载目标留存率（默认 90% 标准记忆）
        FsrsEngine.configure(
            FsrsEngine.DEFAULT_PARAMS,
            FsrsEngine.retentionForTemplate(AppPrefs.reviewTemplateId)
        )
        // 提醒偏好（enabled / hour / minute / frequency / streak）
        ReminderPrefs.init(this)

        // 通知渠道（创建幂等，重复调用安全）
        NotificationHelper.createChannel(this)

        // 后台预热 FSRS 状态单例：getInstance 内部 load() 会同步读盘，
        // 提前在后台线程触发，避免首页首帧在主线程阻塞读 fsrs_state.json。
        kotlin.concurrent.thread {
            try {
                FsrsStateStore.getInstance(filesDir.resolve("fsrs_state.json").absolutePath)
            } catch (_: Exception) { }
        }
    }
}
