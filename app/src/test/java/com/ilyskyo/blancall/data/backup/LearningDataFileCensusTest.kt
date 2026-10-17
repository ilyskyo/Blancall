// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份清单 census 守卫：**任何落在 filesDir 根上的 `.json` 学习数据都必须被 [BackupManager.ROOT_DATA_FILES] 覆盖**。
 *
 * 为什么要机器来查：`ROOT_DATA_FILES` 是手抄的 9 个文件名，而文件名散在各 Store 的
 * `File(filesDir, "x.json")` 与各调用点的 `filesDir.resolve("x.json")` 里。新加一个 Store
 * 却忘记登记时，备份会「成功」——只是那份数据从此不再被备份，用户换机后静默丢失，
 * 而且没有任何一处会报错。这正是「守文档里对用户的承诺」那一类守卫该覆盖的形状。
 *
 * 判据取自源码文本而不是反射：文件名本来就是字符串，没有可供反射枚举的注册表。
 * 扫不到任何文件就判失败 —— 一条永远不会红的守卫比没有守卫更糟。
 */
class LearningDataFileCensusTest {

    private val mainSrc = File("src/main/java")

    /** filesDir 根上的 `.json`：两种写法都要抓（Store 用 File()，调用点用 resolve()） */
    private val viaFile = Regex("""File\(\s*[^,)]*filesDir\s*,\s*"([^"]+?\.json)"""")
    private val viaResolve = Regex("""filesDir\.resolve\(\s*"([^"]+?\.json)"""")

    /**
     * 不是学习数据、按设计不进备份清单的名字（每条都要写理由，白名单不是垃圾桶）。
     * 口径出处：[BackupManager] 类注释「只覆盖学习数据……不含偏好设置与 AI 配置」。
     * 动态名在这里按**静态前缀**登记（如 `"ai_history/"`）。
     */
    private val NOT_LEARNING_DATA = mapOf(
        "ai_config.json" to "AI 配置含用户自填的服务端点与模型名，属另一套口径；Key 另存 SecurePrefs",
        "ai_history.json" to "AI 对话历史不在「学习数据」承诺范围内",
        "ai_history/" to "AI 会话逐条落盘在子目录，按设计不备份",
        "ai_analysis/" to "AI 训练分析逐条落盘在子目录，按设计不备份",
        "reminder_state.json" to "通知去重状态（今天已发过哪几条提醒），按设备时区日历天失效；" +
            "它是瞬时状态不是学习数据，备份/恢复它没有意义（换机当天顶多多收一条提醒）"
    )

    /** 按前缀收集的「每篇一个文件」族（collectBackupFiles 用 startsWith 收，不按整名登记） */
    private val DYNAMIC_FAMILIES = setOf("practice_state_", "practice_state")

    @Test
    fun `filesDir 根上的学习数据 json 全部登记在备份清单里`() {
        assertTrue("测试工作目录变了：找不到 ${mainSrc.absolutePath}", mainSrc.isDirectory)

        val scanned = linkedSetOf<String>()
        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            viaFile.findAll(text).forEach { scanned += it.groupValues[1] }
            viaResolve.findAll(text).forEach { scanned += it.groupValues[1] }
        }
        assertTrue(
            "只扫到 ${scanned.size} 个 filesDir 根上的 json 文件名，census 等于没跑",
            scanned.size >= BackupManager.ROOT_DATA_FILES.size
        )

        val registered = BackupManager.ROOT_DATA_FILES.toSet()
        val problems = mutableListOf<String>()
        for (name in scanned) {
            if (name in registered) continue
            if (NOT_LEARNING_DATA.containsKey(name)) continue
            // 含插值的名要按「静态前缀」判定：练习进度是每篇一个文件（practice_state_<id>.json），
            // 由 collectBackupFiles 按前缀收集，所以前缀在族清单里即可；其余动态名一律要单独登记
            val family = name.substringBefore("$")
            if (family in DYNAMIC_FAMILIES || NOT_LEARNING_DATA.containsKey(family)) continue
            problems += "$name 没登记进 BackupManager.ROOT_DATA_FILES（它会被备份静默漏掉）"
        }
        assertTrue(
            "备份清单与代码实际写的文件不一致：\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /** 清单自己也不能陈旧：每一条都得真有对应的文件名写在代码里 */
    @Test
    fun `备份清单里没有已不存在的陈旧条目`() {
        assertTrue(mainSrc.isDirectory)
        val scanned = buildList {
            mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                viaFile.findAll(text).forEach { add(it.groupValues[1]) }
                viaResolve.findAll(text).forEach { add(it.groupValues[1]) }
            }
        }.toSet()
        val stale = BackupManager.ROOT_DATA_FILES.filterNot { it in scanned }
        assertTrue(
            "ROOT_DATA_FILES 里有代码中已不存在的文件名（要么文件已改名、要么漏了新写法）：$stale",
            stale.isEmpty()
        )
    }
}
