// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import com.ilyskyo.blancall.ui.common.rememberEnteredOnFirstFrame
import com.ilyskyo.blancall.ui.common.staggerDelay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.CoachAnchorState
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.HapticTier
import com.ilyskyo.blancall.ui.common.rememberHaptic
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.pressFeedback
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import com.ilyskyo.blancall.ui.common.coachAnchor
import com.ilyskyo.blancall.ui.common.pressClick
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor

// ---------------------------------------------------------------------------
// 尺寸常量
// ---------------------------------------------------------------------------

/** 单位行高：卡片高度 = rowSpan * ROW_UNIT（同时是纵向网格节距） */
private val ROW_UNIT = 92.dp

/**
 * 列间距（横向网格节距 = cellW + COLUMN_GAP）。
 * 卡面左右不再内缩（见 [SLOT_INSET]），所以它就是相邻卡面的**实际视觉间距**。
 * 满宽卡宽 = 列数 × cellW + (列数−1) × COLUMN_GAP = 画布宽度，与「今日计划」卡对齐。
 */
private val COLUMN_GAP = 12.dp

/**
 * 槽位**纵向**内缩，给上下相邻卡片留出视觉间隙 / 拖动抬起余量。
 * 只作用于纵向：左右不内缩，满宽卡（colSpan = 列数）的左右边缘才能与「今日计划」卡
 * 完全对齐（改造前左右各缩 5dp，满宽卡比「今日计划」窄 10dp）。
 */
private val SLOT_INSET = 5.dp

/** 编辑态控件的触摸热区（≥32dp，图形本身更小） */
private val CONTROL_HIT = 32.dp

/**
 * 编辑态控件的**视觉**白圆直径（比热区小）。
 * 关键约束：相邻两张卡的同侧控件圆心距 = 卡面间距 + 两侧各 8dp（圆心距卡面 8dp）：
 * 横向 = 12dp + 16dp = 28dp，纵向 = 10dp + 16dp = 26dp，都不小于 26dp 视觉直径 ——
 * 任何相邻关系（横排 / 竖排 / 对角）下圆钮都不会相交。
 */
private val CONTROL_VISUAL = 26.dp

/**
 * 编辑态控件圆心相对**卡角**的纵向外扩量：控件热区 32dp 居中放 26dp 图形，
 * 向卡角外偏移 3dp 后圆心距卡面 8dp（卡面上/下边距槽位边缘 [SLOT_INSET] = 5dp，13 − 5 = 8），
 * 圆钮「骑」在卡角上而不外凸过多 —— 相邻卡片的行间隙只有 10dp，
 * 控件伸得太多会与邻卡的控件相互贴住（用户反馈「还是略有遮挡」）。
 */
private val CONTROL_INSET = 3.dp

/**
 * 编辑态控件圆心的**横向**外扩量：卡面左右与槽位齐平（不再内缩 [SLOT_INSET]），
 * 因此要比纵向多外扩一个 [SLOT_INSET]，圆心才同样距卡面 8dp ——
 * 否则圆钮会整体偏进卡内 5dp，看起来「还停在旧卡边缘的位置」（用户反馈）。
 */
private val CONTROL_INSET_X = CONTROL_INSET + SLOT_INSET

/** 拉伸手柄的触摸热区 */
private val HANDLE_HIT = 40.dp

/** 编辑态控件图标尺寸 */
private val CONTROL_ICON = 18.dp

/**
 * 编辑态控件的圆形底衬：**不透明白色**。
 * 原先是 25% 白（0x40FFFFFF），在粉/绿/蓝等马卡龙卡面上几乎看不见（用户反馈「固定键、修改键、拉伸键都不显眼」）。
 * 改为实心白底后，任何卡面（含深色玻璃）上都能清晰跳出。
 */
private val CONTROL_SCRIM = Color.White

/** 编辑态控件图标色（近黑，白底上对比度拉满） */
private val CONTROL_ICON_COLOR = Color(0xFF1F1F24)

/** 拉伸手柄弧线颜色（与控件图标同色，白底上清晰） */
private val HANDLE_COLOR = Color(0xFF1F1F24)

/** 白底圆钮的描边：让白钮在纯白/米白卡面上也能一眼看出边界 */
private val CONTROL_RING = Color(0x33000000)

/** 删除红叉的颜色 */
private val CLOSE_COLOR = Color(0xFFE53935)

/**
 * 长按进「卡片编辑态」的判定门槛。
 *
 * 不用系统的 400ms：首页这张画布上，一次长按要同时和「点开详情」「拿起卡片拖动」抢同一段
 * 手指时间，400ms 落在误触区间里（用户 2026-10-09：进编辑态太容易）。
 * 取 600ms —— 这是 Android 自己在「设置 → 无障碍 → 触摸和按住延迟」里给的「中」档
 * （短/中/长/超长 = 300/600/1000/1500ms），各家桌面长按进编辑态的量级也在这一档；
 * 比系统默认慢一档，但仍然短到不需要用户重新学一次「长按」这个动作。
 *
 * 用 maxOf 而不是直接写死：用户在系统无障碍里把延迟调得更长时要尊重他的设置，
 * 那台设备上的 400ms 本来就不是我们的基准。
 */
private const val EDIT_MODE_LONG_PRESS_MS = 600L

// ---------------------------------------------------------------------------
// 渲染辅助
// ---------------------------------------------------------------------------

/**
 * 一张卡在画布内的当前几何（含拖动 / 缩放预览与位置动画）。
 * 内容层与控件层**共用同一份**几何：两层用相同的 offset/width/height 与 graphicsLayer，
 * 保证控件白钮在拖动/缩放中始终与卡片像素级对齐。
 */
private data class CardGeo(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
    val colSpan: Int,
    val rowSpan: Int,
    val isDragging: Boolean,
    val isResizing: Boolean,
)

// ---------------------------------------------------------------------------
// 布局算法（纯函数，可单测）
// ---------------------------------------------------------------------------

/**
 * 网格布局：把 [cards] 按顺序流式放进 [columns] 列网格。
 *
 * 列数由界面按**可用宽度**动态计算（窄屏手机 2 列，平板横屏更多列），
 * 默认值保持 [HomeLayoutStore.COLUMNS] 以兼容既有调用与单测。
 * 平板横屏下若继续用固定 2 列，卡片会被拉成超宽扁条且行数暴涨，
 * 这正是原先「锁竖屏」想回避的问题——正解是增列，而非锁方向。
 *
 * **第一轮（钉住的卡优先占位）**：按 cards 顺序，把 `pinned && lockRow >= 0 && lockCol >= 0`
 * 的卡放到它的锁定槽位 `(lockRow, lockCol)` 上。`lockCol` 先收敛到
 * `0..columns-colSpan`（防手工改 JSON 越界）；若该区域已被前面的钉住卡占走，
 * 则**沿同一列向下顺延**到最近可用行（列表靠前的钉住卡优先）。
 *
 * **第二轮（自由卡首次适配）**：按 cards 顺序，对其余卡（非 pinned，或 pinned 但 lock 未设定）
 * 自上而下、自左向右扫描，取**第一个能完整容纳 `colSpan × rowSpan` 的空位**放进去并占位。
 * 扫描逐格判定，跨行卡留下的空洞会被后续较矮的卡绕行填补。
 *
 * 复杂度 O(n × rows × columns)，卡片数量很小，无需优化。
 *
 * @param columns 网格列数，需与调用方绘制时用的列数**一致**，否则拖拽换位会整体错位。
 * @return cardId → `intArrayOf(row, col)`（左上角格坐标）
 */
