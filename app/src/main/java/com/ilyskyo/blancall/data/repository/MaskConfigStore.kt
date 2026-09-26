// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.util.AtomicFiles
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
     * 现在：① 主文件损坏 → 先另存 .corrupt-<ts>（供事后人工恢复）再回读 .bak；
     * ② 备份也不可用 → loadFailed 置位并在 writeRoot 拒绝写盘。
     *
     * loadFailed 是**粘性**标记：主文件读坏即置位，只有「成功解析主文件或备份」才清除。
     * 旧实现在尾部用 `loadFailed = file.exists()` 判定 —— 主文件损坏时已被
     * preserveCorruptFile 改名搬走，该表达式恒为 false，会把「损坏且无备份」这条危险
     * 路径误判成「首次使用」放行写盘，下一次保存即以空 root 抹掉全库（已修复）。
     */
    private fun readRoot(): JSONObject {
        if (file.exists()) {
            try {
                loadFailed = false
                return migrate(JSONObject(file.readText()))
            } catch (_: Exception) {
                // 主文件损坏：置粘性危险标记（仅由成功解析主/备份清除）并保留现场
                loadFailed = true
                preserveCorruptFile()
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
        // 直接返回空库：loadFailed 保留「是否损坏过」的粘性结论 ——
        // 首次使用为 false（正常空库、可写）；损坏搬迁后为 true（writeRoot 拒绝写盘）
        return freshRoot()
    }

    /**
     * 主文件解析失败时另存一份 .corrupt-<ts>（尽力而为）：
     * 否则后续写盘会以 bak 快照 + 新改动覆盖主文件，损坏现场（可能含比 bak 更新的配置）无法人工找回。
     */
    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
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
            // 原子写 + fsync + 备份轮换统一到 AtomicFiles（tmp → fsync → .bak → rename → 目录 fsync）
            AtomicFiles.writeTextAtomic(file, root.toString())
        } catch (_: Exception) { /* 写失败不影响主流程，下次保存重试 */ }
    }

    private fun articlesObj(root: JSONObject): JSONObject =
        root.optJSONObject("articles") ?: JSONObject().also { root.put("articles", it) }

    private fun selectedObj(root: JSONObject): JSONObject =
        root.optJSONObject("selected") ?: JSONObject().also { root.put("selected", it) }

    /** 解析单个配置对象（getConfigs / listAll / getConfig 共用，保证字段口径一致） */
    private fun parseConfig(o: JSONObject): MaskConfig {
        val spans = mutableListOf<MaskSpan>()
        val sArr = o.optJSONArray("spans")
        if (sArr != null) {
            for (j in 0 until sArr.length()) {
                val s = sArr.optJSONObject(j) ?: continue
                spans.add(MaskSpan(s.optInt("p"), s.optInt("a"), s.optInt("e"), s.optInt("c")))
            }
        }
        return MaskConfig(
            id = o.optLong("id"),
            name = o.optString("name", "自定义"),
            createdAt = o.optLong("createdAt"),
            spans = spans,
            contentHash = o.optString("contentHash", "").ifEmpty { null }
        )
    }

    /** 读取某篇文章的全部遮挡配置（按创建时间升序） */
    fun getConfigs(articleId: Long): List<MaskConfig> = synchronized(lock) {
        val arr = articlesObj(readRoot()).optJSONArray(articleId.toString()) ?: return emptyList()
        val out = mutableListOf<MaskConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseConfig(o))
        }
        out.sortedBy { it.createdAt }
    }

    /** 按 (articleId, configId) 精确取单套配置（不存在返回 null；不含跨文章扫描） */
    fun getConfig(articleId: Long, configId: Long): MaskConfig? = synchronized(lock) {
        if (articleId <= 0L || configId <= 0L) return null
        val arr = articlesObj(readRoot()).optJSONArray(articleId.toString()) ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") == configId) return parseConfig(o)
        }
        null
    }

    /**
     * 反查某配置所属文章。
     * - [articleId] > 0：按 (articleId, configId) 精确命中（不跨文章），不存在返回 null；
     * - [articleId] <= 0：全库扫描按 configId 找第一个（仅供无 articleId 的历史卡片兼容）。
     * configId 仅文章内自增，非全局唯一，新调用方一律传 articleId。
     */
    fun findArticleIdByConfigId(configId: Long, articleId: Long = -1L): Long? = synchronized(lock) {
        if (configId <= 0L) return null
        val articles = articlesObj(readRoot())
        if (articleId > 0L) {
            val arr = articles.optJSONArray(articleId.toString()) ?: return null
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optLong("id") == configId) return articleId
            }
            return null
        }
        val keys = articles.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val aid = key.toLongOrNull() ?: continue
            val arr = articles.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optLong("id") == configId) return aid
            }
        }
        return null
    }

    /** 全部配置（文章 id + 配置），供首页「添加卡片」等枚举（主文件损坏时回读 .bak，与 getConfigs 同语义） */
    fun listAll(): List<Pair<Long, MaskConfig>> = synchronized(lock) {
        val articles = articlesObj(readRoot())
        val out = mutableListOf<Pair<Long, MaskConfig>>()
        val keys = articles.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val aid = key.toLongOrNull() ?: continue
            val arr = articles.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optLong("id") <= 0L) continue
                out.add(aid to parseConfig(o))
            }
        }
        out
    }

    /** 删除某篇文章的全部配置（文章删除时级联清理）；该文章的使用标记一并清除 */
    fun removeArticle(articleId: Long) = synchronized(lock) {
        val root = readRoot()
        val articles = articlesObj(root)
        val key = articleId.toString()
        val had = articles.has(key) || selectedObj(root).has(key)
        if (articles.has(key)) articles.remove(key)
        selectedObj(root).remove(key)
        if (had) {
            revision++
            writeRoot(root)
        }
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
