// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 [BlancallAlertDialog] 的按钮关闭路径。
 *
 * 旧实现只有点遮罩 / 返回才会等退场；确认/取消按钮把 flag 置 false 时整个 Dialog
 * 同帧出组合，150ms 的缩放淡出从未上屏。这里用测试时钟把那一帧钉住：
 * 点击后 50ms 内容必须还在，播完才允许消失。
 */
@RunWith(AndroidJUnit4::class)
class AlertDialogExitTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun 按钮关闭也要等退场播完() {
        var visible by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AnimatedOverlayPresence(visible = visible) {
                BlancallAlertDialog(
                    onDismissRequest = { visible = false },
                    title = { Text("dialog-title") },
                    text = { Text("dialog-body") },
                    confirmButton = {
                        TextButton(onClick = { visible = false }) { Text("dialog-confirm") }
                    },
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("dialog-title").assertExists()

        rule.onNodeWithText("dialog-confirm").performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithText("dialog-title").assertExists()

        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("dialog-title").assertDoesNotExist()
    }

    @Test
    fun 关掉系统动画时按钮关闭立即到位() {
        val saved = Motion.animatorsEnabled
        Motion.animatorsEnabled = { false }
        try {
            var visible by mutableStateOf(true)
            rule.setContent {
                AnimatedOverlayPresence(visible = visible) {
                    BlancallAlertDialog(
                        onDismissRequest = { visible = false },
                        title = { Text("instant-title") },
                        confirmButton = {
                            TextButton(onClick = { visible = false }) { Text("instant-confirm") }
                        },
                    )
                }
            }
            rule.onNodeWithText("instant-title").assertExists()
            rule.onNodeWithText("instant-confirm").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("instant-title").assertDoesNotExist()
        } finally {
            Motion.animatorsEnabled = saved
        }
    }
}
