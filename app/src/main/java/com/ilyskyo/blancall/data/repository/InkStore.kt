// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次练习的错题墨迹存档（读取结果；坐标为板面 px，渲染端按 boardW/H 等比缩放）。
 */
data class InkPayload(
    val recordId: Long,
    val articleId: Long,
    val savedAt: Long,
    val blanks: List<BlankInk>
)

/** 单个空（错题）的墨迹：按提交批次分组。 */
data class BlankInk(
    val blankIndex: Int,
    val batches: List<InkBatch>
)

/**
 * 一个提交批次的墨迹：带上当时板面尺寸（旋转 / 分屏改板尺寸也能正确复现）。
 * [strokes] 每笔为一条点串（px 坐标）。
 */
data class InkBatch(
    val boardW: Int,
    val boardH: Int,
    val strokes: List<List<Offset>>
)

/**
 * 「错题墨迹」旁路存档。
 *
 * ## 为什么是旁路文件而不是记录字段
 * `records.json` 是判分 / 统计的核心数据文件（删除仍整体重写），墨迹（单次 5–15KB）
 * 内联会让主文件迅速膨胀；且 `PracticeRecord` 为展示性数据改字段得不偿失。
 * 故按「一次练习一个文件」旁路存放：`ink/<recordId>.json`，删文章联动清理、
 * 超量按最旧淘汰，展示层读不到就静默隐藏 —— 绝不影响练习主流程。
 *
 * ## 文件格式（紧凑 JSON，坐标定点化）
 * ```
 * { "v": 1, "recordId": 123, "articleId": 12, "savedAt": 1758...,
 *   "blanks": [ { "blankIndex": 3,
 *       "batches": [ { "w": 1000, "h": 480, "strokes": [ [[1234,5678],[...]], ... ] } ] } ] }
 * ```
 * - 坐标：x/w、y/h 归一化后 ×[INK_FIXED_SCALE] 取整（约 0.1px 精度，比浮点 JSON 省约 40% 字节）；
 * - 写入前按 [INK_MIN_POINT_DIST_PX] 抽稀（一笔 100–300 采样点降到 30–80，肉眼复现无差）；
 * - 解析全程容错：坏文件返回 null、缺字段跳过，展示数据永不拖垮主流程。
 */
class InkStore(private val dir: File) {

