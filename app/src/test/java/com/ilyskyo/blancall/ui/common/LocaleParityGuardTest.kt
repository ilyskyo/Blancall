// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多语言的键集守卫：`values-<locale>/strings.xml` 必须与默认目录**键集相等**，
 * 且非拉丁语种里不许残留中文。
 *
 * 为什么守键集而不是守译文：漏一条 `<string>` 编译照过、跑起来只是那一处回落到简体中文，
 * 用户看到的是「半英半中」而不是报错 —— 正是「每种语言都要仔细检查一遍，千万不要有误」
 * 里最容易无声滑过去的那一类。键集相等是机器能查的那一半；查得准不准只能人看。
 *
 * 四条附带判据各管一件事：
 * 1. 占位符（`%1$s` / `%d` / `%1$s` 之类）个数与种类必须和默认一致 —— 少一个参数是崩溃，
 *    多一个是 `IllegalFormatConversionException`；
 * 2. 英/俄/韩这些语种里出现汉字，几乎只可能是「把中文整条复制过去了」；
 *    日语与繁中不查（汉字是它们的正字）；
 * 3. 声明与目录**双向**比对：有目录没声明 = 选不到；有声明没目录 = 选了它只会回落成简体中文，
 *    那比不提供更糟；
 * 4. 扫到的目录数/键数低于下限时直接判失败：一条永远不会红的守卫比没有守卫更糟。
 *
 * 默认目录（简体中文 = 源语言）也在被查之列，见 [默认目录不许有空值也不许写非中文]：
 * 抽取曾经把键在三份里都建了、文字却只写进繁体与英文两份，默认那份留成空串，
 * 界面上不报错、只是那几项直接没有字（用户截图抓到 20 条）。只查译文目录等于没查。
 */
class LocaleParityGuardTest {

    private val defaultFile by lazy { File("src/main/res/values/strings.xml") }

    private val localeDirs by lazy {
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            .orEmpty()
            .filter { it.name != "values-night" && File(it, "strings.xml").isFile }
            .sortedBy { it.name }
    }

