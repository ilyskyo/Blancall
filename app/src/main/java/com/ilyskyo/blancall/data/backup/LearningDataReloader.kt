// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.backup

import android.content.Context
import com.ilyskyo.blancall.data.repository.AchievementTimelineStore
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.data.repository.RecordRepository

/**
 * 备份导入后的全量内存重载（B1-3）：取消「导入必须重启」。
 *
 * ## 为什么必须「全量」
 * 部分 Store 的内存镜像只在进程启动时加载一次；导入恢复替换磁盘文件后，任何**没有参与重载**
 * 的常驻 Store 都会在下次写入时用旧内存覆盖刚恢复的数据 —— 这就是「导入后必须重启」的由来。
 * 这里维护一份与 [BackupManager.ROOT_DATA_FILES] 对齐的重载清单，并由
 * `LearningDataReloaderTest` 钉死两份清单相等：新增备份文件而忘配重载动作（或反之）会直接红。
 *
 * ## 为什么一半文件没有动作
 * 这些 Store 的读取路径本就是「每次读盘」（TagStore / CustomClozeStore / SentenceCardStore）
 * 或带磁盘指纹校验的缓存（HomeLayoutStore：mtime+size 变了自动失效），文件被替换后它们
 * 天然读到新数据。它们仍必须进 [NO_ACTION_FILES] 清单 —— 占位本身是「已核查、不需要动作」的证据。
 *
 * ## 失败处理
 * 单个 Store 重载失败会被计数、不中断其余文件：坏掉的那个留下的是旧内存（下次启动依然会从
 * 新磁盘加载），总比一个坏文件挡住其余所有恢复要好。
 */
internal object LearningDataReloader {

    /** 需要真实重载动作的文件（与 [reloadAll] 的动作键一一对应） */
    internal val REQUIRES_RELOAD_FILES = setOf(
        "articles.json", "records.json", "fsrs_state.json",
        "mask_config.json", "reader_prefs.json", "achievements.json",
        // ReminderPrefs 的考试日期镜像：恢复后必须重读，否则内存里仍是旧日期
        "exam_goal.json",
    )

    /** 已核查「无需动作」的文件：读取即读盘，或缓存带磁盘指纹自动失效 */
    internal val NO_ACTION_FILES = setOf(
        // CustomClozeStore：每次 readRoot() 读盘
        "custom_cloze.json",
        // HomeLayoutStore：cachedCards 由 mtime+size 指纹校验，盘变即失效
        "home_layout.json",
        // SentenceCardStore：每次 readLocked() 读盘
        "sentence_card.json",
        // TagStore：每次 readDataLocked() 读盘
        "tags.json",
    )

    /** 重载覆盖的备份文件全集：守卫测试断言它与 [BackupManager.ROOT_DATA_FILES] 完全相等 */
    val COVERED_FILES: Set<String> = REQUIRES_RELOAD_FILES + NO_ACTION_FILES

    /**
     * 重载全部学习数据。返回失败个数（0 = 全部成功）。
     *
     * ⚠️ 维护约定：[REQUIRES_RELOAD_FILES] 的每一项必须在这里有同名动作；
     * 新增备份文件时三处一起改 —— `ROOT_DATA_FILES` / 本清单 / 本函数。
     */
    suspend fun reloadAll(context: Context): Int {
        val filesDir = context.filesDir
        var failures = 0
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不是「这个 Store 重载失败」：继续重载其余 Store 反而会在
                // 半取消状态下继续改内存。原样上抛，由调用方决定是否 NonCancellable。
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        attempt { ArticleRepository.getInstance(filesDir.resolve("articles.json").absolutePath).reload() }
        attempt { RecordRepository.getInstance(filesDir.resolve("records.json").absolutePath).reload() }
        attempt { FsrsStateStore.getInstance(filesDir.resolve("fsrs_state.json").absolutePath).reload() }
        attempt { MaskConfigStore.getInstance(filesDir).reload() }
        attempt { ReaderPrefsStore.getInstance(context).reload() }
        attempt { AchievementTimelineStore.getInstance(filesDir).reload() }
        attempt { com.ilyskyo.blancall.ui.theme.ReminderPrefs.reloadExamGoal() }
        return failures
    }
}
