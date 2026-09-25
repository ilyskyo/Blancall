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
     * 构建大卡片会话队列：今日句置顶 → 全部到期句（按逾期升序）→ **全部未学过的新句**
     * （文章顺序 × 句序）。
     *
     * 新句必须入队（用户要求：所有句子都要能抽到，「记完一个还有下一个直到全记完」）——
     * 旧实现只排「今日 + 到期」，首次使用 / 新文章反复打开都只有 1 张卡，复习推进不下去。
     * 已学过的句子不在此列（等 FSRS 到期再入队，即间隔复习语义）；文章范围由调用方过滤
     * （标签筛选 = 用户选定的抽句范围）。
     *
     * 到期句/新句的句文均按「文章全文切句」反查（每篇文章只切一次）；
     * 反查失败（文章被删/句文被编辑）的到期项跳过展示（状态保留，不静默删数据）。
     *
     * @param includeAllLearned 主动复习（用户自己点「复习」/ 完成页「再复习一轮」）：true 时改为
     *   **全量队列**（已学过含未到期的句子按「最久未复习优先」排前，新句按文章顺序接后）；
     *   false（默认）= 日常间隔复习语义。
     */
    fun buildQueue(
        store: SentenceCardStore,
        articles: List<Article>,
        sentenceStates: Map<String, FsrsEngine.CardState>,
        now: Long = System.currentTimeMillis(),
        includeAllLearned: Boolean = false,
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

        // 每篇文章只切一次句（到期反查 / 新句入队 / 主动复习轮共用）
        val candCache = HashMap<Long, List<SentenceSelector.Pick>>()
        fun candsOf(article: Article): List<SentenceSelector.Pick> =
            candCache.getOrPut(article.id) { SentenceSelector.candidates(article) }

        // 主动复习轮：全量入队（含已学过未到期），提前返回（无「今日句」特殊位置）
        if (includeAllLearned) {
            return buildReviewAllQueue(articles, sentenceStates) { candsOf(it) }
        }

        val due = sentenceStates.entries
            .filter {
                it.key.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX) &&
                    FsrsEngine.isDue(it.value, now)
            }
            .sortedBy { it.value.due }
        for (e in due) {
            if (e.key == today?.key) continue
            val aid = SentenceSelector.articleIdOf(e.key) ?: continue
            val article = articles.firstOrNull { it.id == aid } ?: continue
            val text = candsOf(article).firstOrNull { it.key == e.key }?.text ?: continue
            out += QueueItem(
                key = e.key,
                articleId = aid,
                title = article.title,
                text = text,
                isToday = false,
            )
        }

        // ── 新句（从未学过）：全部入队直到记完 ──
        // 今日句若本身是新句，已在队首占位；已学过的句子由 FSRS 到期机制召回。
        val seen = HashSet<String>(out.size * 2 + 16)
        out.forEach { seen.add(it.key) }
        for (article in articles) {
            for (cand in candsOf(article)) {
                if (cand.key in sentenceStates) continue
                if (!seen.add(cand.key)) continue
                out += QueueItem(
                    key = cand.key,
                    articleId = article.id,
                    title = article.title,
                    text = cand.text,
                    isToday = false,
                )
            }
        }
        return out
    }

    /**
     * 主动复习轮队列（用户主动发起的「再学一轮」）：**范围内全部合格句** ——
     * 已学过的按 lastReview 升序（最久没复习的排最前，key 次序保证确定性），
     * 未学过的新句按文章顺序 × 句序接在其后。
     * 文章被删 / 句文被编辑的旧键自然查不到（跳过，状态保留）。
     */
    private fun buildReviewAllQueue(
        articles: List<Article>,
        sentenceStates: Map<String, FsrsEngine.CardState>,
        candsOf: (Article) -> List<SentenceSelector.Pick>,
    ): List<QueueItem> {
        val out = ArrayList<QueueItem>()
        val learned = sentenceStates.entries
            .filter { it.key.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX) }
            .sortedWith(compareBy({ it.value.lastReview }, { it.key }))
        val seen = HashSet<String>(learned.size * 2 + 16)
        for (e in learned) {
            val aid = SentenceSelector.articleIdOf(e.key) ?: continue
            val article = articles.firstOrNull { it.id == aid } ?: continue
            val text = candsOf(article).firstOrNull { it.key == e.key }?.text ?: continue
            if (!seen.add(e.key)) continue
            out += QueueItem(
                key = e.key,
                articleId = aid,
                title = article.title,
                text = text,
                isToday = false,
            )
        }
        for (article in articles) {
            for (cand in candsOf(article)) {
                if (cand.key in sentenceStates) continue
                if (!seen.add(cand.key)) continue
                out += QueueItem(
                    key = cand.key,
                    articleId = article.id,
                    title = article.title,
                    text = cand.text,
                    isToday = false,
                )
            }
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
