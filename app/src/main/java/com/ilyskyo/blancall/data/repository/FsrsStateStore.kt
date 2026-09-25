// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.util.AtomicFiles
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
 *
 * 「句子卡片」的句子级状态复用同一文件、同一把锁与同一套备份机制，
 * 以字符串命名空间键 `s:<articleId>:<hash16>` 区分（见 [sentenceStates]）：
 * - 数值键 → 文章级 states（原读写路径与语义完全不变，[allStates] 只含文章级）；
 * - `s:` 前缀键 → 句子级 sentenceStates；
 * - 其余键静默忽略（前后兼容：旧版本读非数值键会忽略，写回只丢句子键、不回损文章状态）。
 */
class FsrsStateStore private constructor(private val file: File) {

    private val states = ConcurrentHashMap<Long, FsrsEngine.CardState>()

    /** 句子级记忆状态：键 = `s:<articleId>:<hash16>`（hash16 = SHA-256(句文) 前 8 字节 hex） */
    private val sentenceStates = ConcurrentHashMap<String, FsrsEngine.CardState>()
    // 加载完成门闩：后台加载完成后 countDown；save/remove 前需等加载完成，避免 persist 覆盖丢旧状态
    private val loadLatch = CountDownLatch(1)
    // 落盘互斥：persist() 是「全量序列化 → 写 tmp → rename」。
    // 若并发 save 不加锁，会出现：A 序列化完 {1:X} 后被抢占 → B 完整写入 {1:X,2:Y} →
    // A 恢复并把 tmpA rename 覆盖 → 文章 2 的状态凭空丢失。故「改 map + 落盘」必须整体串行。
    private val persistLock = Any()

    companion object {
        /** 句子键前缀（命名空间分隔）；单一来源在 [SentenceSelector.SENTENCE_KEY_PREFIX] */
        const val SENTENCE_KEY_PREFIX = SentenceSelector.SENTENCE_KEY_PREFIX

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
        val bak = File(file.parentFile, file.name + ".bak")
        try {
            if (!file.exists()) {
                // 主文件缺失：尝试从备份恢复（与 ArticleStorage / RecordRepository 行为一致）
                if (bak.exists()) {
                    parseInto(bak.readText())
                    Log.w("FsrsStateStore", "主文件丢失，已从备份恢复 ${states.size} 条记忆状态")
                }
                return
            }
            parseInto(file.readText())
        } catch (e: Exception) {
            // 主文件损坏：先回读备份，避免整库记忆状态静默归零
            Log.e("FsrsStateStore", "加载 FSRS 状态失败，尝试从备份恢复", e)
            try {
                if (bak.exists()) {
                    synchronized(persistLock) { states.clear() }
                    parseInto(bak.readText())
                    Log.w("FsrsStateStore", "已从备份恢复 ${states.size} 条记忆状态")
                }
            } catch (e2: Exception) {
                Log.e("FsrsStateStore", "备份恢复也失败，将从空状态开始", e2)
            }
        }
    }

    /** 解析 JSON 并灌入内存状态（单条损坏不影响其余，逐键容忍；句子键与文章键按前缀分流） */
    private fun parseInto(json: String) {
        val obj = JSONObject(json)
        obj.keys().forEach { key ->
            val o = obj.optJSONObject(key) ?: return@forEach
            val state = FsrsEngine.CardState(
                difficulty = o.optDouble("difficulty", 0.0),
                stability = o.optDouble("stability", 0.0),
                due = o.optLong("due", 0L),
                lastReview = o.optLong("lastReview", 0L),
                reviewCount = o.optInt("reviewCount", 0),
                lapses = o.optInt("lapses", 0)
            )
            if (key.startsWith(SENTENCE_KEY_PREFIX)) {
                if (isSentenceKey(key)) sentenceStates[key] = state
            } else {
                val id = key.toLongOrNull() ?: return@forEach
                states[id] = state
            }
        }
    }

    /** 句子键合法性：`s:<数字articleId>:<非空hash>`，防止脏键污染句子表 */
    private fun isSentenceKey(key: String): Boolean {
        val parts = key.split(':')
        return parts.size == 3 && parts[1].toLongOrNull() != null && parts[2].isNotBlank()
    }

    private fun stateToJson(s: FsrsEngine.CardState): JSONObject = JSONObject()
        .put("difficulty", s.difficulty).put("stability", s.stability)
        .put("due", s.due).put("lastReview", s.lastReview)
        .put("reviewCount", s.reviewCount).put("lapses", s.lapses)

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            states.forEach { (id, s) -> json.put(id.toString(), stateToJson(s)) }
            // 句子级状态与文章级同文件共存，键即命名空间（旧版本读非数值键时忽略）
            sentenceStates.forEach { (key, s) -> json.put(key, stateToJson(s)) }
            // 原子写 + fsync + 备份轮换统一到 AtomicFiles（tmp → fsync → .bak → rename → 目录 fsync）
            AtomicFiles.writeTextAtomic(file, json.toString())
        } catch (e: Exception) {
            Log.e("FsrsStateStore", "保存 FSRS 状态失败", e)
        }
    }

    /** 获取某文章的记忆状态；未练习过返回 null */
    fun get(articleId: Long): FsrsEngine.CardState? = states[articleId]

    /** 保存（更新）某文章的记忆状态；先等初始加载完成，避免写入时覆盖未加载的旧状态 */
    fun save(articleId: Long, state: FsrsEngine.CardState) {
        loadLatch.await()
        // 改 map 与落盘整体串行，避免并发 save 互相覆盖（见 persistLock 注释）
        synchronized(persistLock) {
            states[articleId] = state
            persist()
        }
    }

    /** 删除某文章的记忆状态（文章删除时连带清理，避免孤儿状态） */
    fun remove(articleId: Long) {
        loadLatch.await()
        synchronized(persistLock) {
            if (states.remove(articleId) != null) persist()
        }
    }

    /** 全部状态（只读视图，供预测与列表页面使用）；仅文章级，语义与历史一致 */
    fun allStates(): Map<Long, FsrsEngine.CardState> = states.toMap()

    // ────────────────────────────────────────────────
    // 句子级状态（「句子卡片」专用；与文章级互不影响）
    // ────────────────────────────────────────────────

    /** 获取某句子的记忆状态；未练习过返回 null */
    fun getSentence(key: String): FsrsEngine.CardState? = sentenceStates[key]

    /** 保存（更新）某句子的记忆状态；键不合法时直接忽略（防脏键写入） */
    fun saveSentence(key: String, state: FsrsEngine.CardState) {
        if (!isSentenceKey(key)) return
        loadLatch.await()
        synchronized(persistLock) {
            sentenceStates[key] = state
            persist()
        }
    }

    /** 删除某文章的全部句子状态（文章删除时连带清理，避免孤儿状态） */
    fun removeSentencesForArticle(articleId: Long) {
        loadLatch.await()
        synchronized(persistLock) {
            val prefix = "$SENTENCE_KEY_PREFIX$articleId:"
            val removed = sentenceStates.keys.filter { it.startsWith(prefix) }
            if (removed.isEmpty()) return
            removed.forEach { sentenceStates.remove(it) }
            persist()
        }
    }

    /** 全部句子级状态（只读视图，供到期判定与句子记忆统计使用） */
    fun allSentenceStates(): Map<String, FsrsEngine.CardState> = sentenceStates.toMap()
}