internal fun layoutSlots(
    cards: List<HomeLayoutStore.Card>,
    columns: Int = HomeLayoutStore.COLUMNS
): Map<String, IntArray> {
    val cols = columns.coerceAtLeast(1)
    val out = LinkedHashMap<String, IntArray>(cards.size)
    // 占用表：每行一个 BooleanArray(cols)，按需向下增长
    val grid = mutableListOf<BooleanArray>()
    fun ensureRows(n: Int) {
        while (grid.size < n) grid.add(BooleanArray(cols))
    }
    fun fits(row: Int, col: Int, colSpan: Int, rowSpan: Int): Boolean {
        if (col < 0 || col + colSpan > cols) return false
        for (r in row until row + rowSpan) {
            for (c in col until col + colSpan) {
                if (grid[r][c]) return false
            }
        }
        return true
    }

    // 行号安全上界：任何布局最多用到 cards.size * MAX_ROW_SPAN 行。
    // lockRow 来自 JSON，可能被手工改成极大值 → 不设上限会 row+rowSpan 溢出成负数并越界崩溃。
    val maxRow = cards.size * HomeLayoutStore.MAX_ROW_SPAN

    // ---- 第一轮：钉住的卡 ---- //
    for (card in cards) {
        if (!card.pinned || card.lockRow < 0 || card.lockCol < 0) continue
        val colSpan = card.colSpan.coerceIn(
            HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
        )
        val rowSpan = card.rowSpan.coerceIn(
            HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN
        )
        val lockCol = card.lockCol.coerceIn(
            0, (cols - colSpan).coerceAtLeast(0)
        )
        var row = card.lockRow.coerceIn(0, maxRow)
        // 先试锁定槽位，被占则沿同列向下顺延
        while (true) {
            ensureRows(row + rowSpan)
            if (fits(row, lockCol, colSpan, rowSpan)) break
            row++
        }
        for (r in row until row + rowSpan) {
            for (c in lockCol until lockCol + colSpan) grid[r][c] = true
        }
        out[card.id] = intArrayOf(row, lockCol)
    }

    // ---- 第二轮：其余卡首次适配 ---- //
    for (card in cards) {
        if (out.containsKey(card.id)) continue
        val colSpan = card.colSpan.coerceIn(
            HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
        )
        val rowSpan = card.rowSpan.coerceIn(
            HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN
        )
        var placedRow = 0
        var placedCol = 0
        var row = 0
        scan@ while (true) {
            ensureRows(row + rowSpan)
            for (col in 0..cols - colSpan) {
                if (fits(row, col, colSpan, rowSpan)) {
                    placedRow = row
                    placedCol = col
                    break@scan
                }
            }
            row++
        }
        for (r in placedRow until placedRow + rowSpan) {
            for (c in placedCol until placedCol + colSpan) grid[r][c] = true
        }
        out[card.id] = intArrayOf(placedRow, placedCol)
    }
    return out
}

/**
 * 拖动松手后的落位结算（纯函数，可单测）。
 *
 * ① 目标格被另一张卡占据 → **交换两者在列表中的顺序**（尺寸差异由网格重新流式排布消化）；
 * ② 目标格为空 → 把 [dragged] 插到**最近的卡**之前 / 之后（按阅读顺序判断前后）。
 *
 * ⚠ 目标格一律用**视觉布局**（`layoutSlots(cards)`，含被拖卡）来查：用户拖动时看到的就是
 * 这个布局。曾经误用「移除被拖卡之后的紧凑布局」`layoutSlots(others)`，两者恰好差一张卡的
 * 身位 —— 把卡拖到 c 的格子上却换掉了 d，且被拖卡自己也会少落一行。**不要再改回去。**
 *
 * @param targetRow 拖动后卡片中心点所在的网格行
 * @param targetCol 拖动后卡片中心点所在的网格列
 */
internal fun resolveDrop(
    cards: List<HomeLayoutStore.Card>,
    dragged: HomeLayoutStore.Card,
    targetRow: Int,
    targetCol: Int,
    columns: Int = HomeLayoutStore.COLUMNS
): List<HomeLayoutStore.Card> {
    val cols = columns.coerceAtLeast(1)
    val fromIndex = cards.indexOfFirst { it.id == dragged.id }
    if (fromIndex < 0) return cards
    val others = cards.filterNot { it.id == dragged.id }
    if (others.isEmpty()) return cards

    // 视觉布局：与用户拖动时看到的一致（原地守卫与占位者判定都用它）
    val slots = layoutSlots(cards, cols)

    // 原地放回：目标格就是自己原来的槽位 → 直接不动作。
    // 否则「拿起又放下」会因为列表重排而误触发一次交换。
    val origin = slots[dragged.id]
    if (origin != null && origin[0] == targetRow && origin[1] == targetCol) return cards

    // ① 目标格上的占位者（尺寸同样收敛，与 layoutSlots 内部口径一致）
    val occupant = others.firstOrNull { c ->
        val s = slots[c.id] ?: return@firstOrNull false
        val rs = c.rowSpan.coerceIn(HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN)
        val cs = c.colSpan.coerceIn(HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN)
        targetRow >= s[0] && targetRow < s[0] + rs &&
            targetCol >= s[1] && targetCol < s[1] + cs
    }
    if (occupant != null) {
        val toIndex = cards.indexOfFirst { it.id == occupant.id }
        if (toIndex < 0) return cards
        val swapped = cards.toMutableList()
        swapped[fromIndex] = cards[toIndex]
        swapped[toIndex] = cards[fromIndex]
        return swapped
    }

    // ② 空位：找网格距离最近的卡，按阅读顺序插到它前 / 后
    val nearest = others.minByOrNull { c ->
        val s = slots[c.id] ?: return@minByOrNull Int.MAX_VALUE
        abs(s[0] - targetRow) * cols + abs(s[1] - targetCol)
    } ?: return cards
    val nSlot = slots[nearest.id] ?: return cards
    val insertBefore =
        targetRow < nSlot[0] || (targetRow == nSlot[0] && targetCol < nSlot[1])
    val nIndex = others.indexOfFirst { it.id == nearest.id }
    val insertAt = if (insertBefore) nIndex else nIndex + 1
    return others.toMutableList().apply { add(insertAt.coerceIn(0, size), dragged) }
}

/**
 * 钉住的卡被拖动后的落位（纯函数，可单测）。
 *
 * 语义与自由卡不同：钉住的卡**不是**去换别人的顺序，而是把自己**重新钉到落点格**——
 * 也就是把 `lockRow/lockCol` 改写成落点格（`lockCol` 收敛到 `0..columns-colSpan`，
 * 防止 `lockCol + colSpan` 越界），列表顺序保持不变。
 *
 * **落点被另一张钉住的卡占着时两张卡真正换位**：被占卡挪到 [dragged] 原来的锁定格。
 * 否则只把落点写给 [dragged]，渲染时它会被 [layoutSlots] 第一轮按「列表靠前优先」顺延下去，
 * 表现为「拖不动、弹回去了」，交互上很困惑。
 *
 * 之所以不改列表顺序：pinned 卡的槽位完全由 lock 决定（[layoutSlots] 第一轮），
 * 列表下标只影响「多张钉住卡抢同一槽位时谁优先」，保持稳定才不会让冲突结果乱跳。
 *
 * @return 新列表；落点与现有 lock 相同则原样返回（调用方据此跳过回写）
 */
