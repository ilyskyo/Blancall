// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presence 宿主的核心承诺：调用方的可见状态先翻 false，浮层仍留在组合里；
 * 容器播完退场并回执后，宿主才真正移除内容。
 *
 * 这条正是 7.3.2 旧实现的缺口：按钮直接清 flag 时 Dialog/Sheet 同帧被拆，
 * 退场动画永远没有机会播放。
 */
@RunWith(AndroidJUnit4::class)
class OverlayPresenceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun 退场回执前内容必须留在场上() {
        var visible by mutableStateOf(true)
        var finished = false
        rule.setContent {
            AnimatedOverlayPresence(
                visible = visible,
                onExitFinished = { finished = true },
            ) {
                FakeExitingOverlay()
            }
        }
        rule.onNodeWithText("overlay").assertExists()

        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.onNodeWithText("overlay").assertExists()
        assertTrue("状态翻转时不应提前宣布退场完成", !finished)

        rule.onNodeWithText("finish-exit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("overlay").assertDoesNotExist()
        assertTrue("容器回执后宿主应把子树移除", finished)
    }
}

@Composable
private fun FakeExitingOverlay() {
    val notifyFinished = LocalOverlayExitFinished.current
    // 真实容器在动画播完时调用回执；这里用按钮模拟“动画播完”的那一刻。
    Text("overlay")
    Text(
        "finish-exit",
        modifier = androidx.compose.ui.Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { notifyFinished?.invoke() },
    )
}
