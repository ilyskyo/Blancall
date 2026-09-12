// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 阅读模式「遮挡自定义」配置存储（按文章保存，每篇文章可存多套配置）。
 *
 * 配置的 spans 以「段落索引 + 段内字符区间 [a, e) + 挡片颜色索引」描述遮挡位置：
 * - 段落索引与 ReaderOcclusion.splitParagraphs(全文) 的口径一致（按空行段落），
 *   段内区间对应该段落 trim 后文本的局部偏移；
 * - 颜色索引 0-5 对应阅读设置挡片颜色同款马卡龙色板（每块遮挡可独立选色）。
 *
 * 持久化：filesDir/mask_config.json
 * {"version":1,"articles":{"<articleId>":[{"id":..,"name":..,"createdAt":..,
 *   "contentHash":"<md5>","spans":[{"p":0,"a":5,"e":9,"c":2}]}]},"selected":{"<articleId>":<configId>}}
 */
class MaskConfigStore private constructor(private val file: File) {

    /** 一块遮挡：第 [p] 段（splitParagraphs 口径）内 [a, e) 区间，颜色 [c]（0-5） */
    data class MaskSpan(val p: Int, val a: Int, val e: Int, val c: Int = 0)

    data class MaskConfig(
        val id: Long,
        val name: String,
        val createdAt: Long,
        val spans: List<MaskSpan>,
        /** 保存时的文章内容指纹（MD5 hex）；旧配置为 null，null 不做失配提示 */
        val contentHash: String? = null
    )

    private val lock = Any()

    /** 内存修订号：任何写操作自增。阅读页用它做重算 key，配置变更后遮挡立即刷新 */
    private var revision = 0L

    /** 当前修订号（供 UI 作为重算 key） */
    fun getRevision(): Long = synchronized(lock) { revision }

    /**
     * 读盘失败标记（主文件与备份都解析不了时为 true）。
     * 用于阻止随后的写盘：内存里此时是空 root，写下去会把其他文章的全部配置抹掉。
     */
    private var loadFailed = false

    private fun freshRoot(): JSONObject =
        JSONObject().put("version", CURRENT_VERSION).put("articles", JSONObject()).put("selected", JSONObject())

    /**
     * 读取根节点。
     *
     * 安全语义（重要）：解析失败时**不能**当作「空配置库」——旧实现直接返回 freshRoot()，
     * 之后的保存会以空 root 覆盖写盘，把其他文章的全部遮挡配置静默抹除。
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
            Log.e("MaskConfigStore", "配置库读取失败，已阻止写盘以免覆盖其他文章的配置：${file.absolutePath}")
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

    private fun selectedObj(root: JSONObject): JSONObject =
        root.optJSONObject("selected") ?: JSONObject().also { root.put("selected", it) }

    /** 读取某篇文章的全部遮挡配置（按创建时间升序） */
    fun getConfigs(articleId: Long): List<MaskConfig> = synchronized(lock) {
        val arr = articlesObj(readRoot()).optJSONArray(articleId.toString()) ?: return emptyList()
        val out = mutableListOf<MaskConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val spans = mutableListOf<MaskSpan>()
            val sArr = o.optJSONArray("spans")
            if (sArr != null) {
                for (j in 0 until sArr.length()) {
                    val s = sArr.optJSONObject(j) ?: continue
                    spans.add(MaskSpan(s.optInt("p"), s.optInt("a"), s.optInt("e"), s.optInt("c")))
                }
            }
            out.add(
                MaskConfig(
                    id = o.optLong("id"),
                    name = o.optString("name", "自定义"),
                    createdAt = o.optLong("createdAt"),
                    spans = spans,
                    contentHash = o.optString("contentHash", "").ifEmpty { null }
                )
            )
        }
        out.sortedBy { it.createdAt }
    }

    /** 保存配置：id<=0 视为新建（自动分配 id），否则按 id 覆盖更新。返回实际 id */
    fun saveConfig(articleId: Long, config: MaskConfig) = synchronized(lock) {
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
        if (!config.contentHash.isNullOrEmpty()) entry.put("contentHash", config.contentHash)
        val sArr = JSONArray()
        config.spans.forEach { sArr.put(JSONObject().put("p", it.p).put("a", it.a).put("e", it.e).put("c", it.c)) }
        entry.put("spans", sArr)
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optLong("id") == newId) {
                arr.put(i, entry); replaced = true; break
            }
        }
        if (!replaced) arr.put(entry)
        revision++
        writeRoot(root)
        newId
    }

    /** 删除某篇文章的一个配置；若该配置正被使用则同时清除使用标记 */
    fun deleteConfig(articleId: Long, configId: Long) = synchronized(lock) {
        val root = readRoot()
        val articles = articlesObj(root)
        val key = articleId.toString()
        val arr = articles.optJSONArray(key)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optLong("id") == configId) {
                    arr.remove(i)
                    break
                }
            }
        }
        if (selectedObj(root).optLong(key, -1L) == configId) {
            selectedObj(root).remove(key)
        }
        revision++
        writeRoot(root)
    }

    /** 该文章当前「使用中」的配置 id（-1 = 未选择） */
    fun getSelected(articleId: Long): Long = synchronized(lock) {
        selectedObj(readRoot()).optLong(articleId.toString(), -1L)
    }

    /** 标记某篇文章当前使用的配置（遮挡粒度切到 custom 时读取） */
    fun setSelected(articleId: Long, configId: Long) = synchronized(lock) {
        val root = readRoot()
        selectedObj(root).put(articleId.toString(), configId)
        revision++
        writeRoot(root)
    }

    companion object {
        private const val CURRENT_VERSION = 1

        @Volatile
        private var instance: MaskConfigStore? = null

        fun getInstance(filesDir: java.io.File): MaskConfigStore =
            instance ?: synchronized(this) {
                instance ?: MaskConfigStore(File(filesDir, "mask_config.json")).also { instance = it }
            }
    }
}
