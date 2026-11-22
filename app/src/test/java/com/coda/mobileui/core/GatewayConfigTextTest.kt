package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关配置日志脱敏（#436）与探测端口选择（#427/#428）的纯逻辑测试。
 *
 * 这两段原本埋在 WorkBuddyGateway 的启动流程里，只能靠真机 proot 才能触发；
 * 抽成不依赖 Context 的函数后，脱敏是否漏字段、bootstrap 端口是否真的错开，都能在 JVM 上断言。
 */
class GatewayConfigTextTest {
    @Test
    fun masksApiKey() {
        val out = GatewayConfigText.redact("""{"api_key": "sk-real-secret", "listen": "127.0.0.1:8123"}""")
        assertFalse(out.contains("sk-real-secret"))
        assertTrue(out.contains("127.0.0.1:8123"))
    }

    @Test
    fun masksTomlStyleAssignment() {
        val out = GatewayConfigText.redact("api_key = \"sk-toml\"\nlisten = \"127.0.0.1:8123\"")
        assertFalse(out.contains("sk-toml"))
        assertTrue(out.contains("listen = \"127.0.0.1:8123\""))
    }

    @Test
    fun masksOtherCredentialFields() {
        val src = """{"token":"t1","secret":"s1","access_key":"a1","client_secret":"c1","password":"p1"}"""
        val out = GatewayConfigText.redact(src)
        for (v in listOf("t1", "s1", "a1", "c1", "p1")) assertFalse("leaked $v", out.contains(v))
    }

    @Test
    fun keepsNonSecretFieldsUntouched() {
        val src = """{"listen":"127.0.0.1:8123","auth_dir":"/root/auth","state_file":"/root/data/state.json"}"""
        assertEquals(src, GatewayConfigText.redact(src))
    }

    @Test
    fun doesNotMaskFieldsThatMerelyContainKeyAsSubstring() {
        // monkey / keyboard 这类字段不是凭据，不能因为名字里有 key 就被打掉。
        val src = """{"monkey":"yes","keyboard_layout":"us"}"""
        assertEquals(src, GatewayConfigText.redact(src))
    }

    @Test
    fun maskIsStableForRepeatedCallsAndEmptyInput() {
        assertEquals("", GatewayConfigText.redact(""))
        val src = """{"api_key":"k"}"""
        assertEquals(GatewayConfigText.redact(src), GatewayConfigText.redact(src))
    }

    @Test
    fun redactedPreviewRespectsLimit() {
        val src = "a".repeat(2000)
        assertEquals(600, GatewayConfigText.preview(src, 600).length)
    }

    @Test
    fun bootstrapPortNeverEqualsGatewayPort() {
        // #428：bootstrap 必须错开真实端口，否则两个进程抢同一个端口。
        for (p in intArrayOf(8123, 8124, 8125, 65535)) {
            val b = GatewayConfigText.bootstrapPort(p)
            assertTrue("bootstrap $b collided with $p", b != p)
            assertTrue(b in 1..65535)
        }
    }

    @Test
    fun bootstrapPortIsDeterministic() {
        assertEquals(GatewayConfigText.bootstrapPort(8123), GatewayConfigText.bootstrapPort(8123))
    }

    @Test
    fun bootstrapListenSeedsPortIntoConfig() {
        // bootstrap 的临时配置必须带上 listen，不能只靠 -config 让程序用默认端口。
        val seeded = GatewayConfigText.seedListen("""{"api_key":"x"}""", 45999)
        assertTrue(seeded.contains("45999"))
        assertTrue(seeded.contains("127.0.0.1"))
    }

    @Test
    fun seedListenOverwritesExistingListen() {
        val seeded = GatewayConfigText.seedListen("""{"listen":"127.0.0.1:8123","api_key":"x"}""", 45999)
        assertFalse(seeded.contains("8123"))
        assertTrue(seeded.contains("45999"))
    }

    @Test
    fun seedListenFallsBackToRawTextWhenNotJson() {
        val raw = "not json at all"
        assertEquals(raw, GatewayConfigText.seedListen(raw, 45999))
    }
}