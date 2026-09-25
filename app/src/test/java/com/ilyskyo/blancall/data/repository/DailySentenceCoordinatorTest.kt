// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.model.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [DailySentenceCoordinator] 回归测试（JVM + 临时目录）。
 *
 * 注意：SentenceCardStore 为进程级单例（目录与实例经 [SentenceCardTestDir] 与其它测试类共享），
 * 因此每用例开头必须 store.clear()，并用固定 now（2031 年）与其它测试类的日期键隔离，
 * 保证用例不依赖执行顺序。
 *
 * 覆盖：抽新句与同日幂等、到期优先、快照失效重抽、零候选空态、
 * 队列（今日置顶 / 到期按 due 升序 / 新句全量入队 / 解析失败跳过）与今日句去重。
 */
class DailySentenceCoordinatorTest {

    companion object {
        private val store = SentenceCardTestDir.store
    }

    private val day = 24L * 60 * 60 * 1000
    /** 固定基准时间（约 2031 年，与其它测试的日期键隔离） */
    private val now = 1_930_000_000_000L

    private fun article(id: Long, content: String) = Article(id = id, title = "文章$id", content = content)

    private fun state(due: Long, lastReview: Long, reviewCount: Int = 1) = FsrsEngine.CardState(
        difficulty = 5.0,
        stability = 3.0,
        due = due,
        lastReview = lastReview,
        reviewCount = reviewCount,
        lapses = 0,
    )

    @Test
    fun `ensureToday 无到期抽新句且同日幂等`() {
        store.clear()
        val a1 = article(1, "山重水复疑无路，柳暗花明又一村。")
        val a2 = article(2, "会当凌绝顶，一览众山小。")
        val s1 = DailySentenceCoordinator.ensureToday(store, listOf(a1, a2), emptyMap(), now, Random(1))
        assertNotNull(s1)
        // 换一个随机种子再次调用：同日应直接返回已有快照（幂等，不换句）
        val s2 = DailySentenceCoordinator.ensureToday(store, listOf(a1, a2), emptyMap(), now, Random(2))
        assertNotNull(s2)
        assertEquals(s1!!.key, s2!!.key)
        assertEquals(s1.text, s2.text)
    }

    @Test
    fun `ensureToday 到期优先取最逾期句`() {
        store.clear()
        val text1 = "落霞与孤鹜齐飞，秋水共长天一色。"
        val text2 = "先天下之忧而忧，后天下之乐而乐。"
        val a1 = article(1, text1)
        val a2 = article(2, text2)
        val k1 = SentenceSelector.sentenceKey(1, text1)
        val k2 = SentenceSelector.sentenceKey(2, text2)
        val states = mapOf(
            k1 to state(due = now - 2 * day, lastReview = now - 10 * day),
            k2 to state(due = now - 5 * day, lastReview = now - 20 * day), // 更逾期 → 应被选中
        )
        val snap = DailySentenceCoordinator.ensureToday(store, listOf(a1, a2), states, now, Random(1))
        assertEquals(k2, snap?.key)
        assertEquals(text2, snap?.text)
        assertEquals(2L, snap?.articleId)
    }

    @Test
    fun `ensureToday 快照失效后重抽`() {
        store.clear()
        val a1 = article(1, "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。")
        val snap1 = DailySentenceCoordinator.ensureToday(store, listOf(a1), emptyMap(), now, Random(3))
        assertNotNull(snap1)
        // 文章内容被编辑：移除已抽句 → 快照失效，应重抽为另一句
        val edited = a1.copy(content = a1.content.replace(snap1!!.text, ""))
        val snap2 = DailySentenceCoordinator.ensureToday(store, listOf(edited), emptyMap(), now, Random(4))
        assertNotNull(snap2)
        assertNotEquals("失效快照应被替换", snap1.key, snap2!!.key)
    }

    @Test
    fun `ensureToday 无文章或零候选返回 null`() {
        store.clear()
        assertNull(DailySentenceCoordinator.ensureToday(store, emptyList(), emptyMap(), now, Random(1)))
        // 全部句子不合格（纯标点 / 纯数字）→ 零候选空态
        assertNull(
            DailySentenceCoordinator.ensureToday(
                store,
                listOf(article(1, "。！？123"), article(2, "45678")),
                emptyMap(),
                now,
                Random(1),
            )
        )
    }

