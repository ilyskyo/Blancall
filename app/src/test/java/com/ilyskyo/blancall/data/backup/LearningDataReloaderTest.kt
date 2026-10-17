// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LearningDataReloader] 的清单守卫（B1-3 的「漏项守卫」）：
 * 重载覆盖必须与备份清单**完全相等** —— 漏一个 Store 的后果是它在下次写盘时
 * 用旧内存覆盖刚恢复的数据（这正是「导入必须重启」的根因），必须机器钉死。
 */
class LearningDataReloaderTest {

    @Test
    fun `重载覆盖清单与备份清单完全相等`() {
        assertEquals(
            "重载清单与 BackupManager.ROOT_DATA_FILES 不一致：" +
                "新增备份文件必须同时决定它「需要重载动作」还是「读取即读盘」",
            BackupManager.ROOT_DATA_FILES.toSet(),
            LearningDataReloader.COVERED_FILES
        )
    }

    @Test
    fun `动作清单与免动作清单不相交且并集即全量`() {
        val requires = LearningDataReloader.REQUIRES_RELOAD_FILES
        val noAction = LearningDataReloader.NO_ACTION_FILES
        assertTrue(
            "同一文件不能既声明要动作又声明不要：${requires intersect noAction}",
            (requires intersect noAction).isEmpty()
        )
        assertEquals(
            LearningDataReloader.COVERED_FILES.size,
            requires.size + noAction.size
        )
    }
}
