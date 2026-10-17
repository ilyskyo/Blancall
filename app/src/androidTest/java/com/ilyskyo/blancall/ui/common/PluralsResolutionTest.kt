// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.blancall.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 单复数在**资源解析层**的守卫（设备测试）。
 *
 * 为什么必须落到设备上：JVM 那侧的 `LocaleParityGuardTest` 读的是 XML 文本，它能保证
 * 「量档齐、两句不同」，但「quantity=1 到底取到哪一档、占位符填进去还剩什么」是 aapt2 编出来的
 * 资源表在运行时决定的。这一轮我是拿脚本把 `<string>` 整块换成 `<plurals>` 的，
 * 键没进表 / 量档拼错这类毛病只有这条路能发现。
 *
 * 这里用 `createConfigurationContext` 是合法的量法：它量的是**该语种目录确实存在时选不选得中**。
 * 之前那条教训说的是另一件事 —— 拿它去量一个没有译文目录的语种，永远量不到东西。
 */
@RunWith(AndroidJUnit4::class)
class PluralsResolutionTest {

    private fun res(tag: String) = run {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        base.createConfigurationContext(config).resources
    }

    @Test
    fun 英文真的按数量分叉() {
        val en = res("en")
        assertEquals("1 character", en.getQuantityString(R.plurals.char_count, 1, 1))
        assertEquals("552 characters", en.getQuantityString(R.plurals.char_count, 552, 552))
        assertEquals("1 tag", en.getQuantityString(R.plurals.tag_total_count, 1, 1))
        assertEquals("3 tags", en.getQuantityString(R.plurals.tag_total_count, 3, 3))
        assertEquals("1 article", en.getQuantityString(R.plurals.tag_usage_count, 1, 1))
        // one 档整句不带数字，多传的参数被忽略；other 档必须把数字填回去
        assertEquals(
            "Delete tag “它”? That article will lose it (the article is kept). This cannot be undone.",
            en.getQuantityString(R.plurals.delete_tag_confirm_bound, 1, "它", 1)
        )
        assertEquals(
            "Delete tag “它”? 4 articles will lose it (the articles are kept). This cannot be undone.",
            en.getQuantityString(R.plurals.delete_tag_confirm_bound, 4, "它", 4)
        )
    }

    @Test
    fun 中文只有一档但数字照样填() {
        assertEquals("1 字符", res("zh-Hans").getQuantityString(R.plurals.char_count, 1, 1))
        assertEquals("552 字符", res("zh-Hans").getQuantityString(R.plurals.char_count, 552, 552))
        assertEquals("共 3 個標籤", res("zh-Hant").getQuantityString(R.plurals.tag_total_count, 3, 3))
        assertEquals("共 1 個標籤", res("zh-Hant").getQuantityString(R.plurals.tag_total_count, 1, 1))
    }
}
