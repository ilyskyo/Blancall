// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.statistics

import com.ilyskyo.blancall.data.model.PracticeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 练习历史的「按月分组 + 首屏预览」（B3-3）。
 *
 * 为什么需要：历史列表原先按篇平铺 —— 一篇练过 500 次就是 500 个 item（每条 RecordCard
 * 还会去读墨迹 sidecar），想翻「上个月练了什么」只能一路往下滚。审计给的形态是
 * 「按「月」分组头 + 首屏预览 N 条 + 展开全部」，与热力图卡的既有规范一致（同款
 * truncated 提示 + 查看全部/收起按钮）。
 *
 * 纯函数：分组与截断只依赖输入列表（须已按时间倒序），且与 locale 无关 ——
 * 月份标签由 UI 用 `DateFormat` 本地化（跟随系统语言），这里只产出毫秒锚点。
 */
internal object HistoryGrouping {

    /** 首屏预览条数：够看清「最近练了什么」，又不至于把首屏铺满 */
    const val PREVIEW_COUNT = 10

    /** 列表行：月份组头 或 一条记录 */
    sealed interface Row {
        /** 月份组头（[monthStartMs] = 该月 1 日 00:00 的毫秒；UI 负责本地化显示） */
        data class MonthHeader(val monthStartMs: Long) : Row

        /** 一条练习记录 */
        data class RecordRow(val record: PracticeRecord) : Row
    }

    /**
     * 把倒序记录列表铺成「组头 + 记录」的行序列。
     *
     * @param records 已按时间倒序（最新在前）的练习记录
     * @param expanded false = 只铺最近 [previewCount] 条（跨月处照常插组头）；true = 全部
     * @param previewCount 预览条数（测试注入小值用）
     * @param zoneId 时间戳 → 本地年月的换算时区（默认系统时区，与页面其它日期同一口径）
     */
    fun build(
        records: List<PracticeRecord>,
        expanded: Boolean,
        previewCount: Int = PREVIEW_COUNT,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Row> {
        if (records.isEmpty() || previewCount <= 0) return emptyList()
        val limit = if (expanded) Int.MAX_VALUE else previewCount
        val rows = mutableListOf<Row>()
        var shown = 0
        var currentMonth = Int.MIN_VALUE
        for (r in records) {
            if (shown >= limit) break
            val month = yearMonthKey(r.timestamp, zoneId)
            if (month != currentMonth) {
                currentMonth = month
                rows += Row.MonthHeader(monthStartMs(month / 100, month % 100, zoneId))
            }
            rows += Row.RecordRow(r)
            shown++
        }
        return rows
    }

    /** 本地年月压缩成 `yyyyMM` 整数（整数比较；日期对象只在换算边界处构造） */
    private fun yearMonthKey(timestampMs: Long, zoneId: ZoneId): Int {
        val d = try {
            Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()
        } catch (_: Exception) {
            // 异常时间戳（被手工改坏）按「现在所在月」归组 ——
            // 宁可归错月，也不让一条记录从列表里凭空消失（其它统计处的口径是跳过该条，这里不能跳）
            LocalDate.now(zoneId)
        }
        return d.year * 100 + d.monthValue
    }

    private fun monthStartMs(year: Int, month: Int, zoneId: ZoneId): Long =
        LocalDate.of(year, month, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
}
