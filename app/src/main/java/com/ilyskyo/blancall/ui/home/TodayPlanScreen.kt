// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.DayBucket
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.algorithm.StudyCoach
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.InProgressPracticeStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.ui.common.AmbientBackground
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.heroElement
import com.ilyskyo.blancall.ui.navigation.navigateToTab
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.Macaron
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.temporal.ChronoUnit

/**
 * 「今日计划」展开页：首页那张卡点开后看到的**全部今日内容**。
 *
 * 为什么不再复用 `buildTasks`：那是学习任务流的数据源，回答的是「FSRS 里有哪些文章」。
 * 今日计划回答的是「今天有哪些事」—— 继续没答完的、到期该复习的、句子卡（含每日一句）、
 * 薄弱篇、错题集训、每日目标。任务流只负责这些文章的**管理**（暂停 / 改期），
 * 入口仍留在右上角，两页不再共用同一份清单。
 *
 * 数据仍与首页卡片同源：[StudyCoach.Prepared] 只算一次，[StudyCoach.Snapshot] 负责
 * 时间相关计数，本页只做分组与点击动作，不另立优先级。
 */
@Composable
fun TodayPlanScreen(navController: NavController) {
    val context = LocalContext.current
    val fsrsStore = remember {
        FsrsStateStore.getInstance(context.filesDir.resolve("fsrs_state.json").absolutePath)
    }
    val articleRepo = remember {
        ArticleRepository.getInstance(context.filesDir.resolve("articles.json").absolutePath)
    }
    val articles by articleRepo.allArticles.collectAsState(initial = emptyList())

    var states by remember { mutableStateOf(fsrsStore.allStates()) }
    var pauses by remember { mutableStateOf(fsrsStore.pausedArticles()) }
    var sentenceStates by remember { mutableStateOf(fsrsStore.allSentenceStates()) }
    var enrolled by remember { mutableStateOf(fsrsStore.enrolledArticles()) }
    val fsrsRevision by fsrsStore.revision.collectAsState()
    LaunchedEffect(fsrsRevision) {
        fsrsStore.awaitLoaded()
        states = fsrsStore.allStates()
        pauses = fsrsStore.pausedArticles()
        sentenceStates = fsrsStore.allSentenceStates()
        enrolled = fsrsStore.enrolledArticles()
    }

    // 跨午夜自动换桶（与任务流、统计页同款分钟 ticker）
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    // 旧版/CSV 回灌只有记录、没有 FSRS 状态；判断「练过没有」必须带上 records
    val recordRepo = remember {
        RecordRepository.getInstance(context.filesDir.resolve("records.json").absolutePath)
    }
    val allRecords by recordRepo.records.collectAsState()

    // ── 计划设置面板要的两样东西：预算输入与「今天预估多少分钟」。
    // 与首页同一个 StudyCoach 口径，面板里的预估才不会和卡片副标题对不上 ──
    val reviewTemplateId by AppPrefs.reviewTemplateFlow.collectAsState()
    val reviewTemplate = remember(reviewTemplateId) { ReviewTemplate.fromId(reviewTemplateId) }
    val goalMinutes by ReminderPrefs.goalMinutesFlow.collectAsState()
    val dailyPracticeGoal by ReminderPrefs.dailyPracticeGoalFlow.collectAsState()
    val examEpochDay by ReminderPrefs.examEpochDayFlow.collectAsState()
    val rawResumables by produceState(emptyList<StudyCoach.InProgressPractice>(), articles) {
        value = if (articles.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { InProgressPracticeStore.scan(context.filesDir) }
    }
    val todaySentenceKey by produceState<String?>(null, nowTick) {
        val today = DayBucket.localDate(nowTick).toString()
        value = withContext(Dispatchers.IO) {
            SentenceCardStore.getInstance(context.filesDir).today()
                ?.takeIf { it.date == today }?.key
        }
    }
    val prepared = remember(
        articles, allRecords, states, pauses, sentenceStates, enrolled, reviewTemplate,
        rawResumables, todaySentenceKey,
    ) {
        StudyCoach.prepare(
            StudyCoach.Source(
                articles = articles,
                records = allRecords,
                states = states,
                pauses = pauses,
                sentenceStates = sentenceStates,
                template = reviewTemplate,
                resumables = rawResumables,
                todaySentenceKey = todaySentenceKey,
                enrolled = enrolled,
            )
        )
    }
    val snapshot = remember(prepared, nowTick, goalMinutes, dailyPracticeGoal, examEpochDay) {
        StudyCoach.snapshot(
            prepared = prepared,
            now = nowTick,
            goalMinutes = goalMinutes,
            dailyPracticeGoal = dailyPracticeGoal,
            examEpochDay = examEpochDay.takeIf { it > 0L },
        )
    }
    val plan = remember(snapshot) { StudyCoach.buildPlan(snapshot.input) }
    val estimatedMinutes = plan.estimatedMinutes
    var showPlanSettings by remember { mutableStateOf(false) }

    // ── 今日清单：只放「今天能做」的事，不把任务流里未来/暂停的文章搬进来 ──
    val resumables = snapshot.resumables
    val resumableIds = remember(resumables) { resumables.mapTo(HashSet()) { it.articleId } }
    // 同一篇文章若还有没答完的练习，就先出现在「继续完成」里；到期清单不重复列同一篇
    val dueNow = remember(snapshot, resumableIds) {
        snapshot.input.dueArticles.filter { it.articleId !in resumableIds }
    }
    val sentenceDue = snapshot.sentenceTotalDue
    // 每日一句即使没到期也属于「今天可以做的事」：首页的句子卡常驻，计划页不能把它藏起来。
    val sentenceStatus = remember(todaySentenceKey, sentenceStates, nowTick) {
        todaySentenceKey?.let { key ->
            sentenceStatusLine(context, sentenceStates[key], nowTick)
        }
    }
    val showSentence = sentenceStatus != null || sentenceDue > 0
    val sentenceSummaryCount = if (sentenceStatus != null) {
        1 + snapshot.sentenceOtherDue
    } else {
        sentenceDue
    }
    val mistakeCount = snapshot.mistakeCount
    val weakest = remember(snapshot, resumableIds, dueNow) {
        snapshot.weakest?.takeIf { w ->
            w.articleId !in resumableIds && dueNow.none { it.articleId == w.articleId }
        }
    }
    val goalGap = (snapshot.input.dailyPracticeGoal - snapshot.input.todayPracticeCount)
        .coerceAtLeast(0)
    val actionableCount = resumables.size + dueNow.size +
        (if (showSentence) 1 else 0) +
        (if (weakest != null) 1 else 0) +
        (if (mistakeCount > 0) 1 else 0) +
        (if (goalGap > 0) 1 else 0)
    val hasAny = actionableCount > 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 与任务流、任务详情同一套：内容整体让开状态栏，标题 Row 再从 12dp 起算
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        AmbientBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = { navController.popBackStack() })
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.today_plan_all_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                // 管理（暂停 / 改期 / 全部暂停）仍是任务流的职责，这里只留入口
                TextButton(onClick = { navController.navigate("study_flow") }) {
                    Text(
                        stringResource(R.string.today_plan_all_manage),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                TextButton(onClick = { showPlanSettings = true }) {
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 40.dp)
            ) {
                // hero 目标：与首页计划卡共用节点，卡片直接长大成这一块
                item(key = "plan_hero") {
                    PlanHero(
                        plan = plan,
                        actionableCount = actionableCount,
                        hasAny = hasAny,
                        resumeCount = resumables.size,
                        dueCount = dueNow.size,
                        sentenceCount = sentenceSummaryCount,
                        mistakeCount = mistakeCount,
                        todayPracticeCount = snapshot.input.todayPracticeCount,
                        dailyPracticeGoal = dailyPracticeGoal,
                    )
                }

                if (resumables.isNotEmpty()) {
                    item(key = "hdr_resume") {
                        PlanSectionHeader(R.string.resume_tasks, resumables.size)
                    }
                    items(
                        items = resumables,
                        // 同一篇可能留下多个模式的未完成状态；把模式与时间一起放进 key，避免撞 key
                        key = { "resume_${it.articleId}_${it.mode}_${it.lastTime}" }
                    ) { item ->
                        PlanTaskRow(
                            title = item.title,
                            meta = resumeMeta(item),
                            action = stringResource(R.string.continue_action),
                            accent = Macaron.continueP().accent,
                            onClick = {
                                navController.navigate("practice/${item.articleId}?resume=true")
                            },
                        )
                    }
                }

                if (dueNow.isNotEmpty()) {
                    item(key = "hdr_due") {
                        PlanSectionHeader(R.string.due_tasks, dueNow.size)
                    }
                    items(dueNow, key = { "due_${it.articleId}" }) { article ->
                        DuePlanRow(
                            article = article,
                            now = nowTick,
                            onClick = {
                                navController.navigate("practice/${article.articleId}?mode=SENTENCE")
                            },
                        )
                    }
                }

                if (weakest != null) {
                    item(key = "plan_weak") {
                        PlanTaskRow(
                            title = weakest.title,
                            meta = if (weakest.mistakeCount > 0) {
                                stringResource(R.string.today_plan_weak_detail, weakest.mistakeCount)
                            } else {
                                stringResource(R.string.weak_articles_title)
                            },
                            action = stringResource(R.string.practice_short),
                            accent = Macaron.warn().accent,
                            onClick = {
                                navController.navigate(
                                    "practice/${weakest.articleId}?mode=SENTENCE&sectionMode=WEAKNESS"
                                )
                            },
                        )
                    }
                }

                if (showSentence) {
                    item(key = "plan_sentence") {
                        PlanTaskRow(
                            title = stringResource(R.string.sentence_cards),
                            meta = sentenceMeta(
                                status = sentenceStatus,
                                otherDue = snapshot.sentenceOtherDue,
                                totalDue = sentenceDue,
                            ),
                            action = stringResource(R.string.review_action),
                            accent = Macaron.info().accent,
                            onClick = { navController.navigate("sentence_cards") },
                        )
                    }
                }

                if (mistakeCount > 0) {
                    item(key = "plan_mistake") {
                        PlanTaskRow(
                            title = stringResource(R.string.today_plan_mistakes),
                            meta = stringResource(R.string.today_plan_mistake_detail, mistakeCount),
                            action = stringResource(R.string.ov_next_step_go_mistakes),
                            accent = Macaron.lavender().accent,
                            onClick = { navController.navigate("mistakes") },
                        )
                    }
                }

                if (goalGap > 0) {
                    item(key = "plan_goal") {
                        PlanTaskRow(
                            title = stringResource(R.string.today_plan_daily_goal),
                            meta = stringResource(R.string.today_plan_goal_detail, goalGap),
                            action = stringResource(R.string.ov_next_step_go_articles),
                            accent = Macaron.continueP().accent,
                            onClick = { navController.navigateToTab("list") },
                        )
                    }
                }

                // 暂停入口只在确有暂停时露出：平时不增加常驻按钮，空态单独给一句可点的提示
                if (snapshot.pausedArticleCount > 0) {
                    item(key = "plan_paused") {
                        val onlyPaused = !hasAny && snapshot.allScheduledPaused
                        TextButton(
                            onClick = { navController.navigate("study_flow") },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (onlyPaused) R.string.today_plan_empty_paused
                                    else R.string.today_plan_paused_hint,
                                    snapshot.pausedArticleCount
                                ),
                                style = if (onlyPaused) {
                                    MaterialTheme.typography.bodyMedium
                                } else {
                                    MaterialTheme.typography.labelSmall
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    TodayPlanSettingsSheet(
        visible = showPlanSettings,
        estimatedMinutes = estimatedMinutes,
        onDismiss = { showPlanSettings = false },
    )
}

/** hero 共享节点的键：首页计划卡与这里必须一致 */
internal const val TODAY_PLAN_HERO_KEY = "today_plan"

@Composable
private fun PlanHero(
    plan: StudyCoach.Plan,
    actionableCount: Int,
    hasAny: Boolean,
    resumeCount: Int,
    dueCount: Int,
    sentenceCount: Int,
    mistakeCount: Int,
    todayPracticeCount: Int,
    dailyPracticeGoal: Int,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heroElement(TODAY_PLAN_HERO_KEY, renderInOverlay = false),
        backdrop = false,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (hasAny) {
                    stringResource(R.string.today_plan_all_ready, actionableCount)
                } else {
                    stringResource(R.string.today_plan_all_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (hasAny) {
                    stringResource(
                        R.string.today_plan_board_summary,
                        resumeCount, dueCount, sentenceCount, mistakeCount
                    )
                } else {
                    stringResource(R.string.today_plan_all_empty)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (plan.examPace == StudyCoach.ExamPace.BEHIND) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.today_plan_exam_behind),
                    style = MaterialTheme.typography.labelSmall,
                    color = Macaron.warn().accent
                )
            }
            // 今日目标进度：与首页计划卡同一口径，达成后换成强调色
            if (dailyPracticeGoal > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.today_plan_goal_progress,
                        dailyPracticeGoal,
                        todayPracticeCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (todayPracticeCount >= dailyPracticeGoal) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun PlanSectionHeader(titleRes: Int, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 清单行：标题 + 一行事实 + 动作 + 箭头。
 *
 * 不用任务流的 [com.ilyskyo.blancall.ui.statistics.FlowTaskRow]：那一行自带相位色、
 * 留存率与「为什么今天轮到它」，是给 FSRS 任务看的；今日计划只需要「做什么、多少、去哪」。
 */
@Composable
private fun PlanTaskRow(
    title: String,
    meta: String,
    action: String,
    accent: Color,
    onClick: () -> Unit,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backdrop = false,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1
            )
            AppIcon(
                AppIconKind.ChevronRight,
                modifier = Modifier
                    .size(18.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun sentenceMeta(status: String?, otherDue: Int, totalDue: Int): String = when {
    // 与首页句子卡同一句状态：第一次记忆 / 待复习 · 上次 N 天前 / 已记录 · 下次 M 天后
    status != null && otherDue > 0 -> {
        status + " · " + pluralStringResource(
            R.plurals.pending_sentences_hint, otherDue, otherDue
        )
    }
    status != null -> status
    else -> stringResource(R.string.today_plan_sentence_detail, totalDue)
}

@Composable
private fun resumeMeta(item: StudyCoach.Resumable): String {
    val modeLabel = when (item.mode) {
        "SENTENCE" -> stringResource(R.string.cloze_mode_sentence)
        "WORD" -> stringResource(R.string.cloze_mode_word)
        "REVERSE" -> stringResource(R.string.cloze_mode_reverse)
        else -> stringResource(R.string.practice_short)
    }
    return modeLabel + " · " + stringResource(
        R.string.remaining_of_total, item.remaining, item.total
    )
}

@Composable
private fun DuePlanRow(article: StudyCoach.DueArticle, now: Long, onClick: () -> Unit) {
    // 逾期按日历日算：今天早些时候到期仍属于「今天」，不能报「逾期 1 天」
    val overdueDays = remember(article.due, now) {
        ChronoUnit.DAYS.between(
            DayBucket.localDate(article.due),
            DayBucket.localDate(now)
        ).toInt()
    }.coerceAtLeast(0)
    val overdue = overdueDays > 0
    val meta = when {
        // 加入计划但一次没练：不能对没读过的文章说「到期复习」
        !article.hasPractice -> stringResource(R.string.today_plan_start_detail)
        overdue -> stringResource(R.string.reason_overdue, overdueDays)
        else -> stringResource(R.string.today_plan_review_detail)
    }
    PlanTaskRow(
        title = article.title,
        meta = meta,
        action = stringResource(
            if (article.hasPractice) R.string.review_action else R.string.start_action
        ),
        accent = if (overdue) Macaron.review().accent else Macaron.warn().accent,
        metaColor = if (overdue) {
            Macaron.review().accent
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = onClick,
    )
}
