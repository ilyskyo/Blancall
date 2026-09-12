// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import com.ilyskyo.blancall.data.repository.HomeLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页卡片画布布局算法的回归测试（纯逻辑，JVM 可测）。
 *
 * 覆盖 [layoutSlots] 的两轮占位（pinned 优先 / 其余首次适配）、跨行卡空洞绕行、
 * 尺寸越界收敛与确定性，以及 [resolveDrop] / [resolvePinnedDrop] 的落位结算。
 *
 * 这些行为直接决定用户排好的布局会不会被一次拖动或一次新增打乱，写错会静默丢位置。
 */
class HomeCardCanvasTest {

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 只关心网格位置，用自定义卡类型避免耦合系统卡的语义 */
    private fun card(
        id: String,
        colSpan: Int = 1,
        rowSpan: Int = 1,
        pinned: Boolean = false,
        lockRow: Int = -1,
        lockCol: Int = -1
    ) = HomeLayoutStore.Card(
        id = id,
        type = HomeLayoutStore.CardType.CUSTOM_CLOZE,
        colSpan = colSpan,
        rowSpan = rowSpan,
        pinned = pinned,
        lockRow = lockRow,
        lockCol = lockCol
    )

    /** `IntArray` 没有结构相等，转成 `List<Int>` 才能用 assertEquals 比内容 */
    private fun layoutOf(cards: List<HomeLayoutStore.Card>): Map<String, List<Int>> =
        layoutSlots(cards).mapValues { it.value.toList() }

    private fun idsOf(cards: List<HomeLayoutStore.Card>): List<String> = cards.map { it.id }

    // ------------------------------------------------------------------
    // layoutSlots：基本流式排布
    // ------------------------------------------------------------------

    @Test
    fun `empty list yields empty layout`() {
        assertEquals(emptyMap<String, List<Int>>(), layoutOf(emptyList()))
    }

    @Test
    fun `single full width card sits at origin`() {
        assertEquals(mapOf("a" to listOf(0, 0)), layoutOf(listOf(card("a", colSpan = 2))))
    }

    @Test
    fun `two single column cards share the first row`() {
        val out = layoutOf(listOf(card("a"), card("b")))
        assertEquals(mapOf("a" to listOf(0, 0), "b" to listOf(0, 1)), out)
    }

    @Test
    fun `full width card pushes following single column cards to next row`() {
        val out = layoutOf(listOf(card("a", colSpan = 2), card("b"), card("c")))
        assertEquals(
            mapOf(
                "a" to listOf(0, 0),
                "b" to listOf(1, 0),
                "c" to listOf(1, 1)
            ),
            out
        )
    }

    // ------------------------------------------------------------------
    // layoutSlots：空洞绕行（不「整行跳过」的关键行为）
    // ------------------------------------------------------------------

    @Test
    fun `short card fills the hole left beside a tall card instead of skipping the row`() {
        // a 独占第 0 行；b 竖跨第 1~2 行的左列；c 应补进 (1,1) 这个空洞
        val out = layoutOf(
            listOf(
                card("a", colSpan = 2, rowSpan = 1),
                card("b", colSpan = 1, rowSpan = 2),
                card("c", colSpan = 1, rowSpan = 1)
            )
        )
        assertEquals(
            mapOf(
                "a" to listOf(0, 0),
                "b" to listOf(1, 0),
                // 若实现是「整行跳过」，c 会被挤到 (3,0)
                "c" to listOf(1, 1)
            ),
            out
        )
    }

    @Test
    fun `card lands beside a two row card in the same row`() {
        val out = layoutOf(listOf(card("a", colSpan = 1, rowSpan = 2), card("b", colSpan = 1)))
        assertEquals(mapOf("a" to listOf(0, 0), "b" to listOf(0, 1)), out)
    }

    // ------------------------------------------------------------------
    // layoutSlots：pinned 优先占位
    // ------------------------------------------------------------------

    @Test
    fun `pinned card keeps its locked slot even when it is first in the list`() {
        // p 在列表最前，但锁在第 2 行 → 前面的自由卡只能占 0/1 行
        val out = layoutOf(
            listOf(
                card("p", colSpan = 2, pinned = true, lockRow = 2, lockCol = 0),
                card("u1", colSpan = 2),
                card("u2", colSpan = 2)
            )
        )
        assertEquals(
            mapOf(
                "p" to listOf(2, 0),
                "u1" to listOf(0, 0),
                "u2" to listOf(1, 0)
            ),
            out
        )
    }

    @Test
    fun `pinned cards fighting for one slot push the loser down and keep both`() {
        val cards = listOf(
            card("p1", colSpan = 2, pinned = true, lockRow = 0, lockCol = 0),
            card("p2", colSpan = 2, pinned = true, lockRow = 0, lockCol = 0)
        )
        val out = layoutOf(cards)
        assertEquals(mapOf("p1" to listOf(0, 0), "p2" to listOf(1, 0)), out)
        assertEquals("冲突后两张卡都不允许丢失", 2, out.size)
    }