internal fun resolvePinnedDrop(
    cards: List<HomeLayoutStore.Card>,
    dragged: HomeLayoutStore.Card,
    targetRow: Int,
    targetCol: Int,
    columns: Int = HomeLayoutStore.COLUMNS
): List<HomeLayoutStore.Card> {
    val cols = columns.coerceAtLeast(1)
    if (cards.none { it.id == dragged.id }) return cards
    val colSpan = dragged.colSpan.coerceIn(
        HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
    )
    val rowSpan = dragged.rowSpan.coerceIn(
        HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN
    )
    val maxRow = cards.size * HomeLayoutStore.MAX_ROW_SPAN
    val lockCol = targetCol.coerceIn(
        0, (cols - colSpan).coerceAtLeast(0)
    )
    // 行号同样设上界，避免落点算出极端值时把锁定坐标写坏
    val lockRow = targetRow.coerceIn(0, maxRow)
    if (dragged.lockRow == lockRow && dragged.lockCol == lockCol) return cards

    // 落点是否压在另一张钉住卡的锁定格上（两个格子矩形相交）
    val blocker = cards.firstOrNull { o ->
        if (o.id == dragged.id || !o.pinned) return@firstOrNull false
        if (o.lockRow < 0 || o.lockCol < 0) return@firstOrNull false
        val oCol = o.colSpan.coerceIn(
            HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
        )
        val oRow = o.rowSpan.coerceIn(
            HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN
        )
        lockRow < o.lockRow + oRow && o.lockRow < lockRow + rowSpan &&
            lockCol < o.lockCol + oCol && o.lockCol < lockCol + colSpan
    }
    if (blocker != null) {
        // 被占卡挪到 dragged 原来的锁定格；只有换位后位置本身可用时才真换
        val swapRow = dragged.lockRow
        val swapCol = dragged.lockCol
        val blockerColSpan = blocker.colSpan.coerceIn(
            HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
        )
        val swapUsable = swapRow >= 0 && swapRow <= maxRow &&
            swapCol >= 0 &&
            swapCol + blockerColSpan <= cols &&
            // 换完不能落在它当前的位置（否则等于没换，还会造出两个相同的 lock）
            !(swapRow == blocker.lockRow && swapCol == blocker.lockCol)
        if (swapUsable) {
            return cards.map {
                when (it.id) {
                    dragged.id -> it.copy(lockRow = lockRow, lockCol = lockCol)
                    blocker.id -> it.copy(lockRow = swapRow, lockCol = swapCol)
                    else -> it
                }
            }
        }
    }

    // 退化：dragged 之前没有锁定格、或换位后位置不可用 → 照旧只写 dragged 的落点，
    // 残余冲突交给 [layoutSlots] 第一轮顺延，坐标始终合法。
    return cards.map {
        if (it.id == dragged.id) it.copy(lockRow = lockRow, lockCol = lockCol) else it
    }
}

// ---------------------------------------------------------------------------
// 手势工具
// ---------------------------------------------------------------------------

/**
 * 首页共享的「当前按在屏幕上的手指数」。
 *
 * 为什么必须跨级共享：Compose 的指针事件只派发给**该指针命中的节点链**，
 * 第二根手指若落在另一张卡（或画布空白）上，当前卡的手势协程根本看不到它。
 * 用户的要求是「双指一律等于滚动页面」，所以每张卡都必须知道全局手指数。
 *
 * 用普通 var 而不是 Compose State：它只在手势协程里读写，不参与组合，
 * 每次触屏都触发重组反而会把整页拖慢。
 */
class HomePointerCountState {
    var pressedPointers: Int = 0
}

/**
 * 「滚动页面」的速度门限（dp/s，竖直分量）。
 *
 * 依据：
 * - Android 的 `ViewConfiguration` 里 fling 最低速度只有 50dp/s，那只是「算不算惯性」，
 *   远低于「用户想翻页」的意图；
 * - 实测/常见量级：缓慢翻页 300–800dp/s，真正甩动一次 1000–4000dp/s；
 *   按住卡片再挪动则多在 200–600dp/s（拿起是「按住 → 移动」，不是甩）；
 * - 取 900dp/s：比典型拖拽高一档、比典型甩动低一档，两边都留余量。
 */
private const val SCROLL_FLICK_DP_PER_SEC = 900f

/** 起手窗口：按下后这么短时间内就越过 slop，是「甩」而不是「按住再拿」 */
private const val SCROLL_ONSET_MS = 90L

/** 起手窗口内的速度门限（dp/s）：比持续甩动低一档，起手即走几乎只可能是滚动 */
private const val SCROLL_ONSET_DP_PER_SEC = 700f

/** 起手窗口内单步就跨过这个倍数的 slop：只有甩动才会一步走这么远 */
private const val SCROLL_JUMP_SLOP_FACTOR = 3f

/** 位移跳变的观察窗口（ms）：超过这个时间才走远，是慢慢拖，不是甩 */
private const val SCROLL_JUMP_MS = 160L

/** 单帧位移门限（相对 slop 的倍数）：一帧跨过 2 倍 slop 只可能是甩 */
private const val SCROLL_STEP_SLOP_FACTOR = 2f

/** 「竖直占优」：纵向分量至少是横向的 1.15 倍（首页只能纵向滚动） */
private const val SCROLL_VERTICAL_DOMINANCE = 1.15f

/**
 * 单指在越过 touch slop 那一刻的意图判定：`true` = 用户在滚动页面，卡片不应接管。
 *
 * 四条并列条件（任一成立即滚动），都建立在「首页只能纵向滚动」上：
 * 1. **速度**：竖直分量 ≥ [SCROLL_FLICK_DP_PER_SEC] —— 典型甩动；
 * 2. **起手速度**：[SCROLL_ONSET_MS] 内越过 slop 且竖直速度 ≥ [SCROLL_ONSET_DP_PER_SEC]
 *    —— 没有「按住再拿」的停顿；
 * 3. **位移范围**：[SCROLL_JUMP_MS] 内已累计 ≥ [SCROLL_JUMP_SLOP_FACTOR] 倍 slop
 *    —— 一步跨过（约 24dp），只有甩动会这样起手；
 * 4. **单帧位移**：越过 slop 的这一帧本身 ≥ [SCROLL_STEP_SLOP_FACTOR] 倍 slop
 *    —— 兜住「先停住、再甩」：停顿时速度采样只剩一个样本（算出来是 0），
 *    但一帧内走这么远，只可能是甩动。
 *
 * 为什么不是纯距离：慢拖 8dp 也会越过 slop，但那是小心地拿起卡片；
 * 为什么不是纯速度：低采样率屏幕会把缓慢拖动的瞬时速度算高，位移跳变可以兜底；
 * 为什么要求竖直占优：横向 / 斜向移动只可能是换列或拉伸，页面不会横向滚动。
 *
 * @param accumulated 越过 slop 时的累计位移（px）
 * @param lastStep 越过 slop 这一帧的位移（px）
 * @param velocity 越过 slop 时的速度（px/s）
 * @param elapsedMs 从按下列越过 slop 的时间（ms）
 * @param touchSlopPx 系统 touch slop（px）
 * @param density 屏幕密度（px/dp）
 */
