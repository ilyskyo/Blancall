// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
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
 * {"version":1,"articles":{"<articleId>":[{"id":..,"name":..,"createdAt":..,"mode":"WORD",
 *   "levels":[0,2,…],"contentHash":"<md5>","blanks":[{"s":0,"a":5,"b":9}]}]}}
 */
class CustomClozeStore private constructor(private val file: File) {

    data class BlankSpec(val s: Int, val a: Int, val b: Int)

    data class CustomConfig(
        val id: Long,
        val name: String,
        val createdAt: Long,
        val blanks: List<BlankSpec>,
        /** 目标练习模式：SENTENCE / WORD / REVERSE（旧配置无此字段默认 WORD） */
        val mode: String = "WORD",
        /** 每句拆分粒度（仅 WORD 模式有意义，下标=句索引，0..3）；旧配置为空列表 */
        val levels: List<Int> = emptyList(),
        /** 保存时的文章内容指纹（MD5 hex）；旧配置为 null，null 不做失配提示 */
        val contentHash: String? = null
    )

    private val lock = Any()

    /**
     * 读盘失败标记（主文件与备份都解析不了时为 true）。
     * 用于阻止随后的写盘：内存里此时是空 root，写下去会把其他文章的全部配置抹掉。
     */
    private var loadFailed = false

    private fun freshRoot(): JSONObject =
        JSONObject().put("version", CURRENT_VERSION).put("articles", JSONObject())

    /**
     * 读取根节点。
     *
     * 安全语义（重要）：解析失败时**不能**当作「空配置库」——旧实现直接返回 freshRoot()，
     * 之后的保存会以空 root 覆盖写盘，把其他文章的全部配置静默抹除。
     * 现在：① 主文件损坏 → 先回读 .bak；② 备份也不可用 → 置 loadFailed 并在 writeRoot 拒绝写盘。
     */
    private fun readRoot(): JSONObject {
        if (file.exists()) {
            try {
                loadFailed = false
                return migrate(JSONObject(file.readText()))
            } catch (_: Exception) {
                // 落到下方备份分支
            }
        }
        val bak = File(file.parentFile, file.name + ".bak")
        if (bak.exists()) {
            try {
                loadFailed = false
                return migrate(JSONObject(bak.readText()))
            } catch (_: Exception) {
                // 备份也不可用，落到下方
            }
        }
        // 文件确实不存在（首次使用）→ 正常空库；文件存在却读不出来且无备份 → 危险状态，禁止写盘
        loadFailed = file.exists()
        return freshRoot()
    }

    /** 版本迁移钩子：未来 schema 变更在此逐级迁移（当前无可迁移步骤，仅就位结构） */
    private fun migrate(root: JSONObject): JSONObject {
        val v = root.optInt("version", 1)
        if (v < CURRENT_VERSION) {
            // v1 → v2 …：逐级迁移后写回当前版本（本次仅在内存中标记，随下次写盘持久化）
            root.put("version", CURRENT_VERSION)
        }
        return root
    }

    private fun writeRoot(root: JSONObject) {
        if (loadFailed) {
            // 读盘失败时内存 root 为空，写盘会抹掉其他文章的配置 —— 宁可本次保存失败也不破坏数据
            Log.e("CustomClozeStore", "配置库读取失败，已阻止写盘以免覆盖其他文章的配置：${file.absolutePath}")
            return
        }
        try {
            file.parentFile?.mkdirs()
            // 原子写：先写临时文件再改名，避免写一半崩溃/被杀导致 JSON 损坏丢全部配置
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            // 覆盖前保留上一版本，供读取失败时回读（旧实现没有备份，损坏即全丢）
            if (file.exists()) {
                val bak = File(file.parentFile, file.name + ".bak")
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            if (!tmp.renameTo(file)) {
                // rename 失败（个别 ROM/占用）退回直接覆盖，至少不比原来差
                file.writeText(root.toString())
                tmp.delete()
            }
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
            val levels = mutableListOf<Int>()
            o.optJSONArray("levels")?.let { lv ->
                for (j in 0 until lv.length()) levels.add(lv.optInt(j, 0))
            }
            out.add(
                CustomConfig(
                    id = o.optLong("id"),
                    name = o.optString("name", "自定义"),
                    createdAt = o.optLong("createdAt"),
                    blanks = blanks,
                    mode = o.optString("mode", "WORD"),
                    levels = levels,
                    contentHash = o.optString("contentHash", "").ifEmpty { null }
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
        // createdAt：上层显式传 >0 用之；覆盖已有且上层未传（≤0）→ 保留磁盘原值，防列表排序跳变；其余取当前时间
        var createdAt = if (config.createdAt > 0) config.createdAt else 0L
        if (createdAt <= 0L && config.id > 0) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o?.optLong("id") == newId) {
                    createdAt = o.optLong("createdAt")
                    break
                }
            }
        }
        if (createdAt <= 0L) createdAt = System.currentTimeMillis()
        val entry = JSONObject()
            .put("id", newId)
            .put("name", config.name)
            .put("createdAt", createdAt)
            .put("mode", config.mode)
        if (config.levels.isNotEmpty()) {
            entry.put("levels", JSONArray().apply { config.levels.forEach { put(it) } })
        }
        if (!config.contentHash.isNullOrEmpty()) entry.put("contentHash", config.contentHash)
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
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: CustomClozeStore? = null

        fun getInstance(filesDir: java.io.File): CustomClozeStore =
            instance ?: synchronized(this) {
                instance ?: CustomClozeStore(File(filesDir, "custom_cloze.json")).also { instance = it }
            }
    }
}
