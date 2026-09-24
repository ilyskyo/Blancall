// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.json.JSONObject
import java.io.File

/**
 * 「句子卡片」每日快照持久化（sentence_card.json）。
 *
 * 只存"今天抽到的那一句"：日期 + 句子键 + 归属文章 + 句文与位置快照 + 标题快照。
 * 同一天内重复进入页面必须展示同一句（抽句幂等），跨日由 [DailySentenceCoordinator] 重抽。
 *
 * 与其它 Store 的差异：**不设 loadFailed 写保护**——快照是可再生数据
 * （损坏/缺失只意味着今天没抽过），直接重抽覆盖即可，无需 corrupt 保留与拒绝写盘。
 * 写盘仍保持「tmp + rename（+ .bak 轮换）」的原子语义，避免写一半被杀导致 JSON 损坏。
 *
 * 文件格式：
 * {"version":1,"date":"2026-09-24","key":"s:12:ab12cd34ef56ab78","articleId":12,
 *  "text":"……","start":345,"end":372,"title":"沁园春·长沙"}
 */
class SentenceCardStore private constructor(private val file: File) {

    /** 今日句快照 */
    data class Snapshot(
        /** 抽取日期（yyyy-MM-dd，系统默认时区，与 FSRS 同日判定同口径） */
        val date: String,
        /** 句子记忆状态键：s:<articleId>:<hash16> */
        val key: String,
        /** 归属文章 id */
        val articleId: Long,
        /** 句文快照（展示与有效性校验用） */
        val text: String,
        /** 句文在文章全文中的起始字符位置（-1 = 未知） */
        val start: Int,
        /** 句文在文章全文中的结束字符位置（exclusive；-1 = 未知） */
        val end: Int,
        /** 文章标题快照（文章被删时的展示兜底） */
        val title: String,
    )

    private val lock = Any()
    private val bak = File(file.parentFile, file.name + ".bak")

    companion object {
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: SentenceCardStore? = null

        fun getInstance(filesDir: File): SentenceCardStore =
            instance ?: synchronized(this) {
                instance ?: SentenceCardStore(File(filesDir, "sentence_card.json")).also { instance = it }
            }
    }

    /** 读取今日句快照；无快照或文件损坏返回 null（调用方据此重抽） */
    fun today(): Snapshot? = synchronized(lock) { readLocked() }

    /**
     * 仅当同日尚无快照时写入（锁内判日）：
     * 首个写入生效，后续同日调用直接返回已存在的快照——保证同一天并发/重复调用只抽一次。
     */
    fun setTodayIfAbsent(date: String, snapshot: Snapshot): Snapshot = synchronized(lock) {
        val cur = readLocked()
        if (cur != null && cur.date == date) return cur
        writeLocked(snapshot)
        snapshot
    }

    /** 删除今日快照（今日句所属文章被删除时的级联清理） */
    fun clearIfArticle(articleId: Long) {
        synchronized(lock) {
            val cur = readLocked()
            if (cur != null && cur.articleId == articleId) {
                runCatching { file.delete() }
                runCatching { bak.delete() }
            }
        }
    }

    /** 清除今日快照（快照失效——文章被编辑导致句文不存在——后重抽用） */
    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            runCatching { bak.delete() }
        }
    }

    private fun readLocked(): Snapshot? = try {
        if (!file.exists()) null else parse(JSONObject(file.readText()))
    } catch (_: Exception) {
        null
    }

    private fun parse(o: JSONObject): Snapshot? {
        val date = o.optString("date", "")
        val key = o.optString("key", "")
        val text = o.optString("text", "")
        if (date.isBlank() || key.isBlank() || text.isBlank()) return null
        return Snapshot(
            date = date,
            key = key,
            articleId = o.optLong("articleId", -1L),
            text = text,
            start = o.optInt("start", -1),
            end = o.optInt("end", -1),
            title = o.optString("title", ""),
        )
    }

    private fun writeLocked(s: Snapshot) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("version", CURRENT_VERSION)
                .put("date", s.date)
                .put("key", s.key)
                .put("articleId", s.articleId)
                .put("text", s.text)
                .put("start", s.start)
                .put("end", s.end)
                .put("title", s.title)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (file.exists()) {
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (_: Exception) { /* 写失败不阻塞展示，下次 ensureToday 重试 */ }
    }
}
