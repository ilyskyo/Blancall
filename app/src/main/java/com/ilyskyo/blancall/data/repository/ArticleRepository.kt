// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.data.database.ArticleStorage
import com.ilyskyo.blancall.data.model.Article
import kotlinx.coroutines.flow.Flow

class ArticleRepository(filePath: String) {
    private val storage = ArticleStorage.getInstance(filePath)

    val allArticles: Flow<List<Article>> = storage.articles

    /** 挂起直到首次读盘完成；桌面小组件等非页面消费者需要它避免读到空库。 */
    suspend fun awaitLoaded() = storage.awaitLoaded()

    suspend fun getArticleById(id: Long): Article? = storage.getById(id)

    suspend fun insert(article: Article): Long = storage.insert(article)

    suspend fun update(article: Article) = storage.update(article)

    suspend fun delete(article: Article) = storage.delete(article)

    /** 备份导入后重载（B1-3）：转发到底层存储；等待首载并串行化写盘 */
    suspend fun reload() = storage.reload()

    companion object {
        @Volatile
        private var instance: ArticleRepository? = null

        /**
         * 单例获取，与 RecordRepository.getInstance 保持一致。
         * 构造函数保留以兼容现有调用方，但推荐改用 getInstance。
         * 底层委托 ArticleStorage.getInstance 共享同一存储。
         */
        fun getInstance(filePath: String): ArticleRepository {
            return instance ?: synchronized(this) {
                instance ?: ArticleRepository(filePath).also { instance = it }
            }
        }
    }
}
