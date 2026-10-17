// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「每个源文件都带 MIT 版权头」的守卫。
 *
 * 这条是项目的硬约束（新建 .kt 必须两行头：Copyright + SPDX-License-Identifier），
 * 但它坏得无声无息：漏一次只有那个文件没有头，编译、运行、测试全都照常，
 * 等到要核对授权信息时才发现有一批文件是裸的。所以写成守卫，而不是靠自觉。
 *
 * 只查文件**开头**（前两行注释），不查内容：头部注释必须在 package 之前才有效。
 */
class SourceHeaderGuardTest {

    @Test
    fun 每个Kotlin文件都带MIT版权头() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (srcSet in listOf("main", "test", "androidTest")) {
            val root = File("src/$srcSet/java")
            if (!root.isDirectory) continue
            root.walkBottomUp().filter { it.extension == "kt" }.forEach { file ->
                scanned++
                val head = try {
                    file.readText(Charsets.UTF_8).take(240)
                } catch (e: Exception) {
                    offenders += "${file.name} 读不出来：${e.message}"
                    return@forEach
                }
                if (!head.contains(COPYRIGHT) || !head.contains(SPIDX)) {
                    offenders += file.invariantSeparatorsPath.substringAfter("java/")
                }
            }
        }

        // 反空集：没扫到足够文件就说明工作目录或路径不对，下面的结论不作数
        assertTrue("扫描到 $scanned 个 .kt，少于下限 $MIN_FILES —— 路径没解析对，守卫没在看东西",
            scanned >= MIN_FILES)
        assertTrue(
            "这些文件缺 MIT 版权头（前两行）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    private companion object {
        const val COPYRIGHT = "Copyright (c) 2026 ilyskyo"
        const val SPIDX = "SPDX-License-Identifier: MIT"

        /** 本项目有 200+ 个 .kt；少于此数说明压根没遍历到源码树。 */
        const val MIN_FILES = 150
    }
}
