// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.util.UUID

/**
 * 阅读字体管理：
 * - 扫描系统字体目录（/system/fonts 下的 .ttf/.otf），解析字体家族名用于展示
 * - 导入本地字体文件（ttf/otf，内部目录持久化），支持删除
 * - 按字体 id 解析为 Compose [FontFamily]
 *
 * id 约定（与 [com.ilyskyo.blancall.ui.theme.AppPrefs.readingFontId] 对应）：
 * - "0" 系统默认 / "1" 宋体 / "2" 黑体 / "3" 等宽
 * - 绝对路径：扫描到的系统字体文件路径
 * - "imp:<文件名>"：导入到内部目录的字体
 */
data class ReaderFont(
    val id: String,
    val name: String,
    val source: FontSource
)

enum class FontSource { PRESET, SYSTEM, IMPORTED }

object ReaderFonts {

    /** 内置预设字体 */
    val presets: List<ReaderFont> = listOf(
        ReaderFont("0", "默认", FontSource.PRESET),
        ReaderFont("1", "宋体", FontSource.PRESET),
        ReaderFont("2", "黑体", FontSource.PRESET),
        ReaderFont("3", "等宽", FontSource.PRESET)
    )

    private val SYSTEM_FONT_DIRS = listOf("/system/fonts")

