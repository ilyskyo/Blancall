// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

/**
 * 段落分层切割器：将长文本按自然段落/空行/标题/首行缩进切分为小节
 *
 * 切分规则（优先级从高到低）：
 * 1. 双换行（空行）→ 硬分割边界
 * 2. 首行缩进（全角空格/两个以上半角空格/Tab）→ 段落边界（当无空行时启用）
 * 3. 疑似标题行（短行、无句末标点、可能带编号）→ 作为节标题
 * 4. 单换行 → 软边界（保留在同一节内）
 *
 * 用于 F3 段落分层复习：支持局部集训（仅复习出错段落）和全文连贯模式
 */
object SectionSplitter {

    /** 空行（双换行，中间可有空白）切分正则，提为常量避免重复编译 */
    private val EMPTY_LINE_REGEX = Regex("\n\\s*\n")

    /** 句末标点 + 换行 + 缩进 的段落边界正则，提为常量避免重复编译 */
    private val INDENT_BOUNDARY_REGEX =
        Regex("(?<=[。！？；…」』\u201d])\n(?=(?:\u3000\u3000|\t| {2,})\\S)")

    /** 句末标点 + 单换行（无空行、无缩进时回退使用，支持 \r\n）的段落边界正则 */
    private val SENTENCE_END_NEWLINE_REGEX =
        Regex("(?<=[。！？…」』\u201d])\\r?\\n")

    /** 标题行带编号模式列表，提为常量避免每次调用重新编译 */
    private val NUMBERING_PATTERNS: List<Regex> = listOf(
        Regex("^第[一二三四五六七八九十百千]+[章节回篇]"),  // 第X章
        Regex("^[一二三四五六七八九十]+[、，\\s]"),        // 一、
        Regex("^\\d+[.、．]"),                             // 1. 或 1、
        Regex("^[（(]\\d+[)）]"),                           // (1)
        Regex("^[①②③④⑤⑥⑦⑧⑨⑩]"),                        // ①
        Regex("^第\\s*\\d+\\s*[章节]"),                    // 第 1 章
        Regex("^PART\\s+\\d+", RegexOption.IGNORE_CASE),  // PART 1
        Regex("^Chapter\\s+\\d+", RegexOption.IGNORE_CASE) // Chapter 1
    )

    /** 标题行判定用句末标点集合 */
    private val HEADING_END_CHARS = setOf('。', '！', '？', '.', '!', '?', '…', '~')

    /** 文本段 */
    data class Section(
        val index: Int,             // 段序号（从 0 开始）
        val heading: String?,       // 节标题（可能为 null）
        val text: String,           // 段落全文（含标题）
        val contentOnly: String,    // 纯正文（不含标题行）
        val startChar: Int,         // 在原文本中的起始字符位置
        val endChar: Int,           // 在原文本中的结束字符位置（exclusive）
        val sentenceCount: Int,     // 段内句子数
        val startSentenceIndex: Int = 0  // 该段首句在全文句子索引中的起点（用于错误率归因）
    )

    /** 切分时记录的段落原始偏移区间（已 trim） */
    private data class ParagraphSpan(
        val text: String,   // trim 后的段落文本
        val start: Int,     // 在原文本中的起始字符位置（trimStart 后）
        val end: Int        // 在原文本中的结束字符位置（trimEnd 后，exclusive）
    )

