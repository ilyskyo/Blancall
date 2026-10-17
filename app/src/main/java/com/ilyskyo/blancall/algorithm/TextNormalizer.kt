// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.text.Normalizer

/**
 * 文本归一化：把"排版/书写层面的等价差异"统一，只保留真正的记忆内容差异。
 *
 * ## 全 app 只有这一份「怎么算等价」
 *
 * 之前同一次提交算出两个互不相同的判定：页面判分走 `AnswerChecker` 自己那套
 * （删标点 + 全半角 + lowercase，不折繁简），FSRS 评级走这里（保留标点、区分英文大小写、
 * 折繁简与中文数字）。后果都是用户可感知的：繁体作答页面判错、评级却在表内字上折成简体 →
 * 相似度虚高，用户看到红的、系统记成 GOOD，记忆状态被污染、下次排期推后。
 *
 * 现在两条路共用 [normalize]，唯一区别是**标点去不去**这一个开关：
 * - [forAnswerCompare]：删标点 —— 「算不算对」不看标点；
 * - [forDictationScore]：留标点 —— 评分器按 0.5 权重给标点扣分，所以它得看得见标点。
 * 繁简、中文数字、大小写、空白四类折叠两条路一致地做，不再一条做半套。
 *
 * ## 归一化范围
 * - Unicode NFKC 规范化（兼容字符、全角标点折叠、部分异体字）
 * - 全角 ASCII → 半角
 * - 空白删除（空格/换行/制表符全部移除：中文默写中空白属排版差异，不参与评分）
 * - 常见繁体字 → 简体（小型映射表，仅覆盖常用字，罕见字依赖 NFKC 兜底）
 * - 中文数字 → 阿拉伯数字（宽松等价：如「三」与「3」视为一致）
 * - 英文大小写折叠（AnswerChecker 一直在做，评级通道原先漏了）
 *
 * 已知边界：繁简表只有约 50 字，覆盖不全。这是"半套表"，但现在**两条路一致地半套** ——
 * 同一张表既判对也评级，不会再出现「页面判错、系统记成 GOOD」那种互相打脸。
 * 要真正补齐得引 OpenCC 一类数据表（依赖与包体都要谈），不属于这里能顺手做完的事。
 */
object TextNormalizer {

    /** 中文标点（与产品里「标点不算内容」的口径一致） */
    private val CHINESE_PUNCT = setOf(
        '，', '。', '！', '？', '；', '：', '、',
        '“', '”', '‘', '’', '（', '）', '【', '】', '《', '》',
        '…', '—', '～', '「', '」', '『', '』',
        '·', '﹐', '﹑'
    )

    /** 英文标点与排版符号 */
    private val ENGLISH_PUNCT = setOf(
        ',', '.', '!', '?', ';', ':', '(', ')', '[', ']', '<', '>',
        '"', '\'', '-', '_', '/', '\\', '|', '@', '#', '$', '%', '^', '&', '*',
        '+', '=', '`', '~'
    )

    /** 常见繁→简映射（覆盖高频字；完整映射需引入 OpenCC 类依赖，暂不纳入） */
    private val TRADITIONAL_TO_SIMPLIFIED: Map<Char, Char> = mapOf(
        '們' to '们', '說' to '说', '話' to '话', '這' to '这', '個' to '个',
        '會' to '会', '來' to '来', '對' to '对', '時' to '时', '後' to '后',
        '點' to '点', '問' to '问', '題' to '题', '學' to '学', '習' to '习',
        '體' to '体', '為' to '为', '與' to '与', '發' to '发', '國' to '国',
        '開' to '开', '關' to '关', '書' to '书', '讀' to '读', '寫' to '写',
        '裡' to '里', '邊' to '边', '還' to '还', '聽' to '听', '見' to '见',
        '覺' to '觉', '愛' to '爱', '氣' to '气', '樂' to '乐', '興' to '兴',
        '東' to '东', '車' to '车', '長' to '长', '門' to '门',
        '應' to '应', '當' to '当', '從' to '从', '經' to '经', '過' to '过',
        '萬' to '万', '歲' to '岁', '馬' to '马', '鳥' to '鸟', '魚' to '鱼'
    )

    /** 中文数字 → 阿拉伯数字（宽松等价） */
    private val CHINESE_DIGITS: Map<Char, Char> = mapOf(
        '零' to '0', '〇' to '0', '一' to '1', '二' to '2', '兩' to '2', '两' to '2',
        '三' to '3', '四' to '4', '五' to '5', '六' to '6', '七' to '7',
        '八' to '8', '九' to '9'
    )

    /** 判「算不算对」用这一份：标点不参与内容比对 */
    fun forAnswerCompare(text: String): String = normalize(text, keepPunctuation = false)

