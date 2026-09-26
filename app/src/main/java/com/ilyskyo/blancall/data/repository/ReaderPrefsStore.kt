// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.content.Context
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

    private var cache: MutableMap<Long, ReaderPrefs>? = null

    private fun load(): MutableMap<Long, ReaderPrefs> {
        cache?.let { return it }
        val map = mutableMapOf<Long, ReaderPrefs>()
        try {
            if (file.exists()) {
                val articles = JSONObject(file.readText()).optJSONObject("articles")
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
        } catch (_: Exception) {
            // 文件损坏时退化为空表（各文章回落全局基线），不阻塞阅读
        }
        cache = map
        return map
    }

    /** 该文章的存档；null = 从未改过（调用方回落到全局 AppPrefs 基线）。 */
    fun get(articleId: Long): ReaderPrefs? = load()[articleId]

    /** 写入该文章的存档（用户每次改设置即调用）。 */
    fun save(articleId: Long, prefs: ReaderPrefs) {
        val map = load()
        map[articleId] = prefs
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
            // 原子写 + fsync 统一到 AtomicFiles（旧实现直接 writeText，中断会损坏本文件）
            AtomicFiles.writeTextAtomic(
                file,
                JSONObject().apply {
                    put("version", 1)
                    put("articles", articles)
                }.toString()
            )
        } catch (_: Exception) {
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