    @Test
    fun `pinned card pushed down keeps the same column`() {
        val out = layoutOf(
            listOf(
                card("p1", pinned = true, lockRow = 0, lockCol = 1),
                card("p2", pinned = true, lockRow = 0, lockCol = 1)
            )
        )
        // 顺延是「同列向下」，不是换到 (0,0)
        assertEquals(mapOf("p1" to listOf(0, 1), "p2" to listOf(1, 1)), out)
    }

    @Test
    fun `unpinned card is displaced when it overlaps a pinned slot`() {
        val out = layoutOf(
            listOf(
                card("p", colSpan = 1, pinned = true, lockRow = 1, lockCol = 0),
                card("u", colSpan = 1)
            )
        )
        assertEquals(
            mapOf(
                "p" to listOf(1, 0),
                // u 不能顶掉钉住的卡，只能落到 (0,0)
                "u" to listOf(0, 0)
            ),
            out
        )
    }

    // ------------------------------------------------------------------
    // layoutSlots：越界收敛与健壮性
    // ------------------------------------------------------------------

    @Test(timeout = 2_000)
    fun `out of range spans are clamped instead of crashing or hanging`() {
        val out = layoutOf(
            listOf(
                card("zero", colSpan = 0, rowSpan = 0),
                card("neg", colSpan = -5, rowSpan = -3),
                card("huge", colSpan = 99, rowSpan = 99),
                card("tail")
            )
        )
        assertEquals(
            mapOf(
                // colSpan/rowSpan 都被收敛到 1
                "zero" to listOf(0, 0),
                "neg" to listOf(0, 1),
                // colSpan → 2、rowSpan → 4，占第 1~4 行整行
                "huge" to listOf(1, 0),
                // tail 只能在第 5 行：反向证明 rowSpan 恰好被收敛到 4（不是 99）
                "tail" to listOf(5, 0)
            ),
            out
        )
    }

    @Test(timeout = 2_000)
    fun `extreme lock coordinates from hand edited json do not overflow`() {
        val lone = listOf(
            card(
                "p",
                pinned = true,
                lockRow = Int.MAX_VALUE,
                lockCol = Int.MAX_VALUE
            )
        )
        val out = layoutOf(lone)
        // 行收敛到 cards.size * MAX_ROW_SPAN = 4，列收敛到 COLUMNS - colSpan = 1
        assertEquals(mapOf("p" to listOf(4, 1)), out)
    }

    @Test(timeout = 2_000)
    fun `negative lock coordinates are treated as unset flow layout`() {
        val out = layoutOf(
            listOf(
                card("p", pinned = true, lockRow = -1, lockCol = -1),
                card("u")
            )
        )
        assertEquals(mapOf("p" to listOf(0, 0), "u" to listOf(0, 1)), out)
    }

    // ------------------------------------------------------------------
    // layoutSlots：确定性
    // ------------------------------------------------------------------

    @Test
    fun `same input always produces the same layout`() {
        val cards = listOf(
            card("a", colSpan = 2),
            card("p", pinned = true, lockRow = 3, lockCol = 0, colSpan = 2),
            card("b", colSpan = 1, rowSpan = 2),
            card("c", colSpan = 2, rowSpan = 2),
            card("d")
        )
        assertEquals(layoutOf(cards), layoutOf(cards))
    }

    // ------------------------------------------------------------------
    // resolveDrop：自由卡落位
    // ------------------------------------------------------------------

    @Test
    fun `dropping a card back onto its own slot is a no-op`() {
        val cards = listOf(
            card("a", colSpan = 2),
            card("b", colSpan = 2),
            card("c", colSpan = 2),
            card("d", colSpan = 2)
        )
        val dragged = cards[1]
        assertEquals("原地放回必须原样返回，不能误触发一次交换", cards, resolveDrop(cards, dragged, 1, 0))
    }

    @Test
    fun `dropping onto an occupied slot swaps the two cards in list order`() {
        val cards = listOf(card("a", colSpan = 2), card("b", colSpan = 2))
        // a 在 (0,0)、b 在 (1,0)，把 a 拖到 b 的格子上 → 两者交换
        val out = resolveDrop(cards, cards[0], 1, 0)
        assertEquals(listOf("b", "a"), idsOf(out))
    }

    @Test
    fun `dropping onto a far card swaps with the card the user sees at that slot`() {
        // 回归守护：目标格必须按**视觉布局**（含被拖卡）结算，不能用「移除被拖卡之后的
        // 紧凑布局」，否则整体错位一张卡的身位 —— 曾经把 a 拖到第 2 行（视觉上 c 的位置）
        // 却换掉了第 3 行的 d，a 少落一行、d 又从底部跳到顶部（用户根本没碰 d）。
        // 旧实现的错误结果是 ["d","b","c","a"]，这里锁死修复后的正确结果。
        val cards = listOf(
            card("a", colSpan = 2),
            card("b", colSpan = 2),
            card("c", colSpan = 2),
            card("d", colSpan = 2)
        )
        val out = resolveDrop(cards, cards[0], 2, 0)
        assertEquals(listOf("c", "b", "a", "d"), idsOf(out))
    }

