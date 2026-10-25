// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.contentpack

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 可插拔内容包：用户自己的 zip（若干 txt/md + 可选 manifest.json）导入成本地内容库。
 *
 * ## 安全边界（这是本功能真正的工作量，不是解压）
 * - **不按 zip 内路径落盘**：正文一律写成 `e<n>.txt`，原始路径只用于清单校验。
 *   即使有人构造 `../../files/...` 也碰不到任何真实路径 —— 但这类条目仍然直接拒绝，
 *   因为「能导入成功」会让用户以为包是合法的。
 * - 单条解压上限 2MB、整包 20MB、条目 500 个：防 zip bomb 与一次导入两百篇拖慢首屏。
 * - 加密包、坏 manifest、空条目：一律失败并给出可读原因，不静默导半个包。
 *
 * ## 懒加载
 * [listPacks] 只读 `pack.json` 元数据（标题/作者/字数），不读正文；
 * 只有 [readEntry] 或「全部导入」时才逐篇读文件。
 */
class ContentPackStore internal constructor(private val root: File) {

    data class EntryMeta(
        val title: String,
        val author: String,
        val chars: Int,
        val file: String,
    )

    data class PackMeta(
        val id: String,
        val name: String,
        val importedAt: Long,
        val entries: List<EntryMeta>,
    )

    sealed interface ImportResult {
        data class Success(val pack: PackMeta) : ImportResult
        data class Failure(val reason: Reason) : ImportResult
    }

    enum class Reason {
        EMPTY,
        BAD_PATH,
        TOO_MANY_ENTRIES,
        TOO_LARGE,
        BAD_MANIFEST,
        ENCRYPTED,
        READ_FAILED,
    }

