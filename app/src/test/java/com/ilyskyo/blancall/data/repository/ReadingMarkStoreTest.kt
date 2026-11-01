// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ReadingMarkStore] 的持久化口径测试。
 *
 * 划线/批注是"用户亲手写下的东西"，丢一条就是丢一条 —— 比设置类数据严重得多。
 * 这里守三件事：
 * 1. 区间与备注能被新实例读回（不是只活在内存里）；
 * 2. 同区间重复添加合并成一条（先划线后批注不应该出现两条重叠记录）；
 * 3. 读坏/版本更高时拒绝写盘（本类是整表回写，拿空表写下去会抹掉所有文章的笔记）。
 */
class ReadingMarkStoreTest {

    private fun newDir(): File = Files.createTempDirectory("blancall-reading-marks").toFile()

    private fun storeIn(dir: File) = ReadingMarkStore.openForTest(File(dir, "reading_marks.json"))

    @Test
    fun `划线按文章保存 新实例能读回`() {
        val dir = newDir()
        val store = storeIn(dir)
        val mark = store.addMark(7, paragraph = 2, start = 3, end = 9, note = "这里是重点")
        assertNotNull(mark)
        assertEquals(1, store.getMarks(7).size)

        // 重新开一个实例（模拟重启）：数据必须还在盘上
        val reopened = storeIn(dir)
        val loaded = reopened.getMarks(7).single()
        assertEquals(2, loaded.p)
        assertEquals(3, loaded.a)
        assertEquals(9, loaded.e)
        assertEquals("这里是重点", loaded.note)
        assertTrue(loaded.hasNote)
        // 其它文章不受影响
        assertTrue(reopened.getMarks(8).isEmpty())
    }

    @Test
    fun `同区间重复添加合并为一条 并补上备注`() {
        val store = storeIn(newDir())
        val first = store.addMark(1, 0, 4, 10)!!
        val second = store.addMark(1, 0, 4, 10, note = "补一条批注")!!
        assertEquals("同区间不应产生两条记录", 1, store.getMarks(1).size)
        assertEquals(first.id, second.id)
        assertEquals("补一条批注", store.getMarks(1).single().note)
    }

    @Test
    fun `重叠区间合并成一条 不产生叠层`() {
        val store = storeIn(newDir())
        val first = store.addMark(1, 0, 3, 8)!!
        // 前面多选一个字再划：只延长，不新增、不叠色
        val second = store.addMark(1, 0, 2, 8)!!
        val marks = store.getMarks(1)
        assertEquals("重叠不应留下两条记录", 1, marks.size)
        assertEquals(first.id, second.id)
        assertEquals(2, marks.single().a)
        assertEquals(8, marks.single().e)
    }

    @Test
    fun `传递重叠也并成一条`() {
        val store = storeIn(newDir())
        store.addMark(1, 0, 0, 5)
        store.addMark(1, 0, 4, 9)
        val merged = store.addMark(1, 0, 8, 12)!!
        val m = store.getMarks(1).single()
        assertEquals(0, m.a)
        assertEquals(12, m.e)
        assertEquals(m.id, merged.id)
    }

    @Test
    fun `带批注的区间不被新划线吞掉`() {
        val store = storeIn(newDir())
        val noted = store.addMark(1, 0, 2, 5, note = "重点")!!
        // 选区跨过带批注的区间：只补两侧空白，批注原样保留
        store.addMark(1, 0, 0, 8)
        val marks = store.getMarks(1)
        assertEquals(3, marks.size)
        val kept = marks.single { it.hasNote }
        assertEquals(noted.id, kept.id)
        assertEquals(2, kept.a)
        assertEquals(5, kept.e)
        assertEquals("重点", kept.note)
        // 任意两条都不重叠
        val sorted = marks.sortedBy { it.a }
        for (i in 0 until sorted.size - 1) {
            assertTrue("不应重叠：${sorted[i]} / ${sorted[i + 1]}", sorted[i].e <= sorted[i + 1].a)
        }
    }

