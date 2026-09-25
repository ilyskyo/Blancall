// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.util

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 原子化文本写盘：全部 Store 的统一落盘入口。
 *
 * 流程（与各 Store 旧手工实现逐步骤等价，仅追加 fsync）：
 * 1. `tmp` 写入全文并 `fd.sync()` —— 数据块先落盘；
 * 2. 覆盖前把旧主文件轮换为 `.bak`（读盘损坏时回读用）；
 * 3. `rename` 替换主文件；个别 ROM / 文件被占用导致 rename 失败时退回直接覆盖写；
 * 4. 父目录 `fsync`（尽力而为）—— 让 rename 的目录项也落盘。
 *
 * 为什么要 fsync：`rename` 保证进程被杀时不会读到半截文件（原子性），
 * 但断电 / 强制重启时数据块与目录项可能尚未落盘（持久性），
 * fsync 把「最近一次写入」的丢失窗口收敛为零。
 *
 * 异常语义：**不吞异常** —— 写失败原样抛给调用方，由各 Store 维持既有处理
 * （静默重试 / 记录日志 / 上抛）。目录 fsync 为纯增强，失败时静默降级。
 */
object AtomicFiles {

    /**
     * 原子写 [text]（UTF-8）到 [file]。
     * 会按需创建父目录；tmp 残留（上次写中断）会被直接覆盖，无需调用方清理。
     */
    fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        // 1) 写临时文件并同步数据块（truncate 语义：上次中断残留的 tmp 会被完整覆盖）
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            runCatching { out.fd.sync() }
        }
        // 2) 备份轮换：保留一份上一版本供读盘失败回读
        if (file.exists()) {
            val bak = File(file.parentFile, file.name + ".bak")
            if (bak.exists()) bak.delete()
            file.renameTo(bak)
        }
        // 3) rename 替换；失败（个别 ROM / 占用）退回直接覆盖写，至少不比旧实现差
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        // 4) 目录 fsync（尽力而为，见下）
        fsyncDirectoryBestEffort(file.parentFile)
    }

    /**
     * 追加一行文本（UTF-8，自动补换行）并 fsync —— 供「只增不改」的大文件
     * （练习记录 JSONL）做 O(1) 落盘，避免每次 insert 全量重写。
     *
     * 与 [writeTextAtomic] 的分工：
     * - 追加语义决定不能用 tmp+rename（会变成整体替换）；持久性靠 fd.sync；
     * - 崩溃语义：追加中被杀只会留下**半行**，读取端按「逐行容错解析」跳过即可；
     * - `.bak` 轮换只由全量重写路径（删除/转格式）负责，追加路径不动备份。
     *
     * 会按需创建父目录；追加失败原样抛出，由调用方维持既有处理。
     */
    fun appendTextLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            runCatching { out.fd.sync() }
        }
        fsyncDirectoryBestEffort(file.parentFile)
    }

    /**
     * 目录 fsync：Linux / Android 允许以只读打开目录并 fsync（持久化 rename 的目录项）。
     * Windows（JVM 单测环境）不允许打开目录、个别文件系统也不支持 —— 捕获后静默降级，
     * 目录 fsync 只是额外的持久化保证，不影响主写入路径。
     */
    private fun fsyncDirectoryBestEffort(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // 见上：单测（Windows）与不支持的平台走这里，属预期降级
        }
    }
}
