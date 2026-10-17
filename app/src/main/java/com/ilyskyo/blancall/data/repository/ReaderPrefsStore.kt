// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.content.Context
import android.util.Log
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONObject
import java.io.File

/**
 * 单篇文章的阅读设置（阅读页「阅读设置」面板的全部项）。
 *
 * ## 为什么按文章存（真机反馈）
 * 旧实现所有阅读设置都写全局 AppPrefs：在 A 文章调了「米白底 + 霞雾文楷 + 超大字号 + 遮挡」，
 * 打开 B 文章也带同一套 —— 用户要求「哪一篇文章里面的设置就仅针对那一篇文章」。
 * 现在：**改动只写当前文章的存档**；**没有存档的文章以全局 AppPrefs 的值作为起点**
 * （兼容既有用户的全局偏好；且新机制下文章内的改动不再污染全局基线）。
 *
 * 存储：filesDir/reader_prefs.json
 * `{"version":1,"articles":{"<articleId>":{"bgMode":0,...}}}`
 *
 * 构造函数 internal：生产代码统一走 [getInstance]（进程级单例），
 * open 给单测用临时文件直就连实例，验证序列化口径（含 occlusionCustomConfigId）。
 */
data class ReaderPrefs(
    val bgMode: Int,
    val fontId: String,
    val fontWeight: Int,
    val fontPx: Float,
    val lineHeight: Float,
    val layoutMode: Int,
    val occlusionEnabled: Boolean,
    val occlusionMode: String,
    val occlusionColor: Int,
    val occlusionCustomConfigId: Long
)

class ReaderPrefsStore internal constructor(private val file: File) {

    /**
     * 全类唯一的一把锁。旧实现完全没有同步：`save()` 先往共享 map 写入、紧接着遍历同一个 map
     * 做序列化，另一线程的 `save()` 插进来遍历时抛 ConcurrentModificationException，
     * 又被空 catch 吞掉 —— 用户那次改阅读设置**静默不落盘**。
     */
    private val lock = Any()

    /** 由 [lock] 守护，所以不需要 @Volatile：所有取用点都在锁内 */
    private var cache: MutableMap<Long, ReaderPrefs>? = null

    /**
     * 粘性危险标记：只有「成功解析主文件或备份」才清除。
     * 置位期间 [writeLocked] 拒绝落盘 —— 本类的写路径是**整表回写**，
     * 若把损坏当空表继续写，第一次改阅读设置就会抹掉其余所有文章的存档。
     */
    private var loadFailed = false

    /** 把一份 reader_prefs JSON 文本解析进 map（主文件与备份共用，保证字段口径一致） */
    private fun parseInto(text: String, map: MutableMap<Long, ReaderPrefs>) {
        val articles = JSONObject(text).optJSONObject("articles")
        articles?.keys()?.forEach { k ->
            val o = articles.optJSONObject(k) ?: return@forEach
            val id = k.toLongOrNull() ?: return@forEach
            map[id] = ReaderPrefs(
                bgMode = o.optInt("bgMode", 0),
                fontId = o.optString("fontId", "0"),
                fontWeight = o.optInt("fontWeight", 400),
                fontPx = o.optDouble("fontPx", 17.0).toFloat(),
                lineHeight = o.optDouble("lineHeight", 2.0).toFloat(),
                layoutMode = o.optInt("layoutMode", 0),
                occlusionEnabled = o.optBoolean("occlusionEnabled", false),
                occlusionMode = o.optString("occlusionMode", "long"),
                occlusionColor = o.optInt("occlusionColor", 0),
                occlusionCustomConfigId = o.optLong("occlusionCustomConfigId", -1L)
            )
        }
    }

