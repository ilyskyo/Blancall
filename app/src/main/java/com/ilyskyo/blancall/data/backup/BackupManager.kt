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

    /**
     * 备份/恢复失败的类型：只带错误码与参数，展示文案由 UI 层取资源（键 `backup_err_*`）。
     *
     * 早先这里直接抛带中文消息的 IllegalArgumentException —— 界面切到英/日/韩/俄时，
     * 「导入失败：不是有效的 Blancall 备份文件」整句仍是简体中文（多语言守卫对数据层的
     * 字符串字面量全瞎），而测试只能对着中文措辞断言，改个用词就得改测试。
     */
    sealed class BackupError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** 不是有效的 Blancall 备份（缺 manifest） */
        class NotABackup : BackupError("not a Blancall backup")
        /** 备份的清单（manifest.json）存在但解析不了 */
        class CorruptManifest(cause: Throwable?) : BackupError("corrupt manifest", cause)
        /** 备份格式版本不受支持（[version] 为文件声明的版本） */
        class UnsupportedVersion(val version: Int) : BackupError("unsupported backup version: $version")
        /** manifest 登记了但归档中缺失的条目（[entryName] 为相对路径） */
        class Incomplete(val entryName: String) : BackupError("missing entry: $entryName")
        /** 条目或整包超出大小上限（[entryName] 为空表示整包超限） */
        class TooLarge(val entryName: String?) : BackupError("too large: ${entryName ?: "total"}")
        /** 条目名非法（绝对路径 / `..` 穿越 / 非法字符） */
        class UnsafeEntry(val entryName: String) : BackupError("unsafe entry: $entryName")
        /** 备份里没有可恢复的数据条目 */
        class EmptyData : BackupError("no data to restore")
        /** 没有可备份的学习数据（导出时） */
        class NothingToExport : BackupError("no data to export")
        /** 打开目标输出流失败（导出时无法写入所选文件） */
        class CannotWrite : BackupError("cannot open output")
        /** 打开来源输入流失败（导入时无法读取所选文件） */
        class CannotRead : BackupError("cannot open input")
    }

    /** 备份格式版本，未来不兼容改动时递增并在导入侧分支处理。 */
    const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"

    /**
     * 固定清单：filesDir 根目录下的学习数据主文件（各 Store 硬编码同名）。
     * internal 是给 [com.ilyskyo.blancall.data.backup.LearningDataFileCensusTest] 做 census 比对用的 ——
     * 这份清单就是「一键备份覆盖哪些数据」对用户的承诺，漏登记不会编译失败、只会静默丢数据。
     */
    internal val ROOT_DATA_FILES = listOf(
        "articles.json",
        "records.json",
        "fsrs_state.json",
        "custom_cloze.json",
        "mask_config.json",
        "home_layout.json",
        "sentence_card.json",
        "reader_prefs.json",
        "tags.json",
        // 成就解锁时刻（AchievementTimelineStore）：徽章本身是派生的，但「何时拿到」只有这一份，
        // 备份里没有它，恢复之后统计页就答不出「这周新得了几枚」
        "achievements.json",
    )

    /** 单条目解压上限，防异常压缩包拖垮内存（正常学习数据远小于此值）。 */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /**
     * 整包解压总量上限：单条目上限挡不住「many 个小条目」把整包字节全驻内存，
     * restoreZip 是先在内存里凑齐 data 才开始落盘的。
     */
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /** 两段式写回的暂存后缀（不在 collectBackupFiles 的任何收集模式内，天然不进备份）。 */
    private const val IMPORT_SUFFIX = ".import"

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
     * 没有任何可备份文件时抛 [BackupError.NothingToExport]，避免产出空备份。
     * IO 异常原样上抛，由调用方提示。
     */
    fun exportZip(filesDir: File, output: OutputStream) {
        val entries = collectBackupFiles(filesDir)
        if (entries.isEmpty()) throw BackupError.NothingToExport()
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
     * 校验（manifest 存在、版本匹配、条目名无路径穿越、至少一个数据条目、
     * manifest 登记的每个条目都能读出来）全部通过后才会落盘；
     * 校验失败抛 [IllegalArgumentException] 且不碰任何文件 —— 包括不清孤儿。
     * 写回分两段（先全部暂存再统一提换），中途一次写失败不会留下新旧混杂的数据。
     * 写回后内存单例仍是旧数据，调用方须引导用户重启应用。
     */
    fun restoreZip(zipFile: File, filesDir: File): RestoreReport {
        val data: List<Pair<String, ByteArray>>
        ZipFile(zipFile).use { zip ->
            data = readDataEntries(zip)
            data.forEach { (rel, _) ->
                if (!isSafeRelativeName(rel)) throw BackupError.UnsafeEntry(rel)
            }
            if (data.isEmpty()) throw BackupError.EmptyData()
        }
        // 两段式：先把每个条目完整写到 .import 上（磁盘满、写中断都在这里暴露，此时一个
        // 正式数据文件都还没被动），再统一提换。旧实现边读 ZIP 边写主文件，中途失败即
        // 「前半新、后半旧」混杂，而没写成那些条目的旧数据已经被 .bak 轮换走了。
        val staged = data.map { (rel, bytes) ->
            val dst = File(filesDir, rel)
            File(dst.parentFile, dst.name + IMPORT_SUFFIX).also { AtomicFiles.writeStaged(it, bytes) } to dst
        }
        try {
            staged.forEach { (stage, dst) -> AtomicFiles.promoteStaged(stage, dst) }
        } finally {
            // 提换成功时 staged 已被 rename 走；这里只清失败中断留下的孤儿暂存文件
            staged.forEach { (stage, _) -> if (stage.exists()) stage.delete() }
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
        // 导入是整库替换：promoteStaged 已把导入前的旧主文件轮换成 .bak，这些 .bak
        // 属于旧库。留着会在主文件缺失/损坏时被各 Store 回读，把刚恢复的数据“复活”成旧版本。
        // 只有全部提换成功走到这里才删；中途失败时 .bak 仍保留，用于回滚。
        for (name in ROOT_DATA_FILES) {
            val bak = File(filesDir, "$name.bak")
            if (bak.isFile) bak.delete()
        }
        filesDir.listFiles { f ->
            f.isFile && f.name.startsWith("practice_state_") && f.name.endsWith(".json.bak")
        }?.forEach { it.delete() }
        return RestoreReport(data.size, removed)
    }

    /** 解析 manifest 并读出其登记的全部数据条目（相对路径 → 字节）。 */
    private fun readDataEntries(zip: ZipFile): List<Pair<String, ByteArray>> {
        val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw BackupError.NotABackup()
        val json = try {
            JSONObject(zip.getInputStream(manifestEntry).reader().readText())
        } catch (e: Exception) {
            throw BackupError.CorruptManifest(e)
        }
        val version = json.optInt("formatVersion")
        if (version != FORMAT_VERSION) throw BackupError.UnsupportedVersion(version)
        val names = json.optJSONArray("files") ?: JSONArray()
        val result = mutableListOf<Pair<String, ByteArray>>()
        var totalBytes = 0L
        for (i in 0 until names.length()) {
            val entry = names.optString(i)
            if (!entry.startsWith(FILES_PREFIX)) continue
            // manifest 登记了、归档里却没有 → 必须整包拒绝。旧实现写的是 `?: continue`，
            // 被跳过的条目既进不了 data、也就不在 restoreZip 的 keep 集合里，紧接着那段
            // 孤儿清理会把磁盘上对应的真实数据当残留删掉 —— 截断过或手工改过的备份
            // 「恢复」一次，那一类学习数据就归零。
            val zipEntry = zip.getEntry(entry)
                ?: throw BackupError.Incomplete(entry.removePrefix(FILES_PREFIX))
            val bytes = readEntryBytes(zip, zipEntry)
            totalBytes += bytes.size
            if (totalBytes > MAX_TOTAL_BYTES) throw BackupError.TooLarge(null)
            result.add(entry.removePrefix(FILES_PREFIX) to bytes)
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
                if (total > MAX_ENTRY_BYTES) throw BackupError.TooLarge(entry.name)
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
