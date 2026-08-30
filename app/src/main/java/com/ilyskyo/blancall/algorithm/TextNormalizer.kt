// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.text.Normalizer

/**
 * 默写文本归一化：把"排版/书写层面的等价差异"统一，只保留真正的记忆内容差异。
 *
 * 归一化范围（背诵原文场景的宽松等价）：
 * - Unicode NFKC 规范化（兼容字符、全角标点折叠）
 * - 全角 ASCII → 半角
 * - 空白删除（空格/换行/制表符全部移除：中文默写中空白属排版差异，不参与评分）
 * - 常见繁体字 → 简体（小型映射表，仅覆盖常用字，罕见字依赖 NFKC 兜底）
 * - 中文数字 → 阿拉伯数字（宽松等价：如「三」与「3」视为一致）
 *
 * 注意：标点符号**不在**这里去除（逐字复现要求标点准确，由评分器按 0.5 权重扣分）。
 */
object TextNormalizer {

    /** 常见繁→简映射（覆盖高频字；完整映射需引入 OpenCC 类依赖，暂不纳入） */
    private val TRADITIONAL_TO_SIMPLIFIED: Map<Char, Char> = mapOf(
        '們' to '们', '說' to '说', '話' to '话', '這' to '这', '個' to '个',
        '會' to '会', '來' to '来', '對' to '对', '時' to '时', '後' to '后',
        '點' to '点', '問' to '问', '題' to '题', '學' to '学', '習' to '习',
        '體' to '体', '為' to '为', '與' to '与', '發' to '发', '國' to '国',
        '開' to '开', '關' to '关', '書' to '书', '讀' to '读', '寫' to '写',
        '裡' to '里', '邊' to '边', '還' to '还', '聽' to '听', '見' to '见',
        '覺' to '觉', '愛' to '爱', '氣' to '气', '樂' to '乐', '興' to '兴',
        '東' to '东', '車' to '车', '長' to '长', '門' to '门', '問' to '问',
        '應' to '应', '當' to '当', '從' to '从', '經' to '经', '過' to '过',
        '萬' to '万', '歲' to '岁', '馬' to '马', '鳥' to '鸟', '魚' to '鱼'
    )

    /** 中文数字 → 阿拉伯数字（宽松等价） */
    private val CHINESE_DIGITS: Map<Char, Char> = mapOf(
        '零' to '0', '〇' to '0', '一' to '1', '二' to '2', '兩' to '2', '两' to '2',
        '三' to '3', '四' to '4', '五' to '5', '六' to '6', '七' to '7',
        '八' to '8', '九' to '9'
    )

    /** 归一化单条文本 */
    fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = toHalfWidth(s)
        s = mapChars(s)
        // 删除全部空白（含换行/制表符）：中文默写中空白是排版差异，不参与评分
        return s.filterNot { it.isWhitespace() }
    }

    /** 批量归一化（供原文从句一次性缓存，避免提交时重复计算） */
    fun normalizeList(texts: List<String>): List<String> = texts.map { normalize(it) }

    /** 全角 ASCII → 半角（与 AnswerChecker.toHalfWidth 同口径） */
    private fun toHalfWidth(s: String): String {
        return s.map { c ->
            when {
                c in '！'..'～' -> (c.code - 0xFEE0).toChar()
                c == '　' -> ' '
                else -> c
            }
        }.joinToString("")
    }

    /** 繁→简 + 中文数字→阿拉伯（单遍 StringBuilder） */
    private fun mapChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(TRADITIONAL_TO_SIMPLIFIED[c] ?: CHINESE_DIGITS[c] ?: c)
        }
        return sb.toString()
    }
}

/** 判断一行是否已有首行缩进（任意空白开头即视为已缩进，避免重复叠加） */
fun isLineIndented(line: String): Boolean =
    line.isNotEmpty() && line[0].isWhitespace()

/**
 * 幂等地为每段首行补全缩进（两个全角空格）。
 * 以空行划分段落，仅缩进每个段落的起始行；已缩进的段落原样保留，重复调用结果不变。
 * 用于导入粘贴/纯文本时写入存储数据，使阅读与背诵显示一致。
 */
fun applyFirstLineIndent(text: String): String {
    val paragraphs = text.split("\n\n")
    return paragraphs.joinToString("\n\n") { para ->
        val lines = para.split("\n", limit = 2)
        val first = lines[0]
        if (first.isEmpty() || isLineIndented(first)) {
            para
        } else if (lines.size == 1) {
            "\u3000\u3000$first"
        } else {
            "\u3000\u3000$first\n${lines[1]}"
        }
    }
}
