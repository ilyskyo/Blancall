// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.model.Article
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.CustomClozeStore
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.data.repository.InkStore
import com.ilyskyo.blancall.data.repository.MaskConfigStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.ReaderPrefsStore
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.data.repository.TagStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArticleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ArticleRepository.getInstance(
        application.filesDir.resolve("articles.json").absolutePath
    )

    /**
     * 编辑保存后「已清理多少句失效记忆状态」的一次性提示数（0 = 无提示）。
     *
     * 走 StateFlow 而不是回调：清理发生在 VM 的协程里，而提示要在编辑页上弹 Toast ——
     * 回调会把这个协程的生命周期绑到某个 Composable 上，退出编辑页时回调就丢了，
     * 于是「改了文章但没告诉我状态被清了」这类静默丢失又回来了。UI 消费后调 [consumeOrphanNotice]。
     */
    private val _orphanStatesPruned = MutableStateFlow(0)
    val orphanStatesPruned: StateFlow<Int> = _orphanStatesPruned.asStateFlow()

    /** 提示已展示，计数归零（避免旋转屏幕后重复弹） */
    fun consumeOrphanNotice() {
        _orphanStatesPruned.value = 0
    }

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
                // 级联清理自定义挖空/遮挡配置、未完成练习进度与首页对应卡片：
                // 否则首页「添加卡片」仍会列出幽灵配置，点击后进入已删文章 → 无限 loading；
                // 残留进度文件还会触发通知点击死链；孤儿配置在 UI 中无法再删除。
                withContext(Dispatchers.IO) {
                    try {
                        // remove/removeSentencesForArticle 内部会等首载 + 全量序列化 + fsync，
                        // 必须与其余级联清理一起放在 IO 线程，不能占着主线程做盘写。
                        fsrsStore.remove(article.id)
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
                                // 文章卡漏了这一支时，删掉文章后卡还留在首页，渲染成一张永远点不开的空卡
                                // （ArticleCard 拿到 article=null 只显示空态，用户只能再长按删一次）
                                HomeLayoutStore.CardType.ARTICLE ->
                                    c.refId == article.id || c.id == HomeLayoutStore.articleCardId(article.id)
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
                        // 文章标签：级联解绑该文章的全部标签（标签本体保留）
                        TagStore.getInstance(filesDir).removeArticle(article.id)
                        // 按文章的阅读设置存档：本类此前没有 removeArticle API，所以这条一直在漏，
                        // 每删一篇永久留一条孤儿，reader_prefs.json 只增不减
                        // （getInstance 收 Context 而非 filesDir，与其余 Store 不同，沿用其既有形态）
                        ReaderPrefsStore.getInstance(getApplication()).removeArticle(article.id)
                        // 错题墨迹存档：按文章联动清理（同一步 IO 内完成，避免主线程扫描目录）
                        InkStore.getInstance(filesDir.resolve("ink").absolutePath)
                            .deleteByArticleId(article.id)
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
                pruneOrphanSentenceStates(article)
            } catch (e: Exception) {
                Log.e("ArticleViewModel", "updateArticle failed", e)
            }
        }
    }

    /**
     * 编辑期句子孤儿清理（2026-10-13 用户拍板：**prune + 提示**，不做「迁移」）。
     *
     * 为什么选 prune：迁移要按相似度把旧句的记忆状态搬到新句，而相似度会误配 ——
     * 把 A 句的记忆挂到 B 句上，界面上完全看不出来（两句都「有状态」），
     * 却让调度器按一句从未被记过的话来安排复习，比没有状态更难发现、更难纠正。
     * prune 的代价只是「改写过的句子要重新学」——这个代价用户看得见，也补得回来。
     *
     * 失败一律静默：清理是存储卫生，不该让「保存文章」这件事失败。
     */
    private suspend fun pruneOrphanSentenceStates(article: Article) {
        try {
            val store = FsrsStateStore.getInstance(
                getApplication<Application>().filesDir.resolve("fsrs_state.json").absolutePath
            )
            // await/allSentenceStates/removeSentenceKeys 里有阻塞等锁与整文件落盘，
            // 全部放到 IO；调用方是 viewModelScope（Main）。
            val removed = withContext(Dispatchers.IO) {
                store.awaitLoaded()
                val orphans = SentenceSelector.orphanSentenceKeys(
                    store.allSentenceStates().keys, article.id, article.content
                )
                store.removeSentenceKeys(orphans)
            }
            if (removed > 0) _orphanStatesPruned.value = removed
        } catch (e: Exception) {
            Log.e("ArticleViewModel", "pruneOrphanSentenceStates failed", e)
        }
    }

    suspend fun getArticleById(id: Long): Article? {
        return repository.getArticleById(id)
    }
}