    /**
     * 将文章按段落切分为小节
     * @param text 文章全文
     * @param minSectionChars 最小段落字符数，过短则合并到上一段
     */
    fun split(text: String, minSectionChars: Int = 30): List<Section> {
        if (text.isBlank()) return emptyList()

        // 第一步：按双换行（空行）粗切（带原始偏移）
        val rawParagraphs = splitByEmptyLines(text)

        // 第二步：若空行切分结果过少，尝试按首行缩进分段（中文排版常见：每段开头缩进两字，段间无空行）
        // 此分支仅当全文无空行（视为单段）时进入，直接对原文本切分以保留正确偏移
        // 再回退：按"句末标点 + 单换行"分段（无空行、无缩进的文本，如贴吧/笔记风格）
        val paragraphs = if (rawParagraphs.size <= 1 &&
            (rawParagraphs.firstOrNull()?.text?.length ?: 0) > 100) {
            val indentSplit = splitByIndentation(text)
            if (indentSplit.size > 1) {
                indentSplit
            } else {
                val newlineSplit = splitBySentenceEndNewline(text)
                if (newlineSplit.size > 1) newlineSplit else rawParagraphs
            }
        } else {
            rawParagraphs
        }

        if (paragraphs.isEmpty()) {
            return listOf(Section(
                index = 0, heading = null,
                text = text.trim(), contentOnly = text.trim(),
                startChar = 0, endChar = text.length,
                sentenceCount = SentenceSplitter.split(text.trim()).size,
                startSentenceIndex = 0
            ))
        }

        // 第三步：识别每个段落标题，构建 Section 列表
        val sections = mutableListOf<Section>()
        var sentenceCursor = 0  // 全文句子索引游标（用于 startSentenceIndex 归因）

        for (span in paragraphs) {
            val paragraph = span.text

            // 按换行拆分为行
            val lines = paragraph.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            // 判断首行是否为标题行
            val (heading, contentLines) = if (lines.size >= 2 && isHeadingLine(lines.first())) {
                lines.first() to lines.drop(1)
            } else if (lines.size == 1 && isHeadingLine(lines.first())) {
                // 单行标题（无正文），标题即内容
                null to lines
            } else {
                null to lines
            }

            val contentOnly = contentLines.joinToString("\n")
            val sectionText = if (heading != null) "$heading\n$contentOnly" else contentOnly

            val sentenceCount = SentenceSplitter.split(contentOnly).size.coerceAtLeast(1)

            val section = Section(
                index = sections.size,
                heading = heading,
                text = sectionText,
                contentOnly = contentOnly,
                startChar = span.start,
                endChar = span.end,
                sentenceCount = sentenceCount,
                startSentenceIndex = sentenceCursor
            )
            sentenceCursor += sentenceCount

            sections.add(section)
        }

        // 第四步：合并过短的段落到上一段
        return mergeShortSections(sections, minSectionChars)
    }

    /**
     * 按空行（双换行）切分文本，并记录每段在原文本中的偏移区间。
     */
    private fun splitByEmptyLines(text: String): List<ParagraphSpan> {
        val result = mutableListOf<ParagraphSpan>()
        var lastEnd = 0
        for (match in EMPTY_LINE_REGEX.findAll(text)) {
            addSpanIfNotEmpty(text, lastEnd, match.range.first, result)
            lastEnd = match.range.last + 1
        }
        addSpanIfNotEmpty(text, lastEnd, text.length, result)
        return result
    }

    /** 将 text[from, to) 区间 trim 后加入结果（自动修正偏移） */
    private fun addSpanIfNotEmpty(text: String, from: Int, to: Int, out: MutableList<ParagraphSpan>) {
        if (to <= from) return
        val span = makeSpan(text, from, to)
        if (span.text.isNotEmpty()) out.add(span)
    }

    /** 由原文本区间构造 ParagraphSpan（trim，并修正偏移到首个/末个非空白字符） */
    private fun makeSpan(text: String, from: Int, to: Int): ParagraphSpan {
        val raw = text.substring(from, to)
        val trimmed = raw.trim()
        val lead = raw.length - raw.trimStart().length
        val trail = raw.length - raw.trimEnd().length
        return ParagraphSpan(trimmed, from + lead, to - trail)
    }

    /**
     * 按首行缩进切分段落：
     * 中文排版常见格式——每段开头缩进两个全角空格（  ）或两个以上半角空格，段间无空行。
     * 检测逻辑：若超过 50% 的缩进行前方有句末标点，则确认为段落边界。
     * 返回带原始偏移的段落区间。
     */
    private fun splitByIndentation(text: String): List<ParagraphSpan> {
        // 匹配：前面有句末标点（可选右引号）+ 换行 + 缩进（全角空格/Tab/2+半角空格）+ 非空白字符
        val result = mutableListOf<ParagraphSpan>()
        var lastEnd = 0
        for (match in INDENT_BOUNDARY_REGEX.findAll(text)) {
            // 边界 \n 归属下一段（trim 时去除）
            addSpanIfNotEmpty(text, lastEnd, match.range.first, result)
            lastEnd = match.range.first
        }
        addSpanIfNotEmpty(text, lastEnd, text.length, result)
        if (result.size > 1) return result

        // 回退：不要求前方有标点，仅按缩进行切分（但需多数行有缩进才生效）
        return splitByIndentLines(text)
    }

    /**
     * 按"句末标点 + 单换行"切分段落（无空行、无缩进的文本回退方案）。
     * 仅当空行切分和缩进切分都失败时启用。
     * 换行前是 。！？… 等强句末标点时，视为段落边界。
     */
    private fun splitBySentenceEndNewline(text: String): List<ParagraphSpan> {
        val result = mutableListOf<ParagraphSpan>()
        var lastEnd = 0
        for (match in SENTENCE_END_NEWLINE_REGEX.findAll(text)) {
            // 换行归属下一段（trim 时去除），保留 \r 让 makeSpan 的 trim 清理
            addSpanIfNotEmpty(text, lastEnd, match.range.first, result)
            lastEnd = match.range.first
        }
        addSpanIfNotEmpty(text, lastEnd, text.length, result)
        return result
    }

