package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号动作的可用性与错误文案。
 *
 * 可用性必须与能力探测一致：声明了却没有分支的动作等于不可达，
 * 只看「有面板」就放出删除入口则会让用户点了必然 404。
 */
class OAuthActionsGuardTest {
    private fun caps(
        routes: Set<String> = emptySet(),
        clearCooldown: Boolean = false,
        unfreeze: Boolean = false,
    ) = OAuthAccounts.Capabilities(
        panel = true,
        checkin = false,
        growth = false,
        refresh = false,
        keepalive = false,
        routes = routes,
        clearCooldown = clearCooldown,
        unfreeze = unfreeze,
    )

    private fun ids(c: OAuthAccounts.Capabilities) = OAuthActions.available(c).map { it.id }

    @Test
    fun `clear cooldown alone is reachable`() {
        val result = ids(caps(clearCooldown = true))
        assertTrue(result.contains("clear-cooldown"))
        assertFalse(result.contains("unfreeze"))
    }

    @Test
    fun `both cooldown actions appear when both are supported`() {
        val result = ids(caps(clearCooldown = true, unfreeze = true))
        assertTrue(result.contains("clear-cooldown"))
        assertTrue(result.contains("unfreeze"))
    }

    @Test
    fun `neither cooldown action appears when unsupported`() {
        val result = ids(caps())
        assertFalse(result.contains("clear-cooldown"))
        assertFalse(result.contains("unfreeze"))
    }

    @Test
    fun `delete needs its route not just a panel`() {
        assertFalse(ids(caps()).contains("delete"))
        assertTrue(ids(caps(routes = setOf("/delete"))).contains("delete"))
    }

    @Test
    fun `no panel means no actions at all`() {
        val none =
            OAuthAccounts.Capabilities(
                panel = false,
                checkin = false,
                growth = false,
                refresh = false,
                keepalive = false,
                routes = setOf("/delete"),
            )
        assertTrue(OAuthActions.available(none).isEmpty())
    }

    @Test
    fun `error text is matched case insensitively`() {
        assertEquals("凭据已失效，需要重新授权", OAuthActions.describe("Unauthorized"))
        assertEquals("凭据已失效，需要重新授权", OAuthActions.describe("Invalid token"))
        assertEquals("该动作在当前插件版本里不可用", OAuthActions.describe("Not Found"))
        assertEquals("没有找到该账号，可能已失效", OAuthActions.describe("No matching account"))
    }

    @Test
    fun `invalid parameter is not mistaken for an expired credential`() {
        val text = OAuthActions.describe("invalid parameter: auth_index")
        assertFalse(text.contains("重新授权"))
        assertTrue(text.contains("参数"))
    }

    @Test
    fun `unknown text is passed through`() {
        assertEquals("something odd", OAuthActions.describe("something odd"))
    }

    @Test
    fun `already checked in is left to the checkin parser`() {
        // describe 不再重复处理签到语义，交给 checkinOutcome 统一解释。
        assertEquals("Already checked in", OAuthActions.describe("Already checked in"))
    }
}
