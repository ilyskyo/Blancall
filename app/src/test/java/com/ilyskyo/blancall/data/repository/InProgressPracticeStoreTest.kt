// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [InProgressPracticeStore] 的扫描语义：只收「进行中且还有剩余空」的记录，
 * 按最近练习倒序；暂停/删除过滤不在这里做（调用方的 Snapshot 负责）。
 */
class InProgressPracticeStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("blancall-progress-test").toFile()

    private fun write(dir: File, name: String, json: String) {
        File(dir, name).writeText(json)
    }

    @Test
    fun `只收进行中且仍有剩余空的记录并按最近练习倒序`() {
        val dir = tempDir()
        write(
            dir, "practice_state_1.json",
            """{"status":"IN_PROGRESS","articleId":1,"mode":"SENTENCE","totalBlanks":10,"answeredCount":3,"lastPracticeTime":200}"""
        )
        write(
            dir, "practice_state_2.json",
            """{"status":"COMPLETED","articleId":2,"mode":"WORD","totalBlanks":10,"answeredCount":10,"lastPracticeTime":999}"""
        )
        write(
            dir, "practice_state_3.json",
            """{"status":"IN_PROGRESS","articleId":3,"mode":"WORD","totalBlanks":5,"answeredCount":5,"lastPracticeTime":998}"""
        )
        write(dir, "practice_state_4.json", "{not json")
        write(
            dir, "practice_state_5.json",
            """{"status":"IN_PROGRESS","articleId":5,"mode":"SENTENCE","totalBlanks":8,"answeredCount":2,"lastPracticeTime":300}"""
        )
        write(
            dir, "practice_state_6.json",
            """{"status":"IN_PROGRESS","articleId":6,"mode":"REVERSE","totalBlanks":8,"answeredCount":1,"lastPracticeTime":100}"""
        )
        write(dir, "unrelated.json", """{"status":"IN_PROGRESS","articleId":7,"totalBlanks":9,"answeredCount":0}""")

        val found = InProgressPracticeStore.scan(dir)

        assertEquals(listOf(5L, 1L, 6L), found.map { it.articleId })
        assertEquals(listOf(6, 7, 7), found.map { it.remaining })
        assertEquals("REVERSE", found.last().mode)
    }

    @Test
    fun `没有进度文件时返回空表`() {
        assertTrue(InProgressPracticeStore.scan(tempDir()).isEmpty())
    }
}
