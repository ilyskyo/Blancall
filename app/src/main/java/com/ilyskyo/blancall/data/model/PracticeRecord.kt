// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.model

/** 练习模式枚举，对应 [PracticeRecord.mode] 字符串字段 */
enum class PracticeMode {
    /** 句子挖空 */
    SENTENCE,
    /** 单词挖空 */
    WORD,
    /** 反向练习 */
    REVERSE
}

/** 错误类型枚举，对应 [MistakeDetail.errorType] 字符串字段 */
enum class ErrorType {
    /** 拼写错误 */
    TYPO,
    /** 漏填 */
    MISSING,
    /** 多填 */
    EXTRA,
    /** 顺序错误 */
    WRONG_ORDER
}

/** 将字符串转换为 [PracticeMode]；无法识别时返回 null */
fun String.toPracticeMode(): PracticeMode? = runCatching { PracticeMode.valueOf(this) }.getOrNull()

/** 将字符串转换为 [ErrorType]；无法识别时返回 null */
fun String.toErrorType(): ErrorType? = runCatching { ErrorType.valueOf(this) }.getOrNull()

/**
 * 练习记录
 */
data class PracticeRecord(
    val id: Long = 0,
    val articleId: Long,
    // 保持 String 以兼容旧 JSON 数据；推荐通过 toPracticeMode() 转换为枚举使用
    val mode: String,         // "SENTENCE" | "WORD" | "REVERSE"，见 PracticeMode
    val totalBlanks: Int,
    val correctCount: Int,
    val mistakes: List<MistakeDetail> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    // 本次练习耗时（毫秒），0 表示旧数据或未记录
    val duration: Long = 0L,
    // 默写文本相似度（0..1），0 表示旧数据；供 FSRS 评级与未来连续 Grade 实验
    val similarity: Float = 0f,
    // 本次练习进入 FSRS 的评级（1=AGAIN 2=HARD 3=GOOD 4=EASY），0 表示旧数据
    val rating: Int = 0
)

data class MistakeDetail(
    val blankIndex: Int,
    val correctAnswer: String,
    val userAnswer: String,
    // 保持 String 以兼容旧 JSON 数据；推荐通过 toErrorType() 转换为枚举使用
    val errorType: String    // TYPO | MISSING | EXTRA | WRONG_ORDER，见 ErrorType
)