    /**
     * 保存一次练习的错题墨迹（覆盖同 recordId 的旧文件），并顺带执行上限清理。
     * 失败只记日志不抛出 —— 墨迹是展示性数据，不能影响练习提交主流程。
     */
    fun save(
        recordId: Long,
        articleId: Long,
        blanks: List<BlankInk>,
        savedAt: Long = System.currentTimeMillis()
    ) {
        if (recordId <= 0L || blanks.isEmpty()) return
        try {
            dir.mkdirs()
            val root = JSONObject()
            root.put("v", 1)
            root.put("recordId", recordId)
            root.put("articleId", articleId)
            root.put("savedAt", savedAt)
            val blanksArr = JSONArray()
            for (bk in blanks) {
                val bObj = JSONObject()
                bObj.put("blankIndex", bk.blankIndex)
                val batchesArr = JSONArray()
                for (bt in bk.batches) {
                    if (bt.boardW <= 0 || bt.boardH <= 0) continue
                    val strokesArr = JSONArray()
                    for (pts in bt.strokes) {
                        val thinned = thinPoints(pts)
                        if (thinned.isEmpty()) continue
                        val ptArr = JSONArray()
                        for (p in thinned) {
                            ptArr.put(
                                JSONArray()
                                    .put(toFixed(p.x, bt.boardW))
                                    .put(toFixed(p.y, bt.boardH))
                            )
                        }
                        strokesArr.put(ptArr)
                    }
                    if (strokesArr.length() > 0) {
                        val btObj = JSONObject()
                        btObj.put("w", bt.boardW)
                        btObj.put("h", bt.boardH)
                        btObj.put("strokes", strokesArr)
                        batchesArr.put(btObj)
                    }
                }
                if (batchesArr.length() > 0) {
                    bObj.put("batches", batchesArr)
                    blanksArr.put(bObj)
                }
            }
            if (blanksArr.length() == 0) return
            root.put("blanks", blanksArr)
            // 原子写 + fsync 统一到 AtomicFiles（tmp → fsync → .bak → rename → 目录 fsync）
            AtomicFiles.writeTextAtomic(fileOf(recordId), root.toString())
            cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "保存墨迹失败（不影响主流程）", e)
        }
    }

    /** 读取一次练习的墨迹；不存在 / 损坏返回 null（展示层静默隐藏）。 */
    fun load(recordId: Long): InkPayload? {
        val file = fileOf(recordId)
        if (!file.exists()) return null
        return try {
            parsePayload(file.readText(), fallbackRecordId = recordId)
        } catch (e: Exception) {
            Log.w(TAG, "读取墨迹失败 recordId=$recordId: ${e.message}")
            null
        }
    }

    /** 该记录是否有墨迹存档（快速探测，不解析文件内容）。 */
    fun has(recordId: Long): Boolean = fileOf(recordId).exists()

    /**
     * 删除某文章的全部墨迹（删文章联动；以文件内 articleId 字段为准，
     * 不用先查记录 id）。失败只记日志。
     */
    fun deleteByArticleId(articleId: Long) {
        try {
            val files = listInkFiles() ?: return
            files.forEach { f ->
                val payload = runCatching { parsePayload(f.readText(), fallbackRecordId(f.name)) }
                    .getOrNull()
                if (payload?.articleId == articleId) f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "按文章清理墨迹失败", e)
        }
    }

    /** 清空全部墨迹存档（设置页「清空已存的错题墨迹」）。失败只记日志。 */
    fun clearAll() {
        try {
            listInkFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "清空墨迹失败", e)
        }
    }

    /**
     * 上限清理：超过 [maxFiles] 个文件时按 recordId 从小到大删最旧（记录 id 单调递增）。
     * 保存路径会顺带调用，属兜底策略；失败只记日志。
     */
    fun cleanup(maxFiles: Int = MAX_FILES) {
        try {
            val files = listInkFiles() ?: return
            if (files.size <= maxFiles) return
            files.sortedBy { it.name.removeSuffix(FILE_SUFFIX).toLongOrNull() ?: Long.MAX_VALUE }
                .take(files.size - maxFiles)
                .forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "墨迹清理失败", e)
        }
    }

    // ========== 内部实现 ==========

    private fun fileOf(recordId: Long): File = File(dir, "$recordId$FILE_SUFFIX")

    private fun listInkFiles(): List<File>? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) }?.toList()

    private fun fallbackRecordId(fileName: String): Long =
        fileName.removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    /** 解析存档文件；任何字段缺失/越界都走跳过或兜底，整体失败返回 null。 */
    private fun parsePayload(text: String, fallbackRecordId: Long): InkPayload? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "墨迹文件不是合法 JSON: ${e.message}")
            return null
        }
        val recordId = root.optLong("recordId", fallbackRecordId)
        val blanksArr = root.optJSONArray("blanks") ?: JSONArray()
        val blanks = ArrayList<BlankInk>(blanksArr.length())
        for (i in 0 until blanksArr.length()) {
            val b = blanksArr.optJSONObject(i) ?: continue
            val blankIndex = b.optInt("blankIndex", -1)
            if (blankIndex < 0) continue
            val batchesArr = b.optJSONArray("batches") ?: JSONArray()
            val batches = ArrayList<InkBatch>(batchesArr.length())
            for (j in 0 until batchesArr.length()) {
                val bt = batchesArr.optJSONObject(j) ?: continue
                val w = bt.optInt("w", 0)
                val h = bt.optInt("h", 0)
                if (w <= 0 || h <= 0) continue
                val strokesArr = bt.optJSONArray("strokes") ?: JSONArray()
                val strokes = ArrayList<List<Offset>>(strokesArr.length())
                for (g in 0 until strokesArr.length()) {
                    val strokeArr = strokesArr.optJSONArray(g) ?: continue
                    val pts = ArrayList<Offset>(strokeArr.length())
                    for (k in 0 until strokeArr.length()) {
                        val pair = strokeArr.optJSONArray(k) ?: continue
                        val fx = pair.optInt(0, 0)
                        val fy = pair.optInt(1, 0)
                        pts.add(
                            Offset(
                                fx / INK_FIXED_SCALE.toFloat() * w,
                                fy / INK_FIXED_SCALE.toFloat() * h
                            )
                        )
                    }
                    if (pts.isNotEmpty()) strokes.add(pts)
                }
                if (strokes.isNotEmpty()) batches.add(InkBatch(w, h, strokes))
            }
            if (batches.isNotEmpty()) blanks.add(BlankInk(blankIndex, batches))
        }
        if (blanks.isEmpty()) return null
        return InkPayload(
            recordId = recordId,
            articleId = root.optLong("articleId", 0L),
            savedAt = root.optLong("savedAt", 0L),
            blanks = blanks
        )
    }

    companion object {
        private const val TAG = "InkStore"
        private const val FILE_SUFFIX = ".json"

        /** 存档文件数上限（约 ≤3MB）；超出按最旧淘汰。 */
        const val MAX_FILES = 200

        /** 定点化刻度：归一化坐标 ×10000 取整（约 0.1px 精度 @1080p）。 */
        internal const val INK_FIXED_SCALE = 10000

        /** 抽稀的最小相邻点距（px）：小于它的中间点丢弃。 */
        internal const val INK_MIN_POINT_DIST_PX = 2f

        @Volatile
        private var INSTANCE: InkStore? = null

        /**
         * 单例获取。注意：仅首次调用的 dirPath 生效（单例语义），
         * 调用方应确保全应用使用同一目录（filesDir/ink）。
         */
        fun getInstance(dirPath: String): InkStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InkStore(File(dirPath)).also { INSTANCE = it }
            }
        }
    }
}

/**
 * 抽稀：相邻点距离小于 [InkStore.INK_MIN_POINT_DIST_PX] 的中间点丢弃
 * （保留首点与末点；单点 / 双点笔原样保留）。纯函数，供单测锁精度。
 */
internal fun thinPoints(pts: List<Offset>, minDistPx: Float = InkStore.INK_MIN_POINT_DIST_PX): List<Offset> {
    if (pts.size <= 2) return pts
    val out = ArrayList<Offset>(pts.size)
    out.add(pts.first())
    var lastKept = pts.first()
    for (i in 1 until pts.size - 1) {
        val p = pts[i]
        val dx = p.x - lastKept.x
        val dy = p.y - lastKept.y
        if (dx * dx + dy * dy >= minDistPx * minDistPx) {
            out.add(p)
            lastKept = p
        }
    }
    out.add(pts.last())
    return out
}

/**
 * 定点化：板面 px 坐标归一化（value / boardPx）后 ×[InkStore.INK_FIXED_SCALE] 取整，
 * 夹到 0..scale。读端乘以 boardPx 还原，往返误差 ≤ 半刻度（≈0.05px @1080p）。
 */
internal fun toFixed(value: Float, boardPx: Int): Int {
    if (boardPx <= 0) return 0
    val v = Math.round(value / boardPx * InkStore.INK_FIXED_SCALE)
    return v.coerceIn(0, InkStore.INK_FIXED_SCALE)
}
