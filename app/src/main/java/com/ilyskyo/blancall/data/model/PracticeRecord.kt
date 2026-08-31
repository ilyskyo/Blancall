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
    val rating: Int = 0,
    // 本次练习的弱提示次数（淡显提示下一字）与强提示次数（自动帮填），0 表示未启用/旧数据
    val weakHints: Int = 0,
    val strongHints: Int = 0,
    // 本次判分【实际作答】的句子在【文章全文】中的字符起始位置（用于记忆热力图区分
    // "答对"与"未作答"；未完成提交时被跳过的空不会计入）。
    //
    // 为什么存字符位置而不是句子索引：全文切句与各段落分别切句的口径不同（标题行在全文
    // 中会被算作独立句子，而段落 contentOnly 不含标题），段落（Section）模式下"子集内
    // 句子索引"与"全文句子索引"整体错位。字符位置是唯一跨口径稳定的锚点。
    //
    // 兼容：旧记录的 answeredSentences（索引语义）字段已废弃，本字段缺失时热力图回退整篇统计。
    val answeredSentenceStarts: List<Int> = emptyList()
)

data class MistakeDetail(
    val blankIndex: Int,
    val correctAnswer: String,
    val userAnswer: String,
    // 保持 String 以兼容旧 JSON 数据；推荐通过 toErrorType() 转换为枚举使用
    val errorType: String    // TYPO | MISSING | EXTRA | WRONG_ORDER，见 ErrorType
)
