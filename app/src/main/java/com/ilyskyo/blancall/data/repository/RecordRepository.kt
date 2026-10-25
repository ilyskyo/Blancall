// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.data.model.AnsweredBlank
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 练习记录仓库。
 *
 * ## 存储格式与落盘策略
 * - 主文件为 **JSONL**（一行一条记录）：insert 只追加一行（O(1) + fsync），
 *   不再每次全量重写；deleteByArticleId 等低频路径才整体重写（含 .bak 轮换）。
 * - 兼容旧版「JSON 数组」文件：加载时自动识别，首次写入时整体转写为 JSONL；
 *   主文件解析失败（损坏）时同样置位「下次整体重写」，不会把新记录追加进垃圾堆。
 * - 内存维护 articleId 索引，getByArticleId 由 O(n) 过滤降为 O(k)。
 */
class RecordRepository(private val filePath: String) {

    private val _records = MutableStateFlow<List<PracticeRecord>>(emptyList())
    val records: StateFlow<List<PracticeRecord>> = _records.asStateFlow()

    private var nextId = 1L
    private val fileMutex = Mutex()
    // 内存状态读改写锁，保证 insert/deleteByArticleId 的读改写整体原子，避免并发互相覆盖
    private val stateLock = Any()
    // 加载完成的门闩；init 后台加载完成后 countDown，写操作需先 await 以防加载覆盖新增数据
    private val loadLatch = CountDownLatch(1)
    // articleId → 该文章的记录（与 _records 同在 stateLock 内维护；getByArticleId 走这里）
    private var articleIndex: Map<Long, List<PracticeRecord>> = emptyMap()
    // 主文件需要「下次落盘整体重写」：旧数组格式（不能直接追加）/ 主文件损坏空起步。
    // 置位后首次写盘走 AtomicFiles.writeTextAtomic（含 .bak 轮换与转格式），成功后清除。
    @Volatile
    private var fileNeedsFullRewrite = false

    init {
        // 异步加载，避免冷启动阻塞 UI 线程；加载完成后释放门闩
        Thread {
            try {
                loadFromFile()
            } finally {
                loadLatch.countDown()
            }
        }.start()
    }

    /** 挂起直至初始加载完成；切到 IO 线程避免阻塞 Main 线程（CountDownLatch.await 是阻塞调用） */
    suspend fun awaitLoaded() {
        withContext(Dispatchers.IO) { loadLatch.await() }
    }

    /**
     * 解析单条记录 JSON（JSONL 的一行 / 旧数组的一个元素共用）。
     * 单条损坏不会拖垮全部记录：optXxx 降级 + 本条 try/catch 返回 null（调用方跳过）。
     *
     * @param where 损坏日志的定位描述（如「第 3 条」「行 12」）
     */
    private fun parseRecord(obj: JSONObject, where: String): PracticeRecord? {
        return try {
            val id = obj.optLong("id", 0L)
            if (id <= 0L) return null
            val mistakesArr = obj.optJSONArray("mistakes") ?: JSONArray()
            val mistakes = mutableListOf<MistakeDetail>()
            for (j in 0 until mistakesArr.length()) {
                val m = mistakesArr.optJSONObject(j) ?: continue
                mistakes.add(
                    MistakeDetail(
                        blankIndex = m.optInt("blankIndex", 0),
                        correctAnswer = m.optString("correctAnswer", ""),
                        userAnswer = m.optString("userAnswer", ""),
                        errorType = m.optString("errorType", ""),
                        // 旧记录无此字段 → -1（热力图对该处回退比例估算，并标「位置为估算」）
                        sentenceIndex = m.optInt("sentenceIndex", -1),
                        // 旧记录无此字段 → -1（逐空轻重分不出来，只能看会话级均值）
                        similarity = m.optDouble("similarity", -1.0).toFloat(),
                        // 旧记录无此字段 → 空串（错题本退回句索引/答案对照，不假装知道原句）
                        sentenceText = m.optString("sentenceText", "")
                    )
                )
            }
            val answeredArr = obj.optJSONArray("answeredBlanks") ?: JSONArray()
            val answeredBlanks = mutableListOf<AnsweredBlank>()
            for (j in 0 until answeredArr.length()) {
                val a = answeredArr.optJSONObject(j) ?: continue
                answeredBlanks.add(
                    AnsweredBlank(
                        blankIndex = a.optInt("blankIndex", 0),
                        correctAnswer = a.optString("correctAnswer", ""),
                        userAnswer = a.optString("userAnswer", ""),
                        errorType = a.optString("errorType", ""),
                        sentenceIndex = a.optInt("sentenceIndex", -1),
                        sentenceText = a.optString("sentenceText", ""),
                        similarity = a.optDouble("similarity", -1.0).toFloat()
                    )
                )
            }
            PracticeRecord(
                id = id,
                articleId = obj.optLong("articleId", 0L),
                mode = obj.optString("mode", ""),
                totalBlanks = obj.optInt("totalBlanks", 0),
                correctCount = obj.optInt("correctCount", 0),
                mistakes = mistakes,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                duration = obj.optLong("duration", 0L),
                similarity = obj.optDouble("similarity", 0.0).toFloat(),
                rating = obj.optInt("rating", 0),
                weakHints = obj.optInt("weakHints", 0),
                strongHints = obj.optInt("strongHints", 0),
                // 旧记录无此字段 → 空列表（热力图回退整篇统计）。
                // 注意：废弃字段 answeredSentences（句子索引语义，段落模式下与全文错位）
                // 在此刻意不读取，确保旧数据走回退分支而不被误判为字符位置。
                answeredSentenceStarts = obj.optJSONArray("answeredSentenceStarts")
                    ?.let { arr -> List(arr.length()) { arr.optInt(it) } }
                    ?: emptyList(),
                // 旧记录无此字段 → 空列表（句级错误画像视为无数据）
                mistakeSentenceIndices = obj.optJSONArray("mistakeSentenceIndices")
                    ?.let { arr -> List(arr.length()) { arr.optInt(it) } }
                    ?: emptyList(),
                // 旧记录无此字段 → 空列表（错题本视为「历史错题均未确认攻克」）
                answeredBlanks = answeredBlanks
            )
        } catch (e: Exception) {
            Log.w("RecordRepository", "跳过损坏的记录（$where）: ${e.message}")
            null
        }
    }