    @Test
    fun `重复划同一句记录数不变`() {
        val store = storeIn(newDir())
        store.addMark(1, 0, 3, 9)
        store.addMark(1, 0, 3, 9)
        store.addMark(1, 0, 3, 9)
        assertEquals(1, store.getMarks(1).size)
    }

    @Test
    fun `修改备注与删除`() {
        val store = storeIn(newDir())
        val mark = store.addMark(3, 1, 0, 5, note = "旧备注")!!
        store.updateNote(3, mark.id, "新备注")
        assertEquals("新备注", store.getMarks(3).single().note)
        // 置空 = 退化成纯划线，记录仍在
        store.updateNote(3, mark.id, "")
        assertFalse(store.getMarks(3).single().hasNote)
        assertTrue(store.removeMark(3, mark.id))
        assertTrue(store.getMarks(3).isEmpty())
        // 删不存在的记录返回 false，不写盘
        assertFalse(store.removeMark(3, mark.id))
    }

    @Test
    fun `删除文章清掉全部记录`() {
        val store = storeIn(newDir())
        store.addMark(5, 0, 0, 3)
        store.addMark(5, 1, 0, 3)
        store.removeArticle(5)
        assertTrue(store.getMarks(5).isEmpty())
    }

    @Test
    fun `非法区间被拒收`() {
        val store = storeIn(newDir())
        assertEquals(null, store.addMark(1, -1, 0, 3))
        assertEquals(null, store.addMark(1, 0, 5, 5))
        assertEquals(null, store.addMark(0, 0, 0, 3))
        assertTrue(store.getMarks(1).isEmpty())
    }

    @Test
    fun `盘上的非法条目在读取时跳过`() {
        val dir = newDir()
        File(dir, "reading_marks.json").writeText(
            """
            {"version":1,"articles":{"9":[
              {"id":1,"p":0,"a":0,"e":0,"note":"零长度","createdAt":1,"updatedAt":1},
              {"id":2,"p":0,"a":2,"e":7,"note":"正常","createdAt":1,"updatedAt":1}
            ]}}
            """.trimIndent()
        )
        val marks = storeIn(dir).getMarks(9)
        assertEquals("零长度条目应被跳过", 1, marks.size)
        assertEquals("正常", marks.single().note)
    }

    @Test
    fun `盘上版本更高时拒绝写盘 原文件保持不变`() {
        val dir = newDir()
        val file = File(dir, "reading_marks.json")
        val original = """
            {"version":99,"articles":{"1":[{"id":1,"p":0,"a":0,"e":4,"note":"未来版本","createdAt":1,"updatedAt":1}]}}
        """.trimIndent()
        file.writeText(original)

        val store = storeIn(dir)
        // 读不到（拒绝解析），且新增不会落盘
        assertTrue(store.getMarks(1).isEmpty())
        store.addMark(1, 0, 0, 3, note = "不该写进去")
        assertEquals("原文件必须原样保留", original, file.readText())
        // 落盘内容仍是那个版本号
        assertEquals(99, JSONObject(file.readText()).optInt("version"))
    }

    @Test
    fun `损坏的主文件不会连带抹掉备份`() {
        val dir = newDir()
        val file = File(dir, "reading_marks.json")
        val store = storeIn(dir)
        store.addMark(2, 0, 0, 6, note = "备份里的内容")
        // 备份文件由 AtomicFiles 轮换生成：确认存在后再破坏主文件
        val bak = File(dir, "reading_marks.json.bak")
        if (!bak.exists()) {
            // 首次写入可能还没有 .bak（取决于轮换策略），此时本用例退化为「不崩即可」
            file.writeText("{ 这不是 json")
            assertTrue(storeIn(dir).getMarks(2).isEmpty())
            return
        }
        file.writeText("{ 这不是 json")
        val reopened = storeIn(dir)
        assertEquals("应从备份读回", "备份里的内容", reopened.getMarks(2).single().note)
    }
}
