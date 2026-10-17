// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import org.json.JSONObject

/**
 * 「盘上的数据比这个 app 新」的判定 —— 各 Store 的版本守卫共用的一份。
 *
 * 为什么要专门立这一条：所有 Store 的 root 里都写着 `version`，但**没有一个**会拒绝高于自己的版本。
 * 一旦用户导入更高版本的备份、或装回了旧包，读侧靠 `optXxx` 默认值兜底继续跑，写侧再把条目重建成
 * 本版本认识的样子 —— 新字段被静默抹掉，用户看不出任何东西丢过。
 * 备份恢复那条路本来就有 `formatVersion` 相等校验，但学习数据主文件这一层是空转的。
 *
 * 拒读写而不是「尽力读」的理由：本版本不认识的那些字段是别人的功能，
 * 覆盖一次就永久没了；而空库+拒写至少把原文件完整地留在盘上，等升级回来还能救。
 */
internal object StoreVersion {

    /** 落盘字段名，全部 Store 一致 */
    const val FIELD = "version"

    /**
     * 盘上版本高于本版本认识的 [currentVersion] 时返回 true，并**已经在 [tag] 下留下原因**。
     *
     * 调用方拿到 true 之后要做两件事：置自己的 `loadFailed`（拒绝写盘）、把 root 原样交回
     * （不 migrate、不补字段 —— 任何一次补字段都是在替未来的版本做决定）。
     */
    fun isFromNewerApp(diskVersion: Int, currentVersion: Int, tag: String): Boolean {
        if (diskVersion <= currentVersion) return false
        Log.e(
            tag,
            "盘上的 $FIELD=$diskVersion 高于本版本认识的 $currentVersion —— " +
                "多半是导入了更新版本的备份或装回了旧包。" +
                "本次拒绝读写：本版本不认识的那些字段若在重建条目时被丢掉，就再也回不来了。"
        )
        return true
    }

    /** 从 root 上读版本号；老文件没这个字段时按 1 算（与既有 migrate 的口径一致） */
    fun diskVersion(root: JSONObject): Int = root.optInt(FIELD, 1)
}
