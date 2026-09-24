// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import java.io.File
import java.nio.file.Files

/**
 * SentenceCardStore 测试共享目录（测试进程内唯一）。
 *
 * [SentenceCardStore] 是进程级单例（getInstance 只认首个路径）：若多个测试类各自
 * 创建临时目录，只有先初始化者的目录生效，另一类的"直接写文件"用例会落到无效路径。
 * 统一在此创建唯一临时目录与实例，供所有相关测试类共享，保证行为确定、顺序无关。
 */
internal object SentenceCardTestDir {
    val dir: File = Files.createTempDirectory("blancall-sentence-card-test").toFile()
    val store: SentenceCardStore = SentenceCardStore.getInstance(dir)
}
