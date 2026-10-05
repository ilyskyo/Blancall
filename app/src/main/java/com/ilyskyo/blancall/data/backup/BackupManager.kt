// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.backup

import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 学习数据一键备份：把 filesDir 里的全部学习数据打包成单个 ZIP，
 * 可在新安装的 Blancall 上一键导入还原。
 *
 * 背景：`backup_rules.xml` / `data_extraction_rules.xml` 刻意把 file 域排除在
 * 系统自动备份之外（防止旧进度覆盖新数据），因此 App 内必须自带备份通路。
 * 只覆盖学习数据（文章、练习记录、FSRS 状态、挖空/遮挡、标签、首页布局、
 * 阅读偏好、每日一句、墨迹），不含偏好设置与 AI 配置。
 *
 * 磁盘即真相：导出直接读磁盘文件而非内存快照，绕开各 Store 的异步加载时序。
 * 写回统一走 [AtomicFiles]，但各 Store 的内存镜像只在进程启动时加载一次，
 * 因此导入完成后必须重启进程才能生效（调用方负责引导退出）。
 */
object BackupManager {

    /** 备份格式版本，未来不兼容改动时递增并在导入侧分支处理。 */
    const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"

    /** 固定清单：filesDir 根目录下的学习数据主文件（各 Store 硬编码同名）。 */
    private val ROOT_DATA_FILES = listOf(
        "articles.json",
        "records.json",
        "fsrs_state.json",
        "custom_cloze.json",
        "mask_config.json",
        "home_layout.json",
        "sentence_card.json",
        "reader_prefs.json",
        "tags.json",
    )

    /** 单条目解压上限，防异常压缩包拖垮内存（正常学习数据远小于此值）。 */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** 导入结果：restored=写回的数据文件数，removed=被清掉的备份外残留数据文件数。 */
    data class RestoreReport(val restored: Int, val removed: Int)

    /**
     * 收集应备份的文件，返回「ZIP 条目名 → 磁盘文件」列表（缺失的跳过）。
     * 覆盖：固定主文件 + `practice_state_` 前缀的 JSON + `ink` 目录下的 JSON；
     * `.tmp` / `.bak` / `.corrupt-*` 不在清单模式内，天然被排除。
     */
    fun collectBackupFiles(filesDir: File): List<Pair<String, File>> {
        val entries = mutableListOf<Pair<String, File>>()
        for (name in ROOT_DATA_FILES) {
            val f = File(filesDir, name)
            if (f.isFile) entries.add(FILES_PREFIX + name to f)
        }
        filesDir.listFiles { f -> f.isFile && f.name.startsWith("practice_state_") && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.forEach { entries.add(FILES_PREFIX + it.name to it) }
        File(filesDir, "ink").listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.forEach { entries.add(FILES_PREFIX + "ink/${it.name}" to it) }
        return entries
    }

    /**
     * 把 [filesDir] 的学习数据写入 ZIP（manifest + files/ 条目）。
     * 没有任何可备份文件时抛 [IllegalStateException]，避免产出空备份。
     * IO 异常原样上抛，由调用方提示。
     */
    fun exportZip(filesDir: File, output: OutputStream) {
        val entries = collectBackupFiles(filesDir)
        check(entries.isNotEmpty()) { "没有可备份的学习数据" }
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, MANIFEST_ENTRY, buildManifest(entries.map { it.first }))
            for ((entryName, file) in entries) {
                writeEntry(zip, entryName, file.readBytes())
            }
        }
    }

    /**
     * 从 ZIP 恢复学习数据到 [filesDir]，整库替换语义：
     * 备份内文件逐一原子写回；备份外的既有学习数据文件删除，
     * 使恢复后的数据域与备份严格一致，避免残留孤儿进度。
     *
     * 校验（manifest 存在、版本匹配、条目名无路径穿越、至少一个数据条目）
     * 全部通过后才会落盘；校验失败抛 [IllegalArgumentException] 且不碰任何文件。
     * 写回后内存单例仍是旧数据，调用方须引导用户重启应用。
     */
    fun restoreZip(zipFile: File, filesDir: File): RestoreReport {
        val data: List<Pair<String, ByteArray>>
        ZipFile(zipFile).use { zip ->
            data = readDataEntries(zip)
            data.forEach { (rel, bytes) ->
                require(isSafeRelativeName(rel)) { "备份包含非法路径：$rel" }
            }
            require(data.isNotEmpty()) { "备份文件中没有可恢复的数据" }
            data.forEach { (rel, bytes) ->
                AtomicFiles.writeTextAtomic(File(filesDir, rel), String(bytes, StandardCharsets.UTF_8))
            }
        }
        // 清理备份外的残留学习数据：只识别学习数据范围内的文件，其余一概不动
        val keep = data.map { it.first }.toSet()
        var removed = 0
        for (name in ROOT_DATA_FILES) {
            val f = File(filesDir, name)
            if (f.isFile && name !in keep && f.delete()) removed++
        }
        filesDir.listFiles { f -> f.isFile && f.name.startsWith("practice_state_") && f.name.endsWith(".json") }
            ?.forEach { if (it.name !in keep && it.delete()) removed++ }
        File(filesDir, "ink").listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.forEach { if ("ink/${it.name}" !in keep && it.delete()) removed++ }
        return RestoreReport(data.size, removed)
    }

    /** 解析 manifest 并读出其登记的全部数据条目（相对路径 → 字节）。 */
    private fun readDataEntries(zip: ZipFile): List<Pair<String, ByteArray>> {
        val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw IllegalArgumentException("不是有效的 Blancall 备份文件")
        val json = try {
            JSONObject(zip.getInputStream(manifestEntry).reader().readText())
        } catch (_: Exception) {
            throw IllegalArgumentException("备份文件清单已损坏")
        }
        require(json.optInt("formatVersion") == FORMAT_VERSION) { "暂不支持该版本的备份文件" }
        val names = json.optJSONArray("files") ?: JSONArray()
        val result = mutableListOf<Pair<String, ByteArray>>()
        for (i in 0 until names.length()) {
            val entry = names.optString(i)
            if (!entry.startsWith(FILES_PREFIX)) continue
            val zipEntry = zip.getEntry(entry) ?: continue
            result.add(entry.removePrefix(FILES_PREFIX) to readEntryBytes(zip, zipEntry))
        }
        return result
    }

    /** 读取条目内容，超过上限视为异常包拒绝。 */
    private fun readEntryBytes(zip: ZipFile, entry: ZipEntry): ByteArray {
        zip.getInputStream(entry).use { input: InputStream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_ENTRY_BYTES) { "备份条目过大：${entry.name}" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** 仅允许「主文件名」或「ink/文件名」两级安全名，各段只含常见安全字符。 */
    private fun isSafeRelativeName(rel: String): Boolean =
        rel.isNotBlank() && !rel.startsWith("/") &&
            rel.split("/").all { it.isNotEmpty() && !it.contains("..") && it.matches(Regex("[A-Za-z0-9_.\\-]+")) } &&
            (rel.count { it == '/' } == 0 || rel.startsWith("ink/"))

    private fun buildManifest(entryNames: List<String>): ByteArray {
        val files = JSONArray()
        entryNames.forEach { files.put(it) }
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("files", files)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
