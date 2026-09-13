// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.rememberConfirmHaptic
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor

// ---------------------------------------------------------------------------
// 尺寸常量
// ---------------------------------------------------------------------------

/** 单位行高：卡片高度 = rowSpan * ROW_UNIT（同时是纵向网格节距） */
private val ROW_UNIT = 92.dp

/** 列间距（横向网格节距 = cellW + COLUMN_GAP） */
private val COLUMN_GAP = 12.dp

/** 槽位内缩，给相邻卡片留出视觉间隙 */
private val SLOT_INSET = 5.dp

/** 编辑态控件的触摸热区（≥32dp，图形本身更小） */
private val CONTROL_HIT = 32.dp

/**
 * 编辑态控件的**视觉**白圆直径（比热区小）。
 * 关键约束：相邻两张卡的同侧控件圆心距 = 卡面实际间距 22dp + 两侧各内缩 10dp = 42dp，
 * 42dp > 26dp + 两侧阴影 —— 任何相邻关系（横排 / 竖排 / 对角）下圆钮都不会相碰。
 */
private val CONTROL_VISUAL = 26.dp

/**
 * 编辑态控件圆心相对卡片角的内缩量：圆心落在**卡片槽位内 11dp** 处
 * （= CONTROL_HIT/2 − CONTROL_INSET）。
 * 槽位比卡面大 SLOT_INSET（5dp），即圆心距**卡面**约 6dp，
 * 圆钮向卡外伸出的量降到最小 —— 相邻卡片的行间隙只有 10dp，
 * 控件伸得太多会与邻卡的控件相互贴住（用户反馈「还是略有遮挡」）。
 */
private val CONTROL_INSET = 3.dp

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
 * 网格布局：把 [cards] 按顺序流式放进固定的 [HomeLayoutStore.COLUMNS] 列网格。
 *
 * **第一轮（钉住的卡优先占位）**：按 cards 顺序，把 `pinned && lockRow >= 0 && lockCol >= 0`
 * 的卡放到它的锁定槽位 `(lockRow, lockCol)` 上。`lockCol` 先收敛到
 * `0..COLUMNS-colSpan`（防手工改 JSON 越界）；若该区域已被前面的钉住卡占走，
 * 则**沿同一列向下顺延**到最近可用行（列表靠前的钉住卡优先）。
 *
 * **第二轮（自由卡首次适配）**：按 cards 顺序，对其余卡（非 pinned，或 pinned 但 lock 未设定）
 * 自上而下、自左向右扫描，取**第一个能完整容纳 `colSpan × rowSpan` 的空位**放进去并占位。
 * 扫描逐格判定，跨行卡留下的空洞会被后续较矮的卡绕行填补。
 *
 * 复杂度 O(n × rows × COLUMNS)，卡片数量很小，无需优化。
 *
 * @return cardId → `intArrayOf(row, col)`（左上角格坐标）
 */
