// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [TagStore] 磁盘往返与健壮性测试（JVM 版 org.json + 临时目录）。
 *
 * 隔离说明：TagStore 是进程级单例（getInstance 先到先得）——**仅本测试类**持有并使用它，
 * 其它测试类不得再调 getInstance（遵循既有单测规范）。类内各用例用唯一名称/文章 id 自隔离。
 *
 * 覆盖：CRUD 往返、重名拦截、排序持久化、覆盖绑定与批量绑定语义、级联解绑、
 * 失效绑定自愈、旧格式向后兼容、主文件损坏回读 .bak 并保留现场、全损时拒绝写盘（防清空标签库）。
 */
class TagStoreTest {

    companion object {
        private val dir: File = Files.createTempDirectory("blancall-tag-store-test").toFile()
        private val store = TagStore.getInstance(dir)
    }

    private val file: File get() = File(dir, "tags.json")

    /** 读取当前健康的磁盘内容（破坏性用例收尾还原用） */
    private fun healthyText(): String = if (file.exists()) file.readText() else ""

    /** 还原磁盘内容：无健康快照时删除文件，回到「首次使用」干净态 */
    private fun restoreHealthy(healthy: String) {
        if (healthy.isBlank()) file.delete() else file.writeText(healthy)
    }

    @Test
    fun `新建往返：名称与颜色持久化到文件`() {
        val id = store.createTag("T1测", 0x123456)
        assertNotNull(id)
        val loaded = store.snapshot().tags.first { it.id == id }
        assertEquals("T1测", loaded.name)
        assertEquals(0x123456, loaded.color)
        // 文件中以 "#RRGGBB" 大写存储
        assertTrue(file.readText().contains("#123456"))
        // 反应式流同步发布
        assertTrue(store.data.value.tags.any { it.id == id })
    }

    @Test
    fun `新建重名忽略大小写被拒`() {
        assertNotNull(store.createTag("T2Alpha", 0xEE9DB4))
        assertNull(store.createTag("t2alpha", 0xEE9DB4))
        assertNull(store.createTag("  T2ALPHA  ", 0xEE9DB4))
        assertEquals(1, store.snapshot().tags.count { it.name.equals("T2alpha", ignoreCase = true) })
    }

    @Test
    fun `新建名称非法被拒（空与超长）`() {
        assertNull(store.createTag("   ", 0xEE9DB4))
        assertNull(store.createTag("一二三四五六七八九十十一十二十三", 0xEE9DB4))
    }

    @Test
    fun `重命名与改色持久化`() {
        val id = store.createTag("T3旧名", 0xEE9DB4)!!
        assertTrue(store.renameTag(id, "T3新名"))
        store.recolorTag(id, 0x00AABB)
        val loaded = store.snapshot().tags.first { it.id == id }
        assertEquals("T3新名", loaded.name)
        assertEquals(0x00AABB, loaded.color)
        assertTrue(file.readText().contains("#00AABB"))
        // 重命名撞已有名（忽略大小写）→ 拒绝
        store.createTag("T3另一个", 0xEE9DB4)
        assertFalse(store.renameTag(id, "t3另一个"))
        // 重命名自身同名 → 允许（排除自身）
        assertTrue(store.renameTag(id, "T3新名"))
    }

    @Test
    fun `排序持久化：数组顺序即展示顺序`() {
        val a = store.createTag("T4a", 0xEE9DB4)!!
        val b = store.createTag("T4b", 0xEE9DB4)!!
        val c = store.createTag("T4c", 0xEE9DB4)!!
        store.reorderTags(listOf(c, b, a))
        val order = store.snapshot().tags.map { it.id }
        val idxC = order.indexOf(c)
        val idxB = order.indexOf(b)
        val idxA = order.indexOf(a)
        assertTrue("期望 c 在 b 前、b 在 a 前：$order", idxC < idxB && idxB < idxA)
        // 未覆盖的标签保持原相对顺序附在末尾
        store.reorderTags(listOf(c))
        assertEquals(c, store.snapshot().tags.first().id)
    }

