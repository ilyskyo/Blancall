// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.algorithm.StudyFlow
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * FSRS 记忆状态持久化存储（fsrs_state.json）。
 *
 * 以文章 id 为键保存 [FsrsEngine.CardState]，练习完成时更新、页面查询时读取。
 * 文件格式：{"version":2,"articleId": {"difficulty":..,"stability":..,"due":..,"lastReview":..,"reviewCount":..,"lapses":..},"paused":{"articleId":{"at":..,"remainingMs":..}}}
 *
 * 版本 2 新增 `paused` 表（学习任务流的暂停快照）。抬版本号是为了让旧版本读到本文件后
 * **拒绝写盘**，而不是在整本重写时把暂停表静默抹掉 —— 与 StoreVersion 的既有约定一致。
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

    /**
     * 文章级暂停快照：暂停时冻结「离到期还有多久」，恢复时从当下接上。
     * 与 states 同文件、同锁：暂停和到期状态必须一起落盘，否则会出现
     * 「due 改了但暂停没存上」这种恢复后凭空早到/晚到的分叉。
     */
    private val pausedArticles = ConcurrentHashMap<Long, StudyFlow.Pause>()

    /**
     * 状态变更计数：界面用它刷新自己的快照。
     * 从「任务流」暂停/改期后，首页、列表、统计页都还在组合里，没有导航重建可依赖；
     * 没有这个信号，用户会看到「任务流说暂停了，首页仍显示待复习」。
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * 盘上那份来自更高版本 ⇒ 本版本拒绝对它做整本重写。
     * 由后台加载线程在 [parseInto] 里置位，之后每一次 [persist] 都先看它。
     */
    @Volatile
    private var fromNewerVersion = false
    // 加载完成门闩：后台加载完成后 countDown；save/remove 前需等加载完成，避免 persist 覆盖丢旧状态
    private val loadLatch = CountDownLatch(1)
    // 落盘互斥：persist() 是「全量序列化 → 写 tmp → rename」。
    // 若并发 save 不加锁，会出现：A 序列化完 {1:X} 后被抢占 → B 完整写入 {1:X,2:Y} →
    // A 恢复并把 tmpA rename 覆盖 → 文章 2 的状态凭空丢失。故「改 map + 落盘」必须整体串行。
    private val persistLock = Any()

    companion object {
        /** 句子键前缀（命名空间分隔）；单一来源在 [SentenceSelector.SENTENCE_KEY_PREFIX] */
        const val SENTENCE_KEY_PREFIX = SentenceSelector.SENTENCE_KEY_PREFIX

        /**
         * 磁盘格式版本。以前这个文件**根本没有版本字段**，于是「装回旧包 / 导入更高版本备份」时
         * 本版本会照写不误 —— 每条卡片状态都按整本重写，新版认识的字段就这样被抹平。
         * 现在写侧带版本、读侧高于本版本就拒绝写盘（见 [StoreVersion]）。
         */
        const val CURRENT_VERSION = 2

        /** 版本键名：与卡片键的命名空间不冲突（卡片键要么是数字、要么以 s: 开头，都不是对象值） */
        private const val VERSION_KEY = "version"

        /** 暂停表键名：值是一层对象（articleId → {at, remainingMs}），不与卡片键同名 */
        private const val PAUSE_KEY = "paused"

        @Volatile
        private var instance: FsrsStateStore? = null

        @JvmStatic
        fun getInstance(path: String): FsrsStateStore =
            instance ?: synchronized(this) {
                instance ?: FsrsStateStore(File(path)).also { it.startLoad() }
            }

        /**
         * 测试用隔离实例：`getInstance` 是进程级单例（只认首个路径），
         * reload/清空类测试若共用单例会把状态污染给同进程的其他用例。
         */
        @androidx.annotation.VisibleForTesting
        internal fun createForTest(path: String): FsrsStateStore =
            FsrsStateStore(File(path)).also { it.startLoad() }
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

    /** 解析结果先落在临时表里，最后在锁内整体换入 —— 绝不让半灌入的 map 被并发 save 写回磁盘 */
    private data class ParsedSnapshot(
        val states: Map<Long, FsrsEngine.CardState>,
        val sentenceStates: Map<String, FsrsEngine.CardState>,
        val pausedArticles: Map<Long, StudyFlow.Pause>,
        val fromNewerVersion: Boolean,
    )

    private fun load(fallbackToBackup: Boolean = true) {
        val bak = File(file.parentFile, file.name + ".bak")
        try {
            if (!file.exists()) {
                if (fallbackToBackup && bak.exists()) {
                    applySnapshot(parseSnapshot(bak.readText()))
                    Log.w("FsrsStateStore", "主文件丢失，已从备份恢复 ${states.size} 条记忆状态")
                } else {
                    applySnapshot(emptySnapshot())
                }
                return
            }
            applySnapshot(parseSnapshot(file.readText()))
        } catch (e: Exception) {
            // 主文件损坏：先回读备份，避免整库记忆状态静默归零
            Log.e("FsrsStateStore", "加载 FSRS 状态失败，尝试从备份恢复", e)
            if (fallbackToBackup) {
                try {
                    if (bak.exists()) {
                        applySnapshot(parseSnapshot(bak.readText()))
                        Log.w("FsrsStateStore", "已从备份恢复 ${states.size} 条记忆状态")
                        return
                    }
                } catch (e2: Exception) {
                    Log.e("FsrsStateStore", "备份恢复也失败，将从空状态开始", e2)
                }
            }
            applySnapshot(emptySnapshot())
        }
    }

    private fun emptySnapshot() = ParsedSnapshot(emptyMap(), emptyMap(), emptyMap(), false)

    /** 在锁内整体替换三张表：并发 save 只能看到「换入前」或「换入后」的完整快照 */
    private fun applySnapshot(snapshot: ParsedSnapshot) {
        synchronized(persistLock) {
            states.clear()
            states.putAll(snapshot.states)
            sentenceStates.clear()
            sentenceStates.putAll(snapshot.sentenceStates)
            pausedArticles.clear()
            pausedArticles.putAll(snapshot.pausedArticles)
            fromNewerVersion = snapshot.fromNewerVersion
        }
    }

    /**
     * 备份导入后重载：先等首载完成，再按「导入语义」重读磁盘。
     *
     * 导入后主文件缺失/损坏就是「备份里没有这份数据」，必须清空且**不回读 `.bak`** ——
     * 那里是导入前的旧状态。解析全部在锁外完成，最后一次性换入，避免并发 save 把
     * 半灌入的表写回磁盘。
     */
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            loadLatch.await()
            load(fallbackToBackup = false)
        }
        bumpRevision()
    }

    /** 解析 JSON 并灌入内存状态（单条损坏不影响其余，逐键容忍；句子键与文章键按前缀分流） */
    private fun parseSnapshot(json: String): ParsedSnapshot {
        val obj = JSONObject(json)
        val newer = StoreVersion.isFromNewerApp(obj.optInt(VERSION_KEY, 1), CURRENT_VERSION, "FsrsStateStore")
        val parsedStates = HashMap<Long, FsrsEngine.CardState>()
        val parsedSentences = HashMap<String, FsrsEngine.CardState>()
        val parsedPauses = HashMap<Long, StudyFlow.Pause>()
        obj.keys().forEach { key ->
            val o = obj.optJSONObject(key) ?: return@forEach
            if (key == PAUSE_KEY) {
                o.keys().forEach { idKey ->
                    val id = idKey.toLongOrNull() ?: return@forEach
                    val item = o.optJSONObject(idKey) ?: return@forEach
                    val at = item.optLong("at", 0L)
                    val remaining = item.optLong("remainingMs", 0L).coerceAtLeast(0L)
                    if (at > 0L) parsedPauses[id] = StudyFlow.Pause(at, remaining)
                }
                return@forEach
            }
            val state = FsrsEngine.CardState(
                difficulty = o.optDouble("difficulty", 0.0),
                stability = o.optDouble("stability", 0.0),
                due = o.optLong("due", 0L),
                lastReview = o.optLong("lastReview", 0L),
                reviewCount = o.optInt("reviewCount", 0),
                lapses = o.optInt("lapses", 0),
                lastRating = o.optString("lastRating", "")
            )
            if (key.startsWith(SENTENCE_KEY_PREFIX)) {
                if (isSentenceKey(key)) parsedSentences[key] = state
            } else {
                val id = key.toLongOrNull() ?: return@forEach
                parsedStates[id] = state
            }
        }
        return ParsedSnapshot(parsedStates, parsedSentences, parsedPauses, newer)
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
        .put("lastRating", s.lastRating)

    private fun persist() {
        try {
            if (fromNewerVersion) {
                // 盘上那份是更高版本写的：本版本不认识它的字段，整本重写会把那些字段抹平。
                // 宁可停在「这次没存上」并留下日志，也不要静默改数据 —— 升级回来原文件还是完整的
                Log.e("FsrsStateStore", "磁盘格式版本高于本应用，拒绝写盘（内存状态仍可用，退出后不保留）")
                return
            }
            file.parentFile?.mkdirs()
            val json = JSONObject().put(VERSION_KEY, CURRENT_VERSION)
            states.forEach { (id, s) -> json.put(id.toString(), stateToJson(s)) }
            // 句子级状态与文章级同文件共存，键即命名空间（旧版本读非数值键时忽略）
            sentenceStates.forEach { (key, s) -> json.put(key, stateToJson(s)) }
            // 暂停表：旧版本会忽略这个非数值、非 s: 的键；本版本恢复后仍能原样读回
            if (pausedArticles.isNotEmpty()) {
                val paused = JSONObject()
                pausedArticles.forEach { (id, info) ->
                    paused.put(
                        id.toString(),
                        JSONObject()
                            .put("at", info.pausedAt)
                            .put("remainingMs", info.remainingMs)
                    )
                }
                json.put(PAUSE_KEY, paused)
            }
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
            bumpRevision()
        }
    }

    /** 删除某文章的记忆状态（文章删除时连带清理，避免孤儿状态） */
    fun remove(articleId: Long) {
        loadLatch.await()
        synchronized(persistLock) {
            val hadState = states.remove(articleId) != null
            val hadPause = pausedArticles.remove(articleId) != null
            if (hadState || hadPause) {
                persist()
                bumpRevision()
            }
        }
    }

    // ────────────────────────────────────────────────
    // 学习任务流：暂停 / 恢复 / 改期
    // ────────────────────────────────────────────────

    fun isArticlePaused(articleId: Long): Boolean = pausedArticles.containsKey(articleId)

    /** 暂停快照（只读拷贝）：界面用它算「暂停中的任务预计哪天回来」 */
    fun pausedArticles(): Map<Long, StudyFlow.Pause> = pausedArticles.toMap()

    /** 暂停中的文章 id 集合：句子卡、提醒等按文章归属过滤时用 */
    fun pausedArticleIds(): Set<Long> = pausedArticles.keys.toSet()

    /**
     * 暂停某文章的 FSRS 调度：冻结「此刻离 due 还有多久」。
     * 未练习过（reviewCount=0）的文章没有可冻结的计划，返回 false。
     * 阻塞等首次加载完成，调用方需在 IO 线程执行（与 [save] 同约定）。
     */
    fun pauseArticle(articleId: Long, now: Long = System.currentTimeMillis()): Boolean {
        loadLatch.await()
        synchronized(persistLock) {
            val state = states[articleId] ?: return false
            if (state.reviewCount <= 0) return false
            if (pausedArticles.containsKey(articleId)) return true
            pausedArticles[articleId] = StudyFlow.pauseOf(state, now)
            persist()
            bumpRevision()
            return true
        }
    }

    /**
     * 恢复某文章的调度：把冻结的剩余期从当下接上；暂停期间手动练过则尊重那次推进。
     * 返回是否真的从暂停态恢复。
     */
    fun resumeArticle(articleId: Long, now: Long = System.currentTimeMillis()): Boolean {
        loadLatch.await()
        synchronized(persistLock) {
            val pause = pausedArticles.remove(articleId) ?: return false
            val state = states[articleId]
            if (state != null) {
                states[articleId] = state.copy(due = StudyFlow.resumedDue(state, pause, now))
            }
            persist()
            bumpRevision()
            return true
        }
    }

    /**
     * 调整某文章的下次复习时间。
     * 暂停中的任务调整的是「恢复后落到哪」：冻结剩余期改成从此刻到目标日期，
     * 保证界面上看到的目标与实际恢复落点一致；未暂停则直接改 due。
     */
    fun rescheduleArticle(
        articleId: Long,
        due: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        loadLatch.await()
        synchronized(persistLock) {
            val state = states[articleId] ?: return false
            if (state.reviewCount <= 0) return false
            val target = due.coerceAtLeast(0L)
            if (pausedArticles.containsKey(articleId)) {
                val pause = pausedArticles.getValue(articleId)
                pausedArticles[articleId] = pause.copy(remainingMs = (target - now).coerceAtLeast(0L))
                // 暂停期间手动练过：展示口径已经是 state.due（见 StudyFlow.projectedDue），
                // 改期必须同步写 state.due，否则界面上看到的目标与恢复后的落点会分叉。
                if (state.lastReview > pause.pausedAt) {
                    states[articleId] = state.copy(due = target)
                }
            } else {
                states[articleId] = state.copy(due = target)
            }
            persist()
            bumpRevision()
            return true
        }
    }

    private fun bumpRevision() {
        _revision.update { it + 1L }
    }

    /**
     * 全部状态（只读视图，供预测与列表页面使用）；仅文章级，语义与历史一致。
     * 必须逐条 copy：CardState 的字段全是 var，`toMap()` 只复制条目引用，
     * 调用方拿到的是内存表里同一个实例 —— 改了就不落盘，视图与盘立即分叉。
     */
    fun allStates(): Map<Long, FsrsEngine.CardState> =
        states.toMap().mapValues { it.value.copy() }

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
            bumpRevision()
        }
    }

    /**
     * 批量保存句子级状态：一次校验、一次全量序列化、一次 fsync、一次 revision。
     *
     * 逐句调用 [saveSentence] 时，一次练习提交会触发 N 次整文件重写 + `.bak` 轮换 +
     * N 次 revision —— 首页、任务流、列表都会被叫醒 N 次。键不合法仍按单条语义静默跳过。
     */
    fun saveSentences(updates: Map<String, FsrsEngine.CardState>) {
        if (updates.isEmpty()) return
        loadLatch.await()
        synchronized(persistLock) {
            var changed = false
            updates.forEach { (key, state) ->
                if (isSentenceKey(key)) {
                    sentenceStates[key] = state
                    changed = true
                }
            }
            if (!changed) return
            persist()
            bumpRevision()
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
            bumpRevision()
        }
    }

    /** 全部句子级状态（只读视图，供到期判定与句子记忆统计使用）；逐条 copy 的理由同 [allStates] */
    fun allSentenceStates(): Map<String, FsrsEngine.CardState> =
        sentenceStates.toMap().mapValues { it.value.copy() }

    /**
     * 定向删除若干个句子态键，返回实际删掉的条数（供「改文后已清理 N 句」提示显示真实数）。
     *
     * 与 [removeSentencesForArticle] 的分工：那个是「整篇没了」时的连带清理，
     * 这个是「文章还在、句子没了」——键由调用方（[com.ilyskyo.blancall.algorithm.SentenceSelector.orphanSentenceKeys]）
     * 算好再传进来，删除本身不认识句文，只认键。
     */
    fun removeSentenceKeys(keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        loadLatch.await()
        synchronized(persistLock) {
            val removed = keys.count { sentenceStates.remove(it) != null }
            if (removed > 0) {
                persist()
                bumpRevision()
            }
            return removed
        }
    }
}
