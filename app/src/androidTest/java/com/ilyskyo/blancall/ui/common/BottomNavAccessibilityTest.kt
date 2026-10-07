// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 底栏的无障碍守卫（设备测试；CI 跑不到，是本地守卫）。
 *
 * 存在的理由：底栏四个 tab 的点击归整条栏的 `pointerInput` 管（它跑在
 * `PointerEventPass.Initial` 上并立刻 consume(down)），所以 tab 自身曾经**没有任何
 * 无障碍动作** —— TalkBack 到不了主导航。修完之后这条性质必须留住。
 *
 * 为什么用 Compose 语义而不是 adb `uiautomator dump`：dump 只是 `AccessibilityNodeInfo`
 * 的投影，它会把「选中项不再报 clickable」显示得像缺陷（实测 3 clickable + 1 selected），
 * 而语义树里四个节点都带着 OnClick 动作。量错工具会把正确的代码改坏。
 *
 * 三条断言各管一件事：
 * 1. 四个 tab 都有点击动作（读屏能激活）；
 * 2. 恰好一个处于选中态；
 * 3. 名称只有**一个来源** —— 来自子树 Text，任何节点都不许自己挂 contentDescription，
 *    否则读屏会念成「首页 首页」（这条真踩过）。
 */
@RunWith(AndroidJUnit4::class)
class BottomNavAccessibilityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val labels = listOf("首页", "我的文章", "数据", "素材库")

    @Test
    fun 四个导航项都可被无障碍激活() {
        renderBar(currentTab = 0)
        val withClick = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("带点击动作的导航项应为 4，实为 ${withClick.size}", withClick.size == 4)

        val selected = rule.onAllNodes(isSelected()).fetchSemanticsNodes()
        assertTrue("选中态节点应恰好 1 个，实为 ${selected.size}", selected.size == 1)
    }

    @Test
    fun 名称只有一个来源() {
        renderBar(currentTab = 2)
        for (label in labels) {
            val fromText = rule.onAllNodesWithText(label).fetchSemanticsNodes()
            assertTrue("读屏取不到名字：$label", fromText.isNotEmpty())

            val fromDesc = rule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
            assertTrue(
                "$label 出现了第二个名称来源（contentDescription 节点 ${fromDesc.size} 个），" +
                    "读屏会念两遍；名字应只来自子树的 Text",
                fromDesc.isEmpty()
            )
        }
    }

    private fun renderBar(currentTab: Int) {
        rule.setContent {
            BottomNavBar(currentTab = currentTab, onSelect = {}, showLibraryTab = true)
        }
        rule.waitForIdle()
    }
}