    /**
     * 解析整个记录文件：**兼容两种格式**——
     * - 旧版：单个 JSON 数组（升级前的历史文件）；
     * - 新版 JSONL：一行一条记录（insert 追加的落盘格式）。
     *
     * 逐行容错：进程在追加途中被杀只会留下残缺的最后一行，跳过即可。
     * @return (记录列表, 最大 id, 是否旧数组格式)；空文件 maxId=0
     */
    private fun parseFile(text: String): Triple<List<PracticeRecord>, Long, Boolean> {
        if (text.trimStart().startsWith("[")) {
            val jsonArray = JSONArray(text)
            val loaded = ArrayList<PracticeRecord>(jsonArray.length())
            var maxId = 0L
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val record = parseRecord(obj, "第 ${i + 1} 条") ?: continue
                loaded.add(record)
                if (record.id > maxId) maxId = record.id
            }
            return Triple(loaded, maxId, true)
        }
        val loaded = ArrayList<PracticeRecord>()
        var maxId = 0L
        var lineNo = 0
        for (line in text.lineSequence()) {
            lineNo++
            val s = line.trim()
            if (s.isEmpty()) continue
            val obj = try {
                JSONObject(s)
            } catch (e: Exception) {
                Log.w("RecordRepository", "跳过损坏的 JSONL 行 $lineNo: ${e.message}")
                continue
            }
            val record = parseRecord(obj, "行 $lineNo") ?: continue
            loaded.add(record)
            if (record.id > maxId) maxId = record.id
        }
        return Triple(loaded, maxId, false)
    }

    private fun loadFromFile(
        clearWhenMissing: Boolean = false,
        allowBackupRecovery: Boolean = true,
    ) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                if (clearWhenMissing) {
                    clearInMemory()
                    fileNeedsFullRewrite = true
                }
                return
            }
            val text = file.readText()
            if (text.isBlank()) {
                if (clearWhenMissing) {
                    clearInMemory()
                    fileNeedsFullRewrite = true
                }
                return
            }
            val (loaded, maxId, legacy) = parseFile(text)
            // JSONL 文件有内容却一条都读不出（逐行解析全部失败）⇒ 视为损坏，走备份恢复
            if (loaded.isEmpty() && !legacy) {
                throw IllegalStateException("JSONL 无有效记录")
            }
            synchronized(stateLock) {
                _records.value = loaded
                nextId = maxId.coerceAtLeast(0) + 1
                articleIndex = loaded.groupBy { it.articleId }
                fileNeedsFullRewrite = legacy
            }
        } catch (e: Exception) {
            Log.e("RecordRepository", "加载练习记录失败，文件可能已损坏", e)
            // 主文件已不可信：下一次落盘必须整体重写（不能把新记录追加进损坏文件）
            fileNeedsFullRewrite = true
            if (!allowBackupRecovery) {
                // 备份导入语义：盘上那份就是唯一真相，不能拿导入前的 .bak 复活旧记录
                clearInMemory()
                return
            }
            // 尝试从备份恢复
            try {
                val bakFile = File(filePath + ".bak")
                if (bakFile.exists()) {
                    val bakStr = bakFile.readText()
                    if (bakStr.isNotBlank()) {
                        val (loaded, maxId, _) = parseFile(bakStr)
                        synchronized(stateLock) {
                            _records.value = loaded
                            nextId = maxId.coerceAtLeast(0) + 1
                            articleIndex = loaded.groupBy { it.articleId }
                        }
                        Log.w("RecordRepository", "已从备份文件恢复 ${loaded.size} 条记录")
                        return
                    }
                }
            } catch (_: Exception) {
                Log.e("RecordRepository", "备份恢复也失败，将从空列表开始")
            }
            clearInMemory()
        }
    }

    private fun clearInMemory() {
        synchronized(stateLock) {
            _records.value = emptyList()
            articleIndex = emptyMap()
            nextId = 1L
        }
    }

    /**
     * 全量整体重写（JSONL 内容，含 .bak 轮换）：删除等低频路径用；
     * 新增走 [appendOrRewriteLocked] 的 O(1) 追加，避免每次 insert 重写全文件。
     * 调用方须已持有 [fileMutex]。
     */
    private suspend fun saveToFileLocked() {
        // 快照必须在锁内读取：并发 insert 时锁外读取会拿到旧值，
        // 待其获得锁后会用「旧快照」覆盖「新快照」写下的文件（内存新、磁盘旧）
        val snapshot = _records.value
        // 文件写入切 IO 线程，避免在 Main 线程上做磁盘 IO（调用方多为 viewModelScope）
        withContext(Dispatchers.IO) {
            try {
                // 原子写 + fsync 统一到 AtomicFiles（tmp → fsync → .bak → rename → 目录 fsync）
                AtomicFiles.writeTextAtomic(File(filePath), serializeRecords(snapshot))
                fileNeedsFullRewrite = false
            } catch (e: Exception) {
                Log.e("RecordRepository", "保存练习记录失败", e)
                throw e
            }
        }
    }

    /**
     * 新增记录的落盘路径（调用方须已持有 [fileMutex]）：
     * - 常规：JSONL 追加一行（O(1) + fsync），不再每插一条重写全文件；
     * - 旧数组格式首次写入 / 主文件损坏空起步：整体重写一次（数组尾部不能直接
     *   追加，会毁文件；损坏文件不能追加垃圾），写盘走 [AtomicFiles.writeTextAtomic]，
     *   顺带完成 .bak 轮换。
     */
    private suspend fun appendOrRewriteLocked(record: PracticeRecord) {
        // 锁内判断/读取：与全量重写路径互斥，保证转写与追加不会交叉
        val needsRewrite = fileNeedsFullRewrite
        val snapshot = _records.value
        withContext(Dispatchers.IO) {
            try {
                if (needsRewrite) {
                    AtomicFiles.writeTextAtomic(File(filePath), serializeRecords(snapshot))
                    fileNeedsFullRewrite = false
                } else {
                    AtomicFiles.appendTextLine(File(filePath), recordToJson(record).toString())
                }
            } catch (e: Exception) {
                Log.e("RecordRepository", "保存练习记录失败", e)
                throw e
            }
        }
    }

    /** JSONL 序列化：一行一条；[records] 为空时返回空串（读取端 blank 视为无数据）。 */
    private fun serializeRecords(records: List<PracticeRecord>): String =
        if (records.isEmpty()) ""
        else records.joinToString(separator = "\n", postfix = "\n") { recordToJson(it).toString() }

    /** 单条记录 → JSON（字段与旧数组格式逐字段一致，仅容器从数组改为行）。 */
    private fun recordToJson(record: PracticeRecord): JSONObject {
        val obj = JSONObject()
        obj.put("id", record.id)
        obj.put("articleId", record.articleId)
        obj.put("mode", record.mode)
        obj.put("totalBlanks", record.totalBlanks)
        obj.put("correctCount", record.correctCount)
        obj.put("timestamp", record.timestamp)
        obj.put("duration", record.duration)
        obj.put("similarity", record.similarity.toDouble())
        obj.put("rating", record.rating)
        obj.put("weakHints", record.weakHints)
        obj.put("strongHints", record.strongHints)
        obj.put("answeredSentenceStarts", JSONArray(record.answeredSentenceStarts))
        obj.put("mistakeSentenceIndices", JSONArray(record.mistakeSentenceIndices))
        val mistakesArr = JSONArray()
        for (m in record.mistakes) {
            val mObj = JSONObject()
            mObj.put("blankIndex", m.blankIndex)
            mObj.put("correctAnswer", m.correctAnswer)
            mObj.put("userAnswer", m.userAnswer)
            mObj.put("errorType", m.errorType)
            // 只在拿得到精确落点时写：records.jsonl 是无界追加的学习档案，
            // 每条错处都挂一个 -1 只会放大文件，读侧的默认值本来就是 -1
            if (m.sentenceIndex >= 0) mObj.put("sentenceIndex", m.sentenceIndex)
            if (m.similarity >= 0f) mObj.put("similarity", m.similarity.toDouble())
            if (m.sentenceText.isNotEmpty()) mObj.put("sentenceText", m.sentenceText)
            mistakesArr.put(mObj)
        }
        obj.put("mistakes", mistakesArr)
        // 逐空全量结果：旧记录没有这个概念，空列表时**不写键**（与既有字段的稀疏写法一致）。
        // 这是错题本判断「后来写对过没有」的唯一依据，新记录必须完整落。
        if (record.answeredBlanks.isNotEmpty()) {
            val answeredArr = JSONArray()
            for (a in record.answeredBlanks) {
                val aObj = JSONObject()
                    .put("blankIndex", a.blankIndex)
                    .put("correctAnswer", a.correctAnswer)
                    .put("userAnswer", a.userAnswer)
                    .put("errorType", a.errorType)
                if (a.sentenceIndex >= 0) aObj.put("sentenceIndex", a.sentenceIndex)
                if (a.similarity >= 0f) aObj.put("similarity", a.similarity.toDouble())
                if (a.sentenceText.isNotEmpty()) aObj.put("sentenceText", a.sentenceText)
                answeredArr.put(aObj)
            }
            obj.put("answeredBlanks", answeredArr)
        }
        return obj
    }

    suspend fun insert(record: PracticeRecord): PracticeRecord {
        awaitLoaded()
        return fileMutex.withLock {
            val newRecord = synchronized(stateLock) {
                val nr = record.copy(id = nextId++)
                _records.value = _records.value + nr
                articleIndex = articleIndex + (nr.articleId to (articleIndex[nr.articleId].orEmpty() + nr))
                nr
            }
            // 内存变更与落盘同持一把锁：reload 不可能落在两者之间
            appendOrRewriteLocked(newRecord)
            newRecord
        }
    }

    suspend fun deleteByArticleId(articleId: Long) {
        awaitLoaded()
        fileMutex.withLock {
            synchronized(stateLock) {
                _records.value = _records.value.filter { it.articleId != articleId }
                articleIndex = articleIndex - articleId
            }
            saveToFileLocked()
        }
    }

    /**
     * 「导出并归档旧记录」（审计 2.2-15 的出口）：取**最旧的** [count] 条（按时间正序）。
     *
     * 与 [removeByIds] 配对使用、刻意拆成两步：先导出成功、再裁剪 ——
     * 任何一步失败都不会丢用户履历（宁可归档不成，不可静默淘汰）。
     */
    suspend fun oldestRecords(count: Int): List<PracticeRecord> {
        awaitLoaded()
        if (count <= 0) return emptyList()
        return synchronized(stateLock) { _records.value.sortedBy { it.timestamp }.take(count) }
    }

    /** 归档文件内容：与主库同一套字段编码（JSONL）——用户拿到手的文件可读、可与 records.json 对照 */
    internal fun archiveJson(records: List<PracticeRecord>): String = serializeRecords(records)

    /** 按 id 批量删除（归档裁剪用）；返回实际删除数。落盘路径与 [deleteByArticleId] 相同 */
    suspend fun removeByIds(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        awaitLoaded()
        return fileMutex.withLock {
            val removed: Int
            synchronized(stateLock) {
                val before = _records.value
                val after = before.filter { it.id !in ids }
                removed = before.size - after.size
                if (removed > 0) {
                    _records.value = after
                    articleIndex = after.groupBy { it.articleId }
                }
            }
            if (removed > 0) saveToFileLocked()
            removed
        }
    }

    /**
     * 备份导入后重载：等首载完成后在写锁内重读整库。
     * 导入语义下主文件缺失/损坏就是「备份里没有/这份数据不可用」，
     * 必须清空内存且不回读 `.bak`（那是导入前的旧记录）。
     */
    suspend fun reload() {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                loadFromFile(clearWhenMissing = true, allowBackupRecovery = false)
            }
        }
    }

    /** 挂起查询：先等待异步加载完成，避免冷启动时返回空结果；走内存索引 O(k) */
    suspend fun getByArticleId(articleId: Long): List<PracticeRecord> {
        awaitLoaded()
        return synchronized(stateLock) { articleIndex[articleId].orEmpty() }
            .sortedByDescending { it.timestamp }
    }

    companion object {
        /** 归档时保留的最近记录条数：只有更旧的记录才允许被导出归档（产品默认：1000 条） */
        const val ARCHIVE_KEEP_RECENT = 1000

        @Volatile
        private var INSTANCE: RecordRepository? = null

        /**
         * 单例获取。注意：仅首次调用的 filePath 生效，后续调用的 filePath 会被忽略
         * （单例语义）。调用方应确保全应用使用同一文件路径。
         */
        fun getInstance(filePath: String): RecordRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecordRepository(filePath).also { INSTANCE = it }
            }
        }
    }
}
