// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
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
import java.io.IOException
import java.util.concurrent.CountDownLatch

/**
 * 练习记录仓库（JSON 文件持久化版）
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
     * 解析 JSON 字符串为记录列表与最大 id。
     * 单条损坏不会拖垮全部记录：用 optXxx 降级 + 单条 try/catch 跳过。
     * @return Pair(记录列表, 最大 id)；空列表时 maxId=0
     */
    private fun parseRecords(jsonStr: String): Pair<List<PracticeRecord>, Long> {
        val jsonArray = JSONArray(jsonStr)
        val loaded = mutableListOf<PracticeRecord>()
        var maxId = 0L
        for (i in 0 until jsonArray.length()) {
            try {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                if (id <= 0L) continue
                val mistakesArr = obj.optJSONArray("mistakes") ?: JSONArray()
                val mistakes = mutableListOf<MistakeDetail>()
                for (j in 0 until mistakesArr.length()) {
                    val m = mistakesArr.optJSONObject(j) ?: continue
                    mistakes.add(
                        MistakeDetail(
                            blankIndex = m.optInt("blankIndex", 0),
                            correctAnswer = m.optString("correctAnswer", ""),
                            userAnswer = m.optString("userAnswer", ""),
                            errorType = m.optString("errorType", "")
                        )
                    )
                }
                val record = PracticeRecord(
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
                        ?: emptyList()
                )
                loaded.add(record)
                if (record.id > maxId) maxId = record.id
            } catch (e: Exception) {
                Log.w("RecordRepository", "跳过损坏的第 ${i + 1} 条记录: ${e.message}")
            }
        }
        return loaded to maxId
    }

    private fun loadFromFile() {
        try {
            val file = File(filePath)
            if (!file.exists()) return
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return
            val (loaded, maxId) = parseRecords(jsonStr)
            synchronized(stateLock) {
                _records.value = loaded
                nextId = maxId.coerceAtLeast(0) + 1
            }
        } catch (e: Exception) {
            Log.e("RecordRepository", "加载练习记录失败，文件可能已损坏", e)
            // 尝试从备份恢复
            try {
                val bakFile = File(filePath + ".bak")
                if (bakFile.exists()) {
                    val bakStr = bakFile.readText()
                    if (bakStr.isNotBlank()) {
                        val (loaded, maxId) = parseRecords(bakStr)
                        synchronized(stateLock) {
                            _records.value = loaded
                            nextId = maxId.coerceAtLeast(0) + 1
                        }
                        Log.w("RecordRepository", "已从备份文件恢复 ${loaded.size} 条记录")
                    }
                }
            } catch (_: Exception) {
                Log.e("RecordRepository", "备份恢复也失败，将从空列表开始")
            }
        }
    }

    private suspend fun saveToFile() {
        val snapshot = _records.value
        fileMutex.withLock {
            // 文件写入切 IO 线程，避免在 Main 线程上做磁盘 IO（调用方多为 viewModelScope）
            withContext(Dispatchers.IO) {
                try {
                    val jsonArray = JSONArray()
                    for (record in snapshot) {
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
                        val mistakesArr = JSONArray()
                        for (m in record.mistakes) {
                            val mObj = JSONObject()
                            mObj.put("blankIndex", m.blankIndex)
                            mObj.put("correctAnswer", m.correctAnswer)
                            mObj.put("userAnswer", m.userAnswer)
                            mObj.put("errorType", m.errorType)
                            mistakesArr.put(mObj)
                        }
                        obj.put("mistakes", mistakesArr)
                        jsonArray.put(obj)
                    }
                    val tmpFile = File(filePath + ".tmp")
                    tmpFile.writeText(jsonArray.toString())
                    val mainFile = File(filePath)
                    if (mainFile.exists()) {
                        val bak = File(filePath + ".bak")
                        if (bak.exists()) bak.delete()
                        mainFile.renameTo(bak)
                    }
                    if (!tmpFile.renameTo(mainFile)) {
                        throw IOException("重命名临时文件失败: ${tmpFile.absolutePath} -> ${mainFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e("RecordRepository", "保存练习记录失败", e)
                    throw e
                }
            }
        }
    }

    suspend fun insert(record: PracticeRecord): PracticeRecord {
        awaitLoaded()
        val newRecord = synchronized(stateLock) {
            val nr = record.copy(id = nextId++)
            _records.value = _records.value + nr
            nr
        }
        saveToFile()
        return newRecord
    }

    suspend fun deleteByArticleId(articleId: Long) {
        awaitLoaded()
        synchronized(stateLock) {
            _records.value = _records.value.filter { it.articleId != articleId }
        }
        saveToFile()
    }

    /** 挂起查询：先等待异步加载完成，避免冷启动时返回空结果 */
    suspend fun getByArticleId(articleId: Long): List<PracticeRecord> {
        awaitLoaded()
        // O(n) 过滤；记录数量可控，暂不维护 articleId 索引 Map
        return _records.value.filter { it.articleId == articleId }.sortedByDescending { it.timestamp }
    }

    companion object {
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
