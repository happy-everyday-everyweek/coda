package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OAuth 侧的归属与拒绝规则。 */
class OAuthGuardTest {
    @Test
    fun longestPrefixOwnsTheModel() {
        val prefixes = listOf("trae" to "trae-", "traework" to "traework-")
        assertEquals("traework", OAuthProviders.ownerOf("traework-solo", prefixes))
        assertEquals("trae", OAuthProviders.ownerOf("trae-solo", prefixes))
        assertNull(OAuthProviders.ownerOf("gpt-5", prefixes))
    }

    @Test
    fun emptyPrefixNeverClaimsAnything() {
        val prefixes = listOf("x" to "")
        assertNull(OAuthProviders.ownerOf("anything", prefixes))
    }

    @Test
    fun upsertReplacesSameIdWithoutDuplicating() {
        val list = mutableListOf(provider("a", "旧"), provider("b", "另一个"))
        ProviderStore.upsert(list, provider("a", "新"))
        assertEquals(2, list.size)
        assertEquals("新", list.first { it.id == "a" }.name)
    }

    @Test
    fun accountScopedActionNeedsAnIndex() {
        assertTrue(OAuthActions.rejectsMissingAccountIndex(OAuthActions.Scope.ACCOUNT, ""))
        assertTrue(OAuthActions.rejectsMissingAccountIndex(OAuthActions.Scope.ACCOUNT, "  "))
        assertFalse(OAuthActions.rejectsMissingAccountIndex(OAuthActions.Scope.ACCOUNT, "0"))
        assertFalse(OAuthActions.rejectsMissingAccountIndex(OAuthActions.Scope.PLATFORM, ""))
    }

    private fun provider(
        id: String,
        name: String,
    ) = ProviderStore.Provider(
        id = id,
        name = name,
        apiType = "openai-chat-completions",
        baseUrl = "http://127.0.0.1:1/v1",
        apiKey = "k",
        models = mutableListOf("m"),
    )
}
