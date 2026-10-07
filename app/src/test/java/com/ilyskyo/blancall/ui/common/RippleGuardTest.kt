// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 法则五「无涟漪」的源码守卫：`clickable` 家族必须显式写 `indication`。
 *
 * 这条约束坏得毫无声响：`Modifier.combinedClickable(onClick = …)` 少写一个
 * `indication = null`，编译照过、单测照绿、屏幕上只是多了一圈 Material 水波 ——
 * 而水波回答的是「系统收到一次点击」，不是「这个东西被按下去了」。
 * 挖空/遮挡配置那 12 处散点就是这么漏掉一整轮的，所以这次把它写成守卫。
 *
 * 只拦**能吃 indication 的那一族**。M3 的 `IconButton`／`Card`／`Surface(onClick=…)`
 * 在实现里把 `indication = ripple()` 写死了（material3 1.4：IconButton.kt:187、
 * Surface.kt:229），既不吃 `LocalIndication` 也没有形参，拦不到它们，只能另做换件；
 * 也不许反过来用全局 `LocalIndication provides null` 蒙混 —— 那会连带关掉
 * 尚未接入 [pressFeedback] 的组件的焦点框与悬停高亮。
 */
class RippleGuardTest {

    @Test
    fun 点击修饰符都显式交代过indication() {
        val root = File("src/main/java")
        assertTrue("找不到 ${root.absolutePath} —— 测试工作目录变了，这个守卫已经失效", root.isDirectory)

        val offenders = mutableListOf<String>()
        var files = 0
        var sites = 0
        root.walkBottomUp().filter { it.extension == "kt" }.forEach { file ->
            val path = file.invariantSeparatorsPath.substringAfter("com/ilyskyo/blancall/")
            files++
            val lines = blankOutCommentsAndLiterals(file.readText()).split('\n')
            lines.forEachIndexed { index, line ->
                val m = CALL.find(line) ?: return@forEachIndexed
                val start = index + 1
                // 只在括号闭合的那几行里找 indication，跨行调用也算得准。
                // 从匹配点起算，否则同一行前半段的 Row( /Modifier 会把深度带偏。
                var depth = 0
                var text = ""
                var j = index
                while (j < lines.size) {
                    text += if (j == index) lines[j].substring(m.range.first) else lines[j]
                    val seg = if (j == index) lines[j].substring(m.range.first) else lines[j]
                    depth += seg.count { it == '(' } - seg.count { it == ')' }
                    if (depth <= 0) break
                    j++
                }
                sites++
                // containsMatchIn 而不是 matches：m.value 连着定界符一起（".clickable {"），
                // 用 matches 会因为尾巴那段永远匹配不上，守卫就瞎了。
                if (RAW.containsMatchIn(m.value) && !INDICATION.containsMatchIn(text)) {
                    offenders += "$path:$start  ${m.value.trim()}"
                }
            }
        }

        assertTrue("扫到的点击点太少（$sites 处 / $files 个文件），解析没生效，结论不作数",
            sites >= MIN_SITES && files >= MIN_FILES)
        assertTrue(
            "这些点击点没关涟漪（该写 indication = null + pressFeedback）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private fun blankOutCommentsAndLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    repeat(nl - i) { out.append(' ') }
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    for (j in i until end) out.append(if (src[j] == '\n') '\n' else ' ')
                    i = end
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    for (j in i until end) out.append(if (src[j] == '\n') '\n' else ' ')
                    i = end
                }

                src[i] == '"' || src[i] == '\'' -> {
                    val quote = src[i]
                    var j = i + 1
                    while (j < src.length && src[j] != quote) {
                        j += if (src[j] == '\\') 2 else 1
                    }
                    val end = minOf(j + 1, src.length)
                    for (k in i until end) out.append(if (src[k] == '\n') '\n' else ' ')
                    i = end
                }

                else -> {
                    out.append(src[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** 点击家族的全部写法都算「点」：裸修饰符 + 本项目的两个无涟漪封装。 */
        val CALL = Regex(
            """\.(clickable|combinedClickable|longClickable|selectable|toggleable|pressClick|combinedPress)\s*[{(]"""
        )

        /** 只有这几个裸名字需要自己交代 indication；封装已在内部关掉。 */
        val RAW = Regex("""^\.(clickable|combinedClickable|longClickable|selectable|toggleable)\b""")
        val INDICATION = Regex("""\bindication\s*=""")

        /** 本项目有 ~60 处点击点；少于这个数就说明解析器什么都没看见。 */
        const val MIN_SITES = 40
        const val MIN_FILES = 100
    }
}
