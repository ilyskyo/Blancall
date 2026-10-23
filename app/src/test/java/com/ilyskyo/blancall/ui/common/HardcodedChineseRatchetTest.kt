// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全仓**硬编码中文棘轮守卫**：每个 `.kt` 文件里含汉字的字符串字面量处数只许减、不许增。
 *
 * ## 为什么需要它
 * [LocaleParityGuardTest] 守的是 `res` 下各 values 目录之间 `strings.xml` 的一致性，
 * 所以它对 `.kt` 里直接写死的中文**全瞎** —— 界面语言切到英/日/韩/俄/繁中时，这些地方仍显示简体中文，
 * 而所有多语言守卫全绿。
 *
 * ## 为什么是棘轮而不是「必须为 0」
 * 2026-10-14 用同一套状态机实测：**Pro 84 个文件 / 1072 处，Std 71 个文件 / 776 处**。
 * 硬断言会当场红一大片，把新增缺陷和既有债混成同一条噪音，结果就是没人看这条守卫。
 * 棘轮的用法是：抽取一批就把对应数字**下调**，清到 0 就删行 —— 它对 0 自动变严。
 *
 * 早先这条守卫只覆盖「统计页族」5 个文件（55 处），覆盖率约 5%，给了「债在收敛」的错觉；
 * 2026-10-14 扩到全仓。
 *
 * ## 基线为什么放在项目根的独立文件里
 * `../cjk-baseline.tsv`。两个原因：
 * ① Pro 与 Std 的源码树不同（Pro 多 AI 文件、Std 多欢迎页），基线数字天然不同 ——
 *    写死在测试里就得让这个测试文件两版分叉；
 * ② 双版一致性守卫只扫 `app/src` 与构建配置，放项目根就不会把「基线不同」误报成「代码漂移」，
 *    于是**测试文件本身仍能两版逐字节一致**（这是本仓库的硬要求）。
 *
 * ## 计数口径
 * 只数**字符串字面量里出现汉字**的处数；注释、KDoc、`//` 后面的说明一律不计 —— 本项目注释本来就要求写中文。
 * 字符字面量（`'中'`）不计。解析器是手写状态机，所以用 [解析器自检对已知样本必须数得准] 钉住它，
 * 而不是靠「扫出来是 0 就报错」那种伪自检（那会把「债清完了」误报成「解析器坏了」）。
 */
class HardcodedChineseRatchetTest {

    private val root = "src/main/java/com/ilyskyo/blancall/"

    /** 基线数据文件（项目根，不在 `app/src` 下，见类注释） */
    private val baselineFile = File("../cjk-baseline.tsv")

    @Test
    fun `基线文件必须存在且可解析`() {
        assertTrue(
            "找不到 ${baselineFile.absolutePath} —— 棘轮守卫没有基线就没有意义，" +
                "请从 _tools 的说明或上一版守卫里恢复它",
            baselineFile.isFile
        )
        val b = baseline()
        // 用「必须含某条已知债务」当解析自检，而不是数总行数：债务清到只剩个位数行时，
        // 行数阈值会把「真的清完了」误报成解析失效。
        assertTrue(
            "基线里没有 algorithm/BlancallGenerator.kt，多半是解析没生效（分隔符应为 TAB）",
            b.containsKey("algorithm/BlancallGenerator.kt")
        )
    }

