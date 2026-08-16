// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArticleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ArticleRepository.getInstance(
        application.filesDir.resolve("articles.json").absolutePath
    )

    val articles: StateFlow<List<Article>> = repository.allArticles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertArticle(title: String, content: String) {
        viewModelScope.launch {
            try {
                repository.insert(
                    Article(title = title, content = content)
                )
            } catch (e: Exception) {
                Log.e("ArticleViewModel", "insertArticle failed", e)
            }
        }
    }

    suspend fun insertArticleBlocking(title: String, content: String): Long {
        return repository.insert(Article(title = title, content = content))
    }

    fun deleteArticle(article: Article) {
        viewModelScope.launch {
            try {
                repository.delete(article)
                val recordRepo = RecordRepository.getInstance(
                    getApplication<Application>().filesDir.resolve("records.json").absolutePath
                )
                recordRepo.deleteByArticleId(article.id)
                // 连带清理 FSRS 记忆状态，避免残留孤儿状态
                val fsrsStore = FsrsStateStore.getInstance(
                    getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
                )
                fsrsStore.remove(article.id)
            } catch (e: Exception) {
                Log.e("ArticleViewModel", "deleteArticle failed", e)
            }
        }
    }

    fun updateArticle(article: Article) {
        viewModelScope.launch {
            try {
                repository.update(article.copy(updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.e("ArticleViewModel", "updateArticle failed", e)
            }
        }
    }

    suspend fun getArticleById(id: Long): Article? {
        return repository.getArticleById(id)
    }
}
