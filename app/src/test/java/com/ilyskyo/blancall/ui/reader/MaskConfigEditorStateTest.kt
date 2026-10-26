// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

import com.ilyskyo.blancall.data.repository.MaskConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遮挡编辑器「回填丢失」真机 bug 的回归锚点。
 *
 * 现场：用户新建一套遮挡配置 → 保存 → 再点编辑，编辑器是空白的；重新标完保存，
 * 配置被改成「自定义 2」，再一轮变成「自定义 3」。
 *
 * 根因两条，都由下面的纯函数钉住：
 * 1. 回放判定读了**实时** `editorSnapshot`，本会话第一帧写下的空现场立刻把它点亮，
 *    磁盘回填被整段跳过（名字与遮挡一起丢）；
 * 2. 名字为空时保存入口去拿 `nextDefaultName()`，于是「编辑一次 = 改名一次」。
 */
class MaskConfigEditorStateTest {

    private fun cfg(id: Long, name: String) = MaskConfigStore.MaskConfig(
        id = id,
        name = name,
        createdAt = id * 1000L,
        spans = listOf(MaskConfigStore.MaskSpan(0, 1, 3, 0))
    )

    // ── ① 现场回放判定 ──

    @Test
    fun `本会话写下的空现场不得参与回放`() {
        // 组合期带进来的快照为 null = 全新进入：哪怕实时 editorSnapshot 已经被本会话写成
        // 一串空 JSON，也不能当作「有现场」。这是真机 bug 的精确形状。
        assertFalse(
            shouldRestoreMaskSnapshot(
                configId = 3L,
                restoredSnapshot = null,
                snapshotOwnerId = 3L,
                alreadyApplied = false,
            )
        )
    }

    @Test
    fun `快照归属必须与本配置一致`() {
        assertFalse(
            shouldRestoreMaskSnapshot(3L, "{\"levels\":[]}", snapshotOwnerId = 2L, alreadyApplied = false)
        )
        assertTrue(
            shouldRestoreMaskSnapshot(3L, "{\"levels\":[]}", snapshotOwnerId = 3L, alreadyApplied = false)
        )
    }

    @Test
    fun `新建配置一律从空白起步`() {
        // configId<=0（点「新建」）时，快照归属也是 -1，与跨会话残留无法区分 —— 禁止回放
        assertFalse(shouldRestoreMaskSnapshot(0L, "{}", snapshotOwnerId = 0L, alreadyApplied = false))
        assertFalse(shouldRestoreMaskSnapshot(-1L, "{}", snapshotOwnerId = -1L, alreadyApplied = false))
    }

    @Test
    fun `已经回放过一次就不再重复回放`() {
        assertFalse(
            shouldRestoreMaskSnapshot(3L, "{}", snapshotOwnerId = 3L, alreadyApplied = true)
        )
    }

    // ── ② 保存前解析名字（不许把配置改成默认编号）──

    @Test
    fun `编辑已有配置时名字取磁盘原值`() {
        val all = listOf(cfg(1L, "自定义1"), cfg(2L, "自定义2"))
        assertEquals("自定义2", existingMaskConfigName(all, 2L))
    }

    @Test
    fun `名字已被改空时仍取回原名而不是另起编号`() {
        // 回填被跳过 → editingName 为空 → 旧实现会去拿 nextDefaultName() 得到「自定义3」
        val all = listOf(cfg(1L, "自定义1"), cfg(2L, "自定义2"))
        assertEquals("自定义2", existingMaskConfigName(all, 2L))
        assertEquals("", existingMaskConfigName(all, 99L))
        assertEquals("", existingMaskConfigName(all, 0L))
    }

    @Test
    fun `名字带空白时按去空白后的值比较`() {
        val all = listOf(cfg(7L, "  自定义1  "))
        assertEquals("自定义1", existingMaskConfigName(all, 7L))
    }
}
