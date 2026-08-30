// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.algorithm.FsrsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * FSRS 记忆状态持久化存储（fsrs_state.json）。
 *
 * 以文章 id 为键保存 [FsrsEngine.CardState]，练习完成时更新、页面查询时读取。
 * 文件格式：{"articleId": {"difficulty":..,"stability":..,"due":..,"lastReview":..,"reviewCount":..,"lapses":..}}
 */
class FsrsStateStore private constructor(private val file: File) {

    private val states = ConcurrentHashMap<Long, FsrsEngine.CardState>()
    // 加载完成门闩：后台加载完成后 countDown；save/remove 前需等加载完成，避免 persist 覆盖丢旧状态
    private val loadLatch = CountDownLatch(1)

    companion object {
        @Volatile
        private var instance: FsrsStateStore? = null

        @JvmStatic
        fun getInstance(path: String): FsrsStateStore =
            instance ?: synchronized(this) {
                instance ?: FsrsStateStore(File(path)).also { it.startLoad() }
            }
    }

    /** 后台线程加载，避免首次访问（常在 UI 线程）同步读文件+解析 JSON 造成卡顿 */
    private fun startLoad() {
        Thread {
            try {
                load()
            } finally {
                loadLatch.countDown()
            }
        }.start()
    }

    /** 挂起直至初始加载完成（列表/统计页首帧后刷新状态用） */
    suspend fun awaitLoaded() {
        withContext(Dispatchers.IO) { loadLatch.await() }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.keys().forEach { key ->
                val id = key.toLongOrNull() ?: return@forEach
                val obj = json.optJSONObject(key) ?: return@forEach
                states[id] = FsrsEngine.CardState(
                    difficulty = obj.optDouble("difficulty", 0.0),
                    stability = obj.optDouble("stability", 0.0),
                    due = obj.optLong("due", 0L),
                    lastReview = obj.optLong("lastReview", 0L),
                    reviewCount = obj.optInt("reviewCount", 0),
                    lapses = obj.optInt("lapses", 0)
                )
            }
        } catch (_: Exception) {
            // 状态文件损坏时忽略，全部重新学习
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            states.forEach { (id, s) ->
                json.put(id.toString(), JSONObject()
                    .put("difficulty", s.difficulty).put("stability", s.stability)
                    .put("due", s.due).put("lastReview", s.lastReview)
                    .put("reviewCount", s.reviewCount).put("lapses", s.lapses))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            val mainFile = file
            if (mainFile.exists()) {
                val bak = File(file.parentFile, file.name + ".bak")
                if (bak.exists()) bak.delete()
                mainFile.renameTo(bak)
            }
            if (!tmp.renameTo(mainFile)) {
                Log.e("FsrsStateStore", "保存 FSRS 状态失败：重命名临时文件失败: ${tmp.absolutePath} -> ${mainFile.absolutePath}")
                return
            }
        } catch (e: Exception) {
            Log.e("FsrsStateStore", "保存 FSRS 状态失败", e)
        }
    }

    /** 获取某文章的记忆状态；未练习过返回 null */
    fun get(articleId: Long): FsrsEngine.CardState? = states[articleId]

    /** 保存（更新）某文章的记忆状态；先等初始加载完成，避免写入时覆盖未加载的旧状态 */
    fun save(articleId: Long, state: FsrsEngine.CardState) {
        loadLatch.await()
        states[articleId] = state
        persist()
    }

    /** 删除某文章的记忆状态（文章删除时连带清理，避免孤儿状态） */
    fun remove(articleId: Long) {
        loadLatch.await()
        if (states.remove(articleId) != null) persist()
    }

    /** 全部状态（只读视图，供预测与列表页面使用） */
    fun allStates(): Map<Long, FsrsEngine.CardState> = states.toMap()
}