    /** 必须在 [lock] 内调用。 */
    private fun loadLocked(): MutableMap<Long, ReaderPrefs> {
        cache?.let { return it }
        val map = mutableMapOf<Long, ReaderPrefs>()
        if (file.exists()) {
            try {
                parseInto(file.readText(), map)
                loadFailed = false
                cache = map
                return map
            } catch (_: Exception) {
                // 主文件损坏：先记粘性标记，再另存 .corrupt-<ts> 保留现场，然后回读备份
                loadFailed = true
                preserveCorruptFile()
            }
        }
        val bak = File(file.parentFile, file.name + ".bak")
        if (bak.exists()) {
            try {
                parseInto(bak.readText(), map)
                loadFailed = false
            } catch (_: Exception) {
                // 备份也不可用，保持 loadFailed：宁可本次保存失败，也不以空表抹掉全库
            }
        }
        cache = map
        return map
    }

    /**
     * 主文件解析失败时另存一份 .corrupt-<ts>（尽力而为）：
     * 否则后续写盘会以 bak 快照 + 本次改动覆盖主文件，损坏现场（可能比 bak 更新）无法人工找回。
     */
    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /** 必须在 [lock] 内调用：整表回写，因此调用方的 map 就是最新内存镜像。 */
    private fun writeLocked(map: Map<Long, ReaderPrefs>) {
        if (loadFailed) {
            Log.e(
                "ReaderPrefsStore",
                "读取失败，已阻止写盘以免抹掉其余文章的阅读存档：${file.absolutePath}"
            )
            return
        }
        try {
            val articles = JSONObject()
            map.forEach { (id, p) ->
                articles.put(
                    id.toString(),
                    JSONObject().apply {
                        put("bgMode", p.bgMode)
                        put("fontId", p.fontId)
                        put("fontWeight", p.fontWeight)
                        put("fontPx", p.fontPx.toDouble())
                        put("lineHeight", p.lineHeight.toDouble())
                        put("layoutMode", p.layoutMode)
                        put("occlusionEnabled", p.occlusionEnabled)
                        put("occlusionMode", p.occlusionMode)
                        put("occlusionColor", p.occlusionColor)
                        put("occlusionCustomConfigId", p.occlusionCustomConfigId)
                    }
                )
            }
            // 原子写 + fsync + 备份轮换统一到 AtomicFiles（旧实现直接 writeText，中断会损坏本文件）
            AtomicFiles.writeTextAtomic(
                file,
                JSONObject().apply {
                    put("version", 1)
                    put("articles", articles)
                }.toString()
            )
        } catch (e: Exception) {
            // 旧实现是空 catch：写失败必须留日志，否则「改了设置没生效」根本无从排查
            Log.e("ReaderPrefsStore", "阅读设置落盘失败：${file.absolutePath}", e)
        }
    }

    /** 该文章的存档；null = 从未改过（调用方回落到全局 AppPrefs 基线）。 */
    fun get(articleId: Long): ReaderPrefs? = synchronized(lock) { loadLocked()[articleId] }

    /** 备份导入后重载（B1-3）：清缓存让下次读取走磁盘（lazy cache 语义的最小正确操作） */
    fun reload() {
        synchronized(lock) { cache = null }
    }

    /** 写入该文章的存档（用户每次改设置即调用）。 */
    fun save(articleId: Long, prefs: ReaderPrefs) {
        synchronized(lock) {
            val map = loadLocked()
            map[articleId] = prefs
            writeLocked(map)
        }
    }

    /**
     * 删除该文章的存档（删除文章时的级联）。
     * 本类原先没有这个 API，所以 `ArticleViewModel` 的删除路径清不到它 ——
     * 每删一篇永久留一条孤儿，reader_prefs.json 无界增长。
     */
    fun removeArticle(articleId: Long) {
        synchronized(lock) {
            val map = loadLocked()
            // 没有该文章的存档就不必整表重写（省一次落盘，也不把无谓的写失败暴露给用户）
            if (map.remove(articleId) == null) return
            writeLocked(map)
        }
    }

    companion object {
        @Volatile private var instance: ReaderPrefsStore? = null
        fun getInstance(context: Context): ReaderPrefsStore =
            instance ?: synchronized(this) {
                instance ?: ReaderPrefsStore(File(context.filesDir, "reader_prefs.json"))
                    .also { instance = it }
            }
    }
}