    @Test
    fun `dropping onto a far card keeps the other cards in place`() {
        // 同上守护的第二条：把 b 拖到第 3 行（d 的位置）→ b 与 d 互换，a / c 原地不动
        val cards = listOf(
            card("a", colSpan = 2),
            card("b", colSpan = 2),
            card("c", colSpan = 2),
            card("d", colSpan = 2)
        )
        val out = resolveDrop(cards, cards[1], 3, 0)
        assertEquals(listOf("a", "d", "c", "b"), idsOf(out))
    }

    @Test
    fun `dropping far below every card inserts after the nearest one`() {
        val cards = listOf(
            card("a", colSpan = 2),
            card("b", colSpan = 2),
            card("c", colSpan = 2),
            card("d", colSpan = 2)
        )
        // (9,0) 是空位，最近的卡是第 3 行的 d，落点在其下方 → 插到 d 之后（即移到末尾）
        val out = resolveDrop(cards, cards[0], 9, 0)
        assertEquals(listOf("b", "c", "d", "a"), idsOf(out))
    }

    @Test
    fun `resolveDrop ignores a card that is not in the list`() {
        val cards = listOf(card("a", colSpan = 2))
        val stranger = card("ghost", colSpan = 2)
        assertEquals(cards, resolveDrop(cards, stranger, 3, 0))
    }

    // ------------------------------------------------------------------
    // resolvePinnedDrop：钉住的卡重新钉到落点
    // ------------------------------------------------------------------

    @Test
    fun `dropping a pinned card rewrites its lock and keeps the list order`() {
        val cards = listOf(
            card("p", pinned = true, lockRow = 0, lockCol = 0),
            card("x")
        )
        val out = resolvePinnedDrop(cards, cards[0], 3, 1)
        assertEquals(listOf("p", "x"), idsOf(out))
        assertEquals(3, out[0].lockRow)
        assertEquals(1, out[0].lockCol)
        // 其余卡不受影响
        assertEquals(cards[1], out[1])
    }

    @Test
    fun `pinned drop clamps lockCol so the card never overflows the grid`() {
        val cards = listOf(card("p", colSpan = 2, pinned = true, lockRow = 0, lockCol = 0))
        val out = resolvePinnedDrop(cards, cards[0], 1, 1)
        assertEquals(1, out[0].lockRow)
        assertEquals("colSpan=2 时 lockCol 必须收敛到 0", 0, out[0].lockCol)
    }

    @Test
    fun `pinned drop onto the same slot is a no-op`() {
        val cards = listOf(card("p", colSpan = 2, pinned = true, lockRow = 2, lockCol = 0))
        assertEquals(cards, resolvePinnedDrop(cards, cards[0], 2, 0))
    }

    @Test
    fun `dropping a pinned card onto another pinned slot swaps their locks`() {
        val cards = listOf(
            card("p1", pinned = true, lockRow = 0, lockCol = 0),
            card("p2", pinned = true, lockRow = 0, lockCol = 1)
        )
        // 把 p2 拖到 p1 的锁定格 → 两者真正换位；旧行为只写 p2 的 lock，
        // 渲染时它会被「列表靠前优先」顺延下去，看起来像「拖不动、弹回去了」
        val out = resolvePinnedDrop(cards, cards[1], 0, 0)
        assertEquals("换位不该改动列表顺序", listOf("p1", "p2"), idsOf(out))
        assertEquals(0, out[0].lockRow)
        assertEquals("p1 应挪到 p2 原来的锁定格", 1, out[0].lockCol)
        assertEquals(0, out[1].lockRow)
        assertEquals("p2 应占住落点格", 0, out[1].lockCol)
        // 换位后真实渲染结果也必须是互换
        assertEquals(mapOf("p1" to listOf(0, 1), "p2" to listOf(0, 0)), layoutOf(out))
    }

    @Test
    fun `pinned drop falls back when the dragged card has no lock to trade`() {
        val cards = listOf(
            card("p1", pinned = true, lockRow = 0, lockCol = 0),
            // p2 刚被钉住、宿主还没来得及写回 lock
            card("p2", pinned = true, lockRow = -1, lockCol = -1)
        )
        val out = resolvePinnedDrop(cards, cards[1], 0, 0)
        // 没有可交换的位置 → 退化：只写落点（坐标仍合法），残余冲突交给 layoutSlots 顺延
        assertEquals(0, out[1].lockRow)
        assertEquals(0, out[1].lockCol)
        assertEquals("p1 不应被动到", cards[0], out[0])
        assertEquals(mapOf("p1" to listOf(0, 0), "p2" to listOf(1, 0)), layoutOf(out))
    }
}
