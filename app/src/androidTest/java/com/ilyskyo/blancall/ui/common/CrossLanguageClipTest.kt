// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.blancall.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 跨语言「文字放不放得下」的守卫（设备测试）。
 *
 * 存在的理由：简体中文是这里最短的一种 ——「我的文章」4 个字，英文 "My Articles"、
 * 俄文 «Мои статьи» 都要长出一截。底栏每个 tab 是 `weight(1f)` 的**定宽槽**，
 * 标签又写死 `maxLines = 1`，所以换语言之后最先出事的就是这里：不报错、不崩溃，
 * 只是尾巴上多一个省略号，或者干脆少一个字。
 *
 * 量法：槽宽来自真实渲染（每个可点 tab 节点的宽度，与语种无关，因为是权重分的），
 * 需要的宽度用 `TextMeasurer` 按**组件自己那套样式**（labelSmall + Medium/SemiBold）现量。
 * 之所以不直接把界面切成各语言再截图：那样要驱动真机换语言、等重建、再还原，
 * 一条守卫付不起这个代价；而槽宽定死这件事让「量文本 vs 量槽」等价且稳定。
 *
 * 语种表刻意取自 [AppLocale.entries] 而不是硬编码三种：译文目录补一个，这条守卫自动多覆盖一个。
 * 还没有目录的语种会回落到默认（中文）那份，测出来自然通过 —— 等 `values-ja` 落地，
 * 它第一天就被查，不需要谁记得回来改这个测试。
 */
@RunWith(AndroidJUnit4::class)
class CrossLanguageClipTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tabRes = listOf(R.string.nav_home, R.string.nav_articles, R.string.nav_stats, R.string.nav_library)

    @Test
    fun 底栏标签在每个语种都放得进自己的槽() {
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        rule.setContent {
            measurer = rememberTextMeasurer()
            style = MaterialTheme.typography.labelSmall
            BottomNavBar(currentTab = 0, onSelect = {}, showLibraryTab = true)
        }
        rule.waitForIdle()

        val slots = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot.width }
        assertTrue("底栏应有 4 个可点槽位，实为 ${slots.size}", slots.size == tabRes.size)

        val problems = mutableListOf<String>()
        for (locale in AppLocale.entries.filter { it.tag.isNotEmpty() }) {
            val res = localized(locale.tag)
            tabRes.forEachIndexed { i, id ->
                val text = res.getString(id)
                // 第 0 个是选中态：组件给它的是 SemiBold，比其余的 Medium 更宽
                val weight = if (i == 0) FontWeight.SemiBold else FontWeight.Medium
                val needed = measurer.measure(text, style.copy(fontWeight = weight)).size.width
                if (needed > slots[i] + 1f) {
                    problems += "${locale.tag} 第${i + 1}个标签「$text」要 ${needed.toInt()}px，" +
                        "槽只有 ${slots[i].toInt()}px —— 会被裁出省略号"
                }
            }
        }
        assertTrue("跨语言排版有问题：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** 只用来取指定语种的资源表；不涉及「app 会不会跟着切语言」那类断言。 */
    private fun localized(tag: String) = run {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        base.createConfigurationContext(config).resources
    }
}