internal fun layoutSlots(cards: List<HomeLayoutStore.Card>): Map<String, IntArray> {
    val out = LinkedHashMap<String, IntArray>(cards.size)
    // 占用表：每行一个 BooleanArray(COLUMNS)，按需向下增长
    val grid = mutableListOf<BooleanArray>()
    fun ensureRows(n: Int) {
        while (grid.size < n) grid.add(BooleanArray(HomeLayoutStore.COLUMNS))
    }
    fun fits(row: Int, col: Int, colSpan: Int, rowSpan: Int): Boolean {
        if (col < 0 || col + colSpan > HomeLayoutStore.COLUMNS) return false
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
            0, (HomeLayoutStore.COLUMNS - colSpan).coerceAtLeast(0)
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
            for (col in 0..HomeLayoutStore.COLUMNS - colSpan) {
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
    targetCol: Int
): List<HomeLayoutStore.Card> {
    val fromIndex = cards.indexOfFirst { it.id == dragged.id }
    if (fromIndex < 0) return cards
    val others = cards.filterNot { it.id == dragged.id }
    if (others.isEmpty()) return cards

    // 视觉布局：与用户拖动时看到的一致（原地守卫与占位者判定都用它）
    val slots = layoutSlots(cards)

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
        abs(s[0] - targetRow) * HomeLayoutStore.COLUMNS + abs(s[1] - targetCol)
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
 * 也就是把 `lockRow/lockCol` 改写成落点格（`lockCol` 收敛到 `0..COLUMNS-colSpan`，
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
    targetCol: Int
): List<HomeLayoutStore.Card> {
    if (cards.none { it.id == dragged.id }) return cards
    val colSpan = dragged.colSpan.coerceIn(
        HomeLayoutStore.MIN_COL_SPAN, HomeLayoutStore.MAX_COL_SPAN
    )
    val rowSpan = dragged.rowSpan.coerceIn(
        HomeLayoutStore.MIN_ROW_SPAN, HomeLayoutStore.MAX_ROW_SPAN
    )
    val maxRow = cards.size * HomeLayoutStore.MAX_ROW_SPAN
    val lockCol = targetCol.coerceIn(
        0, (HomeLayoutStore.COLUMNS - colSpan).coerceAtLeast(0)
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
            swapCol + blockerColSpan <= HomeLayoutStore.COLUMNS &&
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
 * 归一化拖动：先越过 touch slop 才「接管」手势（此前不消费事件，父级滚动 / 子级点击
 * 仍有机会处理），接管后每帧回调位移增量，抬手或指针抬起时收尾。
 *
 * 之所以不用 `detectDragGestures`：这里需要在**未越过 slop 前完全不消费事件**，
 * 让未编辑态的滚动与子元素点击不受影响。
 */
private suspend fun PointerInputScope.dragAfterSlop(
    onStart: (Offset) -> Unit,
    onDelta: (Offset) -> Unit,
    onEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var acc = Offset.Zero
        var started = false
        val touchSlop = viewConfiguration.touchSlop
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!started) {
                    acc += delta
                    if (acc.getDistance() > touchSlop) {
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
    /** 编辑态「管理最近文章」入口（null = 不展示该按钮，如一篇都没有时） */
    onManageArticles: (() -> Unit)? = null,
    /** 非编辑态长按任意卡片（回传卡片 id），宿主据此进入编辑态 */
    onLongPressCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (HomeLayoutStore.Card) -> Unit
) {
    // 回调也用 rememberUpdatedState 兜住：外层 lambda 每次重组换新实例也不会漏掉最新逻辑
    val cbCardsChange by rememberUpdatedState(onCardsChange)
    val cbDelete by rememberUpdatedState(onDeleteCard)
    val cbPin by rememberUpdatedState(onPinToggle)
    val cbEdit by rememberUpdatedState(onEditConfig)
    val cbAdd by rememberUpdatedState(onAddCard)
    val cbLongPress by rememberUpdatedState(onLongPressCard)

    val density = LocalDensity.current
    // 统一强触感：长按进编辑、拖动/缩放开始都用同一套“咔嗒”（各机型一致、明显）
    val confirmHaptic = rememberConfirmHaptic()

    // 手势中的预览态（不落地，松手才写回）
    var dragCardId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
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
    val slots = remember(effectiveCards) { layoutSlots(effectiveCards) }
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
                            "管理最近文章",
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
                        .clickable { cbAdd() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加卡片",
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
                    .clickable { cbAdd() }
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加卡片",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "还没有卡片，点这里添加",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val safeWidth = if (maxWidth.value.isFinite()) maxWidth else 0.dp
            val cellW = (
                (safeWidth - COLUMN_GAP * (HomeLayoutStore.COLUMNS - 1).toFloat()) /
                    HomeLayoutStore.COLUMNS.toFloat()
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
                        val animX by animateDpAsState(
                            targetValue = slotX, animationSpec = tween(220)
                        )
                        val animY by animateDpAsState(
                            targetValue = slotY, animationSpec = tween(220)
                        )
                        val x = if (isDragging) slotX + with(density) { dragOffset.x.toDp() } else animX
                        val y = if (isDragging) slotY + with(density) { dragOffset.y.toDp() } else animY

                        val w = cellW * colSpan.toFloat() +
                            COLUMN_GAP * (colSpan - 1).toFloat()
                        val h = ROW_UNIT * rowSpan.toFloat()

                        geoMap[card.id] = CardGeo(x, y, w, h, colSpan, rowSpan, isDragging, isResizing)
                    }
                }

                // ── 第一遍：全部卡片的内容层（正文 + 指示环 + 拖动遮罩）──
                cards.forEach { card ->
                    key(card.id) {
                        // 注意：不要在这里写 `?: return@key`。在 @Composable inline 函数（key）
                        // 的 lambda 里做 labeled return，Compose 编译器会生成 $NON_LOCAL_RETURN 机制、
                        // 产出名为 <anonymous> 的 JVM 方法 —— ClassFormatError: Illegal method name，
                        // 类一加载就崩（单测/真机同样）。geoMap 在同一组合内先填充，取值为空即异常。
                        val geo = geoMap.getValue(card.id)
                        Box(
                            modifier = Modifier
                                .offset(x = geo.x, y = geo.y)
                                .width(geo.width)
                                .height(geo.height)
                                .zIndex(if (geo.isDragging || geo.isResizing) 1f else 0f)
                                .graphicsLayer {
                                    if (geo.isDragging || geo.isResizing) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        alpha = 0.96f
                                    }
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
                                    .padding(SLOT_INSET)
                                    .clip(HOME_CARD_SHAPE)
                                    .pointerInput(card.id, editMode) {
                                        if (editMode) return@pointerInput
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val slop = viewConfiguration.touchSlop
                                            var acc = Offset.Zero
                                            // 长按判定：按住满 longPressTimeout 且期间**未抬起、未滑动越过 touch slop**。
                                            // 不能用 waitForUpOrCancellation：内层手势（按钮/行点击）消费事件时它会同样
                                            // 返回 null，与「超时」不可区分 —— 那正是之前「一些卡片长按无效 / 乱触发」的根因。
                                            val timedOut = withTimeoutOrNull(
                                                viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes
                                                        .firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) break
                                                    acc += change.positionChange()
                                                    if (acc.getDistance() > slop) break
                                                }
                                            }
                                            // null = 时间到且全程按住未滑动 → 长按：给一次触感反馈并进编辑态
                                            if (timedOut == null) {
                                                confirmHaptic()
                                                cbLongPress(card.id)
                                            }
                                        }
                                    }
                            ) {
                                cardContent(card)
                            }

                            if (editMode) {
                                // ② 编辑态指示环：与卡片**同曲率**（HOME_CARD_SHAPE）且同样内缩一个 SLOT_INSET，
                                // 环正好贴着卡片外缘、内外圆角一致 —— 视觉上像卡片本身被「点亮」，
                                // 而不是套一圈曲率对不上的灰框（旧版 RoundedCornerShape(18.dp)，用户反馈「删掉/把曲率做好」）。
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(SLOT_INSET)
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
                                                onStart = {
                                                    dragCardId = card.id
                                                    dragOffset = Offset.Zero
                                                    confirmHaptic()
                                                },
                                                onDelta = { d -> dragOffset += d },
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
                                                            wPx / 2f + dragOffset.x
                                                        val centerY = rowUnitPx * s[0] +
                                                            hPx / 2f + dragOffset.y
                                                        val tCol = floor(centerX / cellPitchPx)
                                                            .toInt()
                                                            .coerceIn(
                                                                0,
                                                                (HomeLayoutStore.COLUMNS - c.colSpan)
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
                                                            resolvePinnedDrop(currentCards, c, tRow, tCol)
                                                        } else {
                                                            resolveDrop(currentCards, c, tRow, tCol)
                                                        }
                                                        if (next != currentCards) cbCardsChange(next)
                                                    }
                                                    dragCardId = null
                                                    dragOffset = Offset.Zero
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
                            Box(
                                modifier = Modifier
                                    .offset(x = geo.x, y = geo.y)
                                    .width(geo.width)
                                    .height(geo.height)
                                    .zIndex(if (geo.isDragging || geo.isResizing) 3f else 2f)
                                    .graphicsLayer {
                                        if (geo.isDragging || geo.isResizing) {
                                            // 只缩放、**不设 alpha**：alpha<1 会让该层走离屏合成，
                                            // 图层边界=卡片矩形，控件伸出卡角的那一截被直角裁掉
                                            // （用户反馈「拖动/拉伸时圆圈上边和左边被削平」）。
                                            scaleX = 1.03f
                                            scaleY = 1.03f
                                        }
                                    }
                            ) {
                                // 四角控件的圆心一律落在卡片内 10dp（见 CONTROL_INSET），
                                // 与相邻卡同侧控件的圆心距 ≥ 30dp > 视觉直径 26dp，圆钮互不相碰。
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = -CONTROL_INSET, y = -CONTROL_INSET),
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
                                                contentDescription = "编辑卡片配置",
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
                                            contentDescription = if (card.pinned) "取消固定" else "固定卡片",
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
                                        .offset(x = CONTROL_INSET, y = -CONTROL_INSET)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "删除卡片",
                                        tint = CLOSE_COLOR,
                                        modifier = Modifier.size(CONTROL_ICON)
                                    )
                                }

                                // 右下角拉伸手柄：只有一段弧线、没有圆底（用户要求），
                                // 卡角自己的圆角因此完整露出来；热区仍比图形大得多。
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = CONTROL_INSET, y = CONTROL_INSET)
                                        .size(HANDLE_HIT)
                                        .pointerInput(card.id, cellWpx, cellPitchPx, rowUnitPx) {
                                            dragAfterSlop(
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
                                                        HomeLayoutStore.MAX_COL_SPAN
                                                    )
                                                    resizeRowSpan = (
                                                        resizeBaseRow +
                                                            (resizeTotal.y / halfRow).toInt()
                                                        ).coerceIn(
                                                        HomeLayoutStore.MIN_ROW_SPAN,
                                                        HomeLayoutStore.MAX_ROW_SPAN
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
                                                                            HomeLayoutStore.COLUMNS -
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
                                                    if (next != currentCards) cbCardsChange(next)
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
    Box(
        modifier = modifier
            .size(CONTROL_HIT)
            // clip 成圆再 clickable：点击（长按）时的 Material 水波纹被裁成**圆形**，
            // 不然会画成一个灰色方块（用户反馈「点击圆圈时出现灰色矩形」）。
            .clip(CircleShape)
            .clickable(onClick = onClick),
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
 */
@Composable
private fun ResizeHandleGlyph() {
    // 定位：弧圆心需与「卡面右下角圆角」同心，即「卡面右下角 −(20,20)」=「槽位右下角 −25dp」；
    // Canvas 左上角即圆心 → Canvas（26dp）右下 = 槽位角 +1dp；热区右下在槽位外 +3dp
    // （CONTROL_INSET）→ padding = 3 − 1 = 2。
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