    @Test
    fun 每个语种的键集与默认完全相等() {
        assertTrue("找不到 ${defaultFile.absolutePath} —— 测试工作目录变了", defaultFile.isFile)
        val base = keysOf(defaultFile)
        val dirs = localeDirs
        assertTrue(
            "只扫到 ${dirs.size} 个带 strings.xml 的语种目录，守卫等于没跑",
            dirs.isNotEmpty()
        )
        assertTrue("默认目录只有 ${base.size} 条串，解析没生效", base.size >= MIN_KEYS)

        val problems = mutableListOf<String>()
        for (dir in dirs) {
            val here = keysOf(File(dir, "strings.xml"))
            (base.keys - here.keys).forEach { problems += "${dir.name} 缺键：$it" }
            (here.keys - base.keys).forEach { problems += "${dir.name} 多出键（默认目录没有）：$it" }
            for ((key, value) in here) {
                if (key !in base) continue
                if (value.isBlank()) problems += "${dir.name}/${key} 是空的"
                val want = PH_PLACEHOLDER.findAll(base.getValue(key)).map { it.value }.toList().sorted()
                val got = PH_PLACEHOLDER.findAll(value).map { it.value }.toList().sorted()
                if (want != got) problems += "${dir.name}/${key} 占位符对不上：$got ≠ $want"
                // 裸 % 会被 aapt 当成第二个「非位置替换」并只在编译期警告，警告没人看 ⇒ 在这里判红
                lonePercentOffsets(value).forEach { at ->
                    problems += "${dir.name}/${key} 第 $at 个字符是裸 %（写字面百分号要 %%）：$value"
                }
                if (dir.name in MUST_NOT_CONTAIN_HANZI && PH_CJK.containsMatchIn(value)) {
                    problems += "${dir.name}/${key} 里残留中文：$value"
                }
                // 值以句读开头 = 「长句只抽了后半」的形状（真出过 5 条，界面上显示成
                // 「/粘贴文章时…」「，可在学习统计…」）。键在、值非空、占位符也对，
                // 所以前面几条都查不出它 —— 只能单独立一条。
                if (key !in FRAGMENT_ALLOWED && PH_LEAD_PUNCT.containsMatchIn(value.trim())) {
                    problems += "${dir.name}/${key} 以句读开头，多半是半句：$value"
                }
            }
        }
        assertTrue("多语言资源有问题：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** 有资源目录却没在 locales_config 里声明 = 用户在系统设置里永远选不到它。 */
    @Test
    fun 有译文语种都已在locales_config里声明() {
        val declared = declaredLocales()
        val missing = localeDirs.mapNotNull { dir ->
            val tag = dir.name.removePrefix("values-")
            // 目录名 b+zh+Hant 对应声明里的 zh-Hant；其余（en/ja/…）直接同名比对
            val asTag = if (tag.startsWith("b+")) tag.removePrefix("b+").replace('+', '-') else tag
            if (asTag !in declared) "${dir.name} → 声明里找不到 $asTag" else null
        }
        assertTrue("这些语种有译文但选不到：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /**
     * 反过来查：声明了却没译文目录。
     *
     * 这条比上一条更要命 —— 声明里写着 `ja`，用户在系统语言里选到日语，Android 于是
     * 回落到**默认目录**，而默认目录是简体中文。于是「支持日语」的实际交付物是一屏汉字，
     * 比不提供更糟（AppLocale 的 KDoc 里写的就是这个判断）。
     * 所以「加一种语言」必须是同一个提交里声明 + 目录 + 全量键集一起到位。
     */
    @Test
    fun 声明了的语种都必须有译文目录() {
        val declared = declaredLocales()
        val present = localeDirs.map { dir ->
            val tag = dir.name.removePrefix("values-")
            if (tag.startsWith("b+")) tag.removePrefix("b+").replace('+', '-') else tag
        }.toSet()
        // zh-Hans 就是默认目录本身，不该另有 values-zh-rCN
        val gaps = (declared - "zh-Hans" - present).sorted()
        assertTrue(
            "locales_config 声明了这些语言，却没有对应的 values- 目录（选上它只会回落成简体中文）：$gaps",
            gaps.isEmpty()
        )
        assertTrue("声明的语种少于 6 个（简繁英日韩俄），是本守卫假设的语种范围变了：$declared", declared.size >= 6)
    }

    /**
     * 繁体目录里不许出现简体字形。
     *
     * 为什么单独一条：混进一条简体不会让句子读不通，只会让这份译文显得「不走心」，
     * 肉眼看一整表基本抓不到（第一次扫就抓到 4 条：已选/字词/还未/加强）。
     * 判据不是通顺与否，而是「这个字形在正体里是不是合法字」，只有对照表能给答案。
     *
     * 表里只放「正体另有写法，且在中文 UI 里一定是别字」的字。同形有争议的
     * （后/里/面/台/于/着/斗/松/占/藏/干）一律不查：宁可漏报也不误报，
     * 误报会让人开始忽略这条守卫。
     */
    @Test
    fun 繁体目录不许混进简体字形() {
        val hant = File("src/main/res/values-b+zh+Hant/strings.xml")
        assertTrue("找不到繁体目录", hant.isFile)
        val offenders = keysOf(hant)
            .filter { entry -> entry.value.any { it in S2T } }
            .map { (k, v) -> "$k → " + v.filter { it in S2T }.toSet().joinToString(",") { "$it(应作 ${S2T.getValue(it)})" } }
            .sorted()
        assertTrue("繁体资源里混着简体字形：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun declaredLocales(): Set<String> {
        val cfg = File("src/main/res/xml/locales_config.xml")
        assertTrue("manifest 挂了 android:localeConfig，但找不到 ${cfg.absolutePath}", cfg.isFile)
        return Regex("""android:name="([^"]+)"""").findAll(cfg.readText())
            .map { it.groupValues[1] }.toSet()
    }

    /** 默认目录（简体中文）自己不许有空值，也不许出现没有汉字的条目 —— 那多半是写串了语言。 */
    @Test
    fun 默认目录不许有空值也不许写非中文() {
        val text = defaultFile.readText()
        val base = keysOf(defaultFile)
        assertTrue(
            "解析到 ${base.size} 条，文件里却写着 ${declaredCount(text)} 条 <string> —— 解析器漏了某种写法",
            base.size == declaredCount(text)
        )
        val blank = base.filterValues { it.isBlank() }.keys.sorted()
        assertTrue("默认（简体中文）目录里有空值，界面上会直接少字：$blank", blank.isEmpty())
        val noCjk = base.filter { it.key !in CJK_EXEMPT && !PH_CJK.containsMatchIn(it.value) }.keys.sorted()
        assertTrue(
            "默认目录这些条目里没有汉字，多半是把别的语种写进了源语言（确认有意的话加进 CJK_EXEMPT）：$noCjk",
            noCjk.isEmpty()
        )
        // 源语言自己也要过一遍「句读开头」：碎句正是先在默认目录里出现的
        val lead = base.filterKeys { it !in FRAGMENT_ALLOWED }
            .filter { PH_LEAD_PUNCT.containsMatchIn(it.value.trim()) }
            .map { "${it.key} → ${it.value}" }
        assertTrue("默认目录里有以句读开头的半句：\n" + lead.joinToString("\n"), lead.isEmpty())
    }

    /**
     * 单复数按语言各自的规则查，并且**必须真的按数量变**。
     *
     * 为什么值得单独一条：`%1$d articles` 在 1 的时候是错的，而这件事编译与运行都不报错，
     * 只有母语者会看见。所以「加了 plurals」不算完，要查它有没有真的分叉。
     * 中文没有语法单复数 ⇒ 默认与繁中只许 `other`；俄文是 one/few/many/other 四档。
     */
    @Test
    fun 数量条目按语言各自的规则分叉() {
        val base = pluralsOf(defaultFile)
        assertTrue("一条 plurals 都没扫到 —— 守卫等于没跑", base.size >= MIN_PLURALS)
        val problems = mutableListOf<String>()
        for ((name, items) in base) {
            if (items.keys != setOf("other")) {
                problems += "默认（中文）$name 只该有 other，实际 ${items.keys}"
            }
            if (items["other"]?.isBlank() != false) problems += "默认 $name 的 other 是空的"
        }
        for (dir in localeDirs) {
            val here = pluralsOf(File(dir, "strings.xml"))
            val want = QUANTITIES[dir.name] ?: setOf("other")
            (base.keys - here.keys).forEach { problems += "${dir.name} 缺 plurals：$it" }
            (here.keys - base.keys).forEach { problems += "${dir.name} 多出 plurals：$it" }
            for ((name, items) in here) {
                if (name !in base) continue
                if (items.keys != want) {
                    problems += "${dir.name}/$name 的量档是 ${items.keys}，该语言要的是 $want"
                }
                if (items.values.any { it.isBlank() }) problems += "${dir.name}/$name 有空量档"
                items["other"]?.let { o ->
                    val b = base[name]?.get("other").orEmpty()
                    val wantPh = PH_PLACEHOLDER.findAll(b).map { it.value }.toList().sorted()
                    val gotPh = PH_PLACEHOLDER.findAll(o).map { it.value }.toList().sorted()
                    if (wantPh != gotPh) problems += "${dir.name}/$name 的 other 占位符对不上：$gotPh ≠ $wantPh"
                }
                if (dir.name in MUST_NOT_CONTAIN_HANZI && items.values.any { PH_CJK.containsMatchIn(it) }) {
                    problems += "${dir.name}/$name 里残留中文"
                }
            }
            // 英文的 one 必须与 other 真的不同，否则 plurals 只是装饰
            if (dir.name == "values-en") {
                for ((name, items) in here) {
                    if (items["one"] == items["other"]) problems += "values-en/$name 的 one 与 other 一模一样"
                }
            }
        }
        assertTrue("plurals 有问题：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * 棘轮：英文里「数字 + 以 s 结尾的名词」还留在 `<string>` 里，就说明这条该改成 plurals。
     * 抓不到 children/people 这类不规则复数，但铺量期最容易犯的就是规则加 s。
     */
    @Test
    fun 英文不许把带数量的句子留在string里() {
        val offenders = keysOf(File("src/main/res/values-en/strings.xml"))
            .filter { PH_COUNTED_NOUN.containsMatchIn(it.value) }
            .map { "${it.key} → ${it.value}" }
        assertTrue(
            "这些英文条目会输出「1 articles」这种错句，应改成 <plurals>：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 每个键都要真的被引用。
     *
     * 为什么守这条：改完文案把调用点指到别的键之后，旧键会留在三份资源里 ——
     * 它不报错、不占体积，但换语言时会被当成「已翻译」统计进去，于是「还剩多少没抽」这个数
     * 从此失真（我自己造过一次 `done_action` 与既有 `done` 同词同义）。
     *
     * 但**孤儿不等于垃圾**：一条没人用的文案，更可能是「该说给用户的话还没接上」。
     * `doc_images_dropped` 与 `save_failed` 就是这样被这条守卫发现的 —— 提示文字早就写好了，
     * 导入页里却另有一份一模一样的硬编码字面量在产出（两份来源，换语言时资源那份永远不生效）。
     * 2026-10-09 两处都改成读资源，白名单因此删掉了。
     */
    @Test
    fun 每个键都要被引用() {
        val declared = keysOf(defaultFile).keys + pluralsOf(defaultFile).keys
        assertTrue("一条键都没扫到", declared.isNotEmpty())
        val used = HashSet<String>()
        // 整个 src/main：`AndroidManifest.xml` 不在 res/ 里，`android:label="@string/app_name"`
        // 只扫 res 会被当成孤儿（第一次跑就这样）。
        sourceFiles(File("src/main")).forEach { f ->
            val text = f.readText()
            used += PH_KT_REF.findAll(text).map { it.groupValues[1] }.toList()
            used += PH_XML_REF.findAll(text).map { it.groupValues[1] }.toList()
        }
        val orphans = (declared - used).sorted()
        assertTrue(
            "这些键没人用（改完文案留下的旧键要删；确实是「该说给用户却没接上」的，去把调用点接上）：\n" +
                orphans.joinToString("\n"),
            orphans.isEmpty()
        )
    }

    /**
     * 找出「既不是 `%%`、也不是 `%n$x` 开头、又不在串尾」的裸百分号位置。
     *
     * aapt2 遇到 `%1$d% 上昇` 会报 *Multiple substitutions specified in non-positional format*，
     * 但那只是 warning：编译照过、运行照跑，界面上却可能把 `%` 后的内容吃掉。
     * 串尾的裸 `%`（`近7天提升 5%`）aapt 不管，所以也不该管。
     */
    private fun lonePercentOffsets(v: String): List<Int> {
        val tail = v.trimEnd().length
        val out = mutableListOf<Int>()
        var i = 0
        while (i < v.length) {
            if (v[i] != '%') {
                i++
                continue
            }
            val next = if (i + 1 < v.length) v[i + 1] else ' '
            when {
                next == '%' || next.isDigit() || next == '$' -> i += 2
                i >= tail - 1 -> i++
                else -> {
                    out += i
                    i++
                }
            }
        }
        return out
    }

    /**
     * 值里带位置占位符（`%1$s` / `%1$d`）的资源，**调用点必须真的传参**。
     *
     * 为什么单独一条：`pluralStringResource(R.plurals.x, n)` 只给了 quantity、没给格式化参数时，
     * Android 不报错、不崩溃，直接把 `还有 %1$d 个空未完成` 原样显示到屏幕上 ——
     * 用户在真机上截到两张这样的图才发现（2026-10-09）。占位符写错会崩，**漏传参不会**，
     * 所以它是形式守卫全绿也照样漏过去的那一类。
     */
    @Test
    fun 带占位符的资源不许被无参调用() {
        val positional = Regex("""%\d+\$[a-z]""")
        val needsArgs = HashSet<String>()
        keysOf(defaultFile).forEach { (k, v) -> if (positional.containsMatchIn(v)) needsArgs += "string.$k" }
        pluralsOf(defaultFile).forEach { (k, items) ->
            if (items.values.any { positional.containsMatchIn(it) }) needsArgs += "plurals.$k"
        }
        assertTrue("默认目录一条占位符资源都没扫到 —— 守卫等于没跑", needsArgs.size >= MIN_POSITIONAL)

        val offenders = mutableListOf<String>()
        sourceFiles(File("src/main")).filter { it.name.endsWith(".kt") }.forEach { f ->
            val text = f.readText()
            PH_RES_CALL.findAll(text).forEach { m ->
                val fn = m.groupValues[1]
                val kind = m.groupValues[2]
                val key = m.groupValues[3]
                if ("$kind.$key" !in needsArgs) return@forEach
                // 从函数名后那个 '(' 起配平括号，取到实参列表的右边界
                val open = text.indexOf('(', m.range.first)
                val close = matchingParen(text, open)
                if (close < 0) return@forEach
                // 资源引用本身到右边界之间，顶层逗号数 = 资源 id 之外还传了几个实参
                val afterKey = text.substring(m.range.last + 1, close)
                val args = splitTopLevel(afterKey).count { it.isNotEmpty() }
                // pluralStringResource / getQuantityString 的第一个实参是 quantity，不算格式化参数
                val want = if (fn == "pluralStringResource" || fn == "getQuantityString") 2 else 1
                if (args < want) {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${f.name}:$line  ${fn}(R.$kind.$key) 只传了 $args 个实参，" +
                        "而值里有占位符 → 界面会直接显示 ${sampleValue(kind, key)}"
                }
            }
        }
        assertTrue(
            "这些调用点会让占位符原样显示在界面上：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private fun sampleValue(kind: String, key: String): String =
        if (kind == "string") keysOf(defaultFile)[key].orEmpty()
        else pluralsOf(defaultFile)[key]?.get("other").orEmpty()

    /** 从 `open` 处的 '(' 找到配对的 ')'；字符串字面量里的括号不算。找不到返回 -1。 */
    private fun matchingParen(text: String, open: Int): Int {
        if (open < 0) return -1
        var depth = 0
        var inString = false
        for (i in open until text.length) {
            val c = text[i]
            when {
                c == '"' && (i == 0 || text[i - 1] != '\\') -> inString = !inString
                inString -> {}
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 按顶层逗号切分实参（括号与字符串字面量里的逗号不算）。 */
    private fun splitTopLevel(callArgs: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        val buf = StringBuilder()
        for (c in callArgs) {
            when {
                c == '"' -> { inString = !inString; buf.append(c) }
                inString -> buf.append(c)
                c == '(' || c == '[' -> { depth++; buf.append(c) }
                c == ')' || c == ']' -> { depth--; buf.append(c) }
                c == ',' && depth == 0 -> { out += buf.toString().trim(); buf.clear() }
                else -> buf.append(c)
            }
        }
        out += buf.toString().trim()
        return out
    }

    private fun sourceFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().flatMap {
            if (it.isDirectory) sourceFiles(it)
            else if (it.name.endsWith(".kt") || it.name.endsWith(".xml")) listOf(it)
            else emptyList()
        }
    }

    private fun pluralsOf(file: File): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        var name: String? = null
        var items = LinkedHashMap<String, String>()
        for (line in file.readText().split('\n')) {
            val open = PH_PLURALS.find(line)
            if (open != null) {
                name = open.groupValues[1]
                items = LinkedHashMap()
                continue
            }
            if (line.contains("</plurals>")) {
                if (name != null) out[name] = items
                name = null
                continue
            }
            val item = PH_ITEM.find(line)
            if (item != null && name != null) items[item.groupValues[1]] = item.groupValues[2]
        }
        return out
    }

    private fun declaredCount(text: String) = Regex("""<string name="""").findAll(text).count()

    private fun keysOf(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val buf = StringBuilder()
        for (line in file.readText().split('\n')) {
            // 自闭合写法（<string name="x"/>）值是空：必须当场结掉，否则会把下一行吞进来，
            // 于是「空值」在键集比对里看起来完全正常（这条真漏过一次 20 个空串）。
            val self = PH_SELF.find(line)
            if (self != null) {
                out[self.groupValues[1]] = ""
                key = null
                buf.clear()
                continue
            }
            val open = PH_OPEN.find(line)
            if (open != null) {
                key = open.groupValues[1]
                buf.clear()
                val tail = line.substring(open.range.last + 1)
                if (PH_CLOSE.containsMatchIn(tail)) {
                    out[key] = buf.toString() + tail.substringBefore("</string>")
                    key = null
                } else {
                    buf.append(tail.trim())
                }
                continue
            }
            if (key != null) {
                if (line.contains("</string>")) {
                    out[key] = buf.toString() + line.substringBefore("</string>").trim()
                    key = null
                } else {
                    buf.append(line.trim())
                }
            }
        }
        return out
    }

    private companion object {
        val PH_OPEN = Regex("""<string name="([^"]+)"[^>]*>""")
        val PH_SELF = Regex("""<string name="([^"]+)"[^>]*/>""")
        val PH_CLOSE = Regex("</string>")
        val PH_PLACEHOLDER = Regex("""%\d*\$?[a-zA-Z]|%[a-zA-Z]""")
        val PH_CJK = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff]")
        val PH_PLURALS = Regex("""<plurals name="([^"]+)"""")
        val PH_ITEM = Regex("""<item quantity="([^"]+)">(.*)</item>""")

        /** 「数字 + 以 s 结尾的英文名词」——只有该用 plurals 的句子才会长成这样。 */
        val PH_COUNTED_NOUN = Regex("""%\d+\${'$'}d\s+[A-Za-z]+s\b""")

        /** 各语言语法上需要的量档；不在表里的按「只有 other」处理（日/韩与中文一样）。 */
        val QUANTITIES = mapOf(
            "values-en" to setOf("one", "other"),
            "values-ru" to setOf("one", "few", "many", "other"),
        )

        /** 源语言里本来就不该有汉字的条目。
         *  `sentence_card_colon` 是读屏播报里的全角冒号（它自己就不是汉字）；
         *  `first_line_preview` 是「把标题原文包起来的引号」，值里只有 `「%1$s」`。 */
        val CJK_EXEMPT = setOf("app_name", "sentence_card_colon", "first_line_preview")

        val PH_KT_REF = Regex("""R\.(?:string|plurals)\.([A-Za-z0-9_]+)""")
        val PH_XML_REF = Regex("""@(?:string|plurals)/([A-Za-z0-9_]+)""")

        /** 取资源的那四类调用：函数名 + 可选包名前缀 + R.string/R.plurals + 键名。 */
        val PH_RES_CALL = Regex(
            """(stringResource|pluralStringResource|getString|getQuantityString)\s*\(\s*""" +
                """(?:[A-Za-z0-9_.]*\.)?R\.(string|plurals)\.([A-Za-z0-9_]+)"""
        )

        /** 带位置占位符的资源条数下限：低于此值说明扫描没生效，守卫等于没跑。 */
        const val MIN_POSITIONAL = 40

        /** 句读开头 —— 长句只抽了后半的指纹。`（`/`「` 不算：那是括号与引号，是正当写法。 */
        val PH_LEAD_PUNCT = Regex("""^[，、；：/·…]""")

        /** 故意做拼接片段的条目：读屏播报里接在标题后面的「，来自《…》」与那个全角冒号。 */
        val FRAGMENT_ALLOWED = setOf("sentence_card_from", "sentence_card_colon")

        // 英/俄/韩的正字里没有汉字，出现即视为漏译（日语不查：汉字是它们的正字）
        val MUST_NOT_CONTAIN_HANZI = setOf("values-en", "values-ru", "values-ko")

        /**
         * 简体专用字形 → 正体写法。见 [繁体目录不许混进简体字形]。
         * 有争议的同形字（后/里/面/台/于/着/斗/松/占/藏/干/系/挂/据/谷/折）一律不收。
         */
        val S2T_PAIRS = """
            为為 丽麗 举舉 习習 乡鄉 书書 买買 乱亂 争爭 亏虧 云雲 亚亞 产產 亲親 亿億 仅僅 从從 仑侖 仪儀 们們
            优優 会會 伞傘 伟偉 传傳 伤傷 伦倫 伪偽 佣傭 侣侶 侠俠 侨僑 俨儼 俩倆 倾傾 偿償 储儲 觅覓 儿兒 冈岡
            冲沖 决決 况況 净淨 准準 凉涼 减減 凑湊 凛凜 凤鳳 击擊 刘劉 则則 刚剛 删刪 创創 划劃 竞競 简簡 关關
            桩樁 梦夢 检檢 楼樓 标標 栏欄 树樹 样樣 桥橋 欢歡 欧歐 歼殲 残殘 殡殯 殴毆 归歸 录錄 彻徹 径徑 恋戀
            恼惱 悬懸 惊驚 惧懼 惨慘 惯慣 愿願 慑懾 戏戲 扑撲 扪捫 抚撫 抢搶 护護 报報 担擔 拟擬 拥擁 择擇 捞撈
            损損 捡撿 换換 捣搗 摄攝 撑撐 积積 称稱 窍竅 窝窩 篓簍 竖豎 笔筆 俭儉 筛篩 篮籃 筹籌 签簽 类類 粪糞
            粮糧 纪紀 纹紋 绚絢 络絡 绳繩 绵綿 绷繃 综綜 绿綠 缀綴 缅緬 缆纜 缝縫 缨纓 缩縮 缴繳 网網 罗羅 罚罰
            罢罷 码碼 显顯 凿鑿 陇隴 陈陳 陕陝 险險 阴陰 随隨 隐隱 难難 雾霧 霁霽 霉黴 静靜 韦韋 韧韌 韩韓 页頁
            顶頂 项項 顺順 须須 顽頑 顾顧 顿頓 颁頒 颂頌 飙飆 飞飛 饭飯 饮飲 饰飾 饱飽 馆館 馈饋 驶駛 骑騎 验驗
            骤驟 鹅鵝 鹤鶴 鸡雞 鸣鳴 鸽鴿 麦麥 黄黃 点點 党黨 邻鄰 郑鄭 鉴鑒 针針 钉釘 锐銳 闪閃 闭閉 问問 闯闖
            阀閥 阁閣 阅閱 阔闊 阖闔 阙闕 队隊 阳陽 阵陣 阶階 际際 陆陸 驰馳 驻駐 驾駕 驿驛 鸟鳥 鸭鴨 齿齒 龙龍
            龟龜 齐齊 气氣 汇匯 汉漢 沟溝 没沒 沥瀝 沦淪 沪滬 泪淚 洁潔 洒灑 浅淺 济濟 浏瀏 浑渾 浓濃 涂塗 涝澇
            涟漣 测測 涩澀 滥濫 滨濱 滩灘 潇瀟 潍濰 澜瀾 濒瀕 灭滅 灯燈 灵靈 灾災 实實 审審 宪憲 宠寵 携攜 摊攤
            敌敵 敛斂 斩斬 妆妝 运運 这這 进進 远遠 夺奪 寻尋 导導 时時 达達 迁遷 过過 迈邁 还還 贝貝 负負 财財
            责責 败敗 账賬 货貨 质質 贩販 贪貪 贫貧 贬貶 购購 贮貯 贯貫 贱賤 贴貼 贵貴 费費 资資 赋賦 赌賭 赏賞
            赔賠 赖賴 赚賺 赛賽 赞贊 赠贈 赢贏 赵趙 赶趕 跃躍 践踐 选選 遗遺 邮郵 释釋 钟鐘 钥鑰 钩鉤 钮鈕 钱錢
            铁鐵 铃鈴 铅鉛 银銀 链鏈 铜銅 锅鍋 锋鋒 镜鏡 长長 强強 条條 无無 来來 机機 环環 现現 极極 构構 发發
            变變 复復 双雙 术術 杀殺 杂雜 权權 断斷 继繼 晓曉 独獨 墙牆 壶壺 壮壯 声聲 壳殼 爱愛 牵牽 员員 区區
            医醫 华華 协協 单單 卖賣 卫衛 厂廠 厅廳 历歷 压壓 厌厭 厕廁 厢廂 厨廚 厦廈 叹嘆 号號 响響 哑啞 哗嘩
            唤喚 咏詠 园園 图圖 剧劇 劝勸 办辦 苏蘇 莹瑩 荐薦 莱萊 万萬 节節 药藥 补補 视視 览覽 规規 觉覺 虑慮
            蚀蝕 蛮蠻 袭襲 监監 盘盤 矿礦 确確 砖磚 础礎 礼禮 祸禍 离離 绣繡 绘繪 细細 计計 订訂 认認 讨討 让讓
            训訓 议議 讯訊 讲講 许許 诉訴 证證 评評 识識 词詞 译譯 试試 话話 该該 详詳 语語 说說 请請 诸諸 读讀
            调調 谈談 谊誼 谋謀 谣謠 谨謹 谱譜 车車 转轉 轮輪 软軟 轻輕 载載 较較 帮幫 并並 广廣 庆慶 库庫 应應
            庙廟 忆憶 态態 怀懷 总總 恶惡 战戰 户戶 挥揮 数數 斋齋 头頭 频頻 题題 额額 颜顏 风風 马馬 驱驅 鱼魚
            鲁魯 鲜鮮 鸿鴻 写寫 吗嗎 价價 众眾
        """.trimIndent()
        val S2T: Map<Char, String> = S2T_PAIRS.split(' ').filter { it.length == 2 }.associate { it[0] to it[1].toString() }

        const val MIN_KEYS = 4
        const val MIN_PLURALS = 8
    }
}
