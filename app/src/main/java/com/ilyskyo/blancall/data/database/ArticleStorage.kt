// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.database

import android.util.Log
import com.ilyskyo.blancall.data.model.Article
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * 内存存储 + JSON 文件持久化
 */
class ArticleStorage(private val filePath: String) {

    private var nextId = 1L
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    private val fileMutex = Mutex()
    // 内存状态读改写锁，保证 insert/update/delete 的读改写整体原子，避免并发互相覆盖
    private val stateLock = Any()
    // 加载完成的门闩；init 后台加载完成后 countDown，写操作需先 await 以防加载覆盖新增数据
    private val loadLatch = CountDownLatch(1)

    val articles: Flow<List<Article>> = _articles.asStateFlow()

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
     * 解析 JSON 字符串为文章列表与最大 id。
     * 单条损坏不会拖垮整库：用 optXxx 降级 + 单条 try/catch 跳过。
     * @return Pair(文章列表, 最大 id)；空列表时 maxId=0
     */
    private fun parseArticles(jsonStr: String): Pair<List<Article>, Long> {
        val jsonArray = JSONArray(jsonStr)
        val loaded = mutableListOf<Article>()
        var maxId = 0L
        for (i in 0 until jsonArray.length()) {
            try {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                if (id <= 0L) continue
                val title = obj.optString("title", "")
                val content = obj.optString("content", "")
                if (content.isBlank()) continue
                val article = Article(
                    id = id,
                    title = title,
                    content = content,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    autoIndent = obj.optBoolean("autoIndent", true)
                )
                loaded.add(article)
                if (article.id > maxId) maxId = article.id
            } catch (e: Exception) {
                Log.w("ArticleStorage", "跳过损坏的第 ${i + 1} 篇文章: ${e.message}")
            }
        }
        return loaded to maxId
    }

    private fun loadFromFile() {
        try {
            val file = File(filePath)
            // 主文件不存在则尝试备份
            if (!file.exists()) {
                val bakFile = File(filePath + ".bak")
                if (bakFile.exists()) {
                    bakFile.copyTo(file, overwrite = true)
                    Log.w("ArticleStorage", "主文件丢失，已从备份恢复")
                } else {
                    return
                }
            }
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return
            val (loaded, maxId) = parseArticles(jsonStr)
            synchronized(stateLock) {
                _articles.value = loaded
                nextId = maxId.coerceAtLeast(0) + 1
            }
        } catch (e: Exception) {
            Log.e("ArticleStorage", "加载文章失败，文件可能已损坏", e)
            // 尝试从备份恢复
            try {
                val bakFile = File(filePath + ".bak")
                if (bakFile.exists()) {
                    val bakStr = bakFile.readText()
                    if (bakStr.isNotBlank()) {
                        val (loaded, maxId) = parseArticles(bakStr)
                        synchronized(stateLock) {
                            _articles.value = loaded
                            nextId = maxId.coerceAtLeast(0) + 1
                        }
                        Log.w("ArticleStorage", "已从备份文件恢复 ${loaded.size} 篇文章")
                    }
                }
            } catch (_: Exception) {
                Log.e("ArticleStorage", "备份恢复也失败，将从空列表开始")
            }
        }
    }

    private suspend fun saveToFile() {
        val snapshot = _articles.value
        fileMutex.withLock {
            // 文件写入切 IO 线程，避免在 Main 线程上做磁盘 IO（调用方多为 viewModelScope）
            withContext(Dispatchers.IO) {
                try {
                    val jsonArray = JSONArray()
                    for (article in snapshot) {
                        val obj = JSONObject()
                        obj.put("id", article.id)
                        obj.put("title", article.title)
                        obj.put("content", article.content)
                        obj.put("createdAt", article.createdAt)
                        obj.put("updatedAt", article.updatedAt)
                        obj.put("autoIndent", article.autoIndent)
                        jsonArray.put(obj)
                    }
                    val tmpFile = File(filePath + ".tmp")
                    tmpFile.writeText(jsonArray.toString())
                    // 先备份旧文件，再替换
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
                    Log.e("ArticleStorage", "保存文章失败", e)
                    throw e
                }
            }
        }
    }

    suspend fun getById(id: Long): Article? {
        awaitLoaded()
        // O(n) 查找；文章数量较少，暂不维护索引 Map
        return _articles.value.find { it.id == id }
    }

    suspend fun insert(article: Article): Long {
        awaitLoaded()
        val newArticle = synchronized(stateLock) {
            val na = article.copy(id = nextId++)
            _articles.value = _articles.value + na
            na
        }
        saveToFile()
        return newArticle.id
    }

    suspend fun update(article: Article) {
        awaitLoaded()
        synchronized(stateLock) {
            _articles.value = _articles.value.map {
                if (it.id == article.id) article else it
            }
        }
        saveToFile()
    }

    suspend fun delete(article: Article) {
        awaitLoaded()
        synchronized(stateLock) {
            _articles.value = _articles.value.filter { it.id != article.id }
        }
        saveToFile()
    }

    companion object {
        @Volatile
        private var INSTANCE: ArticleStorage? = null

        /**
         * 单例获取。注意：仅首次调用的 filePath 生效，后续调用的 filePath 会被忽略
         * （单例语义）。调用方应确保全应用使用同一文件路径。
         */
        fun getInstance(filePath: String): ArticleStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArticleStorage(filePath).also { INSTANCE = it }
            }
        }
    }
}
