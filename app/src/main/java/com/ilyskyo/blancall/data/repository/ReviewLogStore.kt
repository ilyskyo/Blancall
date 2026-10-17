// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.util.AtomicFiles
import org.json.JSONObject
import java.io.File

/**
 * 复习事件日志（B3-4）：per-card 的 FSRS 复习事件流。
 *
 * ## 为什么需要
 * [FsrsStateStore] 只保存**最终态**，历史被压缩掉了：用户答不了「这句我什么时候练过、
 * 练过几次、中间断了吗」（这才是「履历」二字的含义），工程侧也永远做不了 FSRS
 * 参数个性化 —— optimal-memory 拟合的唯一输入就是 card 级 review 日志。
 *
 * ## 形态
 * JSONL 追加（O(1)，走 [AtomicFiles.appendTextLine]），一行一个事件：
 * `{"v":1,"key":"s:12:ab12…","ts":…,"rating":3,"elapsedMs":…,"d":…,"s":…,"due":…,"reps":…,"lapses":…}`
 * - `key`：与 [FsrsStateStore] 同一命名空间（`s:<articleId>:<hash16>` / `a:<articleId>`）
 * - `rating`：1=AGAIN 2=HARD 3=GOOD 4=EASY（[FsrsEngine.Rating.value]）
 * - `elapsedMs`：本次复习距上一次复习的间隔（0 = 首次）
 * - `d/s/due/reps/lapses`：复习**之后**的状态快照（拟合得到输入与结果）
 *
 * ## 格式版本
 * 每行自带 `v`。读取侧（[read]）拒绝高于 [FORMAT_VERSION] 的行并计数跳过 ——
 * 升级读取器之前不装作看得懂新格式，更不把未知字段猜成旧语义。
 *
 * ## 两个刻意的边界
 * - **软上限**：文件超过 [MAX_BYTES] 后停止追加（不删任何已有行）。日志是「铺路的原料」，
 *   丢掉它不影响任何现有功能；而用户的练习履历绝不能由写入侧静默淘汰（项目既定立场，
 *   归档/清理属于数据管理话题）。
 * - **不进备份清单**：它是可选的增益数据，不是「一键备份覆盖哪些数据」对用户承诺的一部分；
 *   缺了它，恢复后的应用一切功能照常。
 */
internal object ReviewLogStore {

    /** 行格式版本；未来字段不兼容改动时递增。 */
    const val FORMAT_VERSION = 1

    /** 日志文件名（filesDir 根） */
    const val FILE_NAME = "fsrs_reviews.jsonl"

    /** 软上限：8 MB（约 10 万条事件）；超过后停止追加，不清旧行 */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** 单条事件 */
    data class ReviewEvent(
        val key: String,
        val ts: Long,
        val rating: Int,
        val elapsedMs: Long,
        val state: FsrsEngine.CardState,
    )

    /**
     * 追加一条复习事件。任何失败静默返回 false（调用方在练习主流程里，日志绝不阻断学习）。
     *
     * @param maxBytes 软上限（可注入以便单测用小值）
     */
    fun append(
        file: File,
        key: String,
        ts: Long,
        rating: Int,
        elapsedMs: Long,
        state: FsrsEngine.CardState,
        maxBytes: Long = MAX_BYTES,
    ): Boolean {
        return try {
            if (file.isFile && file.length() >= maxBytes) return false
            val line = JSONObject()
                .put("v", FORMAT_VERSION)
                .put("key", key)
                .put("ts", ts)
                .put("rating", rating)
                .put("elapsedMs", elapsedMs)
                .put("d", state.difficulty)
                .put("s", state.stability)
                .put("due", state.due)
                .put("reps", state.reviewCount)
                .put("lapses", state.lapses)
                .toString()
            AtomicFiles.appendTextLine(file, line)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 读取全部可解析的事件（按文件顺序）。
     *
     * @param onSkipped 被跳过的行回调：参数 = (原行, 跳过原因)；高版本行会以
     *   [SkipReason.HIGH_VERSION] 报出 —— 调用方/未来工具据此知道「这里有新格式数据」，
     *   而不是把「读不懂」静默当成「没有」。
     */
    fun read(file: File, onSkipped: ((String, SkipReason) -> Unit)? = null): List<ReviewEvent> {
        if (!file.isFile) return emptyList()
        val out = mutableListOf<ReviewEvent>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val obj = try {
                JSONObject(line)
            } catch (_: Exception) {
                onSkipped?.invoke(line, SkipReason.MALFORMED)
                return@forEachLine
            }
            val v = obj.optInt("v", 0)
            if (v > FORMAT_VERSION) {
                onSkipped?.invoke(line, SkipReason.HIGH_VERSION)
                return@forEachLine
            }
            val key = obj.optString("key", "")
            if (key.isEmpty()) {
                onSkipped?.invoke(line, SkipReason.MALFORMED)
                return@forEachLine
            }
            out.add(
                ReviewEvent(
                    key = key,
                    ts = obj.optLong("ts", 0L),
                    rating = obj.optInt("rating", 0),
                    elapsedMs = obj.optLong("elapsedMs", 0L),
                    state = FsrsEngine.CardState(
                        difficulty = obj.optDouble("d", 0.0),
                        stability = obj.optDouble("s", 0.0),
                        due = obj.optLong("due", 0L),
                        lastReview = obj.optLong("ts", 0L),
                        reviewCount = obj.optInt("reps", 0),
                        lapses = obj.optInt("lapses", 0),
                    ),
                )
            )
        }
        return out
    }

    enum class SkipReason { MALFORMED, HIGH_VERSION }
}
