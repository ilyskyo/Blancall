// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.Article
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 「句子卡片」抽句器：从文章全文切句中筛选合格句，并决定每日抽哪一句。
 *
 * 全部为纯函数（无 IO / 无 Android 依赖），供 JVM 单测直接覆盖。
 *
 * ## 句子身份
 * 键 = `s:<articleId>:<hash16>`，hash16 = SHA-256(trim 后句文) 前 8 字节 hex。
 * 用**文本哈希而非位置**：文章编辑后位置漂移不会串句；同文章同文本的重复句
 * 共享同一记忆状态（天然去重）。
 *
 * ## 抽句策略
 * - 到期优先：有到期句子状态时取最逾期者（由调用方 pickDue 决定今日句）；
 * - 新句兜底：无到期时 [pickNew] 随机新抽，优先"最久未抽过句子的文章"，
 *   已有状态的句子不再作为新句抽取；全部抽过时兜底取 lastReview 最早者。
 */
object SentenceSelector {

    /** 句子键前缀（命名空间分隔；FsrsStateStore 同文件共存依赖该前缀） */
    const val SENTENCE_KEY_PREFIX = "s:"

    /** 一次抽中的候选句 */
    data class Pick(
        val key: String,
        val articleId: Long,
        val text: String,
        val start: Int,
        val end: Int,
    )

    /** 合格句长度范围（字符数，trim 后）：过短无记忆价值，过长卡片放不下 */
    private const val MIN_LEN = 6
    private const val MAX_LEN = 80

    /** 文章轮转候选数：取"最久未抽过句子"的前 N 篇内随机，兼顾跨文章分布与随机性 */
    private const val ROTATION_TOP = 3