    @Test
    fun `覆盖设置文章标签与解绑`() {
        val x = store.createTag("T5x", 0xEE9DB4)!!
        store.setArticleTags(555, setOf(x))
        assertEquals(setOf(x), store.snapshot().links[555])
        // 覆盖为其它集合（此处清空 = 解绑）
        store.setArticleTags(555, emptySet())
        assertNull(store.snapshot().links[555])
        // 不存在的 tag id 被过滤 → 空集 → 同样解绑
        store.setArticleTags(555, setOf(99999L))
        assertNull(store.snapshot().links[555])
    }

    @Test
    fun `批量绑定：并集添加与移除语义`() {
        val a = store.createTag("T6a", 0xEE9DB4)!!
        val b = store.createTag("T6b", 0xEE9DB4)!!
        store.setArticleTags(601, setOf(a))
        // 601 已有 a；对 601、602 添加 b → 601={a,b}、602={b}
        store.applyBatchToggle(listOf(601, 602), add = setOf(b), remove = emptySet())
        assertEquals(setOf(a, b), store.snapshot().links[601])
        assertEquals(setOf(b), store.snapshot().links[602])
        // 对 601、602 移除 a → 601={b}、602={b}
        store.applyBatchToggle(listOf(601, 602), add = emptySet(), remove = setOf(a))
        assertEquals(setOf(b), store.snapshot().links[601])
        assertEquals(setOf(b), store.snapshot().links[602])
        // 全部移除 → 键被清空
        store.applyBatchToggle(listOf(602), add = emptySet(), remove = setOf(b))
        assertNull(store.snapshot().links[602])
    }

    @Test
    fun `删除标签级联解绑`() {
        val t = store.createTag("T7", 0xEE9DB4)!!
        store.setArticleTags(701, setOf(t))
        store.setArticleTags(702, setOf(t))
        store.deleteTag(t)
        val d = store.snapshot()
        assertTrue(d.tags.none { it.id == t })
        assertTrue(d.links[701].isNullOrEmpty())
        assertTrue(d.links[702].isNullOrEmpty())
    }

    @Test
    fun `删除文章清除绑定`() {
        val t = store.createTag("T8", 0xEE9DB4)!!
        store.setArticleTags(801, setOf(t))
        store.removeArticle(801)
        assertNull(store.snapshot().links[801])
        // 标签本体保留
        assertTrue(store.snapshot().tags.any { it.id == t })
    }

    @Test
    fun `读取时丢弃失效绑定与非正 id`() {
        file.writeText(
            """{"version":1,"tags":[
                {"id":1,"name":"T9保留","color":"#112233"},
                {"id":2,"name":"","color":"#112233"}],
               "links":{"901":[1,999],"0":[1],"abc":[1]}}""".replace("\n", "")
        )
        val d = store.snapshot()
        assertEquals(listOf("T9保留"), d.tags.map { it.name })
        assertEquals(setOf(1L), d.links[901])
        assertNull(d.links[0L])
        assertFalse(d.links.keys.any { it <= 0L })
    }

    @Test
    fun `旧格式缺 links 与非法颜色可读（颜色回落）`() {
        file.writeText(
            """{"version":1,"tags":[{"id":5,"name":"T10老","color":"zzz"}]}"""
        )
        val d = store.snapshot()
        val t = d.tags.single()
        assertEquals("T10老", t.name)
        assertEquals(0xB6AFA4, t.color)
        assertTrue(d.links.isEmpty())
    }

    @Test
    fun `主文件损坏时回读备份并保留损坏现场`() {
        val id = store.createTag("T11备份源", 0xEE9DB4)!!
        store.renameTag(id, "T11备份源v2") // 上一版（v1）轮换入 .bak
        val healthy = healthyText()
        file.writeText("{ 损坏的标签库")

        // 读：主损坏 → 现场另存 .corrupt-* → 回读 .bak（上一版 = "T11备份源"）
        val name = store.snapshot().tags.first { it.id == id }.name
        assertEquals("T11备份源", name)
        val corrupt = dir.listFiles { f -> f.name.startsWith("tags.json.corrupt-") }
        assertTrue("损坏现场应被另存保留", corrupt != null && corrupt.isNotEmpty())

        // 收尾：写回健康内容，避免影响其它用例
        restoreHealthy(healthy)
    }

