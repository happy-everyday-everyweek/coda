package com.coda.mobileui.core
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力探测缓存的准入（#748）。
 *
 * 404/超时得到的全 false 是负结果/部分结果，缓存它们会把「暂不支持」
 * 钉死到重启；只有完整探测出的正结果才值得缓存。
 */
class OAuthCapabilityCacheTest {
    private fun caps(
        panel: Boolean = false,
        checkin: Boolean = false,
        growth: Boolean = false,
        refresh: Boolean = false,
        keepalive: Boolean = false,
    ) = OAuthAccounts.Capabilities(panel, checkin, growth, refresh, keepalive)

    @Test
    fun `全 false 的负结果不缓存`() {
        assertFalse(OAuthAccounts.cacheable(reachable = true, caps = caps()))
    }

    @Test
    fun `超时短路的部分结果不缓存`() {
        // reachable=false 时哪怕已探到一项能力也不缓存：探测没做完。
        assertFalse(OAuthAccounts.cacheable(reachable = false, caps = caps(panel = true)))
    }

    @Test
    fun `完整正结果可缓存`() {
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(panel = true)))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(checkin = true)))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(keepalive = true)))
    }
}
