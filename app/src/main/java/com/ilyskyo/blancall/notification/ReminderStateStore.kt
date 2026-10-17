// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.notification

import android.util.Log
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.data.repository.StoreVersion
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「今天已经发过哪些提醒」的落盘去重表（reminder_state.json）。
 *
 * 为什么需要：Worker 是自调度的，且用户改时间、系统补跑、手动触发都可能让同一条提醒在一天内
 * 跑多次 —— 没有去重就会「一天三遍『今天还没背』」，是比不提醒更糟的体验。旧实现完全没有这一层。
 *
 * 「今天」的口径与全 app 一致：**设备时区的日历天**，直接复用 [DayBucket.localDate]，
 * 不自己按 24 小时算（跨时区 / 夏令时会把夜里 23:00 与 01:00 分到不同一侧）。
 * 跨天失效是**比对**出来的（存盘里那份 `day` 与当前天不等即视为全新的一天），
 * 而不是靠定时任务清理 —— 定时清理一旦没跑成就永远不失效。
 *
 * 落盘走 [AtomicFiles]，读写全程在同一把锁内（项目对存储层的统一要求）；
 * 版本守卫与粘性写保护沿用 [StoreVersion] + AchievementTimelineStore 的同一范式。
 */
class ReminderStateStore private constructor(private val file: File) {

    /** 一天的去重集合：`day`（yyyy-MM-dd）+ 当天已发原因名。 */
    private class Cache(val day: String, val sent: MutableSet<String>)

    private val lock = Any()
    private var cache: Cache? = null

    /**
     * 粘性写保护：主文件损坏且备份也不可用时拒绝写盘，避免以空表抹掉盘上那份。
     * 与 [com.ilyskyo.blancall.data.repository.AchievementTimelineStore] 同一取舍。
     */
    private var loadFailed = false

    companion object {
        private const val TAG = "ReminderStateStore"
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: ReminderStateStore? = null

        fun getInstance(filesDir: File): ReminderStateStore =
            instance ?: synchronized(this) {
                instance ?: ReminderStateStore(File(filesDir, "reminder_state.json")).also { it.load() }
            }

        /** 测试用：清掉进程级单例，让每个用例拿到自己目录上的实例 */
        internal fun resetInstanceForTest() {
            instance = null
        }
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
                    Log.w(TAG, "主文件不可用，已从备份恢复提醒去重状态")
                    return
                } catch (_: Exception) {
                    // 备份也不可用：保持 loadFailed，本次不落盘
                }
            }
            if (loadFailed) return
            cache = Cache(day = "", sent = mutableSetOf())   // 首次使用：空表可写
        }
    }

    private fun parse(root: JSONObject): Cache {
        if (StoreVersion.isFromNewerApp(root.optInt("version", 1), CURRENT_VERSION, TAG)) {
            loadFailed = true
        }
        val day = root.optString("day", "")
        val sent = mutableSetOf<String>()
        root.optJSONArray("sent")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "")
                if (name.isNotBlank()) sent.add(name)
            }
        }
        return Cache(day, sent)
    }

    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /**
     * 该原因今天是否还该发。
     *
     * 危险态（表不可用）一律返回 true：宁可偶尔多发一次，也不要因为一次损坏就永久吞掉提醒。
     */
    fun shouldSend(reason: ReminderReason, now: Long): Boolean = synchronized(lock) {
        load()
        val today = DayBucket.localDate(now).toString()
        val c = cache ?: return true
        if (c.day != today) return true   // 跨天：昨天发过不算数
        return reason.name !in c.sent
    }

    /** 记下「今天已发过该原因」。跨天时先换一张空表再记（`Cache.day` 是 val，跨天靠新建实例）。 */
    fun markSent(reason: ReminderReason, now: Long) = synchronized(lock) {
        load()
        val today = DayBucket.localDate(now).toString()
        // 同一张表只在「同一天」复用；否则（首次 / 跨天）新建，旧记录自然失效
        val c = cache?.takeIf { it.day == today }
            ?: Cache(today, mutableSetOf()).also { cache = it }
        c.sent.add(reason.name)
        if (!loadFailed) writeLocked(c)
    }

    /** 今天已发过的原因（供调试与测试断言；未知名字忽略，前后兼容）。 */
    fun sentToday(now: Long): Set<ReminderReason> = synchronized(lock) {
        load()
        val today = DayBucket.localDate(now).toString()
        val c = cache ?: return emptySet()
        if (c.day != today) return emptySet()
        c.sent.mapNotNull { name ->
            runCatching { ReminderReason.valueOf(name) }.getOrNull()
        }.toSet()
    }

    private fun writeLocked(c: Cache) {
        try {
            val root = JSONObject()
                .put("version", CURRENT_VERSION)
                .put("day", c.day)
            val arr = JSONArray()
            c.sent.forEach { arr.put(it) }
            root.put("sent", arr)
            AtomicFiles.writeTextAtomic(file, root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "保存提醒去重状态失败", e)
        }
    }
}
