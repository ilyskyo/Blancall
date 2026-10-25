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

/**
 * 错误类型枚举，对应 [MistakeDetail.errorType] 字符串字段。
 *
 * 落盘的是字符串（`AnswerChecker` 直接写 `Result.name`），所以这份枚举必须**覆盖写方会产出的全部名字**：
 * 原先缺 `INCORRECT`，于是那一类的错在盘上存在、在枚举里查无此值，汇总/雷达那 5 个字符串桶
 * 与本枚举各说各话。[OTHER] 是兜底桶，专给手工编辑过或未来新增的值 —— 让它可见，
 * 而不是在转换失败时静默丢掉一次错误记录。
 *
 * `GLYPH_CONFUSED` 是后加的（字形混淆）。旧记录里那一类错被写成了 `TYPO`，**不追溯重算**：
 * 当时判题确实分不出形近与不相干，按今天的表去改写历史等于伪造证据；两桶分开计数，
 * 旧数据随新练习积累自然稀释。
 */
enum class ErrorType {
    /** 记成他字：同一位置换成了不相干的字 */
    TYPO,
    /** 字形混淆：同一位置换成了形近字（己→已、未→末） */
    GLYPH_CONFUSED,
    /** 漏填 */
    MISSING,
    /** 多填 */
    EXTRA,
    /** 顺序错误 */
    WRONG_ORDER,
    /** 内容错误（AnswerChecker.Result.INCORRECT）：编辑距离过大，等于没记住 */
    INCORRECT,
    /** 认不出的值也计数，不静默丢弃 */
    OTHER
}

/** 将字符串转换为 [PracticeMode]；无法识别时返回 null */
fun String.toPracticeMode(): PracticeMode? = runCatching { PracticeMode.valueOf(this) }.getOrNull()

/** 将字符串转换为 [ErrorType]；认不出的归入 [ErrorType.OTHER]（统计口径不丢数） */
fun String.toErrorType(): ErrorType = runCatching { ErrorType.valueOf(this) }.getOrDefault(ErrorType.OTHER)

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
    val answeredSentenceStarts: List<Int> = emptyList(),
    // 本次判分【答错】的句子在【全文切句】中的句子索引（相对单篇文章/当次练习全文口径）。
    // 供句级错误画像聚合（薄弱优先策略、薄弱集训段落排序）；跨文练习不落此字段
    // （混合坐标仅对当次 mix 有效，换组合无法复用）；旧记录缺失 → 空列表（句级统计视为无数据）。
    val mistakeSentenceIndices: List<Int> = emptyList(),
    // 本次**全部已判分空**的逐空结果（不只错题）。错题本需要它才能判断
    // 「这个字后来写对过没有」：只靠 mistakes 只能看见错，永远不知道是否已攻克。
    // 旧记录为空 → 错题本把历史错题一律视为「未攻克」，并明确标注无法判断。
    val answeredBlanks: List<AnsweredBlank> = emptyList()
)

/**
 * 一次练习中单个空的判分结果（含答对的）。
 *
 * 为什么不复用 [MistakeDetail]：那个类型语义是「错处」，把答对也塞进去会让所有
 * 既有统计（错因分布、错题数）需要重新过滤一遍，漏一处就多算一次错。
 * 两者字段刻意保持同名同序，导出/导入可以逐字段对照。
 *
 * [sentenceText] 是判分当时那句原文，用来解决「句索引会随文章编辑整体错位」：
 * 错题本展示与重练定位都以文字为锚，索引只作为热力图的旧坐标使用。
 */
data class AnsweredBlank(
    val blankIndex: Int,
    val correctAnswer: String,
    val userAnswer: String,
    // 空串 = 答对；其余见 [ErrorType]
    val errorType: String = "",
    val sentenceIndex: Int = -1,
    val sentenceText: String = "",
    val similarity: Float = -1f,
) {
    val correct: Boolean get() = errorType.isEmpty()
}

data class MistakeDetail(
    val blankIndex: Int,
    val correctAnswer: String,
    val userAnswer: String,
    // 保持 String 以兼容旧 JSON 数据；推荐通过 toErrorType() 转换为枚举
    val errorType: String,    // TYPO | GLYPH_CONFUSED | MISSING | EXTRA | WRONG_ORDER | INCORRECT，见 ErrorType
    // 这个错空【所属句子】在【文章全文切句】里的索引，-1 = 旧记录没有落这个字段。
    //
    // 为什么句索引挂在空上、而不是只留 PracticeRecord.mistakeSentenceIndices 那个去重集合：
    // 集合只说「这几句错过」，说不出「这一处错在第几句」，于是记忆热力图只能整篇平摊，
    // 一句错 3 处与 3 句各错 1 处看起来一模一样。索引空间与 mistakeSentenceIndices 同源
    // （都取锚点在全文切句起点表里的下标），所以热力图可以直接按落点计数，不再猜。
    // 跨文练习与锚点不可用时同那个字段一起留 -1（见 PracticeViewModel 提交路径）。
    val sentenceIndex: Int = -1,
    // 这一处作答与正确答案的相似度（0..1），-1 = 旧记录没落这个字段。
    //
    // 为什么逐空也要留：记录里原先只有会话级的 `similarity`（各空均值），
    // 于是「一笔之差」和「全错」在盘上是同一个数 —— 回顾、热力图、AI 都分不出轻重。
    // 均值继续留给现有统计页用，两者并存不冲突。
    val similarity: Float = -1f,
    // 判分当时的完整原句。句索引会随文章编辑整体错位，文字不会：
    // 错题本用它显示「这句到底长什么样」、重练时用它在新正文里重新定位。
    // 旧记录为空串 → 展示层退回句索引或只显示答案对照，绝不跳到可能错误的句子。
    val sentenceText: String = ""
)