internal fun isScrollIntent(
    accumulated: Offset,
    lastStep: Offset,
    velocity: Offset,
    elapsedMs: Long,
    touchSlopPx: Float,
    density: Float
): Boolean {
    if (density <= 0f || touchSlopPx <= 0f) return false
    val dx = abs(accumulated.x)
    val dy = abs(accumulated.y)
    if (dy <= dx * SCROLL_VERTICAL_DOMINANCE) return false
    val verticalSpeedDpPerSec = abs(velocity.y) / density
    if (verticalSpeedDpPerSec >= SCROLL_FLICK_DP_PER_SEC) return true
    if (elapsedMs <= SCROLL_ONSET_MS && verticalSpeedDpPerSec >= SCROLL_ONSET_DP_PER_SEC) return true
    if (
        elapsedMs <= SCROLL_JUMP_MS &&
        accumulated.getDistance() >= touchSlopPx * SCROLL_JUMP_SLOP_FACTOR
    ) {
        return true
    }
    val stepDx = abs(lastStep.x)
    val stepDy = abs(lastStep.y)
    return stepDy > stepDx * SCROLL_VERTICAL_DOMINANCE &&
        lastStep.getDistance() >= touchSlopPx * SCROLL_STEP_SLOP_FACTOR
}

/**
 * 本次事件是否属于多指手势：本节点自己的 changes，加上宿主维护的全局手指数。
 *
 * 只看本节点不够 —— 第二根手指落在别的卡上时，本节点收不到它的 change。
 */
private fun isMultiPointer(event: PointerEvent, shared: HomePointerCountState?): Boolean =
    (shared?.pressedPointers ?: 0) > 1 || event.changes.count { it.pressed } > 1

/**
 * 归一化拖动：先越过 touch slop、且判定为「拿起」意图后才「接管」手势
 * （此前不消费事件，父级滚动 / 子级点击仍有机会处理），接管后每帧回调位移增量，
 * 抬手或指针抬起时收尾。
 *
 * **双指一律不接管**：启动时就是双指、或拖动途中第二根手指落下，都直接放弃本次卡片交互
 * （[onCancel]，不落位不写回），事件全部留给父级滚动 —— 用户明确要求「双指默认就是滚动页面」。
 *
 * 之所以不用 `detectDragGestures`：这里需要在**未越过 slop 前完全不消费事件**，
 * 并通过 [isScrollIntent] 在接管前把「甩动页面」与「拿起卡片」分开。
 */
private suspend fun PointerInputScope.dragAfterSlop(
    pointerCount: HomePointerCountState?,
    onStart: (Offset) -> Unit,
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) {
    val touchSlop = viewConfiguration.touchSlop
    val density = density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (isMultiPointer(currentEvent, pointerCount)) return@awaitEachGesture

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var acc = Offset.Zero
        var started = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                // 第二根手指落下：本次手势永远不再接管卡片（双指 = 滚动）
                if (isMultiPointer(event, pointerCount)) {
                    if (started) {
                        onCancel()
                        started = false
                    }
                    return@awaitEachGesture
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!started) {
                    acc += delta
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (acc.getDistance() > touchSlop) {
                        val velocity = tracker.calculateVelocity()
                        if (
                            isScrollIntent(
                                accumulated = acc,
                                lastStep = delta,
                                velocity = Offset(velocity.x, velocity.y),
                                elapsedMs = change.uptimeMillis - down.uptimeMillis,
                                touchSlopPx = touchSlop,
                                density = density
                            )
                        ) {
                            // 判定为滚动：**不消费**本次事件，父级 verticalScroll 在同一个
                            // Main pass 里紧接着就能拿到它并开始滚动。
                            return@awaitEachGesture
                        }
                        started = true
                        change.consume()
                        onStart(down.position)
                        onDelta(acc)
                    }
                } else {
                    change.consume()
                    onDelta(delta)
                }
            }
        } finally {
            // 手势被系统取消（多指抢占等）时也要收尾，否则预览态会卡住
            if (started) onEnd()
        }
    }
}

/**
 * 首页「卡片画布」：把卡片列表渲染成 2 列网格，支持拖动换位与拉伸缩放。
 *
 * - **非编辑态**：只做静态渲染，不挂任何手势，[cardContent] 内部点击完全不受影响。
 * - **编辑态**：卡片正文上方盖一层拖拽遮罩（拖动换位），四角分别是
 *   左上下「笔 / 大头针」、右上「红叉删除」、右下「拉伸手柄」，顶部中央「+」。
 *
 * 列数默认 [HomeLayoutStore.COLUMNS]（2）、大屏可自适应增列：卡片的 `colSpan` 是
 * 绝对列数语义（1–2 列），列数只影响画布粒度、不影响数据（详见下方 columns 处说明）。
 *
 * @param columns 渲染列数。默认 [HomeLayoutStore.COLUMNS]（2）；大屏由调用方按
 *   画布可用宽度自适应传入更大偶数（见 [com.ilyskyo.blancall.ui.common.homeGridColumns]）。
 *   只改变画布粒度——卡片仍按 colSpan（1–2 列）占位，列数越多一行排得越多、卡越窄。
 *
 * 拖动 / 缩放期间只改本地预览态，**松手才回写** [onCardsChange]，保证一次手势一次提交。
 *
 * @param onPinToggle 点大头针。回传该卡**当前实际槽位** `(row, col)`，
 *   宿主据此在「刚变 pinned 且 lockRow/lockCol 还是 -1」时写入锁定坐标。
 */