    @Test
    fun `主文件与备份全损时拒绝写盘（不得以空库抹掉标签）`() {
        val healthy = healthyText()
        file.writeText("{ 损坏")
        File(dir, "tags.json.bak").delete()

        // 空库但不崩溃
        val empty = store.snapshot()
        assertTrue(empty.tags.none { it.name.startsWith("T12") })

        // 拒绝写盘。旧断言「可重新写入」把这个类固化成了坏行为：主文件已被 preserveCorruptFile
        // 改名搬走，此时以空 root 写盘等于清空全部标签与文章绑定，只剩这一条新标签
        val id = store.createTag("T12恢复", 0xEE9DB4)
        assertFalse("损坏且无备份时必须拒绝写盘", file.exists())
        assertTrue(store.snapshot().tags.none { it.id == id })

        // 收尾：还原健康内容（成功解析会清除粘性标记），不影响其它用例
        restoreHealthy(healthy)
    }

    @Test
    fun `标签文章管理：勾选绑定、取消勾选移出、其它标签不受影响（单次覆盖落盘）`() {
        val t1 = store.createTag("TM1管理", 0x112233)!!
        val t2 = store.createTag("TM2共存", 0x445566)!!
        // 初始：文章 921/922 同时绑 t1+t2；923 无绑定
        store.applyBatchToggle(listOf(921L, 922L), setOf(t1, t2), emptySet())

        // 管理 t1：勾选 921/923，取消 922（922 仅移出 t1）
        store.setTagArticles(t1, setOf(921L, 923L))
        val d = store.snapshot()
        assertTrue(t1 in (d.links[921L] ?: emptySet()))    // 原已绑定保留
        assertTrue(t1 in (d.links[923L] ?: emptySet()))    // 新增绑定
        assertFalse(t1 in (d.links[922L] ?: emptySet()))   // 取消勾选 = 移出
        // 其它标签不受影响（922 的 t2 仍在，921 的 t2 仍在）
        assertTrue(t2 in (d.links[922L] ?: emptySet()))
        assertTrue(t2 in (d.links[921L] ?: emptySet()))
        // 持久化：写盘内容包含新绑定（退出重进可回读）
        assertTrue(file.readText().contains("\"923\""))
        // 再清空 t1：921/923 的 t1 都被移出，t2 仍保留
        store.setTagArticles(t1, emptySet())
        val d2 = store.snapshot()
        assertFalse(t1 in (d2.links[921L] ?: emptySet()))
        assertNull(d2.links[923L])
        assertTrue(t2 in (d2.links[921L] ?: emptySet()))
    }

    @Test
    fun `setTagArticles：标签 id 不存在时无副作用`() {
        val before = store.snapshot().links
        store.setTagArticles(99999999L, setOf(921L))
        assertEquals(before, store.snapshot().links)
    }

    @Test
    fun `盘上格式版本高于本应用时拒绝读写并原样留住文件`() {
        // 场景：用户导入了更新版本的备份，或装回了旧包。本版本不认识高版本里的字段，
        // 而 TagStore 的写盘是「整本重建」—— 一次普通保存就会把那个未知字段抹平，
        // 用户看不出任何东西丢过。守卫要做的正是把这种静默改数据挡下来。
        val healthy = healthyText()
        try {
            file.writeText("""{"version":99,"tags":[],"links":{},"futureField":{"keep":"me"}}""")
            store.snapshot()                                  // 触发读盘：认出「这份比我新」
            store.createTag("降级不该落到盘上", 0xABCDEF)      // 任何一次写都被拒绝
            val now = file.readText()
            assertTrue("版本守卫没生效，文件被本版本重写：$now", now.contains("\"version\":99"))
            assertTrue("高版本才认识的字段被抹掉了：$now", now.contains("futureField"))
            assertFalse("被拒绝的写还是落了盘：$now", now.contains("降级不该落到盘上"))
        } finally {
            restoreHealthy(healthy)
            // loadFailed 是粘性标记：还原健康文件后必须再读一次把它清掉，
            // 否则这条用例会毒化同一个单例，后面每个用例都"拒绝写盘"（同类的自伤踩过一次）
            store.snapshot()
            store.createTag("守卫还原后可写", 0x111111)
            assertTrue("还原后仍拒绝写盘，说明标记没清干净", file.readText().contains("守卫还原后可写"))
        }
    }
}
