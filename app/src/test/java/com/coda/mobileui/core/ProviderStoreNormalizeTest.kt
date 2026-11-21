package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 端点归一化回归测试。
 *
 * 覆盖近期修复的两类线上问题：把整条接口地址贴进输入框（会拼出重复路径），
 * 以及 Anthropic 端点自带 `/v1` 但内核还会再补一层（拼成 `/v1/v1/messages`）。
 */
class ProviderStoreNormalizeTest {
    @Test
    fun stripsChatCompletionsSuffix() {
        assertEquals(
            "https://api.openai.com/v1",
            ProviderStore.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
    }

    @Test
    fun addsVersionForBareOpenAiHost() {
        assertEquals(
            "https://api.openai.com/v1",
            ProviderStore.normalizeBaseUrl("https://api.openai.com"),
        )
    }

    @Test
    fun keepsVersionForOpenAiCompatibleEndpoint() {
        assertEquals(
            "https://gateway.example.com/v1",
            ProviderStore.normalizeBaseUrl("https://gateway.example.com/v1"),
        )
    }

    @Test
    fun stripsVersionForAnthropicFullEndpoint() {
        assertEquals(
            "https://api.anthropic.com",
            ProviderStore.normalizeBaseUrl("https://api.anthropic.com/v1/messages", "anthropic-messages"),
        )
    }

    @Test
    fun stripsVersionForAnthropicProxyRoot() {
        assertEquals(
            "https://proxy.example.com/anthropic",
            ProviderStore.normalizeBaseUrl("https://proxy.example.com/anthropic/v1", "anthropic-messages"),
        )
    }

    @Test
    fun acceptsLegacyAnthropicType() {
        assertEquals(
            "https://api.anthropic.com",
            ProviderStore.normalizeBaseUrl("https://api.anthropic.com/v1", "anthropic"),
        )
    }

    @Test
    fun addsHttpForLocalGateway() {
        assertEquals(
            "http://127.0.0.1:8317/v1",
            ProviderStore.normalizeBaseUrl("127.0.0.1:8317/v1/"),
        )
    }

    @Test
    fun stripsModelsAndTranscriptionSuffixes() {
        assertEquals(
            "https://api.example.com/v1",
            ProviderStore.normalizeBaseUrl("https://api.example.com/v1/models"),
        )
        assertEquals(
            "https://api.example.com/v1",
            ProviderStore.normalizeBaseUrl("https://api.example.com/v1/audio/transcriptions"),
        )
    }
}