    /**
     * 扫描系统字体：列出 /system/fonts 下的 .ttf/.otf 单字体文件（跳过 .ttc 集合，
     * 避免 Typeface.createFromFile 无法正确加载），解析家族名仅供展示。
     */
    fun scanSystemFonts(): List<ReaderFont> {
        val result = mutableListOf<ReaderFont>()
        for (dir in SYSTEM_FONT_DIRS) {
            val d = File(dir)
            if (!d.isDirectory) continue
            val files = d.listFiles { f ->
                f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true))
            } ?: continue
            for (f in files) {
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val name = readFontFamilyName(bytes).ifBlank { f.nameWithoutExtension }
                result.add(ReaderFont(f.absolutePath, name, FontSource.SYSTEM))
            }
        }
        // 稳定排序，优先中文字体感知（名称含 Song/Hei/Ming 的靠前）便于用户辨识
        return result.sortedBy { it.name.lowercase() }
    }

    /** 内部字体目录 */
    private fun fontsDir(context: Context): File =
        File(context.filesDir, "reader_fonts").apply { if (!isDirectory) mkdirs() }

    /** 已导入字体文件列表 */
    private fun importedFiles(context: Context): List<File> {
        val dir = fontsDir(context)
        return (dir.listFiles { f -> f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true)) }
            ?: emptyArray()).sortedBy { it.name }
    }

    /** 已导入字体（解析家族名展示，供遮挡/字体下拉复用） */
    fun listImportedFonts(context: Context): List<ReaderFont> =
        importedFiles(context).map { f ->
            val name = runCatching { readFontFamilyName(f.readBytes()) }
                .getOrDefault("").ifBlank { f.nameWithoutExtension }
            ReaderFont("imp:${f.name}", name, FontSource.IMPORTED)
        }

    /**
     * 导入字体文件：校验是 TTF/OTF 后拷贝到内部目录，解析家族名。
     * @return 导入成功返回 [ReaderFont]，否则（内容非法）返回 null
     */
    fun importFont(context: Context, fileName: String, input: java.io.InputStream): ReaderFont? {
        val bytes = runCatching { input.readBytes() }.getOrNull() ?: return null
        if (!isValidFont(bytes)) return null
        val ext = if (fileName.lowercase().endsWith(".otf")) ".otf" else ".ttf"
        val name = readFontFamilyName(bytes).ifBlank { fileName.substringBeforeLast('.').trim().ifBlank { "导入字体" } }
        val target = File(fontsDir(context), UUID.randomUUID().toString() + ext)
        runCatching { target.writeBytes(bytes) }.getOrNull() ?: return null
        return ReaderFont("imp:${target.name}", name, FontSource.IMPORTED)
    }

    /** 删除已导入字体文件；返回是否删除成功 */
    fun deleteImportedFont(context: Context, id: String): Boolean {
        if (!id.startsWith("imp:")) return false
        val rel = id.substringAfter("imp:")
        val f = File(fontsDir(context), rel)
        return f.exists() && f.delete()
    }

    /** 判断字节是否为合法 TTF/OTF（sfnt 标识 0x00010000 / 'OTTO'），用于导入校验 */
    fun isValidFont(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val tag = String(data, 0, 4, Charsets.US_ASCII)
        return tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO"
    }

    /**
     * 按字体 id 解析为 Compose [FontFamily]。
     * 预设走系统 Typeface；系统/导入字体用 Typeface.createFromFile 加载。
     * 解析失败的任意路径一律回退系统默认，绝不让页面崩溃。
     */
    fun resolveFontFamily(context: Context, id: String): FontFamily? {
        if (id.startsWith("imp:") || id.startsWith("/")) {
            val path = when {
                id.startsWith("imp:") -> File(fontsDir(context), id.substringAfter("imp:")).absolutePath
                else -> id
            }
            val f = File(path)
            if (f.exists()) {
                return runCatching { FontFamily(Typeface.createFromFile(f)) }.getOrNull()
            }
            return null
        }
        return idsToPresetFamily[id]
    }

    private val idsToPresetFamily: Map<String, FontFamily> = mapOf(
        "0" to FontFamily.Default,
        "1" to FontFamily.Serif,
        "2" to FontFamily.SansSerif,
        "3" to FontFamily.Monospace
    )

    // ═══════════════════════════════════════════
    //  sfnt「name」表家族名解析（无需第三方库）
    // ═══════════════════════════════════════════

    /** 首选家族名 nameID：16(typographic family) / 1(family)，回退 4(full name) / 2(subfamily) */
    private fun nameIdsToUse(id: Int): Boolean = id == 16 || id == 1

    /**
     * 从单字体（TTF/OTF）的 sfnt name 表解析家族名，失败返回空串。
     * 偏好 platform 3(Unicode UTF-16BE) 与 0，其次 platform 1(Mac Latin1/UTF-8）。
     */
    internal fun readFontFamilyName(data: ByteArray): String {
        if (data.size < 12) return ""
        val numTables = readU16(data, 4)
        var nameOffset = -1
        for (t in 0 until numTables) {
            val rec = 12 + t * 16
            if (rec + 16 > data.size) break
            if (String(data, rec, 4, Charsets.US_ASCII) == "name") {
                nameOffset = readU32(data, rec + 8)
                break
            }
        }
        if (nameOffset < 0 || nameOffset + 6 > data.size) return ""
        val count = readU16(data, nameOffset + 2)
        val stringDataOff = nameOffset + readU16(data, nameOffset + 4)
        var primary = ""
        var fallback = ""
        for (i in 0 until count) {
            val base = nameOffset + 6 + i * 12
            if (base + 12 > data.size) break
            val platform = readU16(data, base)
            val nameId = readU16(data, base + 6)
            val len = readU16(data, base + 8)
            val off = readU16(data, base + 10)
            if (len <= 0) continue
            val abs = stringDataOff + off
            if (abs + len > data.size) continue
            val s = when (platform) {
                3, 0 -> String(data, abs, len, Charsets.UTF_16BE)
                1 -> String(data, abs, len, Charsets.UTF_8)
                else -> continue
            }.trim()
            if (s.isEmpty() || s.contains('\uFFFD')) continue
            if (nameIdsToUse(nameId) && primary.isEmpty()) primary = s
            if (fallback.isEmpty() && (nameId == 4 || nameId == 2)) fallback = s
            if (primary.isNotEmpty()) return primary
        }
        return primary.ifEmpty { fallback }
    }

    private fun readU32(d: ByteArray, i: Int): Int =
        ((d.getOrElse(i + 0) { 0 }.toInt() and 0xFF) shl 24) or
            ((d.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 16) or
            ((d.getOrElse(i + 2) { 0 }.toInt() and 0xFF) shl 8) or
            (d.getOrElse(i + 3) { 0 }.toInt() and 0xFF)

    private fun readU16(d: ByteArray, i: Int): Int =
        ((d.getOrElse(i + 0) { 0 }.toInt() and 0xFF) shl 8) or
            (d.getOrElse(i + 1) { 0 }.toInt() and 0xFF)
}