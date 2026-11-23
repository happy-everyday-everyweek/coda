package com.coda.mobileui.core
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力探测缓存的准入（#748）。
 *
 * 404/超时得到的全 false 是负结果/部分结果，缓存它们会把「暂不支持」
 * 钉死到重启；只有完整探测出的正结果才值得缓存。
 * 准入按 routes 判（OCR r8 medium）：确认到任何路由都算完整正结果，
 * 不逐个枚举布尔旗标——那会随 Capabilities 增长漏字段。
 */
class OAuthCapabilityCacheTest {
    private fun caps(routes: Set<String> = emptySet()) =
        OAuthAccounts.capabilitiesFrom(routes)

    @Test
    fun `全 false 的负结果不缓存`() {
        assertFalse(OAuthAccounts.cacheable(reachable = true, caps = caps()))
    }

    @Test
    fun `超时短路的部分结果不缓存`() {
        // reachable=false 时哪怕已探到一项能力也不缓存：探测没做完。
        assertFalse(OAuthAccounts.cacheable(reachable = false, caps = caps(setOf("/accounts"))))
    }

    @Test
    fun `完整正结果可缓存`() {
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/accounts"))))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/checkin"))))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/keepalive"))))
    }

    @Test
    fun `只实现启停或签到配置的插件也可缓存`() {
        // OCR r8 medium：/enable+/disable、/checkin/config 映射的能力旗标
        // 不在旧的五项枚举里，但它们同样是完整探测出的正结果。
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/enable", "/disable"))))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/checkin/config"))))
        assertTrue(OAuthAccounts.cacheable(reachable = true, caps = caps(setOf("/unfreeze"))))
    }
}