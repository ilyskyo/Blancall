// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.edit
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi

/**
 * 应用内界面语言（per-app locales），**Android 13 以下同样可切换**。
 *
 * 两条通道，对外统一成「一个语言 tag」：
 * - 33+：框架的 `LocaleManager.applicationLocales`。写完系统会按新配置重建 Activity，
 *   用户在系统「设置 → 应用 → 语言」里看到的也是同一份设置；
 * - 26–32：框架没有这项能力，官方回退在 appcompat 里，而本工程不新增依赖，
 *   所以自己存 tag，由 `MainActivity.attachBaseContext` 在每次创建 Activity 时用
 *   `createConfigurationContext` 把 locale 注进配置。
 *   之所以这样包不会丢主题：`Activity` 本身就是 `ContextThemeWrapper`，
 *   清单里的主题由它在 attach 阶段施加，不依赖 base context 的主题（换成裸 Context 就会出问题）。
 *
 * [tag] 与 `res/xml/locales_config.xml` 一一对应（`LocaleParityGuardTest` 会核）。
 * 空串 = 跟随系统。语言名一律用该语言自己的写法（简体中文 / 繁體中文 / English / 日本語 …）：
 * 切换入口若跟着当前语言走，用户换错一次就找不到回来的那一项，所以这些名字不进 strings.xml。
 *
 * 只做这 6 种（法/德/西/葡不做，2026-10-08 定的范围）：每多一种语言就多一份要逐条核对的译文，
 * 与其给一套半中半英的界面，不如不提供。
 */
enum class AppLocale(val tag: String, val nativeName: String) {
    FollowSystem("", "跟随系统"),
    ChineseSimplified("zh-Hans", "简体中文"),
    ChineseTraditional("zh-Hant", "繁體中文"),
    English("en", "English"),
    Japanese("ja", "日本語"),
    Korean("ko", "한국어"),
    Russian("ru", "Русский");

    companion object {
        private const val PREF_FILE = "locale_prefs"
        private const val PREF_KEY = "app_language_tag"

        /** 33+ 用框架通道；更早的版本用本地 tag + attachBaseContext 注入。 */
        val usesFramework: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /** 旧名字保留给调用方：现在两条通道都能切换，所以恒为 true。 */
        val supported: Boolean get() = true

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        fun storedTag(context: Context): String = prefs(context).getString(PREF_KEY, "") ?: ""

        fun current(context: Context): AppLocale =
            if (usesFramework) resolve(frameworkTag(context), storedTag(context), true)
            else resolve(null, storedTag(context), false)

        /**
         * 切换语言。33+ 交给框架重建（调用方**不要**再 recreate，那会打断这次重建）；
         * 33 以下写完偏好后由这里 recreate，让 `attachBaseContext` 带着新 locale 再跑一遍。
         */
        fun apply(context: Context, choice: AppLocale) {
            prefs(context).edit { putString(PREF_KEY, choice.tag) }
            if (usesFramework) {
                applyApi33(context, choice.tag)
            } else {
                context.findActivity()?.recreate()
            }
        }

        /** 给 `attachBaseContext` 用；33+ 原样返回，避免和框架那套打架。 */
        fun wrap(base: Context): Context {
            if (usesFramework) return base
            val tag = storedTag(base)
            if (tag.isBlank()) return base
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tag))
            return base.createConfigurationContext(config)
        }

        /**
         * 纯函数：把「框架里的语言」与「本地存的 tag」合成当前生效项。
         * 单独拎出来是为了能在 JVM 里测两条通道与简繁区分（没有设备也能测到分支）。
         */
        fun resolve(frameworkTag: String?, storedTag: String, usesFramework: Boolean): AppLocale {
            val effective = if (usesFramework) frameworkTag.orEmpty() else storedTag
            if (effective.isBlank()) return FollowSystem
            val first = effective.split(',').first().trim().replace('_', '-')
            return entries.firstOrNull { it.tag.isNotEmpty() && matches(it.tag, first) } ?: FollowSystem
        }

        /**
         * zh-Hans 与 zh-Hant 必须分得开。
         * actual 可能是 zh-Hans-CN 这种三段写法，所以按「子标签集合」比，
         * 而不是取第二个字段 —— 那样拿到的是 Hans-CN，简繁都会掉回跟随系统（测试抓到过）。
         */
        fun matches(want: String, actual: String): Boolean {
            if (actual.equals(want, ignoreCase = true)) return true
            val wantParts = want.split('-')
            val actualParts = actual.split('-').map { it.lowercase() }
            if (!actualParts.getOrElse(0) { "" }.equals(wantParts[0], ignoreCase = true)) return false
            val wantRegion = wantParts.getOrNull(1) ?: return true   // want 只写语言 → 按语言匹配
            if (actualParts.size < 2) return false                   // want 指定了文字/地区而 actual 没给 → 认不出
            val wr = wantRegion.lowercase()
            if (actualParts.contains(wr)) return true
            return (wr == "hans" && actualParts.contains("cn")) ||
                (wr == "hant" && (actualParts.contains("tw") || actualParts.contains("hk") || actualParts.contains("mo")))
        }

        private fun Context.findActivity(): Activity? {
            var ctx: Context = this
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun frameworkTag(context: Context): String? =
            context.getSystemService(LocaleManager::class.java)
                .applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()

        @SuppressLint("ServiceCast")
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun applyApi33(context: Context, tag: String) {
            val list = if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                       else LocaleList.forLanguageTags(tag)
            context.getSystemService(LocaleManager::class.java).applicationLocales = list
        }
    }
}
