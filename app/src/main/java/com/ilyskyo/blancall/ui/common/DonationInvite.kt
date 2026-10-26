// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ilyskyo.blancall.ui.theme.AppPrefs

/**
 * 赞赏邀请的闸门。
 *
 * 三条候选位置（句子卡完成页 / 交卷后评分卡 / 练习后首页弹层）会**在同一次完成流程里
 * 依次触发**：交卷 → 评分卡；整轮排空 → 完成页；回到首页 → 数据弹层。
 * 如果每个位置各判断各的，一次复习会被问三遍。所以判定必须收口到一处、且是原子的。
 *
 * 三层条件，缺一不可：
 *  1. 资格：累计完成 ≥ [MIN_COMPLETIONS] 次（[AppPrefs.donationCompletionCount]）。
 *     依据 KC/Mak/Ofek 的「先用后付」研究——产品价值高时用后付费显著更高，但**价值低时
 *     效应会逆转**；新用户还没验证过价值，越早问越亏。
 *  2. 冷却：[COOLDOWN_MS] 内不再出现。依据 Apple HIG《Ratings and reviews》原文
 *     「考虑每次请求的间隔时间至少为一周或两周」。
 *  3. 否决：用户点过「不再提示」就永不再问。
 *
 * [ownerSlot] 让一次进程内只有一个位置能拿到这张邀请，但**同一个位置可以反复拿到**——
 * 这一点必须区分开：评分卡是 LazyColumn 的 item，滚出屏幕会被回收、滚回来会重新组合。
 * 如果抢到就永久作废，用户往回滚一下入口就会凭空消失。
 */
object DonationInviteGate {
    internal const val MIN_COMPLETIONS = 5
    internal const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var ownerSlot: String? = null

    /** 请求本次展示权。同一 [slot] 重复调用仍然放行；别的 slot 抢到过则拒绝。 */
    fun claim(slot: String): Boolean {
        if (ownerSlot == slot) return true
        synchronized(this) {
            if (ownerSlot != null) return false
            if (!eligible()) return false
            ownerSlot = slot
            AppPrefs.donationInviteShownAt = System.currentTimeMillis()
            return true
        }
    }

    private fun eligible(): Boolean = donationInviteEligible(
        completionCount = AppPrefs.donationCompletionCount,
        lastShownAt = AppPrefs.donationInviteShownAt,
        dismissed = AppPrefs.donationInviteDismissed,
        now = System.currentTimeMillis(),
    )
}

/**
 * 门槛的纯判定部分，从 [DonationInviteGate] 里抽出来只为能被纯 JVM 单测覆盖。
 *
 * 边界写错一次就会变成两种都不会被人工点按发现的严重后果：
 * 「不足 5 次也放行」= 新用户第一条练习就被要钱；
 * 「冷却比较写成 > 而不是 >=」或常量抄错 = 入口永远不出现。
 * 所以 [MIN_COMPLETIONS] 与 [COOLDOWN_MS] 的边界在 DonationInviteGateTest 里逐条钉死。
 */
internal fun donationInviteEligible(
    completionCount: Int,
    lastShownAt: Long,
    dismissed: Boolean,
    now: Long,
): Boolean {
    if (dismissed) return false
    if (completionCount < DonationInviteGate.MIN_COMPLETIONS) return false
    if (lastShownAt <= 0L) return true
    return now - lastShownAt >= DonationInviteGate.COOLDOWN_MS
}

/**
 * 完成态里的赞赏入口：**一行字**，不是按钮、不是卡片。
 *
 * 形态是设计的结论而不是偷懒：
 *  - Pieters/Wedel/Batra (2010)：feature complexity（元素密集）伤好感，design complexity
 *    （结构精致）才加分。完成页已经有光环、图标、标题、评级分布、三个按钮，再加一个块面
 *    是往「密集」那一侧走。
 *  - Cheshin/Amit/van Kleef (2018)：情绪用力过猛会被判为不真诚。完成页的情绪已到峰值，
 *    附加请求只能降调，不能加码。
 *  - Apple HIG：明确要求「避免用按钮触发」请求。
 * 所以：无背景、无边框、无图标，只有一行 labelSmall；只把「点这里赞赏」这几个字上主色 + SemiBold。
 *
 * 那一处高亮不是装饰，是可点性：第一版整行都用低透明度正文色，实测下来用户根本看不出能点
 * （2026-10-25 反馈）。极简的边界不是「看不见」，是「看得见但不起眼」——可点性优先于克制。
 * 高亮样式与标准版 WelcomeScreen 的「点击此处支持开发者」同源（primary + SemiBold），
 * 让用户在自己的 App 里只需要学会一种「这是链接」的视觉语言。
 *
 * 点击区靠 padding 撑到 44dp 以上，保证好点。
 *
 * @param slot 位置标识（sentence_done / score_card / home_popup），用于跨位置去重
 * @param modifier 宿主给的布局修饰（间距由宿主控制，避免三处各写一套）
 */
@Composable
fun DonationInviteRow(slot: String, modifier: Modifier = Modifier) {
    // claim() 放在 remember 里：一个组合实例只判定一次。
    // 这不是「在 composition 里做副作用」的常规写法，但 claim 有幂等保护，
    // 且必须比首帧更早拿到结果，否则会先闪一帧空白再出现。
    val claimed = remember(slot) { DonationInviteGate.claim(slot) }
    if (!claimed) return

    var showDialog by remember { mutableStateOf(false) }
    // 在弹层里点了「不再提示」后，这一行应当当场消失，而不是等下次重组才不见
    var retired by remember { mutableStateOf(false) }
    if (retired) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 圆角裁切要在 pressClick 之前，否则按压反馈的底色会露出直角
            .clip(RoundedCornerShape(12.dp))
            .pressClick(tier = PressTier.Icon) { showDialog = true }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 前缀用低透明度正文色，「点这里赞赏」单独上主色 —— 不是两个 Text 并排，
        // 而是同一段 AnnotatedString，窄屏折行时仍然是连续的一句话，不会在中间断开。
        val prefix = stringResource(com.ilyskyo.blancall.R.string.donation_nudge_prefix)
        val link = stringResource(com.ilyskyo.blancall.R.string.donation_nudge_link)
        val linkColor = MaterialTheme.colorScheme.primary
        Text(
            buildAnnotatedString {
                append(prefix)
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) {
                    append(link)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }

    if (showDialog) {
        DonationQrDialog(
            onDismiss = { showDialog = false },
            onNeverShow = {
                AppPrefs.donationInviteDismissed = true
                retired = true
            },
        )
    }
}

/**
 * 点开后的二维码弹层。
 *
 * 弹层里只陈述事实 + 给码，不重复邀请文案——邀请已经在上一步说过了，
 * 再说一遍就是 Feiler/Tost/Grant (2012) 说的「混用多套理由抬高说服觉察」。
 * 白底是刚性要求（深色模式下黑码必须有白底才扫得出），与设置页赞赏区同源。
 */
@Composable
private fun DonationQrDialog(onDismiss: () -> Unit, onNeverShow: () -> Unit) {
    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(com.ilyskyo.blancall.R.string.donation_heading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.donation_statement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                ) {
                    Image(
                        painter = painterResource(id = com.ilyskyo.blancall.R.drawable.appreciation_qr_card),
                        contentDescription = stringResource(com.ilyskyo.blancall.R.string.donation_qr),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.close),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onNeverShow(); onDismiss() }) {
                Text(
                    stringResource(com.ilyskyo.blancall.R.string.donation_never),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
