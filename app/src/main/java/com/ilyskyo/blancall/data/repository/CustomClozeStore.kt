// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义挖空配置存储（按文章保存，每篇文章可存多套配置）。
 *
 * 配置的 blanks 以「句子索引 + 句内字符区间 [a, b)」描述挖空位置，
 * 句子索引与 SentenceSplitter.split(全文) 的口径一致，
 * 可确定性构造成字词挖空的 WordClozeResult（练习/判分/进度恢复全线复用字词机制）。
 *
 * 持久化：filesDir/custom_cloze.json
 * {"version":1,"articles":{"<articleId>":[{"id":..,"name":..,"createdAt":..,"blanks":[{"s":0,"a":5,"b":9}]}]}}
 */
class CustomClozeStore private constructor(private val file: File) {

    data class BlankSpec(val s: Int, val a: Int, val b: Int)

    data class CustomConfig(
        val id: Long,
        val name: String,
        val createdAt: Long,
        val blanks: List<BlankSpec>,
        /** 目标练习模式：SENTENCE / WORD / REVERSE（旧配置无此字段默认 WORD） */
        val mode: String = "WORD"
    )

    private val lock = Any()

    private fun readRoot(): JSONObject {
        return try {
            if (file.exists()) JSONObject(file.readText()) else JSONObject().put("version", 1).put("articles", JSONObject())
        } catch (_: Exception) {
            JSONObject().put("version", 1).put("articles", JSONObject())
        }
    }

    private fun writeRoot(root: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        } catch (_: Exception) { /* 写失败不影响主流程，下次保存重试 */ }
    }

    private fun articlesObj(root: JSONObject): JSONObject =
        root.optJSONObject("articles") ?: JSONObject().also { root.put("articles", it) }

    /** 读取某篇文章的全部自定义配置（按创建时间升序） */
    fun getConfigs(articleId: Long): List<CustomConfig> = synchronized(lock) {
        val arr = articlesObj(readRoot()).optJSONArray(articleId.toString()) ?: return emptyList()
        val out = mutableListOf<CustomConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val blanks = mutableListOf<BlankSpec>()
            val bArr = o.optJSONArray("blanks")
            if (bArr != null) {
                for (j in 0 until bArr.length()) {
                    val b = bArr.optJSONObject(j) ?: continue
                    blanks.add(BlankSpec(b.optInt("s"), b.optInt("a"), b.optInt("b")))
                }
            }
            out.add(
                CustomConfig(
                    id = o.optLong("id"),
                    name = o.optString("name", "自定义"),
                    createdAt = o.optLong("createdAt"),
                    blanks = blanks,
                    mode = o.optString("mode", "WORD")
                )
            )
        }
        out.sortedBy { it.createdAt }
    }

    /** 保存配置：id<=0 视为新建（自动分配 id），否则按 id 覆盖更新 */
    fun saveConfig(articleId: Long, config: CustomConfig) = synchronized(lock) {
        val root = readRoot()
        val articles = articlesObj(root)
        val key = articleId.toString()
        val arr = articles.optJSONArray(key) ?: JSONArray().also { articles.put(key, it) }
        val newId = if (config.id > 0) config.id else {
            var max = 0L
            for (i in 0 until arr.length()) max = maxOf(max, arr.optJSONObject(i)?.optLong("id") ?: 0L)
            max + 1
        }
        val entry = JSONObject()
            .put("id", newId)
            .put("name", config.name)
            .put("createdAt", if (config.createdAt > 0) config.createdAt else System.currentTimeMillis())
            .put("mode", config.mode)
        val bArr = JSONArray()
        config.blanks.forEach { bArr.put(JSONObject().put("s", it.s).put("a", it.a).put("b", it.b)) }
        entry.put("blanks", bArr)
        // 覆盖同 id 或插入
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optLong("id") == newId) {
                arr.put(i, entry); replaced = true; break
            }
        }
        if (!replaced) arr.put(entry)
        writeRoot(root)
        newId
    }

    /** 删除某篇文章的一个配置 */
    fun deleteConfig(articleId: Long, configId: Long) = synchronized(lock) {
        val root = readRoot()
        val articles = articlesObj(root)
        val key = articleId.toString()
        val arr = articles.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optLong("id") == configId) {
                arr.remove(i)
                writeRoot(root)
                return
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CustomClozeStore? = null

        fun getInstance(filesDir: java.io.File): CustomClozeStore =
            instance ?: synchronized(this) {
                instance ?: CustomClozeStore(File(filesDir, "custom_cloze.json")).also { instance = it }
            }
    }
}
