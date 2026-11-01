// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条划线 / 批注。
 *
 * 位置口径与 [com.ilyskyo.blancall.ui.reader.ReaderOcclusion.splitParagraphs] 完全一致：
 * [p] = 全文段落索引（按空行分段），[a]/[e) = **该段落 trim 后文本**内的字符区间。
 * 与遮挡配置（[MaskConfigStore.MaskSpan]）同一套坐标，两个功能可以共用段落解析，
 * 也不会因为「翻页模式把长段按行拆片」而漂移（渲染侧按段文本匹配，见 ReadingModeScreen）。
 *
 * [note] 为空 = 纯划线；非空 = 批注。两者共用一张表、同一套渲染（批注多一个页边标记），
 * 这样「先划线、后来补一句话」是原地升级，不会留下两条重叠记录。
 */
data class ReadingMark(
    val id: Long,
    val p: Int,
    val a: Int,
    val e: Int,
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val hasNote: Boolean get() = note.isNotBlank()

    /** 与 [p]/[a]/[e) 是否有交叠（同段且区间相交） */
    fun overlapsWith(paragraph: Int, start: Int, end: Int): Boolean =
        p == paragraph && start < e && end > a

    fun covers(offset: Int): Boolean = offset in a until e
}

/**
 * 阅读模式「划线与批注」存储（按文章保存）。
 *
 * 持久化：filesDir/reading_marks.json
 * `{"version":1,"articles":{"<articleId>":[{"id":1,"p":0,"a":3,"e":12,"note":"…",…}]}}`
 *
 * 三件事在这类里是硬要求，缺一条就会真的丢数据：
 * 1. **原子写**（[AtomicFiles]：tmp → fsync → .bak → rename）；
 * 2. **读坏不写**：主文件损坏先另存 `.corrupt-<ts>` 再回读备份，两处都读不出来时置粘性
 *    `loadFailed` 并拒绝写盘 —— 写路径是整表回写，拿空表写下去会抹掉所有文章的笔记；
 * 3. **版本守卫**（[StoreVersion]）：盘上版本比自己新时拒绝读写，不替未来版本做决定。
 *
 * 读取走内存缓存（[cache]），写操作同步更新缓存 —— 正文每次重组都要拿本段的划线，
 * 每次读盘会让滚动掉帧。
 */
class ReadingMarkStore private constructor(private val file: File) {

    private val lock = Any()

    /** 由 [lock] 守护，因此不需要 @Volatile */
    private var cache: MutableMap<Long, List<ReadingMark>>? = null

    /** 粘性危险标记：只有「成功解析主文件或备份」才清除 */
    private var loadFailed = false

    private var revision = 0L
    private val _revisionFlow = MutableStateFlow(0L)

    /** 修订号：任何写操作自增，供 UI（划线列表 / 正文）作为重算 key */
    val revisionFlow: StateFlow<Long> = _revisionFlow.asStateFlow()

    fun getRevision(): Long = synchronized(lock) { revision }

    /** 备份导入后重载：丢缓存，下次读取走磁盘 */
    fun reload() {
        synchronized(lock) {
            cache = null
            revision++
            _revisionFlow.value = revision
        }
    }

    // ── 读盘 / 写盘 ──

