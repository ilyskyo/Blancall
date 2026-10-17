// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.util.AtomicFiles
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
 * - 系统卡：`"due"` / `"continue"` / `"recent"` / `"sentence"`（内容由系统提供，可删除后重新添加）
 * - 入口卡：`"add"`（添加文章）
 * - 文章卡：`"article:<articleId>"`（单独把某一篇文章放成一张卡，refId = 文章 id）
 * - 自定义卡：`"cloze:<articleId>:<configId>"` / `"mask:<articleId>:<configId>"`（关联用户的自定义挖空/遮挡配置；
 *   配置 id 仅文章内自增、非全局唯一，必须带文章 id 才能唯一定位。旧格式 `"cloze:<configId>"` 仍可读，
 *   反查时按 configId 全库兼容扫描）
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
        ARTICLE,

        /**
         * 句子卡片（每日一句）：每天自动抽一个完整句子展示，点入大卡片做句子级 FSRS 复习。
         * 内容由系统提供（无 refId），与其它系统卡一样可删除后从「添加卡片」重新加回。
         */
        SENTENCE
    }

    /** 一张首页卡片 */
    data class Card(
        /** 实例唯一 id，见类注释的约定 */
        val id: String,
        val type: CardType,
        /** 关联的配置 id（仅 CUSTOM_CLOZE / CUSTOM_MASK 有意义） */
        val refId: Long = -1L,
        /** 关联配置所属文章 id（仅 CUSTOM_CLOZE / CUSTOM_MASK 有意义；-1 = 旧数据未知，反查时兼容扫描） */
        val articleId: Long = -1L,
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

    /**
     * 解析结果缓存：首页首帧曾在主线程整文件 readText + JSON 解析，卡片越多首帧越慢。
     * 只在 [lock] 内读写；「读盘失败回退 .bak」的自愈语义不受影响——命中缓存意味着上一次
     * 读盘是成功的。
     *
     * 失效判据用磁盘指纹而不是「本类写过盘」：一键导入备份是直接往 filesDir 写文件、
     * 不经过本类的，只靠写后失效会给它留下脏缓存（导入完首页还是旧布局）。
     */
    private var cachedCards: List<Card>? = null
    private var cachedStamp = Long.MIN_VALUE

    /** 磁盘指纹 = 修改时间 + 字节数；两次 stat 远比整文件读取 + 解析便宜 */
    private fun diskStamp(): Long =
        if (!file.exists()) 0L else file.lastModified() * 31 + file.length()

    private fun freshRoot(): JSONObject =
        JSONObject().put("version", CURRENT_VERSION).put("cards", JSONArray())

    /**
     * 读取根节点。与 MaskConfigStore 同款安全语义：
     * ① 主文件损坏 → 先另存 .corrupt-<ts>（供事后人工恢复）再回读 .bak；
     * ② 备份也不可用 → 置 loadFailed 并拒绝写盘。
     */
    private fun readRoot(): JSONObject {
        if (file.exists()) {
            try {
                loadFailed = false
                return migrate(JSONObject(file.readText()))
            } catch (_: Exception) {
                // 主文件损坏：先把「损坏过」记成粘性标记，再保留现场落到下方备份分支。
                // 只靠尾部的 file.exists() 判不出来 —— preserveCorruptFile 用 renameTo 把主文件搬走了。
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
        // 首次使用为 false（正常空库、可写）；主文件损坏且备份也不可用时为 true（writeRoot 拒绝写盘）。
        // 旧写法 `loadFailed = file.exists()` 恒为 false（主文件已被改名搬走），会把危险路径放行成
        // 「首次使用」，下一次保存即以空布局覆盖用户摆好的首页。
        return freshRoot()
    }

    /**
     * 主文件解析失败时另存一份 .corrupt-<ts>（尽力而为）：
     * 否则后续写盘会以 bak 快照 + 新改动覆盖主文件，损坏现场无法人工找回。
     */
    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /** 版本迁移钩子 */
    private fun migrate(root: JSONObject): JSONObject {
        val v = root.optInt("version", 1)
        // 高于本版本认识的：拒绝读写（详见 StoreVersion）。首页布局是用户自己摆的画布，
        // 新版可能新增了本版本不认识的卡型，重建数组会把那些卡片整张丢掉
        if (StoreVersion.isFromNewerApp(v, CURRENT_VERSION, "HomeLayoutStore")) {
            loadFailed = true
            return root
        }
        if (v < CURRENT_VERSION) root.put("version", CURRENT_VERSION)
        return root
    }

    private fun writeRoot(root: JSONObject) {
        if (loadFailed) {
            Log.e("HomeLayoutStore", "布局读取失败，已阻止写盘以免覆盖已有布局：${file.absolutePath}")
            return
        }
        try {
            // 原子写 + fsync + 备份轮换统一到 AtomicFiles（tmp → fsync → .bak → rename → 目录 fsync）
            AtomicFiles.writeTextAtomic(file, root.toString())
            cachedCards = null   // 双保险：同毫秒且等字节的重写，磁盘指纹分不出来
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
        val stamp = diskStamp()
        val cached = if (stamp == cachedStamp) cachedCards else null
        cached ?: readCardsLocked().also {
            cachedCards = it
            cachedStamp = diskStamp()
        }
    }

    /** 真正的读盘解析路径；调用方必须已持有 [lock] */
    private fun readCardsLocked(): List<Card> {
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
                    articleId = o.optLong("articleId", -1L),
                    colSpan = clampCol(o.optInt("colSpan", 2)),
                    rowSpan = clampRow(o.optInt("rowSpan", 1)),
                    pinned = o.optBoolean("pinned", false),
                    lockRow = o.optInt("lockRow", -1),
                    lockCol = o.optInt("lockCol", -1),
                    title = o.optString("title", "")
                )
            )
        }
        return if (out.isEmpty()) defaultCards() else out
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
                    .put("articleId", c.articleId)
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
        const val CARD_ID_SENTENCE = "sentence"

        /** 自定义卡 id：含文章 id（配置 id 仅文章内自增，不带文章 id 会跨文章遮蔽/串篇） */
        fun clozeCardId(articleId: Long, configId: Long) = "cloze:$articleId:$configId"
        fun maskCardId(articleId: Long, configId: Long) = "mask:$articleId:$configId"
        fun articleCardId(articleId: Long) = "article:$articleId"

        @Volatile
        private var instance: HomeLayoutStore? = null

        fun getInstance(filesDir: File): HomeLayoutStore =
            instance ?: synchronized(this) {
                instance ?: HomeLayoutStore(File(filesDir, "home_layout.json")).also { instance = it }
            }

        /** 首次使用的默认布局：待复习 + 继续做 + 最近文章 + 句子卡片 + 添加文章入口 */
        fun defaultCards(): List<Card> = listOf(
            Card(CARD_ID_DUE, CardType.DUE),
            Card(CARD_ID_CONTINUE, CardType.CONTINUE),
            Card(CARD_ID_RECENT, CardType.RECENT),
            Card(CARD_ID_SENTENCE, CardType.SENTENCE),
            Card(CARD_ID_ADD, CardType.ADD_ARTICLE)
        )
    }
}