@Composable
fun HomeCardCanvas(
    cards: List<HomeLayoutStore.Card>,
    editMode: Boolean,
    onCardsChange: (List<HomeLayoutStore.Card>) -> Unit,
    onDeleteCard: (HomeLayoutStore.Card) -> Unit,
    onPinToggle: (HomeLayoutStore.Card, row: Int, col: Int) -> Unit,
    onEditConfig: (HomeLayoutStore.Card) -> Unit,
    onAddCard: () -> Unit,
    /** 非编辑态长按任意卡片（回传卡片 id），宿主据此进入编辑态 */
    onLongPressCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 编辑态「管理最近文章」入口（null = 不展示该按钮，如一篇都没有时） */
    onManageArticles: (() -> Unit)? = null,
    /** 首张卡片的锚点：首页「长按卡片」指引要圈住真实卡片，而不是整块画布（null = 不指） */
    firstCardAnchor: CoachAnchorState? = null,
    /** 编辑态「＋」按钮的锚点：供「＋ 能加卡」指引把圈落在按钮上（null = 不指） */
    addButtonAnchor: CoachAnchorState? = null,
    /**
     * 全屏手指数（见 [HomePointerCountState]）。
     * 双指手势一律不接管任何卡片交互（拖动 / 拉伸），事件全部让给页面滚动；
     * null = 不启用该判断（仅本节点自己的 changes 可见）。
     */
    pointerCount: HomePointerCountState? = null,
    /** 渲染列数：大屏自适应多列（见 KDoc）；默认与卡片「绝对列数」存储语义一致 */
    columns: Int = HomeLayoutStore.COLUMNS,
    cardContent: @Composable (HomeLayoutStore.Card) -> Unit
) {
    val add_cardLabel = stringResource(com.ilyskyo.blancall.R.string.add_card)
    val edit_card_configLabel = stringResource(com.ilyskyo.blancall.R.string.edit_card_config)
    val delete_cardLabel = stringResource(com.ilyskyo.blancall.R.string.delete_card)
    // 回调也用 rememberUpdatedState 兜住：外层 lambda 每次重组换新实例也不会漏掉最新逻辑
    val cbCardsChange by rememberUpdatedState(onCardsChange)
    val cbDelete by rememberUpdatedState(onDeleteCard)
    val cbPin by rememberUpdatedState(onPinToggle)
    val cbEdit by rememberUpdatedState(onEditConfig)
    val cbAdd by rememberUpdatedState(onAddCard)
    val cbLongPress by rememberUpdatedState(onLongPressCard)
    // **时序修复**：editMode 必须用 rememberUpdatedState 读最新值，且**不能**作为下面
    // pointerInput 的 key —— 长按进编辑态会立即触发重组，若 pointerInput 因 key 变化被取消，
    // 正在「等待并消费本手势剩余事件（含 UP）」的协程会一并死掉，抬手仍会穿透到卡内点击区
    // （用户反馈：长按添加文章卡→进导入页 / 文章卡→详情页 / 最近使用行→详情页）。
    val currentEditMode by rememberUpdatedState(editMode)

    // ---------------------------------------------------------------------
    // 列数：默认 [HomeLayoutStore.COLUMNS]（2）；大屏由调用方传入更大的偶数
    // （HomeScreen 按画布可用宽度自适应，见 homeGridColumns）。
    //
    // 渲染列数只改变**画布粒度**：卡片尺寸仍是「绝对列数」语义（colSpan 1–2，
    // 满宽 = 2，见 HomeLayoutStore），因此列数增多不会破坏数据语义 ——
    // 卡片照常按 1–2 列占位，一行能排的卡随列数增加、单卡随之变窄，
    // 宽屏下不再被拉成 600dp 级扁条（真机反馈）。
    //
    // 历史：这里曾把「固定 2 列」当作唯一安全解（怕列数 >2 后卡回不到满宽）——
    // 那是把「渲染粒度」错当成「尺寸语义」；两者解耦后此顾虑不成立。
    // ---------------------------------------------------------------------

    val density = LocalDensity.current
    // 统一强触感：长按进编辑、拖动/缩放开始都用同一套“咔嗒”（各机型一致、明显）
    // 触觉闭包要经 rememberUpdatedState：pointerInput(card.id) 只在 key 变化时才重启，
    // 直接捕获的话用户在设置里改「触感强度」对拖拽/缩放落位永远不生效（要到杀进程才更新）。
    val confirmHaptic by rememberUpdatedState(rememberConfirmHaptic())
    // 落位回执：改造前只有**拿起**那一下会振，松手落格却是沉默的 ——
    // 手指做了两件大事（拿起、放下），只听到一件。落格用 Threshold（双段，「跨过一道界」）。
    val commitHaptic by rememberUpdatedState(rememberHaptic(HapticTier.Threshold))

    // 手势中的预览态（不落地，松手才写回）。
    // dragTarget 只被手势/落点计算读取；视觉位移由 dragAnim 在 graphicsLayer 里读取 ——
    // 拖动时每个 move 只改 State/绘相位，不再让整块画布（所有卡片）每帧重组。
    var dragCardId by remember { mutableStateOf<String?>(null) }
    var dragTarget by remember { mutableStateOf(Offset.Zero) }
    val dragAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(dragCardId) {
        if (dragCardId != null) {
            snapshotFlow { dragTarget }.collect { dragAnim.snapTo(it) }
        } else {
            dragAnim.animateTo(Offset.Zero, Motion.slotSettleOffset())
        }
    }
    var resizeCardId by remember { mutableStateOf<String?>(null) }
    var resizeBaseCol by remember { mutableIntStateOf(1) }
    var resizeBaseRow by remember { mutableIntStateOf(1) }
    var resizeColSpan by remember { mutableIntStateOf(1) }
    var resizeRowSpan by remember { mutableIntStateOf(1) }
    var resizeTotal by remember { mutableStateOf(Offset.Zero) }

    // 缩放预览参与排版：尺寸一变，画布立即按新尺寸重新流式排布
    val effectiveCards = remember(cards, resizeCardId, resizeColSpan, resizeRowSpan) {
        val id = resizeCardId
        if (id == null) cards
        else cards.map {
            if (it.id == id) it.copy(colSpan = resizeColSpan, rowSpan = resizeRowSpan) else it
        }
    }
    val slots = remember(effectiveCards, columns) { layoutSlots(effectiveCards, columns) }
    val currentCards by rememberUpdatedState(cards)
    val currentSlots by rememberUpdatedState(slots)

    Column(modifier = modifier) {
        // 编辑态工具条：左「管理最近文章」（有文章时才出现）+ 右「添加卡片」。
        // 行内长按「从首页删除」已下线：它与「长按卡片进编辑态」互相打架（用户反馈「一些卡片长按无效」），
        // 移除入口统一收进这里的管理面板。
        if (editMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onManageArticles?.let { onManage ->
                    GlassButton(
                        onClick = onManage,
                        modifier = Modifier.height(44.dp),
                    ) {
                        AppIcon(
                            kind = AppIconKind.Inbox,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.manage_recent_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .then(if (addButtonAnchor != null) Modifier.coachAnchor(addButtonAnchor) else Modifier)
                        .pressClick { cbAdd() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = add_cardLabel,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 空画布兜底：卡片被全部删光后，长按进编辑态这条路径就不存在了（没有卡片可长按），
        // 必须给一个显式入口，否则用户再也加不回卡片。
        if (cards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .pressClick { cbAdd() }
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = add_cardLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(com.ilyskyo.blancall.R.string.home_no_cards_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val safeWidth = if (maxWidth.value.isFinite()) maxWidth else 0.dp
            val cellW = (
                (safeWidth - COLUMN_GAP * (columns - 1).toFloat()) /
                    columns.toFloat()
                ).coerceAtLeast(0.dp)
            val cellPitch = cellW + COLUMN_GAP
            val cellWpx = with(density) { cellW.toPx() }
            val cellPitchPx = with(density) { cellPitch.toPx() }
            val rowUnitPx = with(density) { ROW_UNIT.toPx() }

            val totalRows = slots.entries.maxOfOrNull { (id, rc) ->
                rc[0] + (effectiveCards.firstOrNull { it.id == id }?.rowSpan ?: 1)
            } ?: 0

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_UNIT * totalRows.toFloat())
            ) {
                // 卡片几何统一先算好（含位移动画）：内容层与控件层**共用同一份**，
                // 两层用相同的 offset/尺寸/缩放，控件白钮在拖动、缩放中始终与卡面像素级对齐。
                val geoMap = LinkedHashMap<String, CardGeo>(cards.size)
                cards.forEach { card ->
                    key(card.id) {
                        val slot = slots[card.id] ?: intArrayOf(0, 0)
                        val isDragging = dragCardId == card.id
                        val isResizing = resizeCardId == card.id
                        val colSpan = if (isResizing) resizeColSpan else card.colSpan
                        val rowSpan = if (isResizing) resizeRowSpan else card.rowSpan

                        val slotX = cellPitch * slot[1].toFloat()
                        val slotY = ROW_UNIT * slot[0].toFloat()
                        // 槽位：换格交给弹簧（改造前是两条互不相干的 220ms 定时补间）
                        val animX by animateDpAsState(
                            targetValue = slotX,
                            animationSpec = Motion.slotTravel(),
                            label = "cardSlotX",
                        )
                        val animY by animateDpAsState(
                            targetValue = slotY,
                            animationSpec = Motion.slotTravel(),
                            label = "cardSlotY",
                        )
                        // 拖出量不再参与组合期几何：由 graphicsLayer 读取 dragAnim，
                        // 松手后同一根二维弹簧把 X/Y 一起弹回 0（见 dragAnim 的 LaunchedEffect）。
                        val x = animX
                        val y = animY

                        val w = cellW * colSpan.toFloat() +
                            COLUMN_GAP * (colSpan - 1).toFloat()
                        val h = ROW_UNIT * rowSpan.toFloat()

                        geoMap[card.id] = CardGeo(x, y, w, h, colSpan, rowSpan, isDragging, isResizing)
                    }
                }

                // ── 第一遍：全部卡片的内容层（正文 + 指示环 + 拖动遮罩）──
                // 冷启动的入场编排：整页卡片按格子顺序错峰淡入。开关放在循环外，
                // 循环里每帧 remember 会因为列表增删而错位。
                val entered = rememberEnteredOnFirstFrame()
                cards.forEachIndexed { index, card ->
                    key(card.id) {
                        // 注意：不要在这里写 `?: return@key`。在 @Composable inline 函数（key）
                        // 的 lambda 里做 labeled return，Compose 编译器会生成 $NON_LOCAL_RETURN 机制、
                        // 产出名为 <anonymous> 的 JVM 方法 —— ClassFormatError: Illegal method name，
                        // 类一加载就崩（单测/真机同样）。geoMap 在同一组合内先填充，取值为空即异常。
                        val geo = geoMap.getValue(card.id)
                        // 抬起要有过程：改造前是 graphicsLayer 里的 `if (isDragging) 1.03f` ——
                        // 布尔硬切，拿起与放下那一帧整张卡突然变大一档，看不出「重量」只看出「跳」。
                        val lifted = geo.isDragging || geo.isResizing
                        val liftScale by animateFloatAsState(
                            targetValue = if (lifted) Motion.Scale.lifted else 1f,
                            animationSpec = Motion.lift(),
                            label = "cardLiftScale"
                        )
                        val liftAlpha by animateFloatAsState(
                            targetValue = if (lifted) Motion.liftAlpha else 1f,
                            animationSpec = MotionFade.alpha(MotionFade.enter),
                            label = "cardLiftAlpha"
                        )
                        // 入场错峰：只淡入、不给位移 —— 卡片是绝对定位的，位移会和槽位弹簧打架。
                        // 减动效下 MotionFade.alpha 退化成 snap，卡片直接以终态出现。
                        val entryAlpha by animateFloatAsState(
                            targetValue = if (entered) 1f else 0f,
                            animationSpec = MotionFade.alpha(
                                MotionFade.itemEnter,
                                staggerDelay(index),
                            ),
                            label = "cardEnterAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .width(geo.width)
                                .height(geo.height)
                                .zIndex(if (geo.isDragging || geo.isResizing) 1f else 0f)
                                // 首卡锚点：首页「长按卡片」指引只圈第一张卡（圈整块画布等于没圈）
                                .then(
                                    if (index == 0 && firstCardAnchor != null) Modifier.coachAnchor(firstCardAnchor)
                                    else Modifier
                                )
                                // 位置走 translation 而不是 Modifier.offset：offset 是布局修饰符，
                                // 槽位弹簧与拖出量每帧都在改它 → 每帧重跑一遍 placement。
                                // translation 只动绘相位，拖动与落位期间卡片不再逐帧重排。
                                .graphicsLayer {
                                    translationX = geo.x.toPx() + dragAnim.value.x
                                    translationY = geo.y.toPx() + dragAnim.value.y
                                    scaleX = liftScale
                                    scaleY = liftScale
                                    alpha = liftAlpha * entryAlpha
                                }
                        ) {
                            // ① 内容层：非编辑态唯一在场的一层，点击原样透传给 cardContent。
                            // 长按检测放在这里而不是宿主外层：用 requireUnconsumed = false 观察事件且
                            // **绝不消费**，所以即使卡片内容内部有自己的行级手势（如「最近使用」列表行），
                            // 长按依然能进编辑态（此前放外层 combinedClickable 会被内层消费掉）。
                            // 关键：裁切用**卡片同曲率的圆角**（不是 clipToBounds 的直角）——
                            // 内容/背景溢出槽位时按圆角轮廓裁掉，四角永远是圆的；
                            // 旧版直角裁切会露出直角残角，看起来像「这个角没做圆角」。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = SLOT_INSET)
                                    .clip(HOME_CARD_SHAPE)
                                    // key 只含 card.id：editMode 引起的重组**不再重启本协程**，
                                    // 长按判定后「消费剩余事件直到抬手」的循环才能活到 UP 被吃掉（时序修复）。
                                    .pointerInput(card.id) {
                                        awaitEachGesture {
                                            if (currentEditMode) {
                                                // 编辑态：消费一切事件（点按/长按）。
                                                // **必须在 Initial pass 消费**：Main pass 是「子→父」，内层 clickable 会最先
                                                // 看到未消费的 UP 并判定为点击 —— 只有父级在 Initial pass（父→子）先消费，
                                                // 内层才会收到「已被消费的 UP」从而取消点击。
                                                // （「长按进编辑后点完成会跳详情、首页↔详情反复跳」的根治）
                                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                while (true) {
                                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                                    e.changes.forEach { it.consume() }
                                                    if (e.changes.all { !it.pressed }) break
                                                }
                                            } else {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                val slop = viewConfiguration.touchSlop
                                                var acc = Offset.Zero
                                                // 长按判定（Initial pass 观察）：按住满 longPressTimeout 且期间**未抬起、未滑动越过 slop**。
                                                // 不能用 waitForUpOrCancellation：内层手势消费事件时它会同样返回 null，
                                                // 与「超时」不可区分 —— 那正是之前「一些卡片长按无效 / 乱触发」的根因。
                                                val timedOut = withTimeoutOrNull(
                                                    maxOf(
                                                        viewConfiguration.longPressTimeoutMillis,
                                                        EDIT_MODE_LONG_PRESS_MS,
                                                    )
                                                ) {
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        val change = event.changes
                                                            .firstOrNull { it.id == down.id } ?: break
                                                        if (!change.pressed) break
                                                        acc += change.positionChange()
                                                        if (acc.getDistance() > slop) break
                                                    }
                                                }
                                                // null = 时间到且全程按住未滑动 → 长按：触感反馈 + 进编辑态
                                                if (timedOut == null) {
                                                    confirmHaptic()
                                                    cbLongPress(card.id)
                                                    // **严肃修复**：长按后的抬手不能被卡内点击区误判为「点击」——
                                                    // 旧版（Main pass 不消费）：「长按添加文章卡→进导入页」「长按文章卡→进详情页」
                                                    // 「长按最近使用行→进详情」。在 Initial pass 消费本手势剩余事件（含 UP），
                                                    // 内层 clickable 收到被消费的 UP → 自动取消点击；高亮/涟漪也一并取消。
                                                    down.consume()
                                                    while (true) {
                                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                                        e.changes.forEach { it.consume() }
                                                        if (e.changes.all { !it.pressed }) break
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                cardContent(card)
                            }

                            if (editMode) {
                                // ② 编辑态指示环：与卡片**同曲率**（HOME_CARD_SHAPE）且同样只在纵向内缩一个 SLOT_INSET，
                                // 环正好贴着卡片外缘、内外圆角一致 —— 视觉上像卡片本身被「点亮」，
                                // 而不是套一圈曲率对不上的灰框（旧版 RoundedCornerShape(18.dp)，用户反馈「删掉/把曲率做好」）。
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = SLOT_INSET)
                                        .border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                            shape = HOME_CARD_SHAPE,
                                        )
                                )

                                // ③ 编辑遮罩：吃掉点按（避免误触卡片内容）、承接拖动换位
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 编辑态**彻底吃掉纯点击**：旧版遮罩不消费点击，点按会穿透到内容层
                                        // 触发「打开阅读」——「长按进编辑后点完成会跳详情、首页↔详情反复跳」的根因。
                                        // 空 clickable 只消费点击（无涟漪）；dragAfterSlop 用
                                        // requireUnconsumed=false 观察事件，拖动换位不受影响。
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {}
                                        .pointerInput(card.id, cellWpx, cellPitchPx, rowUnitPx) {
                                            dragAfterSlop(
                                                pointerCount = pointerCount,
                                                onStart = {
                                                    dragCardId = card.id
                                                    dragTarget = Offset.Zero
                                                    confirmHaptic()
                                                },
                                                onDelta = { d -> dragTarget += d },
                                                onEnd = {
                                                    val c = currentCards.firstOrNull { it.id == card.id }
                                                    val s = currentSlots[card.id]
                                                    if (c != null && s != null) {
                                                        val gapPx =
                                                            (cellPitchPx - cellWpx).coerceAtLeast(0f)
                                                        val wPx = c.colSpan * cellWpx +
                                                            (c.colSpan - 1) * gapPx
                                                        val hPx = c.rowSpan * rowUnitPx
                                                        val centerX = cellPitchPx * s[1] +
                                                            wPx / 2f + dragTarget.x
                                                        val centerY = rowUnitPx * s[0] +
                                                            hPx / 2f + dragTarget.y
                                                        val tCol = floor(centerX / cellPitchPx)
                                                            .toInt()
                                                            .coerceIn(
                                                                0,
                                                                (columns - c.colSpan)
                                                                    .coerceAtLeast(0)
                                                            )
                                                        val tRow = floor(centerY / rowUnitPx)
                                                            .toInt()
                                                            .coerceIn(
                                                                0,
                                                                currentCards.size *
                                                                    HomeLayoutStore.MAX_ROW_SPAN
                                                            )
                                                        // 钉住的卡：重新钉到落点格（改 lock）；自由卡：交换 / 就近插入
                                                        val next = if (c.pinned) {
                                                            resolvePinnedDrop(currentCards, c, tRow, tCol, columns)
                                                        } else {
                                                            resolveDrop(currentCards, c, tRow, tCol, columns)
                                                        }
                                                        if (next != currentCards) {
                                                            // 真的换了格子才给回执；原地弹回不响（那是「没放下」而不是「放好了」）
                                                            commitHaptic()
                                                            cbCardsChange(next)
                                                        }
                                                    }
                                                    dragCardId = null
                                                },
                                                onCancel = {
                                                    // 双指介入：本次不落位、不交换、不写回 ——
                                                    // 卡片只负责弹簧归位，其余交给页面滚动。
                                                    dragCardId = null
                                                }
                                            )
                                        }
                                )

                                // ④ 控件层已移至第二遍「控件浮层」（见下方）：绘制在全部卡片内容之上，
                                //    拖动 / 缩放中不会被任何卡面盖住。
                            }
                        }
                    }
                }

                // ── 第二遍：编辑态控件浮层 ──
                // 写在全部卡片内容**之后**（zIndex ≥ 2f，拖动/缩放中的卡再高一档 3f）：
                // 无论卡片怎样互相重叠、拖动或缩放，四角的圆圈都完整绘制在卡面之上 ——
                // 修掉「拖动 / 拉动编辑大小时圆圈残缺、有遮挡」的问题。
                if (editMode) {
                    cards.forEach { card ->
                        key(card.id) {
                            // 同样：不得用 `return@key`（见上方的说明）。
                            val geo = geoMap.getValue(card.id)
                            // 控件层同样补间抬起倍数（布尔硬切的问题与内容层一致）。
                            val controlsLifted = geo.isDragging || geo.isResizing
                            val controlLiftScale by animateFloatAsState(
                                targetValue = if (controlsLifted) Motion.Scale.lifted else 1f,
                                animationSpec = Motion.lift(),
                                label = "controlLiftScale"
                            )
                            Box(
                                modifier = Modifier
                                    .width(geo.width)
                                    .height(geo.height)
                                    .zIndex(if (geo.isDragging || geo.isResizing) 3f else 2f)
                                    // 与内容层同一套 translation（两层必须逐帧一致，否则白钮会脱离卡角）
                                    .graphicsLayer {
                                        translationX = geo.x.toPx() + dragAnim.value.x
                                        translationY = geo.y.toPx() + dragAnim.value.y
                                        scaleX = controlLiftScale
                                        scaleY = controlLiftScale
                                        // 只缩放、**不设 alpha**：alpha<1 会让该层走离屏合成，
                                        // 图层边界=卡片矩形，控件伸出卡角的那一截被直角裁掉
                                        // （用户反馈「拖动/拉伸时圆圈上边和左边被削平」）。
                                    }
                            ) {
                                // 四角控件的圆心距卡面 8dp、向卡外探出 5dp（见 CONTROL_INSET 与 CONTROL_INSET_X），
                                // 相邻卡同侧控件的圆心距 = 横向 12dp + 16dp = 28dp、纵向 10dp + 16dp = 26dp ≥ 视觉直径 26dp。
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = -CONTROL_INSET_X, y = -CONTROL_INSET),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 「笔」在大头针左侧（仅可编辑卡显示）
                                    if (card.editable) {
                                        ControlButton(
                                            onClick = { cbEdit(card) },
                                            scrim = CONTROL_SCRIM
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_pencil),
                                                contentDescription = edit_card_configLabel,
                                                tint = CONTROL_ICON_COLOR,
                                                modifier = Modifier.size(CONTROL_ICON)
                                            )
                                        }
                                    }
                                    ControlButton(
                                        onClick = {
                                            // 把该卡当前实际槽位一并回传，宿主写入 lockRow/lockCol
                                            val s = slots[card.id] ?: intArrayOf(0, 0)
                                            cbPin(card, s[0], s[1])
                                        },
                                        scrim = CONTROL_SCRIM
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PushPin,
                                            contentDescription = stringResource(
                                                if (card.pinned) com.ilyskyo.blancall.R.string.a11y_unpin_card
                                                else com.ilyskyo.blancall.R.string.a11y_pin_card
                                            ),
                                            // 已固定用主题色（一眼可辨「这张被钉住了」），未固定用深灰（白底上清晰）
                                            tint = if (card.pinned) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                CONTROL_ICON_COLOR
                                            },
                                            modifier = Modifier.size(CONTROL_ICON)
                                        )
                                    }
                                }

                                // 右上角红叉：实心白底衬，浅色卡面 / 深色玻璃上都看得见
                                ControlButton(
                                    onClick = { cbDelete(card) },
                                    scrim = CONTROL_SCRIM,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = CONTROL_INSET_X, y = -CONTROL_INSET)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = delete_cardLabel,
                                        tint = CLOSE_COLOR,
                                        modifier = Modifier.size(CONTROL_ICON)
                                    )
                                }

                                // 右下角拉伸手柄：只有一段弧线、没有圆底（用户要求），
                                // 卡角自己的圆角因此完整露出来；热区仍比图形大得多。
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = CONTROL_INSET_X, y = CONTROL_INSET)
                                        .size(HANDLE_HIT)
                                        .pointerInput(card.id, cellWpx, cellPitchPx, rowUnitPx) {
                                            dragAfterSlop(
                                                pointerCount = pointerCount,
                                                onStart = {
                                                    val c = currentCards
                                                        .firstOrNull { it.id == card.id } ?: card
                                                    resizeBaseCol = c.colSpan
                                                    resizeBaseRow = c.rowSpan
                                                    resizeColSpan = c.colSpan
                                                    resizeRowSpan = c.rowSpan
                                                    resizeTotal = Offset.Zero
                                                    resizeCardId = card.id
                                                    confirmHaptic()
                                                },
                                                onDelta = { d ->
                                                    resizeTotal += d
                                                    val halfCell =
                                                        (cellWpx * 0.5f).coerceAtLeast(1f)
                                                    val halfRow =
                                                        (rowUnitPx * 0.5f).coerceAtLeast(1f)
    resizeColSpan = (
        resizeBaseCol +
            (resizeTotal.x / halfCell).toInt()
        ).coerceIn(
        HomeLayoutStore.MIN_COL_SPAN,
        // 不能超过当前网格总列数（否则 colSpan > columns 会占格越界）
        minOf(HomeLayoutStore.MAX_COL_SPAN, columns)
    )
                                                    // 文章卡片内容少，限制最大尺寸（最多 2 行高）——
                                                    // 拉太大只会剩大片空白；其余卡片自由缩放（用户要求）
                                                    val maxRowSpan =
                                                        if (card.type == HomeLayoutStore.CardType.ARTICLE) {
                                                            2
                                                        } else {
                                                            HomeLayoutStore.MAX_ROW_SPAN
                                                        }
                                                    resizeRowSpan = (
                                                        resizeBaseRow +
                                                            (resizeTotal.y / halfRow).toInt()
                                                        ).coerceIn(
                                                        HomeLayoutStore.MIN_ROW_SPAN,
                                                        maxRowSpan
                                                    )
                                                },
                                                onEnd = {
                                                    val next = currentCards.map {
                                                        if (it.id == card.id) {
                                                            // 钉住的卡：colSpan 变了必须把 lockCol 收进新宽度，
                                                            // 否则 lockCol + colSpan > COLUMNS 会占格越界
                                                            val lockCol =
                                                                if (it.pinned && it.lockCol >= 0) {
                                                                    it.lockCol.coerceIn(
                                                                        0,
                                                                        (
                                                                            columns -
                                                                                resizeColSpan
                                                                            ).coerceAtLeast(0)
                                                                    )
                                                                } else it.lockCol
                                                            it.copy(
                                                                colSpan = resizeColSpan,
                                                                rowSpan = resizeRowSpan,
                                                                lockCol = lockCol
                                                            )
                                                        } else it
                                                    }
                                                    resizeCardId = null
                                                    if (next != currentCards) {
                                                        // 尺寸真的变了才落位回执（与拖动同一档）
                                                        commitHaptic()
                                                        cbCardsChange(next)
                                                    }
                                                },
                                                onCancel = {
                                                    // 双指介入：放弃本次拉伸，尺寸保持原样（不写回）
                                                    resizeCardId = null
                                                    resizeTotal = Offset.Zero
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.BottomEnd
                                ) {
                                    ResizeHandleGlyph()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 编辑态圆形小按钮：**触摸热区**固定 [CONTROL_HIT]（≥32dp），**视觉白圆**为 [CONTROL_VISUAL]，
 * 两者分层 —— 热区不缩水（好点），白圆缩到 26dp 后相邻卡的控件不再相碰。
 * 白色实心底衬 + 投影 + 极淡描边，保证在米白 / 浅粉 / 浅绿等任何卡面上都能一眼看到。
 */
@Composable
private fun ControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrim: Color = CONTROL_SCRIM,
    content: @Composable () -> Unit
) {
    val controlSrc = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(CONTROL_HIT)
            // 角上的圆钮 → Icon 档（行程短，收得最多才看得见）。
            // clip 成圆再 clickable 这条**保留**：它原本是给 Material 水波纹裁形的
            // （用户反馈「点击圆圈时出现灰色矩形」），现在涟漪关了但它仍决定
            // 按压的亮度层画在圆内而不是方块内。
            .pressFeedback(controlSrc, PressTier.Icon)
            .clip(CircleShape)
            .clickable(interactionSource = controlSrc, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(CONTROL_VISUAL)
                // 轻阴影把白色圆钮从卡面上「抬」起来；2dp 即可，再深就会与相邻卡阴影相碰
                .shadow(elevation = 2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(scrim)
                .border(1.5.dp, CONTROL_RING, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * 拉伸手柄图形：**沿卡片右下圆角的一段小弧**（无圆底）。
 *
 * 与卡片圆角**同心、曲率一致**，且在外侧留出合理间距（中心线半径 24dp，
 * 弧线内缘距圆角约 2.5dp）——不贴边、不抢视觉，只截取中间一小段短弧。
 *
 * 圆心必须与**卡面**右下圆角同心（不是槽位角）：卡面左右与槽位齐平、底部内缩 [SLOT_INSET]，
 * 所以圆角圆心 = 槽位右下角 −(20, 25)。热区右下在槽位角 +(CONTROL_INSET_X, CONTROL_INSET)，
 * Canvas 左上角即弧圆心 → Canvas 右下 = 槽位角 +(8−2, 3−1) → 左上 = −(20, 25)，padding = 2 / 2。
 */
@Composable
private fun ResizeHandleGlyph() {
    Box(
        modifier = Modifier.padding(end = 2.dp, bottom = 2.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val stroke = 3.dp.toPx()
            // 中心线半径：圆角（20dp）+ 4dp —— 与圆角同心，外侧留出约 2.5dp 视觉间距
            val radius = 24.dp.toPx()
            drawArc(
                color = HANDLE_COLOR,
                // 只取圆角弧的一小段（30°~60°，共 30°）：短而居中，轻轻抱住卡角
                startAngle = 30f,
                sweepAngle = 30f,
                useCenter = false,
                // 负 topLeft：圆心 = Canvas 左上角
                topLeft = Offset(-radius, -radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}


