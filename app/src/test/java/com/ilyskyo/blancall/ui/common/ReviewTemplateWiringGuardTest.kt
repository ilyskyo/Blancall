// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「该不该复习」在三个界面上必须是同一份判据 —— 源码级守卫。
 *
 * 起因：同一条到期判断有三份代码，而只有一份看用户在设置里选的复习计划。首页与列表页调用
 * `EbbinghausScheduler.getReviewStatus(fsrsState, records)` 时没传模板，落到默认的 STANDARD；
 * 统计页则自己写了一遍 `PRESETS.find { it.id == templateId }`。后果是用户选「冲刺备考」后，
 * 首页与列表对老文章的提醒密度毫无变化，而统计页已经变快 —— 同一篇文章两个界面结论相反。
 *
 * 为什么用扫源码而不是跑断言：这三处都是 Compose 屏里的 `remember` 表达式，JVM 单测跑不了组合；
 * 而「第四份判定长出来」这件事的本质就是**多了一个调用点**，扫调用点恰好是要守的形状。
 *
 * 两个自伤点已经防住：
 * ① 扫不到文件时不能算通过（文件不存在直接记一条问题）；
 * ② 匹配不到任何调用点也不能算通过 —— 那说明函数改名了或这条守卫指错了目录，
 *    静默零命中正是「守卫长绿而它什么都没看」的形态（本项目栽过好几次）。
 */
class ReviewTemplateWiringGuardTest {

    private val root = "src/main/java/com/ilyskyo/blancall/"

    /** 三屏：到期数、逐篇状态、预测榜，必须共用同一个模板来源 */
    private val screens = listOf(
        "ui/home/HomeScreen.kt",
        "ui/list/ListScreen.kt",
        "ui/statistics/OverviewScreen.kt"
    )

    /** 模板映射只许存在这一处（`ReviewTemplate.fromId` 的定义） */
    private val mappingOwner = "algorithm/EbbinghausScheduler.kt"

    @Test
    fun `三屏都通过 ReviewTemplate 唯一映射取模板`() {
        val problems = mutableListOf<String>()
        for (rel in screens) {
            val file = File(root + rel)
            if (!file.isFile) {
                problems += "$rel 找不到（路径变了就改这条守卫，别让它空转）"
                continue
            }
            val text = file.readText()
            if (!text.contains("ReviewTemplate.fromId")) {
                problems += "$rel 没有用 ReviewTemplate.fromId 取用户所选计划 —— 到期判定会落到 STANDARD"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `到期判定的每个调用点都显式传模板`() {
        val problems = mutableListOf<String>()
        var seen = 0
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.invariantSeparatorsPath.substringAfter(root)
            val text = file.readText()
            var index = text.indexOf("getReviewStatus(")
            while (index >= 0) {
                val args = balancedArgs(text, index + "getReviewStatus".length)
                if (args == null) {
                    problems += "$rel 有一处 getReviewStatus( 括号不配对，这条守卫看不出它的实参"
                } else {
                    seen++
                    // 定义与内部转发都在 EbbinghausScheduler 里，那里第一参就是模板
                    if (!args.contains("template") && !args.contains("Template")) {
                        problems += "$rel 的 getReviewStatus(...) 没有传模板实参：${args.replace("\n", " ")}"
                    }
                }
                index = text.indexOf("getReviewStatus(", index + 1)
            }
        }
        assertTrue("全仓只匹配到 0 个 getReviewStatus 调用点 —— 要么函数改名了，要么这条守卫指错了目录，" +
            "不许当成通过", seen > 0)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `模板映射不许出现第二处`() {
        val problems = mutableListOf<String>()
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.invariantSeparatorsPath.substringAfter(root)
            val hits = file.readText().lines().count { it.contains("PRESETS.find") }
            if (hits > 0 && rel != mappingOwner) {
                problems += "$rel 自己写了一遍 PRESETS.find —— 映射必须只有 $mappingOwner 那一处"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * 从 '(' 之后取到配对的另一半，返回括号内的实参文本；遇到未配对返回 null。
     *
     * 不写正则而写这个小循环：Kotlin 里 `getReviewStatus(a, f(x))` 的嵌套括号很常见，
     * 「找第一个 )」会把实参截断，截断后的文本看起来正常，于是守卫以为自己看到了全部实参。
     */
    private fun balancedArgs(text: String, openParenAt: Int): String? {
        if (text[openParenAt] != '(') return null
        var depth = 0
        var i = openParenAt
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParenAt + 1, i)
                }
            }
            i++
        }
        return null
    }
}