    /** 回退：按缩进行切分（行级），返回带偏移的段落区间 */
    private fun splitByIndentLines(text: String): List<ParagraphSpan> {
        // 先把每行与其在原文本中的起始偏移配对
        val lineSpans = mutableListOf<Pair<Int, String>>()
        var pos = 0
        for (line in text.split("\n")) {
            lineSpans.add(pos to line)
            pos += line.length + 1  // +1 为 \n（末行无 \n 也不影响后续使用）
        }

        val indentLineCount = lineSpans.count { (_, l) ->
            l.startsWith("\u3000\u3000") || l.startsWith("\t") || l.startsWith("  ")
        }
        if (indentLineCount < 2) {
            // 整体作为一段
            val span = makeSpan(text, 0, text.length)
            return if (span.text.isEmpty()) emptyList() else listOf(span)
        }

        val result = mutableListOf<ParagraphSpan>()
        var segStart = 0
        var segEnd = 0
        var hasContent = false

        for ((lineStart, line) in lineSpans) {
            val isIndented = line.startsWith("\u3000\u3000") || line.startsWith("\t") ||
                (line.length >= 3 && line[0] == ' ' && line[1] == ' ' && line[2] != ' ')
            if (isIndented && hasContent) {
                result.add(makeSpan(text, segStart, segEnd))
                hasContent = false
            }
            if (!hasContent) segStart = lineStart
            segEnd = lineStart + line.length
            if (line.isNotBlank()) hasContent = true
        }
        if (hasContent) result.add(makeSpan(text, segStart, segEnd))

        return result.filter { it.text.isNotEmpty() }
    }

    /**
     * 判断一行是否为标题行：
     * - 不以句末标点结尾（。！？.!?）
     * - 较短（一般 ≤ 30 字）
     * - 或者带编号（一、1. (1) 第X章 等）
     */
    private fun isHeadingLine(line: String): Boolean {
        if (line.length > 40) return false
        val trimmed = line.trim()

        // 带编号模式（使用预编译常量列表）
        if (NUMBERING_PATTERNS.any { it.containsMatchIn(trimmed) }) return true

        // 不以句末标点结尾
        if (trimmed.isNotEmpty() && trimmed.last() in HEADING_END_CHARS) return false

        // 较短且包含中文
        return trimmed.any { it in '\u4e00'..'\u9fff' } && trimmed.length <= 25
    }

    /**
     * 合并过短的段落（字符数不足 minChars 则合并到前一段）
     */
    private fun mergeShortSections(
        sections: List<Section>,
        minChars: Int
    ): List<Section> {
        if (sections.size <= 1) return sections

        val result = mutableListOf<Section>()
        var accumulator: Section? = null

        for (section in sections) {
            if (accumulator == null) {
                accumulator = section
            } else if (section.contentOnly.length < minChars && accumulator.heading == null) {
                // 当前段太短，合并到前一段（保留首段的 startSentenceIndex）
                accumulator = Section(
                    index = accumulator.index,
                    heading = accumulator.heading,
                    text = accumulator.text + "\n" + section.text,
                    contentOnly = accumulator.contentOnly + "\n" + section.contentOnly,
                    startChar = accumulator.startChar,
                    endChar = section.endChar,
                    sentenceCount = accumulator.sentenceCount + section.sentenceCount,
                    startSentenceIndex = accumulator.startSentenceIndex
                )
            } else {
                result.add(accumulator)
                accumulator = section
            }
        }

        if (accumulator != null) result.add(accumulator)

        // 重新分配序号
        return result.mapIndexed { idx, s -> s.copy(index = idx) }
    }

    /**
     * 获取含错误率权重的段落列表（用于分段复习模式）
     * 返回段落并按 errorRate 倒序排列（薄弱优先）
     */
    data class RankedSection(
        val section: Section,
        val errorRate: Float   // 0-1，该段的错误率
    )

    fun rankByErrorRate(
        sections: List<Section>,
        sentenceErrorRates: Map<Int, Float>
    ): List<RankedSection> {
        return sections.map { section ->
            // 用该段首句在全文的索引作为起点（而非段序号），保证与 sentenceErrorRates 的 key 对齐
            val startIdx = section.startSentenceIndex
            val sentences = SentenceSplitter.split(section.contentOnly)
            var totalRate = 0f
            var matchCount = 0
            for (sIdx in sentences.indices) {
                val globalSentIdx = startIdx + sIdx
                val rate = sentenceErrorRates[globalSentIdx]
                if (rate != null) {
                    totalRate += rate
                    matchCount++
                }
            }
            val avgRate = if (matchCount > 0) totalRate / matchCount else 0f
            RankedSection(section, avgRate)
        }.sortedByDescending { it.errorRate }
    }
}
