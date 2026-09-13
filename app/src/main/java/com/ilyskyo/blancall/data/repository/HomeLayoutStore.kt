// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 首页卡片布局存储。
 *
 * 首页从「纵向列表」改为「二维网格画布」后，每张卡的类型/位置/尺寸/固定状态需要持久化：
 * - **顺序** = cards 数组顺序：网格按此顺序流式排布（宽度吃满一行后换行）
 * - **尺寸**：`colSpan` 占 1~2 列（画布固定 2 列）；`rowSpan` 占 1~4 行（行高由 UI 决定）
 * - **pinned**：左上角大头针。固定的卡片在重排（新增/拖拽其他卡）时保持其槽位不被移动
 *
 * 卡片 id 约定：
 * - 系统卡：`"due"` / `"continue"` / `"recent"`（内容由系统提供，可删除后重新添加）
 * - 入口卡：`"add"`（添加文章）
 * - 文章卡：`"article:<articleId>"`（单独把某一篇文章放成一张卡，refId = 文章 id）
 * - 自定义卡：`"cloze:<configId>"` / `"mask:<configId>"`（关联用户的自定义挖空/遮挡配置）
 *
 * 持久化：filesDir/home_layout.json
 * {"version":1,"cards":[{"id":"due","type":"DUE","refId":-1,"colSpan":2,"rowSpan":1,"pinned":false,"title":""}]}
 */
class HomeLayoutStore private constructor(private val file: File) {

    /** 卡片类型 */
    enum class CardType {
        /** 待复习（聚合系统卡） */
        DUE,

        /** 继续做（未完成的练习） */
        CONTINUE,

        /** 最近文章 */
        RECENT,

        /** 自定义挖空（关联一个 CustomClozeStore 配置） */
        CUSTOM_CLOZE,

        /** 自定义遮挡（关联一个 MaskConfigStore 配置） */
        CUSTOM_MASK,

        /** 添加文章（入口卡） */
        ADD_ARTICLE,

        /**
         * 学习数据（近期/本次练习摘要，点进入统计页）。
         * 用户已把这张卡放到首页时，做完练习不再额外弹学习数据横幅（避免重复）。
         */
        STATS,

        /** 全局数据（累计统计：练习次数 / 正确率 / 累计字数 / 覆盖文章数） */
        GLOBAL_STATS,

        /**
         * 文章卡片：把某一篇具体文章单独放成一张卡（`refId` = 文章 id）。
         * 与「最近文章」不同：内容固定为指定文章，不随最近打开变化。
         */
        ARTICLE
    }

    /** 一张首页卡片 */
    data class Card(
        /** 实例唯一 id，见类注释的约定 */
        val id: String,
        val type: CardType,
        /** 关联的配置 id（仅 CUSTOM_CLOZE / CUSTOM_MASK 有意义） */
        val refId: Long = -1L,
        /** 占列数（1~2） */
        val colSpan: Int = 2,
        /** 占行数（1~4） */
        val rowSpan: Int = 1,
        /** 大头针：固定后重排时不移动该卡 */
        val pinned: Boolean = false,
        /** 锁定的槽位行（仅 pinned 时有意义；-1 = 未设定，交给流式布局） */
        val lockRow: Int = -1,
        /** 锁定的槽位列（仅 pinned 时有意义；-1 = 未设定） */
        val lockCol: Int = -1,
        /** 自定义卡的显示名快照（配置被改名前的兜底展示） */
        val title: String = ""
    ) {
        /** 是否带「笔」修改入口（自定义卡才可编辑其配置） */
        val editable: Boolean
            get() = type == CardType.CUSTOM_CLOZE || type == CardType.CUSTOM_MASK
    }

    private val lock = Any()

    /**
     * 读盘失败标记（主文件与备份都解析不了时为 true）→ 阻止随后的写盘，
     * 避免用空 root 覆盖写把用户精心排好的布局抹掉。
     */
    private var loadFailed = false

    private fun freshRoot(): JSONObject =
        JSONObject().put("version", CURRENT_VERSION).put("cards", JSONArray())

    /**
     * 读取根节点。与 MaskConfigStore 同款安全语义：
     * ① 主文件损坏 → 回读 .bak；② 备份也不可用 → 置 loadFailed 并拒绝写盘。
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
        // 文件确实不存在（首次使用）→ 空库；文件存在却读不出来且无备份 → 危险状态，禁止写盘
        loadFailed = file.exists()
        return freshRoot()
    }

    /** 版本迁移钩子 */
    private fun migrate(root: JSONObject): JSONObject {
        val v = root.optInt("version", 1)
        if (v < CURRENT_VERSION) root.put("version", CURRENT_VERSION)
        return root
    }

