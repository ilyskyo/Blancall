// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEquivalenceJudgeTest {

    private fun candidate(i: Int, expected: String = "之", written: String = "的") =
        AiEquivalenceJudge.Candidate(i, expected, written)

    @Test
    fun `buildRequest 带编号并截断超量候选`() {
        val many = (0 until AiEquivalenceJudge.MAX_CANDIDATES + 5).map { candidate(it, "答$it", "写$it") }
        val req = AiEquivalenceJudge.buildRequest(many)
        assertTrue(req.contains("[0]"))
        assertTrue(req.contains("[9]"))
        // 第 10 个（index=10）超出 MAX_CANDIDATES=10 的条目不应进入请求
        assertFalse(req.contains("[10]"))
        assertTrue(req.contains("标准答案：答0｜学生答案：写0"))
    }

    @Test
    fun `decode 解析正常数组`() {
        val v = AiEquivalenceJudge.decode(
            """[{"i":0,"equivalent":true,"reason":"同义"},{"i":1,"equivalent":false,"reason":"实词不同"}]""",
            setOf(0, 1)
        )
        assertEquals(listOf(0, 1), v.map { it.index })
        assertTrue(v[0].equivalent)
        assertFalse(v[1].equivalent)
        assertEquals("同义", v[0].reason)
    }

    @Test
    fun `decode 宽容代码块与前后缀`() {
        val v = AiEquivalenceJudge.decode(
            "好的，结果如下：\n```json\n[{\"i\":2,\"equivalent\":true,\"reason\":\"可通\"}]\n```\n以上。",
            setOf(2)
        )
        assertEquals(1, v.size)
        assertEquals(2, v.first().index)
    }

    @Test
    fun `decode 越界编号与字符串布尔`() {
        val v = AiEquivalenceJudge.decode(
            """[{"i":9,"equivalent":true,"reason":"越界"},{"i":1,"equivalent":"true","reason":"宽容"},{"i":2,"equivalent":"maybe"}]""",
            setOf(1, 2)
        )
        // 9 越界丢弃；1 的字符串 "true" 宽容为等价；2 的 "maybe" 读不懂丢弃
        assertEquals(listOf(1), v.map { it.index })
        assertTrue(v.first().equivalent)
    }

    @Test
    fun `decode 非 JSON 返回空表`() {
        assertTrue(AiEquivalenceJudge.decode("抱歉，我无法判断。", setOf(0)).isEmpty())
        assertTrue(AiEquivalenceJudge.decode("", setOf(0)).isEmpty())
        assertTrue(AiEquivalenceJudge.decode("[不是数组", setOf(0)).isEmpty())
    }
}