    /**
     * 句子键：`s:<articleId>:<hash16>`（hash16 = SHA-256(句文) 前 8 字节 hex）。
     *
     * 为什么截断到 8 字节就够：这个键只做**身份标识**（同文章同文本 = 同一张记忆卡），
     * 不是加密用途。按生日界估：1 万句时碰撞概率约 1e-11，10 万句约 1e-8 ——
     * 远低于「用户改一个标点」这类正常事件的概率。
     *
     * 但碰撞的后果是**静默**的：两句会共享同一份记忆状态，界面上完全看不出来（两句都「有状态」），
     * 而且会一直错下去。所以把边界写在这里：句量级若真逼近 10 万，应把 hex 加长到 16 字节 ——
     * 键格式一变就必须同批做迁移（旧键全部失效等于用户记忆清零），不能顺手改。
     */
    fun sentenceKey(articleId: Long, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
        val hex = buildString(16) {
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
        return "$SENTENCE_KEY_PREFIX$articleId:$hex"
    }

    /** 从句子键解析所属文章 id（脏键返回 null） */
    fun articleIdOf(key: String): Long? {
        if (!key.startsWith(SENTENCE_KEY_PREFIX)) return null
        val parts = key.split(':')
        if (parts.size != 3 || parts[2].isBlank()) return null
        return parts[1].toLongOrNull()
    }

    /**
     * 编辑文章后应当清除的句子态键（孤儿）。
     *
     * 为什么需要：句子键是 `s:<articleId>:<hash(句文)>`，所以「改文 / 删句」之后旧键不再被
     * 任何一方引用，却仍留在 `fsrs_state.json` 里 —— 它会被当成到期句抽出来（用户看到一句
     * 自己已经删掉的句子），也会算进「已学句子数」与平均留存率（于是统计被拖低而查不出原因）。
     * 原先只在删文章时清（[FsrsStateStore.removeSentencesForArticle]），编辑路径没人管。
     *
     * 判据是「键是否还对应当前内容里的某一句」，**不按合格句过滤**：练习也会写句子态，
     * 而练习用的句子不必满足抽句的 6..80 字门槛 —— 拿 [candidates] 的有效集来 prune 会误删
     * 那些真练过的短句。所以这里用全文切句（与练习写入口径同源）。
     *
     * 只算「该删哪些」，删除动作交给 store：判据因此能在 JVM 里被断言，不必造文件。
     *
     * @param existing 盘上现有的全部句子态键
     * @return 属于该文章、且已不在当前内容中的键（顺序与 [existing] 一致）
     */
    fun orphanSentenceKeys(existing: Set<String>, articleId: Long, content: String): List<String> {
        val prefix = "$SENTENCE_KEY_PREFIX$articleId:"
        val valid = SentenceSplitter.split(content).mapTo(HashSet()) { sentenceKey(articleId, it) }
        return existing.filter { it.startsWith(prefix) && it !in valid }
    }

    /**
     * 句子是否合格：trim 后长度 6..80，且至少含 2 个汉字或字母
     * （滤掉目录编号、页码、纯标点等无记忆价值的"句子"）。
     */
    fun isEligible(text: String): Boolean {
        val t = text.trim()
        if (t.length !in MIN_LEN..MAX_LEN) return false
        val contentCount = t.count { it in '\u4e00'..'\u9fff' || it.isLetter() }
        return contentCount >= 2
    }

    /**
     * 文章全文切句 → 合格句候选（同练习的全文切句口径 [SentenceSplitter.splitWithPositions]）。
     * 同文本重复句按 hash 去重，只保留首处位置（供展示定位）。
     */
    fun candidates(article: Article): List<Pick> {
        val seen = HashSet<String>()
        val out = ArrayList<Pick>()
        for (s in SentenceSplitter.splitWithPositions(article.content)) {
            if (!isEligible(s.text)) continue
            val key = sentenceKey(article.id, s.text)
            if (!seen.add(key)) continue
            out += Pick(key, article.id, s.text, s.startIndex, s.endIndex)
        }
        return out
    }

    /** 到期集合中取最逾期者（due 最小）；无到期返回 null。可排除被暂停文章（含句子归属判断） */
    fun pickDue(
        states: Map<String, FsrsEngine.CardState>,
        now: Long,
        excludedArticleIds: Set<Long> = emptySet(),
    ): String? =
        states.entries
            .filter {
                it.key.startsWith(SENTENCE_KEY_PREFIX) &&
                    FsrsEngine.isDue(it.value, now) &&
                    (excludedArticleIds.isEmpty() || articleIdOf(it.key) !in excludedArticleIds)
            }
            .minByOrNull { it.value.due }
            ?.key

    /** 按文章聚合"最近一次句级复习时间"（= 该文章最近抽句时间的近似），无记录返回空 */
    fun lastReviewByArticle(states: Map<String, FsrsEngine.CardState>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for ((key, state) in states) {
            val aid = articleIdOf(key) ?: continue
            val cur = out[aid]
            if (cur == null || state.lastReview > cur) out[aid] = state.lastReview
        }
        return out
    }

    /**
     * 随机抽新句：优先"最久未抽过句子的文章"（按 lastReviewByArticle 升序取前 [ROTATION_TOP] 篇随机），
     * 每篇文章候选去掉已抽过的句子后随机取一；全库都抽过时兜底取 lastReview 最早的句子；
     * 无文章 / 无合格句 / 全库抽过但无任何状态时返回 null。
     */
    fun pickNew(
        articles: List<Article>,
        states: Map<String, FsrsEngine.CardState>,
        rng: Random = Random.Default,
    ): Pick? {
        if (articles.isEmpty()) return null
        val lastReview = lastReviewByArticle(states)
        val ordered = articles.sortedBy { lastReview[it.id] ?: 0L }
        val head = ordered.take(ROTATION_TOP).shuffled(rng) + ordered.drop(ROTATION_TOP)

        var fallback: Pick? = null
        var fallbackLast = Long.MAX_VALUE
        for (article in head) {
            val cands = candidates(article)
            if (cands.isEmpty()) continue
            val fresh = cands.filter { it.key !in states }
            if (fresh.isNotEmpty()) return fresh.random(rng)
            // 本文章全抽过：记录 lastReview 最早的句子作为兜底
            for (c in cands) {
                val last = states[c.key]?.lastReview ?: continue
                if (last < fallbackLast) {
                    fallbackLast = last
                    fallback = c
                }
            }
        }
        return fallback
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