    /**
     * 导入一个 zip。调用方负责 [InputStream] 的生命周期（本函数会关闭它）。
     *
     * @param fallbackName 没有 manifest 时用作包名（通常是 zip 文件名）
     */
    fun import(
        input: InputStream,
        fallbackName: String,
        maxEntries: Int = MAX_ENTRIES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
    ): ImportResult {
        val contents = LinkedHashMap<String, String>()
        var totalBytes = 0L
        var entryCount = 0
        try {
            ZipInputStream(BufferedInputStream(input), Charsets.UTF_8).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/')
                        if (!isSafePath(name)) return ImportResult.Failure(Reason.BAD_PATH)
                        entryCount++
                        if (entryCount > maxEntries) {
                            return ImportResult.Failure(Reason.TOO_MANY_ENTRIES)
                        }
                        val lower = name.lowercase()
                        val supported = lower == MANIFEST || lower.endsWith(".txt") || lower.endsWith(".md")
                        // 被忽略的文件也读一遍计体积：否则 zip 里塞满不支持的巨大条目
                        // 就能绕过总量上限（解压 CPU/内存仍被消耗）。
                        val bytes = readEntryBytes(zip, maxEntryBytes)
                            ?: return ImportResult.Failure(Reason.TOO_LARGE)
                        totalBytes += bytes.size
                        if (totalBytes > maxTotalBytes) {
                            return ImportResult.Failure(Reason.TOO_LARGE)
                        }
                        if (supported) {
                            contents[name] = bytes.toString(Charsets.UTF_8)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: java.util.zip.ZipException) {
            return ImportResult.Failure(Reason.ENCRYPTED)
        } catch (_: Exception) {
            return ImportResult.Failure(Reason.READ_FAILED)
        }
        if (contents.isEmpty()) return ImportResult.Failure(Reason.EMPTY)

        val manifest = contents[MANIFEST]
        val parsed = if (manifest != null) {
            parseManifest(manifest, contents) ?: return ImportResult.Failure(Reason.BAD_MANIFEST)
        } else {
            val name = fallbackName.substringBeforeLast('.').trim().ifBlank { "Content pack" }
            val entries = contents.keys
                .filter { it.lowercase().endsWith(".txt") || it.lowercase().endsWith(".md") }
                .sorted()
                .map { path ->
                    RawEntry(
                        file = path,
                        title = path.substringAfterLast('/').substringBeforeLast('.').trim(),
                        author = "",
                    )
                }
            RawManifest(name, entries)
        }
        if (parsed.entries.isEmpty()) return ImportResult.Failure(Reason.BAD_MANIFEST)
        val meta = writePack(parsed, contents) ?: return ImportResult.Failure(Reason.READ_FAILED)
        return ImportResult.Success(meta)
    }

    fun listPacks(): List<PackMeta> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(TMP_PREFIX) }
            ?: return emptyList()
        return dirs.mapNotNull { parsePackJson(it) }.sortedByDescending { it.importedAt }
    }

    /** 读取一篇正文；不存在/被删返回 null。 */
    fun readEntry(packId: String, index: Int): String? {
        val pack = listPacks().firstOrNull { it.id == packId } ?: return null
        val entry = pack.entries.getOrNull(index) ?: return null
        if (!isSafeGeneratedName(entry.file)) return null
        val dir = File(root, packId)
        val file = File(dir, entry.file)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun deletePack(packId: String) {
        if (!isSafeGeneratedName(packId)) return
        val dir = File(root, packId)
        if (dir.parentFile?.canonicalPath != root.canonicalPath) return
        runCatching { dir.deleteRecursively() }
    }

    // ── 内部 ──

    private data class RawEntry(val file: String, val title: String, val author: String)
    private data class RawManifest(val name: String, val entries: List<RawEntry>)

    private fun readEntryBytes(zip: ZipInputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parseManifest(text: String, contents: Map<String, String>): RawManifest? {
        val rootObj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (rootObj.optString("format") != FORMAT) return null
        if (rootObj.optInt("version", 0) !in 1..FORMAT_VERSION) return null
        val name = rootObj.optString("name", "").trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        val arr = rootObj.optJSONArray("entries") ?: return null
        if (arr.length() <= 0) return null
        val out = ArrayList<RawEntry>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val file = o.optString("file", "").replace('\\', '/')
            if (!isSafePath(file) || !seen.add(file)) return null
            if (!contents.containsKey(file)) return null
            val title = o.optString("title", "").trim().ifBlank {
                file.substringAfterLast('/').substringBeforeLast('.').trim()
            }
            if (title.isEmpty() || title.length > MAX_TITLE_LENGTH) return null
            val author = o.optString("author", "").trim()
            if (author.length > MAX_AUTHOR_LENGTH) return null
            out += RawEntry(file, title, author)
        }
        return RawManifest(name, out)
    }

    private fun writePack(manifest: RawManifest, contents: Map<String, String>): PackMeta? {
        runCatching { root.mkdirs() }
        val id = System.currentTimeMillis().toString()
        val tmp = File(root, "$TMP_PREFIX$id")
        val target = File(root, id)
        return try {
            if (!tmp.mkdirs()) return null
            val entries = ArrayList<EntryMeta>(manifest.entries.size)
            manifest.entries.forEachIndexed { index, raw ->
                val body = contents[raw.file].orEmpty()
                val fileName = "e$index.txt"
                File(tmp, fileName).writeText(body, Charsets.UTF_8)
                entries += EntryMeta(raw.title, raw.author, body.length, fileName)
            }
            val meta = PackMeta(id, manifest.name, System.currentTimeMillis(), entries)
            File(tmp, PACK_JSON).writeText(packToJson(meta).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.deleteRecursively()
                return null
            }
            meta
        } catch (_: Exception) {
            tmp.deleteRecursively()
            null
        }
    }

    private fun parsePackJson(dir: File): PackMeta? {
        val file = File(dir, PACK_JSON)
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val entries = ArrayList<EntryMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val f = e.optString("file", "")
                if (!isSafeGeneratedName(f)) continue
                entries += EntryMeta(
                    title = e.optString("title", ""),
                    author = e.optString("author", ""),
                    chars = e.optInt("chars", 0),
                    file = f,
                )
            }
            PackMeta(
                id = o.optString("id", dir.name),
                name = o.optString("name", ""),
                importedAt = o.optLong("importedAt", 0L),
                entries = entries,
            )
        }.getOrNull()?.takeIf { it.id == dir.name && it.name.isNotBlank() }
    }

    private fun packToJson(meta: PackMeta): JSONObject = JSONObject()
        .put("format", STORE_FORMAT)
        .put("version", FORMAT_VERSION)
        .put("id", meta.id)
        .put("name", meta.name)
        .put("importedAt", meta.importedAt)
        .put("entries", JSONArray().apply {
            meta.entries.forEach {
                put(
                    JSONObject()
                        .put("title", it.title)
                        .put("author", it.author)
                        .put("chars", it.chars)
                        .put("file", it.file)
                )
            }
        })

    /** 路径安全：拒绝绝对路径、盘符、反斜杠、`..` 段与空名。 */
    private fun isSafePath(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_PATH_LENGTH) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\u0000')) return false
        if (name.length >= 2 && name[1] == ':') return false
        if (name.contains('\\')) return false
        val parts = name.split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** 我们自己生成的文件名：只允许 `e<数字>.txt` 与纯数字目录名。 */
    private fun isSafeGeneratedName(name: String): Boolean =
        name.matches(Regex("e\\d{1,4}\\.txt")) || name.matches(Regex("\\d{1,13}"))

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val PACK_JSON = "pack.json"
        private const val TMP_PREFIX = ".tmp-"
        private const val FORMAT = "blancall-pack"
        private const val STORE_FORMAT = "blancall-pack-store"
        private const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 500
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
        private const val MAX_NAME_LENGTH = 100
        private const val MAX_TITLE_LENGTH = 200
        private const val MAX_AUTHOR_LENGTH = 100
        private const val MAX_PATH_LENGTH = 240

        @Volatile
        private var instance: ContentPackStore? = null

        fun getInstance(filesDir: File): ContentPackStore =
            instance ?: synchronized(this) {
                instance ?: ContentPackStore(File(filesDir, "content_packs")).also { instance = it }
            }
    }
}
