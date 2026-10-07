// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 动效词表的**唯一来源**守卫：`MotionSpec.kt` 之外不许再出现裸的时长、刚度或阻尼比。
 *
 * 这条约束编译查不出、UI 测试也查不出 —— 它坏掉的方式是某个下午有人顺手写了
 * 一个 `tween(120)`：那一处动画立刻变成词表管不到的孤儿（减动效时不会坍缩，
 * 而且再没人知道 120 是从哪来的）。计划要求每阶段删掉自己的魔法数字，
 * 这个测试就是让「删掉」这件事不会悄悄退回去。
 *
 * 允许 `snap()` 留在调用点：那是「跟手」语义（拖动期间动画必须冻住），不是时长。
 */
class MotionVocabularyTest {

    @Test
    fun 词表之外没有裸的动画参数() {
        val root = File("src/main/java")
        assertTrue("找不到 ${root.absolutePath} —— 测试工作目录变了，这个守卫已经失效", root.isDirectory)

        val offenders = mutableListOf<String>()
        var scanned = 0
        root.walkBottomUp().filter { it.extension == "kt" }.forEach { file ->
            val path = file.invariantSeparatorsPath
            if (path.endsWith(VOCABULARY)) return@forEach
            scanned++
            val code = blankOutCommentsAndLiterals(file.readText())
            for (banned in BANNED) {
                val hit = banned.find(code) ?: continue
                val line = code.take(hit.range.last).count { it == '\n' } + 1
                offenders += "${path.substringAfter("com/ilyskyo/blancall/")}:$line  ${hit.value}"
            }
        }

        assertTrue(
            "扫描文件数过少（$scanned），说明源码根路径没解析对，下面的结论不作数",
            scanned >= MIN_KOTLIN_FILES
        )
        // 反向对照：词表文件自己必须命中这些原语，否则「全项目零命中」可能只是正则写坏了。
        val vocabulary = File("src/main/java/com/ilyskyo/blancall/${VOCABULARY.replace("com/ilyskyo/blancall/", "")}")
        val vocabText = blankOutCommentsAndLiterals(vocabulary.readText())
        assertTrue("词表文件里找不到 tween( —— 匹配子句写坏了", vocabText.contains("tween("))
        assertTrue("词表文件里找不到 spring( —— 匹配子句写坏了", vocabText.contains("spring("))
        assertTrue(
            "动效词表之外出现了裸参数：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 注释与字面量整段抹成空格（长度、换行位置都不动，行号才仍然对得上）。
     *
     * 必须先抹注释再抹引号，否则源码里 `// 见 https://x` 会被误当代码；
     * 反过来先抹字面量，注释里的中文引号又会把整行吃掉。
     */
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
        const val VOCABULARY = "ui/common/MotionSpec.kt"

        /** 少于此数就说明没真的扫到源码树（宁红不绿地把「没测到」暴露出来）。 */
        const val MIN_KOTLIN_FILES = 100

        val BANNED = listOf(
            Regex("""\btween\("""),
            Regex("""\bspring\("""),
            Regex("""\bDampingRatio"""),
            Regex("""\bStiffness"""),
            Regex("""\bkeyframes\("""),
            Regex("""\bInfiniteTransition\b"""),
        )
    }
}
