// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArticleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ArticleRepository.getInstance(
        application.filesDir.resolve("articles.json").absolutePath
    )

    val articles: StateFlow<List<Article>> = repository.allArticles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertArticle(
        title: String,
        content: String,
        autoIndent: Boolean = true,
        author: String = ""
    ) {
        viewModelScope.launch {
            try {
                repository.insert(
                    Article(title = title, content = content, autoIndent = autoIndent, author = author)
                )
            } catch (e: Exception) {
                Log.e("ArticleViewModel", "insertArticle failed", e)
            }
        }
    }

    suspend fun insertArticleBlocking(
        title: String,
        content: String,
        autoIndent: Boolean = true,
        author: String = ""
    ): Long {
        return repository.insert(
            Article(title = title, content = content, autoIndent = autoIndent, author = author)
        )
    }

    fun deleteArticle(article: Article) {
        viewModelScope.launch {
            try {
                repository.delete(article)
                val filesDir = getApplication<Application>().filesDir
                val recordRepo = RecordRepository.getInstance(
                    filesDir.resolve("records.json").absolutePath
                )
                recordRepo.deleteByArticleId(article.id)
                // 连带清理 FSRS 记忆状态，避免残留孤儿状态
                val fsrsStore = FsrsStateStore.getInstance(
                    filesDir.resolve("fsrs_state.json").absolutePath
                )
                fsrsStore.remove(article.id)
                // 级联清理自定义挖空/遮挡配置、未完成练习进度与首页对应卡片：
                // 否则首页「添加卡片」仍会列出幽灵配置，点击后进入已删文章 → 无限 loading；
                // 残留进度文件还会触发通知点击死链；孤儿配置在 UI 中无法再删除。
                withContext(Dispatchers.IO) {
                    try {
                        val clozeStore = CustomClozeStore.getInstance(filesDir)
                        val maskStore = MaskConfigStore.getInstance(filesDir)
                        val layout = HomeLayoutStore.getInstance(filesDir)
                        // 先根据卡片反查（旧卡无 articleId 时需查配置库）再删配置，顺序不可颠倒
                        val cards = layout.getCards()
                        val keep = cards.filterNot { c ->
                            when (c.type) {
                                HomeLayoutStore.CardType.CUSTOM_CLOZE ->
                                    c.articleId == article.id ||
                                        (c.articleId <= 0L && runCatching { clozeStore.findArticleIdByConfigId(c.refId) }.getOrNull() == article.id)
                                HomeLayoutStore.CardType.CUSTOM_MASK ->
                                    c.articleId == article.id ||
                                        (c.articleId <= 0L && runCatching { maskStore.findArticleIdByConfigId(c.refId) }.getOrNull() == article.id)
                                else -> false
                            }
                        }
                        if (keep.size != cards.size) layout.saveCards(keep)
                        clozeStore.removeArticle(article.id)
                        maskStore.removeArticle(article.id)
                        filesDir.resolve("practice_state_${article.id}.json").delete()
                        // 句子卡片：清理该文章的句子级 FSRS 状态；今日句指向该文章时连快照一起清
                        // （下次进入首页会自动重抽，不出现指向已删文章的句子卡）
                        fsrsStore.removeSentencesForArticle(article.id)
                        SentenceCardStore.getInstance(filesDir).clearIfArticle(article.id)
                    } catch (e: Exception) {
                        Log.e("ArticleViewModel", "deleteArticle cascade cleanup failed", e)
                    }
                }
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