    private fun parseInto(root: JSONObject, out: MutableMap<Long, List<ReadingMark>>) {
        val articles = root.optJSONObject("articles") ?: return
        val keys = articles.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toLongOrNull() ?: continue
            val arr = articles.optJSONArray(key) ?: continue
            val list = mutableListOf<ReadingMark>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optInt("p", -1)
                val a = o.optInt("a", -1)
                val e = o.optInt("e", -1)
                // 区间不合法（旧版/手改文件）：跳过而不是把 (0,0) 塞进渲染层
                if (p < 0 || a < 0 || e <= a) continue
                list.add(
                    ReadingMark(
                        id = o.optLong("id", 0L),
                        p = p,
                        a = a,
                        e = e,
                        note = o.optString("note", ""),
                        createdAt = o.optLong("createdAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                )
            }
            out[id] = list
        }
    }

    /** 必须在 [lock] 内调用 */
    private fun loadLocked(): MutableMap<Long, List<ReadingMark>> {
        cache?.let { return it }
        val map = mutableMapOf<Long, List<ReadingMark>>()
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                if (StoreVersion.isFromNewerApp(
                        StoreVersion.diskVersion(root), CURRENT_VERSION, "ReadingMarkStore"
                    )
                ) {
                    // 版本比自己新：不解析、不写盘，原文件原样留在盘上
                    loadFailed = true
                    cache = map
                    return map
                }
                parseInto(root, map)
                loadFailed = false
                cache = map
                return map
            } catch (_: Exception) {
                loadFailed = true
                preserveCorruptFile()
            }
        }
        val bak = File(file.parentFile, file.name + ".bak")
        if (bak.exists()) {
            try {
                val root = JSONObject(bak.readText())
                if (!StoreVersion.isFromNewerApp(
                        StoreVersion.diskVersion(root), CURRENT_VERSION, "ReadingMarkStore"
                    )
                ) {
                    parseInto(root, map)
                    loadFailed = false
                }
            } catch (_: Exception) {
                // 备份也不可用，保持 loadFailed：宁可本次保存失败，也不以空表抹掉全库
            }
        }
        cache = map
        return map
    }

    private fun preserveCorruptFile() {
        try {
            val dst = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            if (!file.renameTo(dst)) file.copyTo(dst, overwrite = true)
        } catch (_: Exception) { /* 保留失败不影响主流程 */ }
    }

    /** 必须在 [lock] 内调用：整表回写，[map] 即最新镜像 */
    private fun writeLocked(map: Map<Long, List<ReadingMark>>) {
        if (loadFailed) {
            // 日志用英文：本仓库的硬编码中文棘轮只放行 UI 文案进 strings.xml，
            // 日志不该占用户看得到的多语言预算
            Log.e(
                "ReadingMarkStore",
                "load failed; refusing to write so other articles' notes are not wiped: ${file.absolutePath}"
            )
            return
        }
        try {
            val articles = JSONObject()
            map.forEach { (articleId, marks) ->
                val arr = JSONArray()
                marks.forEach { m ->
                    arr.put(
                        JSONObject().apply {
                            put("id", m.id)
                            put("p", m.p)
                            put("a", m.a)
                            put("e", m.e)
                            put("note", m.note)
                            put("createdAt", m.createdAt)
                            put("updatedAt", m.updatedAt)
                        }
                    )
                }
                articles.put(articleId.toString(), arr)
            }
            AtomicFiles.writeTextAtomic(
                file,
                JSONObject().apply {
                    put("version", CURRENT_VERSION)
                    put("articles", articles)
                }.toString()
            )
        } catch (e: Exception) {
            Log.e("ReadingMarkStore", "failed to persist marks: ${file.absolutePath}", e)
        }
    }

    private fun bump() {
        revision++
        _revisionFlow.value = revision
    }

    // ── 对外 API ──

    /** 该文章的全部划线（按段落、段内起点升序）。无记录返回空表。 */
    fun getMarks(articleId: Long): List<ReadingMark> = synchronized(lock) {
        loadLocked()[articleId].orEmpty()
    }

    /**
     * 新增一条划线/批注。
     *
     * 划线语义：同一段里**不保留重叠记录**。
     * - 与既有纯划线重叠时并成一条（区间取并集，复用最早那条的 id）：
     *   「划过一句，再往前面多选一个字重划」只是把线延长，不会叠出更深的颜色；
     * - 与带批注的记录重叠时不吞并（备注属于那段原文），新划线只补没被覆盖的空隙；
     * - 完全相同的区间复用原记录（先划线、后补批注 = 原地升级）。
     */
    fun addMark(
        articleId: Long,
        paragraph: Int,
        start: Int,
        end: Int,
        note: String = ""
    ): ReadingMark? = synchronized(lock) {
        if (articleId <= 0L || paragraph < 0 || start < 0 || end <= start) return null
        val map = loadLocked()
        val list = map[articleId].orEmpty().toMutableList()
        val now = System.currentTimeMillis()

        val exact = list.firstOrNull { it.p == paragraph && it.a == start && it.e == end }
        if (exact != null) {
            if (note.isBlank()) return exact
            val upgraded = exact.copy(note = note, updatedAt = now)
            list[list.indexOf(exact)] = upgraded
            map[articleId] = list
            writeLocked(map)
            bump()
            return upgraded
        }

        // 1) 纯划线可以并：与新区间重叠（含传递闭包）的空白记录一起取并集
        var s = start
        var e = end
        val absorbed = linkedSetOf<ReadingMark>()
        var expanded = true
        while (expanded) {
            expanded = false
            for (m in list) {
                if (m.p != paragraph || m.hasNote || m in absorbed) continue
                if (m.a < e && m.e > s) {
                    absorbed += m
                    if (m.a < s) s = m.a
                    if (m.e > e) e = m.e
                    expanded = true
                }
            }
        }

        // 2) 带批注的区间不动：从 [s,e) 里挖掉它们，只补空白段
        val blocked = list.filter { it.p == paragraph && it.hasNote && it.a < e && it.e > s }
        var gaps = listOf(s to e)
        for (m in blocked) {
            gaps = gaps.flatMap { (gs, ge) ->
                when {
                    m.e <= gs || m.a >= ge -> listOf(gs to ge)
                    m.a <= gs && m.e >= ge -> emptyList()
                    m.a <= gs -> listOf(m.e to ge)
                    m.e >= ge -> listOf(gs to m.a)
                    else -> listOf(gs to m.a, m.e to ge)
                }
            }
        }

        if (gaps.isEmpty()) {
            // 选区完全落在既有批注里：不产生新记录；带备注时补到覆盖它的那条上
            val covering = blocked.firstOrNull { it.a <= start && it.e >= end } ?: blocked.firstOrNull()
            if (note.isNotBlank() && covering != null) {
                val updated = covering.copy(note = note, updatedAt = now)
                list[list.indexOf(covering)] = updated
                map[articleId] = sorted(list)
                writeLocked(map)
                bump()
                return updated
            }
            return covering
        }

        // 3) 并进来的空白记录删掉，用最早那条的 id 承接（保住 createdAt 与列表顺序）
        val base = absorbed.minByOrNull { it.id }
        list.removeAll(absorbed)
        var nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1L
        var result: ReadingMark? = null
        gaps.forEachIndexed { index, (gs, ge) ->
            val reuse = if (index == 0) base else null
            val mark = ReadingMark(
                id = reuse?.id ?: nextId++,
                p = paragraph,
                a = gs,
                e = ge,
                note = if (index == 0) note else "",
                createdAt = reuse?.createdAt?.takeIf { it > 0L } ?: now,
                updatedAt = now
            )
            list.add(mark)
            if (index == 0) result = mark
        }
        map[articleId] = sorted(list)
        writeLocked(map)
        bump()
        result
    }

    /** 改写某条记录的备注（空串 = 退化成纯划线；记录不存在返回 null） */
    fun updateNote(articleId: Long, markId: Long, note: String): ReadingMark? = synchronized(lock) {
        val map = loadLocked()
        val list = map[articleId].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == markId }
        if (idx < 0) return null
        val updated = list[idx].copy(note = note, updatedAt = System.currentTimeMillis())
        list[idx] = updated
        map[articleId] = list
        writeLocked(map)
        bump()
        updated
    }

    /** 删除一条记录（不存在返回 false，不触发落盘） */
    fun removeMark(articleId: Long, markId: Long): Boolean = synchronized(lock) {
        val map = loadLocked()
        val list = map[articleId].orEmpty().toMutableList()
        if (!list.removeAll { it.id == markId }) return false
        if (list.isEmpty()) map.remove(articleId) else map[articleId] = list
        writeLocked(map)
        bump()
        true
    }

    /** 删除该文章的全部线（删除文章时级联，避免孤儿条目无界增长） */
    fun removeArticle(articleId: Long) {
        synchronized(lock) {
            val map = loadLocked()
            if (map.remove(articleId) == null) return
            writeLocked(map)
            bump()
        }
    }

    private fun sorted(list: List<ReadingMark>): List<ReadingMark> =
        list.sortedWith(compareBy({ it.p }, { it.a }, { it.e }))

    companion object {
        private const val CURRENT_VERSION = 1

        @Volatile private var instance: ReadingMarkStore? = null

        fun getInstance(filesDir: File): ReadingMarkStore =
            instance ?: synchronized(this) {
                instance ?: ReadingMarkStore(File(filesDir, "reading_marks.json")).also { instance = it }
            }

        /** 单测用：直就连临时文件，验证序列化口径 */
        internal fun openForTest(file: File): ReadingMarkStore = ReadingMarkStore(file)
    }
}
