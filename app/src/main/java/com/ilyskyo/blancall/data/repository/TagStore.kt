// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.algorithm.ColorOps
import com.ilyskyo.blancall.algorithm.TagOps
import com.ilyskyo.blancall.data.model.Tag
import com.ilyskyo.blancall.data.model.TagData
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文章标签存储（filesDir/tags.json）。
 *
 * 数据模型：**标签库 + 文章↔标签多对多绑定**同文件持久化（新文件，零迁移——旧版本 APP 不读不受影响）：
 * ```
 * {"version":1,
 *  "tags":[{"id":1,"name":"诗词","color":"#EE9DB4"}],
 *  "links":{"12":[1,3],"15":[2]}}
 * ```
 * - tags 数组顺序 = 展示/排序顺序（与 HomeLayoutStore.cards 同语义）；
 * - links 的 value 为 tag id 集合（去重）；读取时丢弃指向不存在 tag 的旧绑定（自愈）；
 * - 颜色持久化为 "#RRGGBB"（大写 6 位），运行期 0xRRGGBB；解析失败回落 [FALLBACK_COLOR]。
 *
 * 可靠性与并发语义完全对齐 HomeLayoutStore / MaskConfigStore 范式：
 * 单例 + synchronized(lock) + readRoot（主文件损坏→另存 `.corrupt-<ts>`→回读 `.bak`）
 * + [loadFailed] 写保护 + migrate() version 钩子 + AtomicFiles 原子写；
 * **每次读取都从磁盘重读**（读到什么就是什么，外部损坏可自愈可测试）。
 *
 * 反应式：[data] StateFlow——读取/突变都会发布最新快照；UI 请 collectAsState 订阅，
 * 首次应调用一次 [snapshot] 完成 priming（**UI 路径请包 Dispatchers.IO**，禁止主线程磁盘写）。
 */
class TagStore private constructor(private val file: File) {

    private val lock = Any()

    /**
     * 读盘失败标记（主文件与备份都解析不了时为 true）→ 阻止随后的写盘，
     * 避免用空数据覆盖写把用户标签抹掉。
     */
    private var loadFailed = false

    private val _data = MutableStateFlow(TagData())

    /** 标签库快照（Compose 侧 collectAsState 订阅；首帧前先调一次 [snapshot]） */
    val data: StateFlow<TagData> = _data.asStateFlow()

    companion object {
        private const val CURRENT_VERSION = 1

        /** 颜色字段异常/缺失时的回退缩略色（暖白，与 Macaron neutral accent 同族） */
        private const val FALLBACK_COLOR = 0xB6AFA4

        @Volatile
        private var instance: TagStore? = null

        fun getInstance(filesDir: File): TagStore =
            instance ?: synchronized(this) {
                instance ?: TagStore(File(filesDir, "tags.json")).also { instance = it }
            }
    }

    // ── 读取 ──

    /**
     * 读取根节点。与 HomeLayoutStore 同款安全语义：
     * ① 主文件损坏 → 先另存 `.corrupt-<ts>`（供事后人工恢复）再回读 `.bak`；
     * ② 备份也不可用 → 置 [loadFailed] 并拒绝写盘。
     */
    private fun readRoot(): JSONObject {
        if (file.exists()) {
            try {
                loadFailed = false
                return migrate(JSONObject(file.readText()))
            } catch (_: Exception) {
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
        // 文件确实不存在（首次使用）→ 空库；文件存在却读不出来且无备份 → 危险状态，禁止写盘
        loadFailed = file.exists()
        return JSONObject()
    }

    /** 主文件解析失败时另存一份 `.corrupt-<ts>`（尽力而为），避免损坏现场被后续写盘覆盖 */
    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /** 版本迁移钩子（当前无实际步骤；未来字段以 optXxx 可选解析，向后兼容） */
    private fun migrate(root: JSONObject): JSONObject {
        val v = root.optInt("version", 1)
        if (v < CURRENT_VERSION) root.put("version", CURRENT_VERSION)
        return root
    }

    /** 解析根节点 → TagData（单条损坏跳过；名称按 trim+忽略大小写去重；links 过滤失效 id） */
    private fun parse(root: JSONObject): TagData {
        val tagsArr = root.optJSONArray("tags") ?: JSONArray()
        val tags = mutableListOf<Tag>()
        val seenIds = mutableSetOf<Long>()
        val seenNames = mutableSetOf<String>()
        for (i in 0 until tagsArr.length()) {
            val o = tagsArr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            if (id <= 0L || !seenIds.add(id)) continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty() || !seenNames.add(name.lowercase())) continue
            val color = ColorOps.parseHex(o.optString("color", "")) ?: FALLBACK_COLOR
            tags.add(Tag(id = id, name = name, color = color))
        }

        val validIds = tags.mapTo(mutableSetOf()) { it.id }
        val links = mutableMapOf<Long, Set<Long>>()
        root.optJSONObject("links")?.let { linksObj ->
            val keys = linksObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val articleId = key.toLongOrNull() ?: continue
                if (articleId <= 0L) continue
                val arr = linksObj.optJSONArray(key) ?: continue
                val ids = mutableSetOf<Long>()
                for (j in 0 until arr.length()) {
                    val tagId = arr.optLong(j, -1L)
                    if (tagId > 0L && tagId in validIds) ids.add(tagId)
                }
                if (ids.isNotEmpty()) links[articleId] = ids
            }
        }
        return TagData(tags = tags, links = links)
    }

