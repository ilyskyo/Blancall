// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import com.ilyskyo.blancall.data.model.MistakeDetail
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.model.TagData
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据互换：人类可读的 JSON 导出 + CSV 导入。
 *
 * ## JSON 为什么不做「导入」
 * 整包备份（ZIP）已经承担完整恢复；这里的 JSON 是给用户看、给 Excel 分析的中间粒度。
 * 再做一个半吊子的 JSON 导入会与备份恢复争夺「谁是权威」——两条路径对同一份数据
 * 给出不同结果时，用户无法判断该信谁。CSV 导入只回灌练习记录（明确、可核对）。
 *
 * ## CSV 契约
 * 列顺序固定 12 列，表头随界面语言但**导入按列位解析**（不依赖语言）：
 * ID, 文章ID, 标题, 模式, 总空, 正确, 正确率, 用时秒, 时间, 错误数, 错误明细, 错题JSON。
 * 最后一列由本版本新增；旧 CSV（11 列）仍可导入，只是没有逐空错因。
 */
object DataInterchange {

    const val EXPORT_FORMAT = "blancall-export"
    const val EXPORT_VERSION = 1

    data class ImportedRecord(
        val articleId: Long,
        /** CSV 里的标题：文章 id 在新机器上对不上时用标题兜底匹配 */
        val articleTitle: String,
        val mode: String,
        val totalBlanks: Int,
        val correctCount: Int,
        val mistakes: List<MistakeDetail>,
        val timestamp: Long,
        val durationMs: Long,
    )

    data class CsvParse(
        val records: List<ImportedRecord>,
        val badRows: Int,
    )

    // ── JSON 导出 ──

