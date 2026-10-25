// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.FsrsEngine
import com.ilyskyo.blancall.algorithm.SentenceSelector
import com.ilyskyo.blancall.data.repository.ArticleRepository
import com.ilyskyo.blancall.data.repository.DailySentenceCoordinator
import com.ilyskyo.blancall.data.repository.FsrsStateStore
import com.ilyskyo.blancall.data.repository.SentenceCardStore
import com.ilyskyo.blancall.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 桌面小组件：今日到期数 + 每日一句（4x2）。
 *
 * 为什么显示「到期」而不是「推荐」：到期数是可核对的事实；推荐需要一整套决策，
 * 在小组件里既放不下也无法解释。点一下直接进第一件该做的事（到期文章 / 句子卡），
 * 不先把用户送到首页。
 *
 * 刷新约定：系统最短 15 分钟刷新一次，而用户「刚练完回到桌面」期望立刻变 ——
 * [requestRefresh] 在每次提交练习/句子卡评分后主动调用 updateAll，避免组件持续显示旧数。
 */
class BlancallWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL, WIDE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = withContext(Dispatchers.IO) { load(context) }
        provideContent {
            GlanceTheme {
                WidgetContent(context, snapshot)
            }
        }
    }

    private data class Snapshot(
        val dueArticles: Int = 0,
        val dueSentences: Int = 0,
        val firstDueArticleId: Long = -1L,
        val sentence: String = "",
        val sentenceTitle: String = "",
    )

    private suspend fun load(context: Context): Snapshot {
        val filesDir = context.filesDir
        val articleRepo = ArticleRepository.getInstance(filesDir.resolve("articles.json").absolutePath)
        val fsrs = FsrsStateStore.getInstance(filesDir.resolve("fsrs_state.json").absolutePath)
        return runCatching {
            articleRepo.awaitLoaded()
            fsrs.awaitLoaded()
            // awaitLoaded 已保证首载完成，取一次当前快照即可
            val articles = articleRepo.allArticles.first()
            val now = System.currentTimeMillis()
            val paused = fsrs.pausedArticleIds()
            val states = fsrs.allStates()
            val dueArticles = states.filterKeys { it !in paused }.values
                .count { it.reviewCount > 0 && FsrsEngine.isDue(it, now) }
            val firstDue = states.entries
                .filter { it.key !in paused && it.value.reviewCount > 0 && FsrsEngine.isDue(it.value, now) }
                .minByOrNull { it.value.due }?.key ?: -1L
            val sentenceStates = fsrs.allSentenceStates()
            val dueSentences = sentenceStates.count { (key, state) ->
                key.startsWith(SentenceSelector.SENTENCE_KEY_PREFIX) &&
                    FsrsEngine.isDue(state, now) &&
                    SentenceSelector.articleIdOf(key) !in paused
            }
            val today = DailySentenceCoordinator.ensureToday(
                store = SentenceCardStore.getInstance(filesDir),
                articles = articles,
                sentenceStates = sentenceStates,
                now = now,
                excludedArticleIds = paused,
            )
            Snapshot(
                dueArticles = dueArticles,
                dueSentences = dueSentences,
                firstDueArticleId = firstDue,
                sentence = today?.text.orEmpty(),
                sentenceTitle = today?.title.orEmpty(),
            )
        }.getOrDefault(Snapshot())
    }

    @Composable
    private fun WidgetContent(context: Context, snapshot: Snapshot) {
        val size = LocalSize.current
        val wide = size.width >= WIDE_WIDTH
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                NotificationHelper.EXTRA_NAV_ACTION,
                when {
                    snapshot.firstDueArticleId > 0L -> NotificationHelper.NAV_PRACTICE
                    snapshot.dueSentences > 0 -> NotificationHelper.NAV_SENTENCE_CARDS
                    else -> NotificationHelper.NAV_HOME
                }
            )
            if (snapshot.firstDueArticleId > 0L) {
                putExtra(NotificationHelper.EXTRA_ARTICLE_ID, snapshot.firstDueArticleId)
            }
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(14.dp)
                .clickable(actionStartActivity(openIntent)),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = context.getString(R.string.widget_title),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot.dueArticles.toString(),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = if (wide) 30.sp else 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = context.getString(R.string.widget_due_label),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                )
                if (snapshot.dueSentences > 0) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = context.getString(R.string.widget_sentence_due, snapshot.dueSentences),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
            if (wide && snapshot.sentence.isNotBlank()) {
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = snapshot.sentence,
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                    ),
                )
                if (snapshot.sentenceTitle.isNotBlank()) {
                    Text(
                        text = snapshot.sentenceTitle,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val WIDE_WIDTH = 180.dp
    }
}

/** 练习提交 / 句子卡评分后主动刷新，解决系统 15 分钟刷新间隔导致的旧数字。 */
object BlancallWidgetRefresh {
    suspend fun request(context: Context) {
        runCatching {
            BlancallWidget().updateAll(context)
        }
    }
}
