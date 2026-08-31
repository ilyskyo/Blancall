// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.model

/**
 * 练习进度状态
 */
enum class PracticeStatus {
    /** 进行中 —— 用户中途保存退出 */
    IN_PROGRESS,
    /** 已完成 —— 全部提交批改 */
    COMPLETED
}

/**
 * 练习进度快照，用于「继续练习」功能
 */
data class PracticeState(
    val articleId: Long,
    // 保持 String 以兼容旧 JSON 数据；推荐通过 toPracticeMode() 转换为枚举使用
    val mode: String,              // "SENTENCE" | "WORD" | "REVERSE"，见 PracticeMode
    val status: PracticeStatus = PracticeStatus.IN_PROGRESS,
    val totalBlanks: Int = 0,
    val answeredCount: Int = 0,    // 已填写数量
    // 不可变 Map；每次更新需 copy 整个 PracticeState。仓库层内部用 MutableMap，对外暴露不可变快照
    val answers: Map<Int, String> = emptyMap(),
    // 反向默写模式下保存的整段输入文本（句子/字词挖空模式为空）
    val dictationInput: String = "",
    // 上次挖好的空（当前模式的挖空结果 JSON 序列化），供「继续练习」精确恢复，无需重新生成/选难度
    val clozeJson: String? = null,
    val lastPracticeTime: Long = System.currentTimeMillis()
)
