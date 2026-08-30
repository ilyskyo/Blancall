// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** commonmark 子节点列表（Node API 为 firstChild/next 链表） */
private fun Node.childrenOf(): List<Node> {
    val out = mutableListOf<Node>()
    var c = firstChild
    while (c != null) { out.add(c); c = c.next }
    return out
}

/**
 * Markdown 文本渲染（块级 + 行内样式）：供 AI 对话气泡 / 训练分析等 AI 生成文本使用。
 * 支持：标题、段落、粗体/斜体、行内代码、代码块、有序/无序列表、引用、分割线、换行。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val doc = remember(markdown) { Parser.builder().build().parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        doc.childrenOf().forEach { RenderBlock(it, contentColor) }
    }
}

@Composable
private fun RenderBlock(node: Node, contentColor: Color) {
    when (node) {
        is Heading -> {
            val size = when (node.level) {
                1 -> 21.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 15.sp
            }
            Text(
                text = inlineAnnotated(node, contentColor),
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        is Paragraph -> Text(
            text = inlineAnnotated(node, contentColor),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
        is BulletList -> node.childrenOf().forEach { RenderListItem(it, contentColor, "•") }
        is OrderedList -> {
            var idx = node.startNumber
            node.childrenOf().forEach { item ->
                RenderListItem(item, contentColor, "$idx.")
                idx++
            }
        }
        is BlockQuote -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(contentColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                node.childrenOf().forEach { RenderBlock(it, contentColor.copy(alpha = 0.9f)) }
            }
        }
        is FencedCodeBlock -> CodeblockBlock(node.literal.orEmpty(), contentColor)
        is IndentedCodeBlock -> CodeblockBlock(node.literal.orEmpty(), contentColor)
        is ThematicBreak -> HorizontalDivider(
            thickness = 0.5.dp,
            color = contentColor.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 2.dp)
        )
        is ListItem -> RenderListItem(node, contentColor, "·")
        is MdText -> Text(node.literal.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = contentColor)
        is Document -> node.childrenOf().forEach { RenderBlock(it, contentColor) }
        else -> node.childrenOf().forEach { RenderBlock(it, contentColor) }
    }
}

@Composable
private fun RenderListItem(item: Node, contentColor: Color, bullet: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "$bullet ",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(end = 2.dp)
        )
        Column {
            item.childrenOf().forEach { RenderBlock(it, contentColor) }
        }
    }
}

@Composable
private fun CodeblockBlock(code: String, contentColor: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = contentColor.copy(alpha = 0.08f)
    ) {
        Text(
            text = code.trimEnd('\n'),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** 将块内行内节点（粗体/斜体/行内代码/链接/换行）构建为 AnnotatedString */
private fun inlineAnnotated(parent: Node, baseColor: Color): AnnotatedString = buildAnnotatedString {
    parent.childrenOf().forEach { appendInline(it, baseColor) }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(node: Node, baseColor: Color) {
    when (node) {
        is MdText -> append(node.literal.orEmpty())
        is Emphasis -> node.childrenOf().forEach { appendInline(it, baseColor) }
        is StrongEmphasis -> {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            node.childrenOf().forEach { appendInline(it, baseColor) }
            pop()
        }
        is Code -> {
            pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = baseColor.copy(alpha = 0.12f)
                )
            )
            append(node.literal.orEmpty())
            pop()
        }
        is SoftLineBreak -> appendLine()
        is HardLineBreak -> appendLine()
        is Link -> node.childrenOf().forEach { appendInline(it, baseColor) }
        else -> node.childrenOf().forEach { appendInline(it, baseColor) }
    }
}