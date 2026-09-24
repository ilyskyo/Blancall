// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.model.Article
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * 「句子卡片」编排器：把抽句纯逻辑（[SentenceSelector]）、每日快照（[SentenceCardStore]）
 * 与句子级 FSRS 状态（[FsrsStateStore]）串起来，供首页小卡片与大卡片界面调用。
 *
 * 职责边界：
 * - 抽句/队列只做**只读推导**，唯一写盘点是 [ensureToday] 的"今日快照"；
 * - 全部方法为普通（非挂起）函数，调用方在 IO 线程执行（文件读写都很小）。
 *
 * 抽句策略（详见 SentenceSelector 类注释）：
 * - 到期优先：有到期句子时取最逾期者为今日句；
 * - 新句兜底：无到期时轮转抽新句（最久未抽过句子的文章优先）；
 * - 幂等：同日重复调用返回同一句；快照失效（文章被删/句文被编辑）自动重抽。
 */
object DailySentenceCoordinator {

    /** 大卡片队列中的一张句子卡（记忆状态由界面从 [FsrsStateStore.allSentenceStates] 快照读取） */
    data class QueueItem(
        val key: String,
        val articleId: Long,
        val title: String,
        val text: String,
        /** 是否为"今日句"（队列首位置顶项） */
        val isToday: Boolean,
    )

    /** 今天的日期键（yyyy-MM-dd，系统默认时区；与 FSRS 同日判定/连续天数统计同口径） */
    fun dateKey(now: Long): String =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * 确保"今日句"已抽取（幂等）：
     * - 同日且校验通过 → 直接返回已有快照；
     * - 同日但失效（文章被删/句文被编辑）→ 清除后重抽；
     * - 到期优先 → 无到期随机新抽；零候选（无文章/无合格句）返回 null（空态）。
     */
    fun ensureToday(
        store: SentenceCardStore,
        articles: List<Article>,
        sentenceStates: Map<String, FsrsEngine.CardState>,
        now: Long = System.currentTimeMillis(),
        rng: Random = Random.Default,
    ): SentenceCardStore.Snapshot? {
        if (articles.isEmpty()) return null
        val date = dateKey(now)
        val cur = store.today()
        if (cur != null && cur.date == date) {
            if (validate(cur, articles)) return cur
            store.clear()
        }

        val dueKey = SentenceSelector.pickDue(sentenceStates, now)
        val pick = dueKey?.let { resolvePick(it, articles) }
            ?: SentenceSelector.pickNew(articles, sentenceStates, rng)
            ?: return null
        val title = articles.firstOrNull { it.id == pick.articleId }?.title ?: ""
        return store.setTodayIfAbsent(
            date,
            SentenceCardStore.Snapshot(
                date = date,
                key = pick.key,
                articleId = pick.articleId,
                text = pick.text,
                start = pick.start,
                end = pick.end,
                title = title,
            ),
        )
    }

    /**
     * 构建大卡片会话队列：今日句置顶 + 全部到期句（按 due 升序）。
     * 到期句的句文按"文章全文切句 → 键命中"反查（每篇文章只切一次）；
     * 反查失败（文章被删/句文被编辑）的项跳过展示（状态保留，不静默删数据）。
     */
    fun buildQueue(
        store: SentenceCardStore,
        articles: List<Article>,
        sentenceStates: Map<String, FsrsEngine.CardState>,
        now: Long = System.currentTimeMillis(),
    ): List<QueueItem> {
        val out = ArrayList<QueueItem>()
        val today = store.today()?.takeIf { validate(it, articles) }
        if (today != null) {
            val title = articles.firstOrNull { it.id == today.articleId }?.title
                ?.takeIf { it.isNotBlank() } ?: today.title
            out += QueueItem(
                key = today.key,
                articleId = today.articleId,
                title = title,
                text = today.text,
                isToday = true,
            )
        }

        val due = sentenceStates.entries
            .filter {
                it.key.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX) &&
                    FsrsEngine.isDue(it.value, now)
            }
            .sortedBy { it.value.due }
        if (due.isEmpty()) return out

        val textCache = HashMap<Long, Map<String, String>>()
        for (e in due) {
            if (e.key == today?.key) continue
            val aid = SentenceSelector.articleIdOf(e.key) ?: continue
            val article = articles.firstOrNull { it.id == aid } ?: continue
            val texts = textCache.getOrPut(aid) {
                SentenceSelector.candidates(article).associateBy({ it.key }, { it.text })
            }
            val text = texts[e.key] ?: continue
            out += QueueItem(
                key = e.key,
                articleId = aid,
                title = article.title,
                text = text,
                isToday = false,
            )
        }
        return out
    }

    /** 到期句子键 → 候选句（单篇文章切句哈希反查；文章已删/句文已编辑返回 null） */
    private fun resolvePick(key: String, articles: List<Article>): SentenceSelector.Pick? {
        val aid = SentenceSelector.articleIdOf(key) ?: return null
        val article = articles.firstOrNull { it.id == aid } ?: return null
        return SentenceSelector.candidates(article).firstOrNull { it.key == key }
    }

    /** 快照有效性：文章存在且句文仍在正文中（编辑/删除即失效重抽） */
    private fun validate(s: SentenceCardStore.Snapshot, articles: List<Article>): Boolean {
        if (s.text.isBlank()) return false
        val article = articles.firstOrNull { it.id == s.articleId } ?: return false
        return article.content.contains(s.text)
    }
}
