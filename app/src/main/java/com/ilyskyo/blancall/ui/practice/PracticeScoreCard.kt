// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.practice

import androidx.compose.animation.core.animateFloatAsState
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.algorithm.AnswerChecker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/** 句子/字词挖空评分卡片：醒目得分行 + 错误分布 + 相似度，右侧查看本篇文章数据 */
@Composable
internal fun BlancallScoreCard(
    checkResults: Map<Int, AnswerChecker.CheckDetail>,
    onViewArticleData: (() -> Unit)? = null,
    weakHints: Int = 0,
    strongHints: Int = 0
) {
    if (checkResults.isEmpty()) return
    // 入场动画：淡入 + 从下方滑入，让评分卡出现更生动
    var played by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val progress by animateFloatAsState(
        targetValue = if (played) 1f else 0f,
        animationSpec = MotionFade.number(MotionFade.scoreReveal, 60),
        label = "scoreCardEnter"
    )
    val total = checkResults.size
    val correct = checkResults.values.count { it.result == AnswerChecker.Result.CORRECT }
    val typo = checkResults.values.count { it.result == AnswerChecker.Result.TYPO }
    val missing = checkResults.values.count { it.result == AnswerChecker.Result.MISSING }
    val extra = checkResults.values.count { it.result == AnswerChecker.Result.EXTRA }
    val wrongOrder = checkResults.values.count { it.result == AnswerChecker.Result.WRONG_ORDER }
    val incorrect = checkResults.values.count { it.result == AnswerChecker.Result.INCORRECT }
    val score = (correct.toFloat() / total * 100).toInt()
    // 正确率彩色笔：≥80 主色 / ≥60 辅色 / 低分红色（还原红色笔效果）
    val scoreColor = when {
        score >= 80 -> MaterialTheme.colorScheme.primary
        score >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：正确率（彩色笔）+ 右侧按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("正确率：$score%（$correct / $total）",
                    style = MaterialTheme.typography.titleMedium,
                    color = scoreColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "正确率 $score 分，正确 $correct 个，共 $total 个"
                    })
                if (onViewArticleData != null) {
                    TextButton(onClick = onViewArticleData) {
                        Text("查看本篇文章数据", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 错误分布
            val parts = mutableListOf<String>()
            if (typo > 0) parts.add("错别字 $typo")
            if (missing > 0) parts.add("少字 $missing")
            if (extra > 0) parts.add("多字 $extra")
            if (wrongOrder > 0) parts.add("顺序错 $wrongOrder")
            if (incorrect > 0) parts.add("不正确 $incorrect")
            if (parts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全部正确",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    AppIcon(
                        kind = AppIconKind.Check,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Text(parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 平均相似度（与反向默写统一口径，展示整体作答接近程度）
            val avgSim = checkResults.values.map { it.similarity }.average().toFloat()
            if (avgSim > 0f && avgSim < 1f) {
                Spacer(Modifier.height(4.dp))
                Text("平均相似度 ${(avgSim * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            // 提示统计（弱提示淡显 / 强提示自动填入）
            if (weakHints > 0 || strongHints > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "记忆提示：弱提示 $weakHints 次 · 强提示 $strongHints 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
