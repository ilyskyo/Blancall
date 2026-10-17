// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONObject
import java.io.File

/**
 * 成就「解锁时刻」表：只记 `id → 首次达成时间戳`，规则仍然每次从练习记录现算。
 *
 * 为什么只多记这一件事：[com.ilyskyo.blancall.algorithm.AchievementManager] 有意不持久化解锁态，
 * 让「是否达成」与真实数据强一致（删了文章连带记录一起少时，徽章会跟着退回未解锁而不是赖在盘上）。
 * 那个取舍是对的，别推翻。但它把代价推给了体验：没有「何时获得」这一维，徽章就答不出
 * 「这周新得了几枚」，更没有解锁瞬间的任何反馈 —— 只能陈列，不能奖励。
 *
 * 于是这里只补「何时」：派生负责**是不是**，落盘只负责**什么时候**。两份职责不冲突，
 * 派生退回未解锁时这张表不动（那是历史事实：那一刻确实达成过）。
 *
 * 落盘走 [AtomicFiles]、读写全程在同一把锁内（项目对存储层的统一要求）。
 */
class AchievementTimelineStore private constructor(private val file: File) {

    private val lock = Any()
    private var cache: MutableMap<String, Long>? = null

    /** 粘性写保护：主文件损坏且备份也不可用时拒绝写盘，见 [StoreVersion] 与 MaskConfigStore 的同一坑 */
    private var loadFailed = false

    companion object {
        private const val TAG = "AchievementTimelineStore"
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: AchievementTimelineStore? = null

        fun getInstance(filesDir: File): AchievementTimelineStore =
            instance ?: synchronized(this) {
                instance ?: AchievementTimelineStore(File(filesDir, "achievements.json")).also { it.load() }
            }

        /** 测试用：清掉进程级单例，让每个用例拿到自己目录上的实例 */
        internal fun resetInstanceForTest() { instance = null }
    }

    private fun load() {
        synchronized(lock) {
            if (cache != null) return
            val bak = File(file.parentFile, file.name + ".bak")
            if (file.exists()) {
                try {
                    cache = parse(JSONObject(file.readText()))
                    return
                } catch (_: Exception) {
                    loadFailed = true
                    preserveCorruptFile()
                }
            }
            if (bak.exists()) {
                try {
                    cache = parse(JSONObject(bak.readText()))
                    Log.w(TAG, "主文件不可用，已从备份恢复解锁时间戳")
                    return
                } catch (_: Exception) {
                    // 备份也不可用：保持 loadFailed，宁可本次不落盘也不以空表抹掉历史
                }
            }
            if (loadFailed) return
            cache = mutableMapOf()   // 首次使用：空表可写
        }
    }

    private fun parse(root: JSONObject): MutableMap<String, Long> {
        if (StoreVersion.isFromNewerApp(root.optInt("version", 1), CURRENT_VERSION, TAG)) {
            loadFailed = true
        }
        val out = LinkedHashMap<String, Long>()
        val unlocked = root.optJSONObject("unlocked") ?: return out
        unlocked.keys().forEach { id -> out[id] = unlocked.optLong(id, 0L) }
        return out
    }

    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /**
     * 记下本次达成、但表里还没有的 id，返回**真正新增**的那批（调用方据此给一次解锁反馈）。
     *
     * 这张表第一次建立（盘上还没有这个文件、表也是空的）那一刻不播报：那批 id 是用户早就拿到的
     * 成就，不是刚刚解锁 —— 一次弹一排「新成就」是把历史说成现在，用户会觉得莫名其妙。
     */
    fun recordUnlocked(ids: Set<String>, now: Long = System.currentTimeMillis()): Set<String> {
        if (ids.isEmpty()) return emptySet()
        synchronized(lock) {
            load()
            val table = cache ?: return emptySet()
            if (loadFailed) {
                Log.w(TAG, "解锁时间戳落盘被拒（数据处于危险态），本次只更新内存")
            }
            val firstTable = table.isEmpty() && !file.exists()
            val fresh = ids.filter { !table.containsKey(it) }
            if (fresh.isEmpty() && !firstTable) return emptySet()
            fresh.forEach { table[it] = now }
            if (!loadFailed) writeLocked(table)
            return if (firstTable) emptySet() else fresh.toSet()   // 建表那一刻不播报
        }
    }

    /** 全部已记录过的解锁时刻（`id → epochMilli`） */
    /** 备份导入后重载（B1-3）：清缓存让下次读取走磁盘（lazy cache 语义的最小正确操作） */
    fun reload() {
        synchronized(lock) { cache = null }
    }

    fun snapshot(): Map<String, Long> = synchronized(lock) {
        load()
        cache?.toMap() ?: emptyMap()
    }

    private fun writeLocked(table: Map<String, Long>) {
        try {
            val root = JSONObject().put("version", CURRENT_VERSION)
            val unlocked = JSONObject()
            table.forEach { (id, ts) -> unlocked.put(id, ts) }
            root.put("unlocked", unlocked)
            AtomicFiles.writeTextAtomic(file, root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存解锁时间戳失败", e)
        }
    }
}