    @Test
    fun `buildQueue 今日置顶且到期按 due 升序并跳过解析失败项`() {
        store.clear()
        val text1 = "海内存知己，天涯若比邻。"
        val text2 = "欲穷千里目，更上一层楼。"
        val text3 = "春宵一刻值千金，花有清香月有阴。"
        val a1 = article(1, text1)
        val a2 = article(2, text2)
        val a3 = article(3, text3)
        // 今日句直接落快照（避开抽句随机性，控制变量）
        val date = DailySentenceCoordinator.dateKey(now)
        val k3 = SentenceSelector.sentenceKey(3, text3)
        store.setTodayIfAbsent(
            date,
            SentenceCardStore.Snapshot(date, k3, 3, text3, 0, text3.length, "文章3"),
        )
        val k1 = SentenceSelector.sentenceKey(1, text1)
        val k2 = SentenceSelector.sentenceKey(2, text2)
        val kGone = SentenceSelector.sentenceKey(999, "已删除文章的句子内容。")
        val states = mapOf(
            k1 to state(due = now - 1 * day, lastReview = now - 3 * day),
            k2 to state(due = now - 4 * day, lastReview = now - 9 * day), // 更逾期 → 排前
            kGone to state(due = now - 9 * day, lastReview = now - 30 * day),
        )
        val queue = DailySentenceCoordinator.buildQueue(store, listOf(a1, a2, a3), states, now)
        assertEquals("今日 + 两个到期（解析失败项跳过）", 3, queue.size)
        assertTrue("今日句置顶", queue[0].isToday)
        assertEquals(k3, queue[0].key)
        assertEquals(k2, queue[1].key)
        assertEquals(text2, queue[1].text)
        assertEquals(k1, queue[2].key)
        assertTrue("已删文章的到期项不应出现", queue.none { it.key == kGone })
    }

    @Test
    fun `buildQueue 未学过的新句全部入队直到记完`() {
        store.clear()
        // 一篇含 3 个合格句的文章：没有今日快照时，3 句应全部在队列里（不止 1 张卡）
        val a1 = article(1, "海内存知己，天涯若比邻。欲穷千里目，更上一层楼。春宵一刻值千金，花有清香月有阴。")
        val queue = DailySentenceCoordinator.buildQueue(store, listOf(a1), emptyMap(), now)
        assertEquals("全部合格新句都应入队", 3, queue.size)
        assertEquals("新句不带今日标记", 0, queue.count { it.isToday })

        // 已有今日快照（今日句也是新句）：今日句置顶，其余两句仍全部入队，不重复
        DailySentenceCoordinator.ensureToday(store, listOf(a1), emptyMap(), now, Random(1))
        val queue2 = DailySentenceCoordinator.buildQueue(store, listOf(a1), emptyMap(), now)
        assertEquals(3, queue2.size)
        assertTrue("今日句置顶", queue2[0].isToday)
        assertEquals("今日句不重复出现", 1, queue2.count { it.isToday })
        assertEquals("键去重", queue2.size, queue2.map { it.key }.toSet().size)

        // 学过一句（有状态且未到期）：该句不再入队，剩两句
        val learned = queue2[1].key
        val states = mapOf(learned to state(due = now + 10 * day, lastReview = now))
        val queue3 = DailySentenceCoordinator.buildQueue(store, listOf(a1), states, now)
        assertEquals("已学未到期的新句按 FSRS 间隔复习语义不入队", 2, queue3.size)
        assertTrue(queue3.none { it.key == learned })
    }

    @Test
    fun `buildQueue 主动复习：未到期已学句也入队且最久未复习优先`() {
        store.clear()
        val text1 = "海内存知己，天涯若比邻。"
        val text2 = "欲穷千里目，更上一层楼。"
        val text3 = "春宵一刻值千金，花有清香月有阴。"
        val a1 = article(1, text1)
        val a2 = article(2, text2)
        val a3 = article(3, text3)
        val k1 = SentenceSelector.sentenceKey(1, text1)
        val k2 = SentenceSelector.sentenceKey(2, text2)
        // 两句均已学过且**未到期**：k1 最久未复习，应排最前
        val states = mapOf(
            k1 to state(due = now + 5 * day, lastReview = now - 9 * day),
            k2 to state(due = now + 1 * day, lastReview = now - 2 * day),
        )
        val queue = DailySentenceCoordinator.buildQueue(
            store, listOf(a1, a2, a3), states, now, includeAllLearned = true,
        )
        assertEquals("未到期的已学句也要入队，新句接在后", 3, queue.size)
        assertEquals("最久未复习优先", k1, queue[0].key)
        assertEquals(k2, queue[1].key)
        assertEquals("未学新句接在已学之后", 3L, queue[2].articleId)
        assertTrue("主动复习轮无今日句特殊位置", queue.none { it.isToday })

        // 对照组：日常模式不排未到期已学句（现只有未学新句）
        val normal = DailySentenceCoordinator.buildQueue(store, listOf(a1, a2, a3), states, now)
        assertEquals("日常模式仅排新句", 1, normal.size)
        assertTrue(normal.none { it.key == k1 || it.key == k2 })
    }

    @Test
    fun `buildQueue 今日句与到期同句不重复`() {
        store.clear()
        val text = "海内存知己，天涯若比邻。"
        val a1 = article(1, text)
        val k1 = SentenceSelector.sentenceKey(1, text)
        val date = DailySentenceCoordinator.dateKey(now)
        store.setTodayIfAbsent(
            date,
            SentenceCardStore.Snapshot(date, k1, 1, text, 0, text.length, "文章1"),
        )
        val states = mapOf(k1 to state(due = now - day, lastReview = now - 3 * day))
        val queue = DailySentenceCoordinator.buildQueue(store, listOf(a1), states, now)
        assertEquals("今日句同时到期时只出现一次", 1, queue.size)
        assertTrue(queue[0].isToday)
        assertEquals(k1, queue[0].key)
    }
}
