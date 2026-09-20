// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.handwriting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.data.handwriting.HandwritingScript
import com.ilyskyo.blancall.ui.common.GlassModalBottomSheet

/**
 * 「就地书写」底部面板：对某个具体挖空作答。
 *
 * ## 为什么用底部面板而不是在遮块上直接画
 * 遮块本身宽不过两三个字（约 40–56dp），落笔空间远小于一个汉字的合理书写区；
 * 强行在遮块上写会把字写成「蚂蚁字」，识别率崩掉。Windows 手写板同样是把书写区
 * 独立成一个固定大小的板子，而不是让人在输入框里写。
 *
 * 因此这里的做法是：**点遮块 → 底部弹出书写板 → 写完的字实时回填到该空**。
 * 用户在视觉上仍然是「我对着这个空写字」，但书写区有足够大小。
 *
 * ## 回填语义
 * - 每识别出一个字**追加**到该空当前答案末尾（不替换），连续写 = 连续输入
 * - 顶部实时显示当前答案，可退格、可清空
 * - 关闭面板即完成（答案已通过 [onAnswerChange] 实时写回，无需「确定」）
 *
 * @param blankLabel 面板标题里显示的挖空编号（如「第 3 空」），null 表示不显示编号
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingAnswerSheet(
    answer: String,
    hintChar: Char? = null,
    blankLabel: String? = null,
    onAnswerChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    script: HandwritingScript = HandwritingScript.Chinese,
    /** 下一个期望字符（生僻字守卫）；null = 不启用 */
    expectedNextChar: Char? = null,
    /** 英文默写的「答案先验」（剩余标准答案）；null = 不启用。 */
    expectedWord: String? = null
) {
    GlassModalBottomSheet(onDismissRequest = onDismissRequest) {
        // ⚠️⚠️ 这里**绝不能再加 `verticalScroll`**：`GlassModalBottomSheet` 内部
        // （M3 `ModalBottomSheet`）已经把内容放进一个 `Column(verticalScroll)`，
        // 套第二层可滚动容器时，内层会拿到「无限高」约束并直接抛
        // `IllegalStateException: Vertically scrollable component was measured with an
        // infinity maximum height constraints` —— 真机表现为「点开书写弹层立刻闪退」
        // （栈里是 ScrollNode 套 ScrollNode + sheet 的 DraggableAnchorsNode）。
        // 面板内容过高时由外层那层滚动负责。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // ── 标题行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    blankLabel?.let { "手写作答 · $it" } ?: "手写作答",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                if (hintChar != null) {
                    Text(
                        "提示：$hintChar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismissRequest) { Text("完成") }
            }

            Spacer(Modifier.height(8.dp))

            // ── 当前答案回显 ──
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        answer.ifEmpty { "（还没写）" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (answer.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (answer.isNotEmpty()) {
                        TextButton(onClick = { onAnswerChange(answer.dropLast(1)) }) {
                            Text("⌫")
                        }
                        TextButton(onClick = { onAnswerChange("") }) {
                            Text("清空")
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 书写板 ──
            HandwritingPanel(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                autoCommit = true,
                onCharsPicked = { chars -> onAnswerChange(answer + chars.joinToString("")) },
                onUndoLast = { onAnswerChange(answer.dropLast(1)) },
                script = script,
                expectedNextChar = expectedNextChar,
                // 汉字待填字 ⇒ 禁用拉丁回退（用户要求：答案只含汉字不做英文识别）
                allowLatinFallback = expectedNextChar?.let {
                    HandwritingScript.isLatinInputChar(it)
                } != false,
                expectedWord = expectedWord
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "用笔在板上写一个字，认出的字会自动填进这个空；连续写就是连续填。手指划动不会误写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
