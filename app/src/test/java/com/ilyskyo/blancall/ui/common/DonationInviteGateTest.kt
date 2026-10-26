// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赞赏邀请门槛的边界测试。
 *
 * 这两组边界都不会被人工点按发现：门槛写成 0 或 4，测试者第一次练习就看到入口也只会以为
 * 「本来就该这样」；冷却写成一天或一年，测试者只会以为「还没轮到」。所以逐条钉死。
 */
class DonationInviteGateTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun no_completions_never_invites() {
        assertFalse(donationInviteEligible(0, 0L, false, now))
    }

    @Test
    fun one_below_threshold_never_invites() {
        // 门槛是 5：4 次仍然算新用户（KC/Mak/Ofek：价值未验证时请求会反噬）
        assertFalse(donationInviteEligible(4, 0L, false, now))
    }

    @Test
    fun exactly_threshold_invites() {
        assertTrue(donationInviteEligible(5, 0L, false, now))
    }

    @Test
    fun above_threshold_never_shown_invites() {
        assertTrue(donationInviteEligible(37, 0L, false, now))
    }

    @Test
    fun shown_yesterday_is_suppressed() {
        assertFalse(donationInviteEligible(5, now - day, false, now))
    }

    @Test
    fun shown_one_ms_before_cooldown_ends_is_suppressed() {
        assertFalse(
            donationInviteEligible(
                5,
                now - DonationInviteGate.COOLDOWN_MS + 1,
                false,
                now,
            )
        )
    }

    @Test
    fun exactly_at_cooldown_boundary_invites() {
        // >= 而不是 >：正好满 7 天就该放行，否则冷却会悄悄变成 8 天
        assertTrue(
            donationInviteEligible(
                5,
                now - DonationInviteGate.COOLDOWN_MS,
                false,
                now,
            )
        )
    }

    @Test
    fun one_day_past_cooldown_invites() {
        assertTrue(
            donationInviteEligible(
                5,
                now - DonationInviteGate.COOLDOWN_MS - day,
                false,
                now,
            )
        )
    }

    @Test
    fun dismissed_blocks_even_with_everything_else_green() {
        assertFalse(donationInviteEligible(999, 0L, true, now))
    }

    @Test
    fun dismissed_blocks_after_cooldown_too() {
        assertFalse(
            donationInviteEligible(
                999,
                now - DonationInviteGate.COOLDOWN_MS * 10,
                true,
                now,
            )
        )
    }
}
