// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转场必须**两头都写明**的守卫（见 [MotionVocabularyTest] 的姊妹篇）。
 *
 * 拦的是这种坑：`AnimatedVisibility(visible = x, enter = 自定义)` 看着已经「做过动效」了，
 * 但没写 `exit` —— Compose 会补上库默认的 `fadeOut + shrinkVertically`，那份时序写死在库里、
 * **减动效（`animator_duration_scale = 0`）下不会坍缩**，而且没人知道它为什么是那个时长。
 * 本轮就实打实抓到两处：首页「最近使用」的行只写了入场、Std 首启引导只写了退场。
 *
 * 只查 `MotionSpec.kt` 之外的调用点：词表文件自己就是造这些谱子的地方，必须豁免。
 */
class TransitionGuardTest {

    @Test
    fun 转场两头都显式交代过() {
        val root = File("src/main/java")
        assertTrue("找不到 ${root.absolutePath} —— 工作目录变了，守卫失效", root.isDirectory)

        val offenders = mutableListOf<String>()
        var sites = 0
        var files = 0
        root.walkBottomUp().filter { it.extension == "kt" }.forEach { file ->
            if (file.name == "MotionSpec.kt") return@forEach
            files++
            val lines = blankOut(file.readText(Charsets.UTF_8)).split('\n')
            lines.forEachIndexed { index, line ->
                val m = CALL.find(line) ?: return@forEachIndexed
                val name = m.groupValues[1]
                val seg = callText(lines, index, m.range.first)
                sites++
                val ok = when (name) {
                    "AnimatedVisibility" -> seg.has("enter") && seg.has("exit")
                    "AnimatedContent", "Crossfade" -> seg.has("transitionSpec")
                    else -> seg.has("animationSpec") || seg.argsNonEmpty()
                }
                if (!ok) {
                    offenders += "${file.invariantSeparatorsPath.substringAfter("blancall/")}" +
                        ":${index + 1}  $name 少写了 ${(REQUIRED[name] ?: "").ifEmpty { "animationSpec" }}"
                }
            }
        }

        assertTrue("只扫到 $sites 个转场调用（$files 个文件），解析没生效，结论不作数", sites >= MIN_SITES)
        assertTrue("这些转场没两头写明：\n" + offenders.joinToString("\n"), offenders.isEmpty()
        )
    }

    /** 从匹配点起，按括号配平取整段调用文本（同一行前半段的其它括号不计入）。 */
    private fun callText(lines: List<String>, start: Int, offset: Int): String {
        var depth = 0
        val sb = StringBuilder()
        var j = start
        while (j < lines.size && j < start + 20) {
            val seg = if (j == start) lines[j].substring(offset) else lines[j]
            sb.append(seg)
            depth += seg.count { it == '(' } - seg.count { it == ')' }
            if (depth <= 0) break
            j++
        }
        return sb.toString()
    }

    private fun String.has(key: String) = contains("$key =") || contains("$key=")

    /** `animateContentSize(Motion.contentSize())` 是位置参数，同样算交代过。 */
    private fun String.argsNonEmpty(): Boolean {
        val open = indexOf('(')
        val close = lastIndexOf(')')
        return open >= 0 && close > open && substring(open + 1, close).isNotBlank()
    }

    /** 注释与字面量抹成等长空格：长度与换行不变，行号才仍然可信。 */
    private fun blankOut(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            when {
                src.startsWith("//", i) -> {
                    var nl = src.indexOf('\n', i)
                    if (nl < 0) nl = n
                    repeat(nl - i) { out.append(' ') }
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var e = src.indexOf("*/", i + 2)
                    e = if (e < 0) n else e + 2
                    for (j in i until e) out.append(if (src[j] == '\n') '\n' else ' ')
                    i = e
                }

                src.startsWith("\"\"\"", i) -> {
                    var e = src.indexOf("\"\"\"", i + 3)
                    e = if (e < 0) n else e + 3
                    for (j in i until e) out.append(if (src[j] == '\n') '\n' else ' ')
                    i = e
                }

                src[i] == '"' || src[i] == '\'' -> {
                    val q = src[i]
                    var j = i + 1
                    while (j < n && src[j] != q) {
                        j += if (src[j] == '\\') 2 else 1
                    }
                    val e = minOf(j + 1, n)
                    for (k in i until e) out.append(if (src[k] == '\n') '\n' else ' ')
                    i = e
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
        val CALL = Regex(
            """\b(AnimatedVisibility|AnimatedContent|Crossfade|animateContentSize)\s*\("""
        )

        val REQUIRED = mapOf(
            "AnimatedVisibility" to "enter / exit",
            "AnimatedContent" to "transitionSpec",
            "Crossfade" to "transitionSpec",
            "animateContentSize" to "animationSpec",
        )

        /** 两版各有 ~18 处转场调用；少于这个数说明解析器什么都没看见。 */
        const val MIN_SITES = 12
    }
}
