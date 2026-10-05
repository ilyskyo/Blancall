// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [BackupManager] 打包/还原往返测试（JVM 版 org.json + 临时目录）。
 *
 * 覆盖：学习数据全量收集（排除 .tmp/.bak/.corrupt）、导出→导入字节级还原、
 * 整库替换的孤儿清理、空数据拒导、非备份/坏清单/版本不符/路径穿越拒绝，
 * 以及校验失败时不落盘任何文件。
 */
class BackupManagerTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("blancall-backup-$name").toFile()

    private fun seedData(dir: File) {
        File(dir, "articles.json").writeText("""[{"id":1,"title":"劝学"}]""")
        File(dir, "records.json").writeText("""{"articleId":1}""")
        File(dir, "fsrs_state.json").writeText("""{"1":{"s":6.5}}""")
        File(dir, "practice_state_1.json").writeText("""{"articleId":1,"status":"in_progress"}""")
        File(dir, "practice_state_2.json").writeText("""{"articleId":2,"status":"done"}""")
        File(dir, "tags.json").writeText("""{"version":1,"tags":[],"links":{}}""")
        File(dir, "ink").mkdirs()
        File(dir, "ink/rec1.json").writeText("""{"v":1,"recordId":"rec1"}""")
        // 干扰文件：不属于备份范围
        File(dir, "articles.json.tmp").writeText("tmp")
        File(dir, "articles.json.bak").writeText("bak")
        File(dir, "mask_config.json.corrupt-1700000000").writeText("corrupt")
        File(dir, "crash.log").writeText("not data")
    }

    private fun exportToZip(srcDir: File): File {
        // 父目录即 tempDir("zip")，已存在，无需 mkdirs
        val zip = File(tempDir("zip"), "backup.zip")
        BackupManager.exportZip(srcDir, zip.outputStream())
        return zip
    }

    @Test
    fun collectExcludesAuxFiles() {
        val dir = tempDir("collect")
        seedData(dir)
        val names = BackupManager.collectBackupFiles(dir).map { it.first }
        assertTrue(names.contains("files/articles.json"))
        assertTrue(names.contains("files/practice_state_1.json"))
        assertTrue(names.contains("files/ink/rec1.json"))
        assertFalse(names.any { it.endsWith(".tmp") || it.endsWith(".bak") || it.contains("corrupt") })
        assertFalse(names.any { it.contains("crash.log") })
    }

    @Test
    fun exportImportRoundTripRestoresAllBytes() {
        val src = tempDir("src")
        seedData(src)
        val zip = exportToZip(src)
        val dst = tempDir("dst")
        val report = BackupManager.restoreZip(zip, dst)

        val expected = listOf(
            "articles.json", "records.json", "fsrs_state.json",
            "practice_state_1.json", "practice_state_2.json", "tags.json", "ink/rec1.json",
        )
        assertEquals(expected.size, report.restored)
        expected.forEach { rel ->
            assertEquals(File(src, rel).readText(), File(dst, rel).readText())
        }
        // 干扰文件不在还原范围内
        assertFalse(File(dst, "articles.json.bak").exists())
        assertFalse(File(dst, "crash.log").exists())
    }

    @Test
    fun importReplacesAndRemovesOrphans() {
        val src = tempDir("src2")
        seedData(src)
        val zip = exportToZip(src)
        val dst = tempDir("dst2")
        // 目标机已有旧数据：同主文件将被覆盖，备份外孤儿进度/墨迹将被清理
        File(dst, "articles.json").writeText("""[{"id":99,"title":"旧文章"}]""")
        File(dst, "practice_state_9.json").writeText("""{"articleId":9}""")
        File(dst, "ink").mkdirs()
        File(dst, "ink/old.json").writeText("""{"v":1}""")
        // 非学习数据文件不受清理影响
        File(dst, "crash.log").writeText("keep me")

        val report = BackupManager.restoreZip(zip, dst)
        assertEquals(File(src, "articles.json").readText(), File(dst, "articles.json").readText())
        assertEquals(2, report.removed)
        assertFalse(File(dst, "practice_state_9.json").exists())
        assertFalse(File(dst, "ink/old.json").exists())
        assertEquals("keep me", File(dst, "crash.log").readText())
        // 10 个数据文件（含目标机原本已有、备份里也有主文件的场景）：本例备份含 7 个条目
        assertEquals(7, report.restored)
    }

    @Test
    fun exportEmptyDataThrows() {
        val empty = tempDir("empty")
        val zip = File(tempDir("emptyzip"), "backup.zip")
        var thrown = false
        try {
            BackupManager.exportZip(empty, zip.outputStream())
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue("空数据目录应拒绝导出", thrown)
    }

    @Test
    fun rejectsInvalidOrMaliciousZips() {
        val dst = tempDir("reject")
        File(dst, "articles.json").writeText("original")

        // 普通非备份 zip（无 manifest）
        val plain = File(tempDir("plain"), "plain.zip")
        ZipOutputStream(plain.outputStream()).use {
            it.putNextEntry(ZipEntry("files/articles.json")); it.write("hacked".toByteArray()); it.closeEntry()
        }
        expectReject { BackupManager.restoreZip(plain, dst) }

        // 版本不符
        val future = File(tempDir("future"), "future.zip")
        ZipOutputStream(future.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("""{"formatVersion":99,"files":["files/articles.json"]}""".toByteArray())
            it.closeEntry()
        }
        expectReject { BackupManager.restoreZip(future, dst) }

        // 路径穿越：manifest 合法但条目名恶意
        val traversal = File(tempDir("traversal"), "evil.zip")
        ZipOutputStream(traversal.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":1,"files":["files/../escape.json"]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("files/../escape.json"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
        }
        expectReject { BackupManager.restoreZip(traversal, dst) }

        // 三次拒绝均未触碰磁盘
        assertEquals("original", File(dst, "articles.json").readText())
        assertFalse(File(dst.parentFile, "escape.json").exists())
    }

    private fun expectReject(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("非法备份应被拒绝", thrown)
    }
}
