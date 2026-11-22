package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关信息回填的合并语义（#318）。
 *
 * refreshStoredModels / ensureStoredGateways 都可能在几秒的 HTTP 期间与设置页的保存并发。
 * 整份快照回写会把并发方的改动吞掉，所以回写必须只覆盖本次真正算出的字段。
 * 这里断言合并函数只动目标字段，其它字段一律以磁盘上的最新值为准。
 */
class OAuthLoginMergeTest {
    private fun provider(
        id: String,
        models: List<String> = emptyList(),
        baseUrl: String = "http://127.0.0.1:8317/v1",
        apiKey: String = "old-key",
        name: String = "原名",
    ) = ProviderStore.Provider(
        id = id,
        name = name,
        apiType = "openai-chat-completions",
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models.toMutableList(),
    )

    @Test
    fun modelsMergeOnlyTouchesTargetProvider() {
        val current = mutableListOf(provider("cursor"), provider("zcode", listOf("zc-a")))
        val touched = OAuthLogin.mergeModels(current, mapOf("cursor" to listOf("cu-1", "cu-2")))
        assertEquals(1, touched)
        assertEquals(listOf("cu-1", "cu-2"), current.first { it.id == "cursor" }.models)
        assertEquals(listOf("zc-a"), current.first { it.id == "zcode" }.models)
    }

    @Test
    fun modelsMergeKeepsConcurrentEditsToOtherFields() {
        // 并发方在刷新期间改了名字与端点：合并只写 models，这些改动必须留下。
        val current = mutableListOf(provider("cursor", name = "用户改过的名字", baseUrl = "http://127.0.0.1:19999/v1"))
        OAuthLogin.mergeModels(current, mapOf("cursor" to listOf("cu-1")))
        val p = current.first()
        assertEquals("用户改过的名字", p.name)
        assertEquals("http://127.0.0.1:19999/v1", p.baseUrl)
        assertEquals(listOf("cu-1"), p.models)
    }

    @Test
    fun modelsMergeSkipsProvidersRemovedConcurrently() {
        val current = mutableListOf(provider("zcode"))
        assertEquals(0, OAuthLogin.mergeModels(current, mapOf("cursor" to listOf("cu-1"))))
        assertEquals(1, current.size)
    }

    @Test
    fun modelsMergeDoesNotRewriteIdenticalList() {
        val current = mutableListOf(provider("cursor", listOf("cu-1", "cu-2")))
        assertEquals(0, OAuthLogin.mergeModels(current, mapOf("cursor" to listOf("cu-1", "cu-2"))))
    }

    @Test
    fun modelsMergeIgnoresEmptyLiveList() {
        val current = mutableListOf(provider("cursor", listOf("cu-1")))
        assertEquals(0, OAuthLogin.mergeModels(current, mapOf("cursor" to emptyList())))
        assertEquals(listOf("cu-1"), current.first().models)
    }

    @Test
    fun modelsMergeDeduplicates() {
        val current = mutableListOf(provider("cursor"))
        OAuthLogin.mergeModels(current, mapOf("cursor" to listOf("cu-1", "cu-1", "cu-2")))
        assertEquals(listOf("cu-1", "cu-2"), current.first().models)
    }

    @Test
    fun gatewayMergeOnlyTouchesEndpointAndKey() {
        val current = mutableListOf(provider("cursor", listOf("cu-1"), name = "改过的名字"))
        val touched =
            OAuthLogin.mergeGateway(
                current,
                mapOf("cursor" to ("http://127.0.0.1:18317/v1" to "new-key")),
            )
        assertEquals(1, touched)
        val p = current.first()
        assertEquals("http://127.0.0.1:18317/v1", p.baseUrl)
        assertEquals("new-key", p.apiKey)
        assertEquals("改过的名字", p.name)
        assertEquals(listOf("cu-1"), p.models)
    }

    @Test
    fun gatewayMergeIsNoopWhenAlreadyCurrent() {
        val current = mutableListOf(provider("cursor"))
        assertEquals(
            0,
            OAuthLogin.mergeGateway(current, mapOf("cursor" to ("http://127.0.0.1:8317/v1" to "old-key"))),
        )
    }

    @Test
    fun gatewayMergeSkipsMissingProvider() {
        val current = mutableListOf(provider("zcode"))
        assertEquals(0, OAuthLogin.mergeGateway(current, mapOf("cursor" to ("u" to "k"))))
    }

    @Test
    fun needsModelRefreshDetectsForeignPrefix() {
        // 历史串号：Cursor 的配置里存了 cb- 开头的模型，必须判定为需要纠正。
        assertTrue(OAuthLogin.needsModelRefresh(listOf("cb-a"), listOf("cu-1"), "cu-"))
        assertFalse(OAuthLogin.needsModelRefresh(listOf("cu-1"), listOf("cu-1"), "cu-"))
        assertTrue(OAuthLogin.needsModelRefresh(emptyList(), listOf("cu-1"), "cu-"))
    }

    @Test
    fun needsModelRefreshKeepsUnprefixedListThatMatchesGateway() {
        // 有些插件不带前缀，内容与网关一致时不该每次冷启动都重写。
        assertFalse(OAuthLogin.needsModelRefresh(listOf("gpt-x"), listOf("gpt-x"), "cu-"))
    }

    @Test
    fun needsModelRefreshIgnoresEmptyPrefix() {
        assertFalse(OAuthLogin.needsModelRefresh(listOf("a"), listOf("b"), ""))
    }
}