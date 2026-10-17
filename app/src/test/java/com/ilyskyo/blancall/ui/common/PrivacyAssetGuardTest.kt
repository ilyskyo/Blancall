// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「随包带一份用户看不到的隐私政策」这条不能靠人记：守卫的是**资产与代码的一致性**。
 *
 * 规则只有一条：`assets/PRIVACY.md` 存在 ⇔ 有代码读它。
 * - Std（NoAI）版要在欢迎页与设置页展示隐私政策，所以两份都在；
 * - Pro 版按既定决策**不做应用内隐私政策**（Pro 只发源码、不出 APK，对外载体是仓库根的 PRIVACY.md），
 *   所以它连 `assets/PRIVACY.md` 都不该带着 —— 2026-10-11 之前它带着一份没人读、
 *   且「适用版本」还落后一整版的死资产，打进包里既误导审核也误导用户。
 *
 * 这条断言写成「两个仓库同一条不变量」而不是分别写死，是因为两版对隐私政策的处置本来就不同；
 * 一旦有人把 Pro 的做法改回来（加展示入口），它会跟着变，而不是先红一次假债。
 */
class PrivacyAssetGuardTest {

    private val asset = File("src/main/assets/PRIVACY.md")

    @Test
    fun `隐私政策资产与读取它的代码必须同时存在或同时不存在`() {
        val hasAsset = asset.isFile
        val readers = buildList {
            File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                if (f.readText().contains("PRIVACY.md")) add(f.name)
            }
        }
        if (hasAsset) {
            assertTrue(
                "assets/PRIVACY.md 随包带着，却没有任何代码读它 —— 用户看不到的一份隐私政策，" +
                    "只会变成过期文本（Pro 侧就是这么一份死资产，已删）",
                readers.isNotEmpty()
            )
        } else {
            assertTrue(
                "代码里引用了 PRIVACY.md（$readers），但 assets 下没有这份文件 —— 运行时读不到内容",
                readers.isEmpty()
            )
        }
    }
}