    /** 判「扣多少分」用这一份：标点留着，由评分器按权重扣分 */
    fun forDictationScore(text: String): String = normalize(text, keepPunctuation = true)

    /**
     * 归一化单条文本。默认保留标点（评分通道口径），比对答案时传 [keepPunctuation]=false。
     *
     * 保留既有签名与默认值，是为了让 `DictationScorer` 那些调用点不必逐个改；
     * 与旧版的差别只有两处，都是修 bug：新增大小写折叠、可选的标点删除。
     */
    fun normalize(text: String, keepPunctuation: Boolean = true): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        s = toHalfWidth(s)
        s = mapChars(s)
        // 删除全部空白（含换行/制表符）：中文默写中空白是排版差异，不参与评分
        s = if (keepPunctuation) s.filterNot { it.isWhitespace() }
        else stripPunctAndSpace(s)
        return s.lowercase()
    }

    /** 批量归一化（供原文分句一次性缓存，避免提交时重复计算） */
    fun normalizeList(texts: List<String>): List<String> = texts.map { normalize(it) }

    /** 批量归一化（答案比对口径） */
    fun compareList(texts: List<String>): List<String> = texts.map { forAnswerCompare(it) }

    /** 标点/空白判定：展示层要说「你多了标点」时用它，别再抄一份标点表 */
    internal fun isSurfacePunctuation(c: Char): Boolean =
        c.isWhitespace() || c in CHINESE_PUNCT || c in ENGLISH_PUNCT

    /** 全角 ASCII → 半角 */
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

    /**
     * 去标点与空白；小数点夹在数字之间时保留（「3.14」是内容而不是排版）。
     * 这里的数字判断跑在繁简/数字折叠**之后**，所以中文数字被折成阿拉伯数字后同样受保护。
     */
    private fun stripPunctAndSpace(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (c.isWhitespace()) continue
            if (c in CHINESE_PUNCT || c in ENGLISH_PUNCT) {
                if (c == '.' && i > 0 && i < s.length - 1 &&
                    s[i - 1].isDigit() && s[i + 1].isDigit()
                ) {
                    sb.append(c)
                }
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }
}

/** 判断一行是否已有首行缩进（任意空白开头即视为已缩进，避免重复叠加） */
fun isLineIndented(line: String): Boolean =
    line.isNotEmpty() && line[0].isWhitespace()

/**
 * 判断整篇文本是否**已自带首行缩进**。
 *
 * 导入的纯文本里相当一部分（尤其网络下载的中文小说/散文）本来就在每段开头缩进两字。
 * 若此时再补一遍，缩进会越叠越多，所以补缩进前先做一次**整篇**判定：
 * 只要足够比例的首行已经是缩进开头，就认定这篇自带缩进风格，整篇一个字都不补。
 *
 * 统计口径：
 * - 有空行分段时按「每段首行」统计（段内折行不以缩进开头属正常排版，不计入）；
 * - 整篇没有空行（逐句成行的诗词/笔记）时退化为按「行」统计，否则只有一段会误判成未缩进；
 * - **过半（严格多数）**首行已缩进即认定已缩进，避免个别标题行/引文行把整篇判成未缩进。
 *   恰好一半时不认定：那种文档更像「缩进漏了一半」，补齐比整篇放过更接近原文意图。
 */
internal fun isDocumentIndented(text: String): Boolean {
    val paragraphs = text.split("\n\n").filter { it.isNotEmpty() }
    val heads: List<String> = if (paragraphs.size > 1) {
        paragraphs.map { it.substringBefore("\n") }
    } else {
        text.split("\n").filter { it.isNotEmpty() }
    }
    if (heads.isEmpty()) return false
    return heads.count { isLineIndented(it) } * 2 > heads.size
}

/**
 * 幂等地为每段首行补全缩进（两个全角空格）。
 *
 * 先做整篇判定 [isDocumentIndented]：文本若**已自带首行缩进**，原样返回，一个字都不补
 * （「已经缩进了就不自动加缩进」的落点）。否则以空行划分段落，仅给缺少缩进的段落补两格，
 * 已缩进的段落原样保留，因此本函数幂等，重复调用结果不变。
 * 用于导入粘贴/纯文本时写入存储数据，使阅读与背诵显示一致。
 */
fun applyFirstLineIndent(text: String): String {
    // 统一换行符：Windows 文本多为 \r\n，不归一的话按 "\n\n" 分段会把整篇并成一段，
    // 缩进只落在首行（真机导入 .txt 常见），归一后分段与缩进才正确。
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (isDocumentIndented(normalized)) return normalized
    val paragraphs = normalized.split("\n\n")
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
