// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * 通用文件文本提取器，自动识别文件格式并解析为纯文本。
 * 支持：TXT、PDF、DOCX、EPUB、HTML、RTF、MD
 *
 * 注意：extractText 为 suspend 函数，需在协程中调用（已内置 withContext(Dispatchers.IO)）。
 */
object FileTextExtractor {

    /** 最大支持文件大小 50MB，防止大文件 OOM */
    private const val MAX_FILE_SIZE = 50L * 1024 * 1024

    /** 编码检测采样字节数，避免对大文件全量解码 */
    private const val CHARSET_SAMPLE_SIZE = 4096

    // ── HTML 正则常量（避免每次调用 stripHtml 都重新编译） ──
    private val HTML_SCRIPT_REGEX = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val HTML_STYLE_REGEX = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val HTML_BLOCK_REGEX = Regex("</?(div|p|h[1-6]|li|tr|br|hr)[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val HTML_NUM_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HTML_HEX_ENTITY_REGEX = Regex("&#x([0-9a-fA-F]+);")
    private val HTML_WS_REGEX = Regex("[ \\t]+")
    private val HTML_NL_REGEX = Regex("\\n{3,}")

    // ── RTF 正则常量 ──
    /** \uN 转义，N 可为负数（补码），后跟至多一个空格作为分隔符 */
    private val RTF_U_REGEX = Regex("\\\\u(-?\\d+) ?")
    private val RTF_PAR_REGEX = Regex("\\\\par\\b")
    private val RTF_CTRL_WORD_REGEX = Regex("\\\\[a-zA-Z]+\\d* ?")
    private val RTF_CTRL_HEX_REGEX = Regex("\\\\'([0-9a-fA-F]{2})")
    private val RTF_CTRL_SYM_REGEX = Regex("\\\\[^a-zA-Z]")
    private val RTF_NL_REGEX = Regex("\\n{3,}")
    private val RTF_WS_REGEX = Regex("[ \\t]+")

    @Volatile
    private var pdfBoxInitialized = false
    private val pdfBoxInitLock = Any()

    /**
     * 提取文件文本。suspend 函数，内部切换到 Dispatchers.IO 执行。
     * 超过 [MAX_FILE_SIZE] 的文件将抛出异常。
     */
    suspend fun extractText(context: Context, uri: Uri): String = extractTextWithInfo(context, uri).text

    /** 提取结果：text=纯文本，hasImages=源文档是否包含图片/嵌入对象（这些内容不会随文本导入） */
    data class ExtractResult(val text: String, val hasImages: Boolean)

    /**
     * 提取文件文本 + 图片检测结果。suspend 函数，内部切换到 Dispatchers.IO 执行。
     * 超过 [MAX_FILE_SIZE] 的文件将抛出异常。
     */
    suspend fun extractTextWithInfo(context: Context, uri: Uri): ExtractResult = withContext(Dispatchers.IO) {
        // 文件大小检查，防止大文件 OOM
        checkFileSize(context, uri)

        val fileName = getFileName(context, uri) ?: ""
        val mimeType = context.contentResolver.getType(uri)

        when {
            // PDF
            fileName.endsWith(".pdf", ignoreCase = true) || mimeType == "application/pdf" ->
                ExtractResult(extractPdfText(context, uri), detectPdfImages(context, uri))

            // DOCX (Word 2007+)
            fileName.endsWith(".docx", ignoreCase = true) ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                ExtractResult(extractDocxText(context, uri), detectDocxImages(context, uri))

            // DOC（Word 97-2003 二进制格式）
            (fileName.endsWith(".doc", ignoreCase = true) && !fileName.endsWith(".docx", ignoreCase = true)) ||
                mimeType == "application/msword" ->
                extractDocResult(context, uri)

            // EPUB
            fileName.endsWith(".epub", ignoreCase = true) || mimeType == "application/epub+zip" ->
                ExtractResult(extractEpubText(context, uri), detectEpubImages(context, uri))

            // HTML
            fileName.endsWith(".html", ignoreCase = true) ||
                fileName.endsWith(".htm", ignoreCase = true) ||
                mimeType == "text/html" ->
                ExtractResult(extractHtmlText(context, uri), detectHtmlImages(context, uri))

            // RTF
            fileName.endsWith(".rtf", ignoreCase = true) ||
                mimeType == "application/rtf" || mimeType == "text/rtf" ->
                ExtractResult(extractRtfText(context, uri), detectRtfImages(context, uri))

            // 纯文本（TXT / MD / 未知格式等）
            else -> ExtractResult(extractPlainText(context, uri), false)
        }
    }

    /** 获取文件名 */
    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    /** 查询文件大小，无法获取时返回 -1 */
    private fun getFileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) return cursor.getLong(idx)
            }
        }
        return -1
    }

    /** 校验文件大小，超过上限抛出异常 */
    private fun checkFileSize(context: Context, uri: Uri) {
        val size = getFileSize(context, uri)
        if (size > MAX_FILE_SIZE) {
            throw Exception("文件过大（${size / 1024 / 1024}MB），最大支持 ${MAX_FILE_SIZE / 1024 / 1024}MB")
        }
    }

    // ─────────────────── PDF ───────────────────

    private fun extractPdfText(context: Context, uri: Uri): String {
        ensurePdfBoxInitialized(context)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val document = PDDocument.load(inputStream)
            return try {
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(document).trim()
            } finally {
                document.close()
            }
        } ?: throw Exception("无法打开 PDF 文件")
    }

    /** 线程安全的 PDFBox 初始化，双重检查锁防止并发重复 init */
    private fun ensurePdfBoxInitialized(context: Context) {
        if (!pdfBoxInitialized) {
            synchronized(pdfBoxInitLock) {
                if (!pdfBoxInitialized) {
                    PDFBoxResourceLoader.init(context)
                    pdfBoxInitialized = true
                }
            }
        }
    }

    // ─────────────────── DOCX ───────────────────

    private fun extractDocxText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开 DOCX 文件")

        val xmlString = extractDocxXml(bytes)
        return parseDocxXml(xmlString)
    }

    private fun extractDocxXml(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    return String(zip.readBytes(), Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        throw Exception("无效的 DOCX 文件：未找到 document.xml")
    }

    private fun parseDocxXml(xmlString: String): String {
        val sb = StringBuilder()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlString))

        val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        var eventType = parser.eventType
        var inText = false
        var firstParagraph = true

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.namespace == WORD_NS) {
                        when (parser.name) {
                            "p" -> {
                                if (!firstParagraph) sb.append("\n")
                                firstParagraph = false
                            }
                            "br" -> sb.append("\n")
                            "t" -> inText = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) sb.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.namespace == WORD_NS && parser.name == "t") {
                        inText = false
                    }
                }
            }
            eventType = parser.next()
        }
        return sb.toString().trim()
    }

    // ─────────────────── EPUB ───────────────────

    /**
     * EPUB 文本提取。
     * 按 spine 顺序流式处理每个 HTML 文件，即时 strip 后追加到 StringBuilder，
     * 避免将全部 HTML 内容缓存到内存导致 OOM。
     */
    private fun extractEpubText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开 EPUB 文件")

        val sb = StringBuilder()
        var foundAny = false

        // 第一遍：跳过导航/目录/封面，只提取正文 HTML
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                    if (!name.contains("nav") && !name.contains("toc") && !name.contains("cover")) {
                        val entryBytes = zip.readBytes()
                        val html = String(entryBytes, detectCharset(entryBytes))
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(stripHtml(html).trim())
                        foundAny = true
                    }
                }
                entry = zip.nextEntry
            }
        }

        // 若第一遍无内容，回退扫描所有 HTML（含导航/目录），一次遍历完成
        if (!foundAny) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                        val entryBytes = zip.readBytes()
                        val html = String(entryBytes, detectCharset(entryBytes))
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(stripHtml(html).trim())
                    }
                    entry = zip.nextEntry
                }
            }
        }

        return sb.toString().trim()
    }

    // ─────────────────── HTML ───────────────────

    private fun extractHtmlText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开 HTML 文件")
        val html = String(bytes, detectCharset(bytes))
        return stripHtml(html).trim()
    }

    private fun stripHtml(html: String): String {
        var text = html
        // 移除 script / style 块
        text = text.replace(HTML_SCRIPT_REGEX, "")
        text = text.replace(HTML_STYLE_REGEX, "")
        text = text.replace(HTML_COMMENT_REGEX, "")
        // 块级元素 → 换行
        text = text.replace(HTML_BLOCK_REGEX, "\n")
        // 移除所有 HTML 标签
        text = text.replace(HTML_TAG_REGEX, "")
        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&apos;", "'")
        text = text.replace("&#39;", "'")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&mdash;", "—")
        text = text.replace("&ndash;", "–")
        text = text.replace("&lsquo;", "'")
        text = text.replace("&rsquo;", "'")
        text = text.replace("&ldquo;", "\"")
        text = text.replace("&rdquo;", "\"")
        // 数字实体 &#xxxx;
        text = text.replace(HTML_NUM_ENTITY_REGEX) { mr ->
            mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
        }
        // 十六进制实体 &#xXXXX;
        text = text.replace(HTML_HEX_ENTITY_REGEX) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
        }
        // 折叠空白
        text = text.replace(HTML_WS_REGEX, " ")
        text = text.replace(HTML_NL_REGEX, "\n\n")
        return text.trim()
    }

    // ─────────────────── RTF ───────────────────

    private fun extractRtfText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开 RTF 文件")
        val rtf = String(bytes, detectCharset(bytes))
        return stripRtf(rtf).trim()
    }

    private fun stripRtf(rtf: String): String {
        var text = rtf
        // 先解码 RTF 转义（\uN 等 Unicode 转义）
        text = text.replace(RTF_U_REGEX) { mr ->
            val n = mr.groupValues[1].toIntOrNull()
            if (n != null) {
                // RTF \uN 可能是负数（补码表示），需转换为无符号 char
                val code = if (n < 0) n + 65536 else n
                code.toChar().toString()
            } else {
                mr.value
            }
        }
        // 处理段落标记 \par（必须在移除控制字之前）
        text = text.replace(RTF_PAR_REGEX, "\n")
        // 移除控制字 \xxx（后跟空格或数字）
        text = text.replace(RTF_CTRL_WORD_REGEX, "")
        // 移除控制符号 \'xx（十六进制字符）
        text = text.replace(RTF_CTRL_HEX_REGEX) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
        }
        // 移除剩余的反斜杠符号
        text = text.replace(RTF_CTRL_SYM_REGEX, "")
        // 移除花括号
        text = text.replace("{", "").replace("}", "")
        // 折叠空白
        text = text.replace(RTF_NL_REGEX, "\n\n")
        text = text.replace(RTF_WS_REGEX, " ")
        return text.trim()
    }

    // ─────────────────── 图片检测 ───────────────────

    /** PDF：任一页资源含图片 XObject 即视为含图片 */
    private fun detectPdfImages(context: Context, uri: Uri): Boolean {
        ensurePdfBoxInitialized(context)
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                PDDocument.load(ins).use { document ->
                    for (page in document.pages) {
                        val resources = page.resources ?: continue
                        for (name in resources.xObjectNames) {
                            val x = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                            if (x is PDImageXObject) return true
                        }
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** DOCX：zip 内存在 word/media/ 条目即视为含图片 */
    private fun detectDocxImages(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("word/media/", ignoreCase = true)) return true
                        entry = zip.nextEntry
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** DOC：图片/嵌入对象存于 "Data" 流，非空即视为含图片 */
    private fun docHasImages(streams: Map<String, ByteArray>): Boolean {
        return (streams["Data"]?.size ?: 0) > 0
    }

    /** EPUB：正文 HTML 含 <img> 标签即视为含图片 */
    private fun detectEpubImages(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                            if (String(zip.readBytes(), Charsets.ISO_8859_1).contains("<img", ignoreCase = true)) {
                                return true
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun detectHtmlImages(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { bytes ->
                String(bytes, detectCharset(bytes)).contains("<img", ignoreCase = true)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun detectRtfImages(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { bytes ->
                String(bytes, detectCharset(bytes)).contains("\\pict", ignoreCase = true)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    // ─────────────────── DOC（Word 97-2003 二进制格式） ───────────────────

    /** 提取 .doc 文本 + 图片检测结果。伪装成 .doc 的纯文本/HTML/RTF 会按对应格式兜底解析 */
    private fun extractDocResult(context: Context, uri: Uri): ExtractResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开 DOC 文件")

        val streams = try {
            parseOleStreams(bytes)
        } catch (_: Exception) {
            null
        }

        // 无 WordDocument 流：多半不是真正的 .doc，按文本/HTML/RTF 兜底
        if (streams == null || !streams.containsKey("WordDocument")) {
            val asString = String(bytes, detectCharset(bytes)).trimStart('\uFEFF')
            val text = when {
                asString.startsWith("{\\rtf") -> stripRtf(asString)
                asString.contains("<html", ignoreCase = true) || asString.contains("<body", ignoreCase = true) ->
                    stripHtml(asString)
                else -> asString
            }.trim()
            return ExtractResult(text, false)
        }

        return ExtractResult(extractDocText(streams), docHasImages(streams))
    }

    private fun extractDocText(streams: Map<String, ByteArray>): String {
        val wd = streams["WordDocument"] ?: throw Exception("无效的 DOC 文件：未找到 WordDocument 流")
        val flags = leU16(wd, 0x000A)
        if (flags and 0x0100 != 0) throw Exception("文档已加密，无法读取")
        val nFib = leU16(wd, 0x0002)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"

        // 优先走 piece table（Word 97+，支持快速保存的多分片），失败回退 fcMin/fcMac 连续文本
        val pieces = if (nFib >= 0x00C1) parseDocPieceTable(wd, streams[tableName]) else null
        val text = if (pieces != null) {
            buildString {
                for ((raw, isUtf16) in pieces) {
                    append(if (isUtf16) String(raw, Charsets.UTF_16LE) else decodeDocBytes(raw))
                }
            }
        } else {
            val fcMin = leU32(wd, 0x18)
            val fcMac = leU32(wd, 0x1C)
            val byteCount = (fcMac - fcMin).toInt()
            if (byteCount <= 0 || fcMin >= wd.size) return ""
            val raw = wd.copyOfRange(fcMin.toInt(), minOf(wd.size, (fcMin + byteCount).toInt()))
            if (flags and 0x1000 != 0) String(raw, Charsets.UTF_16LE) else decodeDocBytes(raw)
        }
        return cleanupDocText(text)
    }

    /** 压缩（8-bit）分片解码：非 ASCII 字节按 GB18030 兜底，纯 ASCII 用 ISO-8859-1 */
    private fun decodeDocBytes(raw: ByteArray): String {
        return if (raw.any { it < 0 }) {
            runCatching { String(raw, charset("GB18030")) }.getOrDefault(String(raw, Charsets.ISO_8859_1))
        } else {
            String(raw, Charsets.ISO_8859_1)
        }
    }

    /** 解析 FIB → CLX → PlcPcd 分片表；结构不合法返回 null（调用方走回退路径） */
    private fun parseDocPieceTable(wd: ByteArray, table: ByteArray?): List<Pair<ByteArray, Boolean>>? {
        if (table == null) return null
        val fcClx = leU32(wd, 0x01A2)
        val lcbClx = leU32(wd, 0x01A6)
        if (fcClx <= 0L || lcbClx <= 0L || fcClx + lcbClx > table.size) return null
        val clx = table.copyOfRange(fcClx.toInt(), (fcClx + lcbClx).toInt())

        var plc: ByteArray? = null
        var pos = 0
        while (pos < clx.size) {
            when (clx[pos].toInt() and 0xFF) {
                1 -> { // Prc：跳过修改记录
                    if (pos + 3 > clx.size) return null
                    pos += 3 + leU16(clx, pos + 1)
                }
                2 -> { // Pcdt：PlcPcd 所在
                    if (pos + 5 > clx.size) return null
                    val lcb = leU32(clx, pos + 1).toInt()
                    if (lcb <= 0 || pos + 5 + lcb > clx.size) return null
                    plc = clx.copyOfRange(pos + 5, pos + 5 + lcb)
                }
                else -> return null
            }
            if (plc != null) break
        }
        val p = plc ?: return null
        if (p.size < 16 || (p.size - 4) % 12 != 0) return null
        val n = (p.size - 4) / 12
        val result = mutableListOf<Pair<ByteArray, Boolean>>()
        val base = 4 * (n + 1)
        for (i in 0 until n) {
            val cpStart = leU32(p, 4 * i)
            val cpEnd = leU32(p, 4 * (i + 1))
            val cpCount = (cpEnd - cpStart).toInt()
            if (cpCount <= 0) continue
            val fcRaw = leU32(p, base + 8 * i + 2)
            val compressed = fcRaw and 0x40000000L != 0L
            val fc = fcRaw and 0x3FFFFFFFL
            if (compressed) {
                val start = (fc shr 1).toInt()
                if (start >= wd.size) continue
                result.add(wd.copyOfRange(start, minOf(wd.size, start + cpCount)) to false)
            } else {
                val start = fc.toInt()
                if (start >= wd.size) continue
                result.add(wd.copyOfRange(start, minOf(wd.size, start + 2 * cpCount)) to true)
            }
        }
        return if (result.isEmpty()) null else result
    }

    /** Word 控制字符 → 换行/删除，并折叠多余空行 */
    private fun cleanupDocText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\r' || ch == '\u0007' || ch == '\u000B' || ch == '\u000C' -> sb.append('\n')
                ch == '\u001E' -> sb.append('-')
                ch == '\u0013' || ch == '\u0014' || ch == '\u0015' -> { /* 域字符，丢弃 */ }
                ch.code < 0x20 && ch != '\t' && ch != '\n' -> { /* 其他控制字符，丢弃 */ }
                else -> sb.append(ch)
            }
        }
        var out = sb.toString()
        while (out.contains("\n\n\n")) out = out.replace("\n\n\n", "\n\n")
        return out.trim()
    }

    // ─────────────────── OLE2 / CFB 复合文档解析（.doc 容器） ───────────────────

    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    private const val OLE_FREE_SECTOR = -0x1
    private const val OLE_END_OF_CHAIN = -0x2

    private fun leU16(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun leU32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0L
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    /** 解析 OLE2/CFB 复合文档，返回 流名 → 内容。结构非法时抛异常 */
    private fun parseOleStreams(data: ByteArray): Map<String, ByteArray> {
        if (data.size < 512 || !data.copyOfRange(0, 8).contentEquals(OLE_MAGIC)) {
            throw Exception("无效的 DOC 文件")
        }
        val sectorShift = leU16(data, 0x1E)
        val miniShift = leU16(data, 0x20)
        if (sectorShift < 7 || sectorShift > 12 || miniShift < 6 || miniShift > 9) {
            throw Exception("无效的 DOC 文件")
        }
        val ssz = 1 shl sectorShift
        val msz = 1 shl miniShift
        val numFat = leU32(data, 0x2C).toInt()
        val firstDir = leU32(data, 0x30).toInt()
        val miniCutoff = leU32(data, 0x38).toInt()
        val firstMiniFat = leU32(data, 0x3C).toInt()
        val numMiniFat = leU32(data, 0x40).toInt()
        val firstDifat = leU32(data, 0x44).toInt()
        val numDifat = leU32(data, 0x48).toInt()

        fun readSector(s: Int): ByteArray? {
            val off = (s + 1).toLong() * ssz
            if (s < 0 || off + ssz > data.size) return null
            return data.copyOfRange(off.toInt(), (off + ssz).toInt())
        }

        // DIFAT：头部 109 项 + DIFAT 扇区链
        val fatSectors = mutableListOf<Int>()
        for (i in 0 until 109) {
            val v = leU32(data, 0x4C + 4 * i).toInt()
            if (v != OLE_FREE_SECTOR && v != OLE_END_OF_CHAIN) fatSectors.add(v)
        }
        var difatCur = firstDifat
        var difatGuard = 0
        while (difatCur >= 0 && numDifat > 0 && difatGuard < 65536) {
            difatGuard++
            val sec = readSector(difatCur) ?: break
            for (i in 0 until ssz / 4 - 1) {
                val v = leU32(sec, 4 * i).toInt()
                if (v != OLE_FREE_SECTOR && v != OLE_END_OF_CHAIN) fatSectors.add(v)
            }
            difatCur = leU32(sec, ssz - 4).toInt()
            if (difatCur == OLE_FREE_SECTOR || difatCur == OLE_END_OF_CHAIN) break
        }

        // FAT
        val fat = mutableListOf<Int>()
        val fatList = if (numFat in 1..fatSectors.size) fatSectors.take(numFat) else fatSectors
        for (fs in fatList) {
            val sec = readSector(fs) ?: continue
            for (i in 0 until ssz / 4) fat.add(leU32(sec, 4 * i).toInt())
        }
        fun chain(start: Int, table: List<Int>): List<Int> {
            val out = mutableListOf<Int>()
            val visited = mutableSetOf<Int>()
            var c = start
            while (c >= 0 && c !in visited && visited.size <= table.size + 1) {
                visited.add(c)
                out.add(c)
                c = if (c < table.size) table[c] else OLE_END_OF_CHAIN
            }
            return out
        }
        fun readChain(start: Int, size: Long, table: List<Int>, sectorSize: Int, container: ByteArray? = null): ByteArray {
            val buf = java.io.ByteArrayOutputStream()
            for (s in chain(start, table)) {
                if (container != null) {
                    val from = s * sectorSize
                    if (from >= container.size) break
                    buf.write(container, from, minOf(sectorSize, container.size - from))
                } else {
                    val sec = readSector(s) ?: break
                    buf.write(sec)
                }
            }
            val all = buf.toByteArray()
            return if (size >= all.size) all else all.copyOf(size.toInt())
        }

        // 目录项
        val dirData = readChain(firstDir, Long.MAX_VALUE, fat, ssz)
        var rootStart = -1
        data class DirEntry(val name: String, val type: Int, val start: Int, val size: Long)
        val dirEntries = mutableListOf<DirEntry>()
        var off = 0
        while (off + 128 <= dirData.size) {
            val nameLen = leU16(dirData, off + 0x40)
            val type = dirData[off + 0x42].toInt() and 0xFF
            val name = if (nameLen in 2..64) String(dirData, off, nameLen - 2, Charsets.UTF_16LE) else ""
            val start = leU32(dirData, off + 0x74).toInt()
            val size = leU32(dirData, off + 0x78)
            if (type == 5) rootStart = start
            dirEntries.add(DirEntry(name, type, start, size))
            off += 128
        }

        // 小流（mini stream）+ MiniFAT：小于 miniCutoff 的流存在其中
        val miniContainer = if (rootStart >= 0) readChain(rootStart, Long.MAX_VALUE, fat, ssz) else ByteArray(0)
        val miniFat = mutableListOf<Int>()
        if (numMiniFat > 0 && firstMiniFat >= 0) {
            val mfb = readChain(firstMiniFat, Long.MAX_VALUE, fat, ssz)
            for (i in 0 until mfb.size / 4) miniFat.add(leU32(mfb, 4 * i).toInt())
        }

        val streams = mutableMapOf<String, ByteArray>()
        for (e in dirEntries) {
            if (e.type != 2 || e.name.isEmpty() || streams.containsKey(e.name)) continue
            streams[e.name] = if (e.size < miniCutoff) {
                readChain(e.start, e.size, miniFat, msz, miniContainer)
            } else {
                readChain(e.start, e.size, fat, ssz)
            }
        }
        return streams
    }

    // ─────────────────── 纯文本 + 编码检测 ───────────────────

    private fun extractPlainText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("无法打开文件")
        // 移除 BOM 并 trim，与其他格式保持一致，避免 BOM 占用字符配额导致截断后少一个可见字符
        return String(bytes, detectCharset(bytes)).trimStart('\uFEFF').trim()
    }

    /**
     * 自动检测文本编码。
     * 优先级：BOM > UTF-8 验证 > GB18030 > GBK > Big5 > UTF-8 兜底
     *
     * 为避免对大文件全量解码（O(n)），仅对前 [CHARSET_SAMPLE_SIZE] 字节采样检测。
     */
    fun detectCharset(bytes: ByteArray): Charset {
        // ── 1. BOM 检测（仅看前几个字节，O(1)） ──
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> return Charsets.UTF_16LE
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> return Charsets.UTF_16BE
            }
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        ) {
            return charsetOrNull("UTF-32") ?: Charsets.UTF_8
        }

        // ── 采样：仅对前 N 字节做编码探测，避免大文件全量解码 ──
        val sampleEnd = minOf(bytes.size, CHARSET_SAMPLE_SIZE)
        val sample = if (bytes.size <= CHARSET_SAMPLE_SIZE) bytes else bytes.copyOfRange(0, sampleEnd)

        // ── 2. 试 UTF-8 ──
        val utf8 = String(sample, Charsets.UTF_8)
        val utf8Bad = utf8.count { it == '\uFFFD' }
        if (utf8Bad == 0) return Charsets.UTF_8
        if (utf8Bad.toFloat() / sample.size < 0.01f) return Charsets.UTF_8

        // ── 3. 试 GB18030（覆盖简繁中文） ──
        val gb18030 = tryDecode(sample, "GB18030")
        if (gb18030 != null) {
            val gbBad = gb18030.count { it == '\uFFFD' }
            if (gbBad < utf8Bad) return Charset.forName("GB18030")
            // 比较汉字覆盖率
            val gbCjk = gb18030.count { isCjk(it) }
            val utfCjk = utf8.count { isCjk(it) }
            if (gbCjk > utfCjk * 2) return Charset.forName("GB18030")
        }

        // ── 4. 试 GBK ──
        val gbk = tryDecode(sample, "GBK")
        if (gbk != null) {
            val gbkBad = gbk.count { it == '\uFFFD' }
            if (gbkBad < utf8Bad) return Charset.forName("GBK")
        }

        // ── 5. 试 Big5（繁体中文） ──
        val big5 = tryDecode(sample, "Big5")
        if (big5 != null) {
            val big5Bad = big5.count { it == '\uFFFD' }
            if (big5Bad == 0) return Charset.forName("Big5")
        }

        // ── 6. 兜底 UTF-8 ──
        return Charsets.UTF_8
    }

    /**
     * 使用 CharsetDecoder + CodingErrorAction.REPORT 真正探测可解码性。
     * Java 默认 String(bytes, charset) 用替换策略（非法字节→U+FFFD）不抛异常，
     * 导致 tryDecode 永远非 null。改用 REPORT 模式后，非法字节会抛异常从而返回 null。
     */
    private fun tryDecode(bytes: ByteArray, charsetName: String): String? {
        return try {
            val charset = Charset.forName(charsetName)
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun charsetOrNull(name: String): Charset? {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun isCjk(c: Char): Boolean {
        return c in '\u4e00'..'\u9fff' || // CJK Unified Ideographs
            c in '\u3400'..'\u4dbf' ||     // CJK Extension A
            c in '\uf900'..'\ufaff'        // CJK Compatibility Ideographs
    }
}
