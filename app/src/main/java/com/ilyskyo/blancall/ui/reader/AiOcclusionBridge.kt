// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.reader

/**
 * AI 遮挡桥接接口：把「AI 背诵遮挡」从阅读页解耦，让阅读页本身不依赖任何 AI 代码。
 *
 * - Pro 版在调用处注入真实实现（联网调 AI 返回遮挡坐标，软件本地在原文上遮挡，防篡改）
 * - 标准版（NoAI）不注入实现，阅读页自动隐藏「AI 遮挡」选项，仅剩本地算法
 */
interface AiOcclusionBridge {
    /** 是否已配置可用（未配置 AI 则阅读页隐藏 AI 遮挡选项） */
    val available: Boolean

    /**
     * 对整篇正文生成遮挡区间（在全文上的全局 [start,end) 字符区间）。
     * 应挂在工作线程调用；失败或不可用返回 null（阅读页回退本地算法）。
     */
    suspend fun generate(content: String): List<OcclusionSpan>?
}