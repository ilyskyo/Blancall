// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AtomicFiles 写盘语义测试（JVM 临时目录）。
 *
 * 覆盖：首写、覆盖轮换 .bak、中断残留 tmp 的恢复、多次写入只保留最新备份、父目录按需创建、空内容往返。
 * 目录 fsync 在 Windows JVM 上属预期静默降级（见 AtomicFiles KDoc），不影响以下断言。
 */
class AtomicFilesTest {

    private fun tempDir(): File = Files.createTempDirectory("blancall-atomic-test").toFile()

    private fun bakOf(f: File): File = File(f.parentFile, f.name + ".bak")

    private fun tmpOf(f: File): File = File(f.parentFile, f.name + ".tmp")

    @Test
    fun `first write creates file and leaves no bak or tmp`() {
        val f = tempDir().resolve("store.json")
        AtomicFiles.writeTextAtomic(f, "hello")
        assertEquals("hello", f.readText())
        assertFalse(bakOf(f).exists())
        assertFalse(tmpOf(f).exists())
    }

    @Test
    fun `overwrite rotates previous content into bak`() {
        val f = tempDir().resolve("store.json")
        AtomicFiles.writeTextAtomic(f, "v1")
        AtomicFiles.writeTextAtomic(f, "v2")
        assertEquals("v2", f.readText())
        assertEquals("v1", bakOf(f).readText())
        assertFalse(tmpOf(f).exists())
    }

    @Test
    fun `stale tmp from interrupted previous write is recovered`() {
        val f = tempDir().resolve("store.json")
        AtomicFiles.writeTextAtomic(f, "v1")
        // 模拟上次写到一半被杀：主文件仍是旧版、tmp 残留半截内容
        tmpOf(f).writeText("{partial garbage")
        AtomicFiles.writeTextAtomic(f, "v2")
        assertEquals("v2", f.readText())
        assertFalse(tmpOf(f).exists())
        assertEquals("v1", bakOf(f).readText())
    }

    @Test
    fun `keeps only latest bak across multiple writes`() {
        val f = tempDir().resolve("store.json")
        AtomicFiles.writeTextAtomic(f, "v1")
        AtomicFiles.writeTextAtomic(f, "v2")
        AtomicFiles.writeTextAtomic(f, "v3")
        assertEquals("v3", f.readText())
        assertEquals("v2", bakOf(f).readText())
    }

    @Test
    fun `creates missing parent directories`() {
        val f = File(tempDir(), "nested/deep/store.json")
        AtomicFiles.writeTextAtomic(f, "content")
        assertTrue(f.exists())
        assertEquals("content", f.readText())
    }

    @Test
    fun `empty string round trips`() {
        val f = tempDir().resolve("store.json")
        AtomicFiles.writeTextAtomic(f, "")
        assertTrue(f.exists())
        assertEquals("", f.readText())
    }
}