    @Test
    fun `硬编码中文只许减少不许增加`() {
        val problems = mutableListOf<String>()
        for ((rel, allowed) in baseline()) {
            val file = File(root + rel)
            if (!file.isFile) {
                problems += "$rel 找不到（文件改名/移走了：把它这一行改成新路径，别留着空转）"
                continue
            }
            val actual = countCjkStringLiterals(file.readText())
            if (actual > allowed) {
                problems += "$rel 含中文的字符串字面量从 $allowed 涨到 $actual（棘轮只许往下）"
            } else if (actual == 0) {
                problems += "$rel 已经清到 0 —— 把 cjk-baseline.tsv 里这一行删掉（删行即变严）"
            }
        }
        assertTrue("硬编码中文债务变多：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * 新增文件不许无声引入硬编码中文。
     *
     * 只查基线里的文件会漏掉最坏的一种情况：**新写的文件**整篇硬编码中文 ——
     * 那种文件在基线里没有条目，于是「只许减」对它完全不起作用。
     */
    @Test
    fun `新增文件必须显式登记`() {
        val known = baseline().keys
        val problems = mutableListOf<String>()
        for (f in sourceFiles()) {
            val rel = f.relativeTo(File(root)).path.replace('\\', '/')
            if (rel in known) continue
            val n = countCjkStringLiterals(f.readText())
            if (n > 0) problems += "$rel 有 $n 处含中文的字符串字面量，但没登记进 cjk-baseline.tsv"
        }
        assertTrue(
            "新文件引入了硬编码中文：\n" + problems.joinToString("\n") +
                "\n要么抽进 strings.xml（首选），要么在 cjk-baseline.tsv 里登记一行并写清为什么它进不了资源。",
            problems.isEmpty()
        )
    }

    /**
     * 总账只许降：逐文件「只降」管不住**新增文件** —— 新文件登记时只写实测值、没有上限，
     * 总账可以从 998 一路推到 1014（本期 AI 提示词就是 16 处）。这里钉死
     * 「全部行之和 ≤ 基线头部登记的 `# total`」，抽取文案后必须同步下调那一行。
     */
    @Test
    fun `全部文件的处数之和不超过总账`() {
        val limit = totalLimit()
        val sum = baseline().values.sum()
        assertTrue(
            "硬编码中文总账从 $limit 涨到 $sum —— 新增文件只能登记实测值；抽取一批后请下调 `# total`",
            sum <= limit
        )
    }

    /** 基线头部的 `# total <N>`：本版允许的总账上限（两版各一份，只能写在数据文件里） */
    private fun totalLimit(): Int {
        val line = baselineFile.readLines().firstOrNull { it.trim().startsWith("# total") }
            ?: error("cjk-baseline.tsv 缺少 `# total <N>` 行 —— 补一行再跑")
        // 只取数字：行尾可能带中文说明（「只许下调，新增文件只登记实测值」），toIntOrNull 会拿不到
        return Regex("""#\s*total\s+(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("`# total` 行格式不对：$line")
    }

    /**
     * 解析器自检：拿一段**已知答案**的合成样本喂进状态机，数不对就是解析器坏了。
     *
     * 刻意不写成「扫出来是 0 就报错」那种伪自检 —— 那种写法在债真被清完时也会红，
     * 于是要么误导人、要么逼人把守卫删掉。样本与仓库内容无关，永远有效。
     */
    @Test
    fun `解析器自检对已知样本必须数得准`() {
        val sample = """
            // 行注释里的中文不算
            val a = "中文一"
            val b = "中文二"
            /* 块注释里的中文也不算 */
            val c = '中'
            val d = "no cjk here"
            val e = "${'$'}{a}模板里的中文"
        """.trimIndent()
        assertEquals(
            "解析器坏了：注释/字符字面量/无中文串的判定出错（应为 3：两条普通串 + 一条模板串）",
            3,
            countCjkStringLiterals(sample)
        )
    }

    /** 读基线 TSV：`路径<TAB>处数`，`#` 开头与空行跳过 */
    private fun baseline(): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        if (!baselineFile.isFile) return out
        baselineFile.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val parts = t.split('\t')
            if (parts.size < 2) return@forEach
            parts[1].trim().toIntOrNull()?.let { out[parts[0].trim()] = it }
        }
        return out
    }

    private fun sourceFiles(): List<File> =
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * 数「含汉字的字符串字面量」的处数。
     *
     * 手写状态机而不是正则，是因为这三类误判都会让守卫说谎：
     * ① 注释里的中文（本项目注释必须写中文，不能算债）；
     * ② URL 字符串里的双斜杠（按行砍注释的正则会把串砍断）；
     * ③ 三引号 raw 串里的引号。
     * Kotlin 的块注释可嵌套，所以本段刻意不写会触发嵌套的那两个字符组合（既有记录里踩过）。
     */
    private fun countCjkStringLiterals(text: String): Int {
        var i = 0
        var count = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            when {
                c == '/' && i + 1 < len && text[i + 1] == '/' -> {
                    i += 2
                    while (i < len && text[i] != '\n') i++
                }
                c == '/' && i + 1 < len && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < len && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' && i + 2 < len && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    val end = findRawStringEnd(text, i + 3)
                    if (hasCjk(text.substring(i + 3, end))) count++
                    i = end + 3
                }
                c == '"' -> {
                    val end = findPlainStringEnd(text, i + 1)
                    if (hasCjk(text.substring(i + 1, end))) count++
                    i = end + 1
                }
                c == '\'' -> {
                    // 字符字面量：跳过可能转义的 \' 与 \\
                    i++
                    if (i < len && text[i] == '\\') i++
                    if (i < len && text[i] == '\'') i++
                }
                else -> i++
            }
        }
        return count
    }

    /** 普通串：到下一个未转义的 `"` 为止（串里不会出现裸换行，编译器不允许） */
    private fun findPlainStringEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            when (text[j]) {
                '\\' -> j += 2
                '"' -> return j
                '\n' -> return j
                else -> j++
            }
        }
        return text.length
    }

    /** 三引号 raw 串：到下一个 `"""` 为止 */
    private fun findRawStringEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == '"' && j + 2 < text.length && text[j + 1] == '"' && text[j + 2] == '"') return j
            j++
        }
        return text.length
    }

    private fun hasCjk(s: String): Boolean = s.any { it in '\u4e00'..'\u9fff' }
}
