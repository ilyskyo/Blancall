// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.cloze

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldPersistEditorSnapshot] 回归测试（「点开已保存配置回填丢失」真机 bug）：
 * 编辑现场快照由 LaunchedEffect(editSeq) 驱动，editSeq 初始 0 时 effect 也会执行一次 ——
 * 但此刻磁盘回填尚未执行（LaunchedEffect(articleId, configId) 仍挂起在 IO），
 * 写入的「空现场快照」会抢占恢复判断（restoredSnapshot != null 被误判为旋转恢复），
 * 磁盘回填分支永不执行，编辑器显示空白。契约：仅 editSeq > 0（回填完成 / 任何真实编辑）才写快照。
 */
class CustomClozeEditorSnapshotTest {

    @Test
    fun `初次组合（editSeq=0）：不得写快照（否则抢占磁盘回填致回填丢失）`() {
        assertFalse(shouldPersistEditorSnapshot(0))
    }

    @Test
    fun `回填完成或编辑后（editSeq 大于 0）：写快照（保证旋转与进程重建可恢复现场）`() {
        assertTrue(shouldPersistEditorSnapshot(1))
        assertTrue(shouldPersistEditorSnapshot(2))
    }
}