    private fun writeRoot(root: JSONObject) {
        if (loadFailed) {
            Log.e("HomeLayoutStore", "布局读取失败，已阻止写盘以免覆盖已有布局：${file.absolutePath}")
            return
        }
        try {
            file.parentFile?.mkdirs()
            // 原子写：先写临时文件再改名，避免写一半被杀导致 JSON 损坏丢全部布局
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (file.exists()) {
                val bak = File(file.parentFile, file.name + ".bak")
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) { /* 写失败不影响主流程，下次保存重试 */ }
    }

    /** 尺寸收敛：列 1~2、行 1~4（防手工编辑 JSON 或旧数据越界把布局撑坏） */
    private fun clampCol(v: Int) = v.coerceIn(MIN_COL_SPAN, MAX_COL_SPAN)
    private fun clampRow(v: Int) = v.coerceIn(MIN_ROW_SPAN, MAX_ROW_SPAN)

    /**
     * 读取卡片列表。
     * 无记录（首次使用）返回 [defaultCards]；有记录但被解析为空数组时同样回退默认，避免首页空无一物。
     */
    fun getCards(): List<Card> = synchronized(lock) {
        val arr = readRoot().optJSONArray("cards") ?: return defaultCards()
        val out = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").ifBlank { continue }
            if (!seen.add(id)) continue  // 去重：同一张卡只允许出现一次
            val type = runCatching { CardType.valueOf(o.optString("type", "")) }.getOrNull() ?: continue
            out.add(
                Card(
                    id = id,
                    type = type,
                    refId = o.optLong("refId", -1L),
                    colSpan = clampCol(o.optInt("colSpan", 2)),
                    rowSpan = clampRow(o.optInt("rowSpan", 1)),
                    pinned = o.optBoolean("pinned", false),
                    lockRow = o.optInt("lockRow", -1),
                    lockCol = o.optInt("lockCol", -1),
                    title = o.optString("title", "")
                )
            )
        }
        if (out.isEmpty()) defaultCards() else out
    }

    /** 覆盖保存整套布局（首页的增删改拖动最终都汇聚到这里） */
    fun saveCards(cards: List<Card>) = synchronized(lock) {
        val root = readRoot()
        val arr = JSONArray()
        cards.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("type", c.type.name)
                    .put("refId", c.refId)
                    .put("colSpan", clampCol(c.colSpan))
                    .put("rowSpan", clampRow(c.rowSpan))
                    .put("pinned", c.pinned)
                    .put("lockRow", c.lockRow)
                    .put("lockCol", c.lockCol)
                    .put("title", c.title)
            )
        }
        root.put("cards", arr)
        writeRoot(root)
    }

    companion object {
        private const val CURRENT_VERSION = 1

        /** 画布固定列数 */
        const val COLUMNS = 2
        const val MIN_COL_SPAN = 1
        const val MAX_COL_SPAN = 2
        const val MIN_ROW_SPAN = 1
        const val MAX_ROW_SPAN = 4

        const val CARD_ID_DUE = "due"
        const val CARD_ID_CONTINUE = "continue"
        const val CARD_ID_RECENT = "recent"
        const val CARD_ID_ADD = "add"
        const val CARD_ID_STATS = "stats"
        const val CARD_ID_GLOBAL_STATS = "global_stats"

        fun clozeCardId(configId: Long) = "cloze:$configId"
        fun maskCardId(configId: Long) = "mask:$configId"
        fun articleCardId(articleId: Long) = "article:$articleId"

        @Volatile
        private var instance: HomeLayoutStore? = null

        fun getInstance(filesDir: File): HomeLayoutStore =
            instance ?: synchronized(this) {
                instance ?: HomeLayoutStore(File(filesDir, "home_layout.json")).also { instance = it }
            }

        /** 首次使用的默认布局：待复习 + 继续做 + 最近文章 + 添加文章入口 */
        fun defaultCards(): List<Card> = listOf(
            Card(CARD_ID_DUE, CardType.DUE),
            Card(CARD_ID_CONTINUE, CardType.CONTINUE),
            Card(CARD_ID_RECENT, CardType.RECENT),
            Card(CARD_ID_ADD, CardType.ADD_ARTICLE)
        )
    }
}
