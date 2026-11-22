package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth 供应商注册表的约束测试（#345 #346）。
 *
 * 这些约束原先没有任何校验：写错一条表项只会表现为「某家供应商莫名不工作」。
 */
class OAuthProvidersRegistryTest {
    @Test
    fun registryPassesSelfCheck() {
        val issues = OAuthProviders.registryIssues()
        assertTrue("注册表自检未通过：$issues", issues.isEmpty())
    }

    @Test
    fun everyIdResolvesToItself() {
        OAuthProviders.all.forEach { p ->
            assertEquals(p.id, OAuthProviders.byId(p.id)?.id)
        }
    }

    @Test
    fun unknownIdResolvesToNull() {
        assertEquals(null, OAuthProviders.byId("no-such-provider"))
    }

    @Test
    fun workbuddyFamilyUsesDedicatedGateway() {
        assertTrue(OAuthProviders.usesWorkBuddyGateway("workbuddy"))
        assertTrue(OAuthProviders.usesWorkBuddyGateway("workbuddy-ai"))
    }

    @Test
    fun otherProvidersStayOnCliproxy() {
        OAuthProviders.all
            .filter { it.id != "workbuddy" && it.id != "workbuddy-ai" }
            .forEach { assertFalse("${it.id} 不应走 WorkBuddy 网关", OAuthProviders.usesWorkBuddyGateway(it.id)) }
    }

    @Test
    fun onlyTraeworkUsesBrowserLoginSubmit() {
        assertTrue(OAuthProviders.usesBrowserLoginSubmit("traework"))
        OAuthProviders.all
            .filter { it.id != "traework" }
            .forEach { assertFalse("${it.id} 不应走粘贴回调链路", OAuthProviders.usesBrowserLoginSubmit(it.id)) }
    }

    @Test
    fun unknownIdHasNoSpecialCapability() {
        assertFalse(OAuthProviders.usesWorkBuddyGateway("ghost"))
        assertFalse(OAuthProviders.usesBrowserLoginSubmit("ghost"))
    }

    @Test
    fun modelPrefixesAreDistinct() {
        val prefixes = OAuthProviders.all.map { OAuthProviders.modelPrefix(it) }
        assertEquals(prefixes.size, prefixes.toSet().size)
    }

    @Test
    fun assetNamesFollowPluginConvention() {
        OAuthProviders.all.forEach { p ->
            assertTrue("${p.id} 的 asset 应以 pluginId 开头", p.asset.startsWith(p.pluginId + "_"))
            assertTrue("${p.id} 的 asset 应含版本号", p.asset.contains("_" + p.version + "_"))
        }
    }
}