package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新的信任边界。
 *
 * 清单与 sha256 同源就不可信：镜像能同时改写下载地址、摘要与版本号，
 * 客户端「校验自洽」照样通过。所以清单只能来自官方域名，签名指纹必须比对。
 */
class UpdateTrustTest {
    private val pin = "A".repeat(64)

    @Test
    fun `official https hosts are trusted for the manifest`() {
        assertTrue(UpdateTrust.trustedManifestUrl("https://github.com/o/r/releases/download/v1/m.json"))
        assertTrue(UpdateTrust.trustedManifestUrl("https://api.github.com/repos/o/r/releases/latest"))
        assertTrue(UpdateTrust.trustedManifestUrl("https://raw.githubusercontent.com/o/r/main/m.json"))
    }

    @Test
    fun `third party mirrors are not trusted for the manifest`() {
        assertFalse(UpdateTrust.trustedManifestUrl("https://ghproxy.net/https://github.com/o/r/m.json"))
        assertFalse(UpdateTrust.trustedManifestUrl("https://mirror.example.com/m.json"))
    }

    @Test
    fun `plain http and malformed urls are rejected`() {
        assertFalse(UpdateTrust.trustedManifestUrl("http://github.com/o/r/m.json"))
        assertFalse(UpdateTrust.trustedManifestUrl("not a url"))
    }

    @Test
    fun `lookalike host is rejected`() {
        assertFalse(UpdateTrust.trustedManifestUrl("https://github.com.evil.test/m.json"))
    }

    @Test
    fun `fingerprint normalization accepts colon separated form`() {
        val colon = (1..32).joinToString(":") { "AA" }
        assertEquals(pin, UpdateTrust.normalizeFingerprint(colon))
        assertEquals(pin, UpdateTrust.normalizeFingerprint(pin.lowercase()))
    }

    @Test
    fun `invalid fingerprint is rejected`() {
        assertNull(UpdateTrust.normalizeFingerprint("AA:BB"))
        assertNull(UpdateTrust.normalizeFingerprint("Z".repeat(64)))
        assertNull(UpdateTrust.normalizeFingerprint(null))
    }

    @Test
    fun `signer must match the pin`() {
        assertTrue(UpdateTrust.signerMatches(pin, pin.lowercase()))
        assertFalse(UpdateTrust.signerMatches(pin, "B".repeat(64)))
    }

    @Test
    fun `missing pin never allows installation`() {
        assertFalse(UpdateTrust.signerMatches(null, pin))
        assertFalse(UpdateTrust.signerMatches("", pin))
        assertFalse(UpdateTrust.signerMatches(pin, null))
    }
}