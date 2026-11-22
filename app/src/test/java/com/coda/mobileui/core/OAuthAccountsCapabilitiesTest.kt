package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号能力推导与签到结果分类的测试。
 *
 * 覆盖三处口径问题：panel 必须由确认到的 /accounts 推导（#305）、
 * claim-pro 必须进 growthRoutes（#306）、单账号 global 不能算失败（#307）。
 */
class OAuthAccountsCapabilitiesTest {
    @Test
    fun panelIsFalseWhenAccountsRouteAbsent() {
        val caps = OAuthAccounts.capabilitiesFrom(setOf("/models", "/refresh"))
        assertFalse(caps.panel)
        assertTrue(caps.refresh)
    }

    @Test
    fun panelIsTrueWhenAccountsRouteConfirmed() {
        assertTrue(OAuthAccounts.capabilitiesFrom(setOf("/accounts")).panel)
    }

    @Test
    fun claimProIsExposedInGrowthRoutes() {
        val caps = OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/claim-pro"))
        assertTrue(caps.claimPro)
        assertTrue(caps.growthRoutes.contains("claim-pro"))
        assertTrue(caps.growth)
    }

    @Test
    fun everyClaimRouteLandsInGrowthRoutes() {
        val caps = OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/growth", "/claim", "/claim-pro"))
        assertEquals(setOf("growth", "claim", "claim-pro"), caps.growthRoutes)
    }

    @Test
    fun growthFlagFollowsAnyClaimRoute() {
        // 只有 claim-pro 的平台也算有活动领取，否则界面不给入口。
        assertTrue(OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/claim-pro")).growth)
        assertFalse(OAuthAccounts.capabilitiesFrom(setOf("/accounts")).growth)
    }

    @Test
    fun enableDisableNeedsBothRoutes() {
        assertFalse(OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/enable")).enableDisable)
        assertTrue(OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/enable", "/disable")).enableDisable)
    }

    @Test
    fun unfreezeAlsoCountsAsClearCooldown() {
        assertTrue(OAuthAccounts.capabilitiesFrom(setOf("/accounts", "/unfreeze")).clearCooldown)
    }

    @Test
    fun singleGlobalCheckinIsNotFailure() {
        val res =
            JSONObject(
                """{"results":[{"success":false,"reason":"global","message":"国际版账号不支持签到"}]}""",
            )
        val out = OAuthAccounts.checkinOutcome(res)
        assertTrue("global 属于不适用，不是失败", out.ok)
        assertEquals("国际版账号不支持签到", out.message)
    }

    @Test
    fun singleAndBatchAgreeOnGlobal() {
        val single =
            OAuthAccounts.checkinOutcome(JSONObject("""{"results":[{"success":false,"reason":"global"}]}"""))
        val batch =
            OAuthAccounts.checkinOutcome(
                JSONObject("""{"results":[{"reason":"global"},{"reason":"global"}],"summary":{"skipped_global":2,"fail":0}}"""),
            )
        assertTrue(single.ok)
        assertTrue(batch.ok)
        assertEquals(single.ok, batch.ok)
    }

    @Test
    fun singleAlreadyIsNotFailure() {
        val out = OAuthAccounts.checkinOutcome(JSONObject("""{"results":[{"reason":"already"}]}"""))
        assertTrue(out.ok)
        assertEquals("今天已经签过了", out.message)
    }

    @Test
    fun realFailureStaysFailure() {
        val out = OAuthAccounts.checkinOutcome(JSONObject("""{"results":[{"success":false,"message":"上游拒绝"}]}"""))
        assertFalse(out.ok)
        assertEquals("上游拒绝", out.message)
    }

    @Test
    fun globalWithExplicitErrorIsStillFailure() {
        // 插件既标了 global 又给了 error：error 优先，不能因为 reason 就把真失败吞掉。
        val out =
            OAuthAccounts.checkinOutcome(
                JSONObject("""{"results":[{"reason":"global","error":"no matching account"}]}"""),
            )
        assertFalse(out.ok)
    }
}