    fun buildExportJson(
        records: List<PracticeRecord>,
        articleTitles: Map<Long, String>,
        sentenceStates: Map<String, FsrsEngine.CardState>,
        tags: TagData,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
            .put("format", EXPORT_FORMAT)
            .put("version", EXPORT_VERSION)
            .put("exportedAt", exportedAt)
            .put("records", JSONArray().apply {
                records.forEach { put(recordToJson(it, articleTitles[it.articleId].orEmpty())) }
            })
        root.put("sentenceStates", JSONArray().apply {
            sentenceStates.toSortedMap().forEach { (key, state) ->
                put(
                    JSONObject()
                        .put("key", key)
                        .put("difficulty", state.difficulty)
                        .put("stability", state.stability)
                        .put("due", state.due)
                        .put("lastReview", state.lastReview)
                        .put("reviewCount", state.reviewCount)
                        .put("lapses", state.lapses)
                        .put("lastRating", state.lastRating)
                )
            }
        })
        root.put("tags", JSONArray().apply {
            tags.tags.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
                        .put("color", String.format(Locale.ROOT, "#%06X", t.color and 0xFFFFFF))
                )
            }
        })
        root.put("tagLinks", JSONArray().apply {
            tags.links.entries.sortedBy { it.key }.forEach { (articleId, ids) ->
                put(
                    JSONObject()
                        .put("articleId", articleId)
                        .put("tagIds", JSONArray(ids.sorted()))
                )
            }
        })
        return root.toString(2)
    }

    private fun recordToJson(record: PracticeRecord, articleTitle: String): JSONObject {
        val obj = JSONObject()
            .put("id", record.id)
            .put("articleId", record.articleId)
            .put("articleTitle", articleTitle)
            .put("mode", record.mode)
            .put("totalBlanks", record.totalBlanks)
            .put("correctCount", record.correctCount)
            .put("timestamp", record.timestamp)
            .put("durationMs", record.duration)
            .put("similarity", record.similarity.toDouble())
            .put("rating", record.rating)
            .put("weakHints", record.weakHints)
            .put("strongHints", record.strongHints)
            .put("answeredSentenceStarts", JSONArray(record.answeredSentenceStarts))
            .put("mistakeSentenceIndices", JSONArray(record.mistakeSentenceIndices))
            .put("mistakes", JSONArray().apply {
                record.mistakes.forEach { m ->
                    put(
                        JSONObject()
                            .put("blankIndex", m.blankIndex)
                            .put("correctAnswer", m.correctAnswer)
                            .put("userAnswer", m.userAnswer)
                            .put("errorType", m.errorType)
                            .put("sentenceIndex", m.sentenceIndex)
                            .put("similarity", m.similarity.toDouble())
                            .put("sentenceText", m.sentenceText)
                    )
                }
            })
            .put("answeredBlanks", JSONArray().apply {
                record.answeredBlanks.forEach { a ->
                    put(
                        JSONObject()
                            .put("blankIndex", a.blankIndex)
                            .put("correctAnswer", a.correctAnswer)
                            .put("userAnswer", a.userAnswer)
                            .put("errorType", a.errorType)
                            .put("sentenceIndex", a.sentenceIndex)
                            .put("similarity", a.similarity.toDouble())
                            .put("sentenceText", a.sentenceText)
                    )
                }
            })
        return obj
    }

    // ── CSV 导入 ──

    private const val CSV_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun parseCsv(text: String): CsvParse {
        val rows = parseCsvRows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return CsvParse(emptyList(), 0)
        val out = ArrayList<ImportedRecord>()
        var bad = 0
        val format = SimpleDateFormat(CSV_TIME_PATTERN, Locale.getDefault())
        // 第一行是表头（列名随语言），按列位解析，不依赖表头文字
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            if (row.size < 10) {
                bad++
                continue
            }
            val articleId = row.getOrNull(1)?.trim()?.toLongOrNull()
            val total = row.getOrNull(4)?.trim()?.toIntOrNull()
            val correct = row.getOrNull(5)?.trim()?.toIntOrNull()
            val timestamp = row.getOrNull(8)?.trim()?.let {
                runCatching { format.parse(it)?.time }.getOrNull()
            }
            if (articleId == null || articleId <= 0L || total == null || total <= 0 ||
                correct == null || timestamp == null
            ) {
                bad++
                continue
            }
            val mistakes = parseMistakes(row.getOrNull(11))
            out += ImportedRecord(
                articleId = articleId,
                articleTitle = row.getOrNull(2)?.trim().orEmpty(),
                mode = row.getOrNull(3)?.trim().orEmpty(),
                totalBlanks = total,
                correctCount = correct.coerceIn(0, total),
                mistakes = mistakes,
                timestamp = timestamp,
                durationMs = (row.getOrNull(7)?.trim()?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
        return CsvParse(out, bad)
    }

    private fun parseMistakes(raw: String?): List<MistakeDetail> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                MistakeDetail(
                    blankIndex = o.optInt("blankIndex", 0),
                    correctAnswer = o.optString("correctAnswer", ""),
                    userAnswer = o.optString("userAnswer", ""),
                    errorType = o.optString("errorType", ""),
                    sentenceIndex = o.optInt("sentenceIndex", -1),
                    similarity = o.optDouble("similarity", -1.0).toFloat(),
                    sentenceText = o.optString("sentenceText", ""),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** RFC 4180：引号包裹、双引号转义、字段内逗号/换行。 */
    internal fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> {
                    row.add(field.toString()); field.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    /** 供 CSV 导出使用：错题明细的无损列（旧版程序不认这一列，忽略即可）。 */
    fun mistakesToCsvJson(mistakes: List<MistakeDetail>): String =
        JSONArray().apply {
            mistakes.forEach { m ->
                put(
                    JSONObject()
                        .put("blankIndex", m.blankIndex)
                        .put("correctAnswer", m.correctAnswer)
                        .put("userAnswer", m.userAnswer)
                        .put("errorType", m.errorType)
                        .put("sentenceIndex", m.sentenceIndex)
                        .put("similarity", m.similarity.toDouble())
                        .put("sentenceText", m.sentenceText)
                )
            }
        }.toString()
}