    /** 锁内：读盘 → 发布到 [data]（值未变则 StateFlow 自动去重，不触发重组） */
    private fun readDataLocked(): TagData {
        val parsed = parse(readRoot())
        _data.value = parsed
        return parsed
    }

    /** 下一个可用标签 id：max(现有) + 1（每次读取按磁盘实况推导，无跨会话残留） */
    private fun nextIdOf(d: TagData): Long = (d.tags.maxOfOrNull { it.id } ?: 0L) + 1L

    // ── 写入 ──

    /** 原子落盘（持有 lock 时调用；[loadFailed] 时拒绝写，防覆盖已有数据） */
    private fun writeLocked(d: TagData) {
        if (loadFailed) {
            Log.e("TagStore", "标签库读取失败，已阻止写盘以免覆盖已有标签：${file.absolutePath}")
            return
        }
        try {
            val root = JSONObject().put("version", CURRENT_VERSION)
            val tagsArr = JSONArray()
            d.tags.forEach { t ->
                tagsArr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
                        .put("color", ColorOps.toHex(t.color))
                )
            }
            root.put("tags", tagsArr)
            val linksObj = JSONObject()
            // 键按 articleId 升序、值按 tagId 升序 → 文件确定性（便于 diff 与人工检查）
            d.links.keys.sorted().forEach { articleId ->
                val ids = (d.links[articleId] ?: emptySet()).sorted()
                if (ids.isEmpty()) return@forEach
                val arr = JSONArray()
                ids.forEach { arr.put(it) }
                linksObj.put(articleId.toString(), arr)
            }
            root.put("links", linksObj)
            // 原子写 + fsync + .bak 轮换统一到 AtomicFiles（快照已在锁内，符合落盘规范）
            AtomicFiles.writeTextAtomic(file, root.toString())
        } catch (e: Exception) {
            Log.e("TagStore", "写盘失败", e)
        }
    }

    /** 突变模板：锁内 重读 → 变换 → 发布（UI 即时）→ 落盘 */
    private inline fun <T> mutate(block: (TagData, Long) -> Pair<TagData, T>): T = synchronized(lock) {
        val current = readDataLocked()
        val (newData, result) = block(current, nextIdOf(current))
        _data.value = newData
        writeLocked(newData)
        result
    }

    // ── 公开 API ──

    /**
     * 刷新并返回当前快照：UI 首帧调用一次完成 priming（建议在 `LaunchedEffect` +
     * `Dispatchers.IO` 中执行）；非 Composable 路径可直接以返回值使用。
     */
    fun snapshot(): TagData = synchronized(lock) { readDataLocked() }

    /** 新建标签；名称非法（空/超长/重名，见 [TagOps.validateName]）返回 null */
    fun createTag(name: String, color: Int): Long? = mutate { d, nextId ->
        val trimmed = name.trim()
        val invalid = trimmed.isEmpty() ||
            trimmed.length > TagOps.MAX_NAME_LENGTH ||
            d.tags.any { it.name.equals(trimmed, ignoreCase = true) }
        if (invalid) {
            d to null
        } else {
            val tag = Tag(id = nextId, name = trimmed, color = color and 0xFFFFFF)
            d.copy(tags = d.tags + tag) to tag.id
        }
    }

    /** 重命名；名称非法（空/超长/重名）或 id 不存在返回 false */
    fun renameTag(id: Long, name: String): Boolean = mutate { d, _ ->
        val trimmed = name.trim()
        val target = d.tags.firstOrNull { it.id == id }
        val invalid = trimmed.isEmpty() ||
            trimmed.length > TagOps.MAX_NAME_LENGTH ||
            d.tags.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }
        if (target == null || invalid) {
            d to false
        } else {
            d.copy(tags = d.tags.map { if (it.id == id) it.copy(name = trimmed) else it }) to true
        }
    }

    /** 改色（id 不存在时无副作用） */
    fun recolorTag(id: Long, color: Int) = mutate { d, _ ->
        d.copy(
            tags = d.tags.map { if (it.id == id) it.copy(color = color and 0xFFFFFF) else it },
        ) to Unit
    }

    /** 删除标签：级联解绑所有文章（不阻止删除） */
    fun deleteTag(id: Long) = mutate { d, _ ->
        d.copy(
            tags = d.tags.filterNot { it.id == id },
            links = d.links
                .mapValues { (_, ids) -> ids - id }
                .filterValues { it.isNotEmpty() },
        ) to Unit
    }

    /** 排序：数组顺序即展示顺序（[orderedIds] 未覆盖的标签按原相对顺序附在末尾） */
    fun reorderTags(orderedIds: List<Long>) = mutate { d, _ ->
        val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
        val sorted = d.tags.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        d.copy(tags = sorted) to Unit
    }

    /** 覆盖设置某文章的标签集合（空集 = 解绑全部；过滤不存在的 tag id） */
    fun setArticleTags(articleId: Long, tagIds: Set<Long>) = mutate { d, _ ->
        if (articleId <= 0L) {
            d to Unit
        } else {
            val valid = tagIds.filterTo(mutableSetOf()) { tid -> d.tags.any { it.id == tid } }
            val links = if (valid.isEmpty()) d.links - articleId else d.links + (articleId to valid)
            d.copy(links = links) to Unit
        }
    }

    /** 批量绑定：对每篇文章 新集合 = 旧集合 ∪ add − remove（单次落盘；未点击的标签不受影响） */
    fun applyBatchToggle(articleIds: Collection<Long>, add: Set<Long>, remove: Set<Long>) = mutate { d, _ ->
        val validAdd = add.filterTo(mutableSetOf()) { a -> d.tags.any { it.id == a } }
        if (articleIds.isEmpty() || (validAdd.isEmpty() && remove.isEmpty())) {
            d to Unit
        } else {
            val links = d.links.toMutableMap()
            articleIds.forEach { aid ->
                if (aid <= 0L) return@forEach
                val current = links[aid] ?: emptySet()
                val next = (current + validAdd) - remove
                if (next.isEmpty()) links.remove(aid) else links[aid] = next
            }
            d.copy(links = links) to Unit
        }
    }

    /**
     * 覆盖设置某标签下的文章集合（标签文章管理面板）：
     * 选中集合内的文章绑定该标签，**未选中但已绑定的文章解绑**（增/删同一入口，单次落盘）。
     * 仅动这一条标签的关联，其它标签不受影响；标签 id 不存在时无副作用。
     */
    fun setTagArticles(tagId: Long, articleIds: Set<Long>) = mutate { d, _ ->
        if (d.tags.none { it.id == tagId }) {
            d to Unit
        } else {
            val links = d.links.toMutableMap()
            // 受影响集合 = 选中文章 ∪ 当前已绑定该标签的文章（其余文章不碰）
            val bound = d.links.filterValues { tagId in it }.keys
            val affected = bound + articleIds
            affected.forEach { aid ->
                if (aid <= 0L) return@forEach
                val current = links[aid] ?: emptySet()
                val next = if (aid in articleIds) current + tagId else current - tagId
                if (next.isEmpty()) links.remove(aid) else links[aid] = next
            }
            d.copy(links = links) to Unit
        }
    }

    /** 文章删除级联：清除该文章的全部绑定 */
    fun removeArticle(articleId: Long) = mutate { d, _ ->
        if (d.links.containsKey(articleId)) {
            d.copy(links = d.links - articleId) to Unit
        } else {
            d to Unit
        }
    }
}
