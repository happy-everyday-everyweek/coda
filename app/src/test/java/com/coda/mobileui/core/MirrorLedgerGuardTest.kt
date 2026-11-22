package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像与更新链路的纯逻辑守卫。
 *
 * 对应 issue：#242（? 结尾前缀不补斜杠）、#243（直连判定用 startsWith）、
 * #292（评分时间衰减）、#294（VPN 归类）、#404（清单字段校验）、
 * #406（响应体读取上限）、#410（更新包缓存保留）。
 */
class MirrorLedgerGuardTest {
    // ------------------------------------------------------------ #242
    @Test
    fun querySuffixPrefixIsNotGivenASlash() {
        assertEquals("https://down.npee.cn/?", GhMirrors.normalizePrefix("https://down.npee.cn/?"))
        assertEquals("https://m.example/?u=", GhMirrors.normalizePrefix("https://m.example/?u="))
        assertEquals("https://m.example/", GhMirrors.normalizePrefix("https://m.example"))
        assertEquals("https://m.example/", GhMirrors.normalizePrefix("https://m.example/"))
    }

    @Test
    fun npeeMirrorWrapsWithoutSlashAfterQueryMark() {
        val npee = GhMirrors.DEFAULT.first { it.id == "npee" }
        val raw = "https://github.com/o/r/releases/download/v1/a.apk"
        assertEquals(npee.prefix + raw, GhMirrors.wrap(npee.prefix, raw))
        assertTrue(!GhMirrors.wrap(npee.prefix, raw).contains("?/"))
    }

    // ------------------------------------------------------------ #243
    @Test
    fun wrapOnlyTreatsRealDirectPrefixAsDirect() {
        val raw = "https://github.com/o/r/archive/main.zip"
        // 真正的直连前缀：原样返回。
        assertEquals(raw, GhMirrors.wrap("https://github.com/", raw))
        // 代理参数里带 github.com 的前缀不能被误判成直连。
        val proxy = "https://p.example/?u=https://github.com/"
        assertEquals(proxy + raw, GhMirrors.wrap(proxy, raw))
        // 含 github.com 子串的其他主机也不能被误判。
        val cdn = "https://cdn.github.com.example.net/"
        assertEquals(cdn + raw, GhMirrors.wrap(cdn, raw))
    }

    // ------------------------------------------------------------ #292
    @Test
    fun failurePenaltyDecaysWithAge() {
        val now = 1_000_000_000_000L
        val day = 24L * 3600 * 1000
        val fresh = MirrorScoreboard.Stat("m", failed = 3, lastFailMs = now)
        val old = MirrorScoreboard.Stat("m", failed = 3, lastFailMs = now - 60 * day)
        val freshScore = MirrorScoreboard.scoreOf(fresh, now)
        val oldScore = MirrorScoreboard.scoreOf(old, now)
        assertTrue("旧失败应该比新失败扣分少：$oldScore vs $freshScore", oldScore > freshScore)
        assertTrue("久远失败应几乎不再扣分", oldScore > -50.0)
    }

    @Test
    fun verifiedCreditDoesNotGrowForever() {
        val now = 1_000_000_000_000L
        val few = MirrorScoreboard.Stat("m", verified = 3, lastOkMs = now)
        val tons = MirrorScoreboard.Stat("m", verified = 3000, lastOkMs = now)
        val diff = MirrorScoreboard.scoreOf(tons, now) - MirrorScoreboard.scoreOf(few, now)
        // 线性计分下 3000 vs 3 次会差出约 300 万分；对数压缩后差距应在数千分以内。
        assertTrue("历史成功计数不能无限膨胀压死其他镜像，diff=$diff", diff < 10_000.0)
    }

    @Test
    fun statWithoutTimestampsKeepsLegacyBehaviour() {
        val stat = MirrorScoreboard.Stat("m", verified = 2)
        // 旧账本没有时间戳：不衰减，保持既有测试语义（2 次成功 > 5 次失败）。
        assertTrue(MirrorScoreboard.scoreOf(stat) > 0)
        val bad = MirrorScoreboard.Stat("m", failed = 5)
        assertTrue(MirrorScoreboard.scoreOf(bad) < 0)
    }

    // ------------------------------------------------------------ #294
    @Test
    fun vpnResolvesToUnderlyingTransport() {
        assertEquals(
            MirrorScoreboard.Net.WIFI,
            MirrorScoreboard.netOf(wifi = false, cellular = false, vpn = true, underlyingWifi = true, underlyingCellular = false),
        )
        assertEquals(
            MirrorScoreboard.Net.CELLULAR,
            MirrorScoreboard.netOf(wifi = false, cellular = false, vpn = true, underlyingWifi = false, underlyingCellular = true),
        )
        assertEquals(
            MirrorScoreboard.Net.OTHER,
            MirrorScoreboard.netOf(wifi = false, cellular = false, vpn = true, underlyingWifi = false, underlyingCellular = false),
        )
        // 直连判定优先于 VPN 逻辑。
        assertEquals(
            MirrorScoreboard.Net.WIFI,
            MirrorScoreboard.netOf(wifi = true, cellular = false, vpn = false, underlyingWifi = false, underlyingCellular = false),
        )
    }

    // ------------------------------------------------------------ #404
    @Test
    fun manifestRejectsNegativeSize() {
        val json = org.json.JSONObject(
            """{"versionCode":30,"apkUrl":"https://github.com/o/r/a.apk","size":-5,"sha256":"${"a".repeat(64)}"}""",
        )
        assertNull(UpdateChecker.parseManifest(json, "dev"))
    }

    @Test
    fun manifestRejectsMalformedSha256() {
        for (bad in listOf("xyz", "a".repeat(63), "g".repeat(64), "a".repeat(65))) {
            val json = org.json.JSONObject(
                """{"versionCode":30,"apkUrl":"https://github.com/o/r/a.apk","size":10,"sha256":"$bad"}""",
            )
            assertNull("sha256=$bad 应被拒绝", UpdateChecker.parseManifest(json, "dev"))
        }
    }

    @Test
    fun manifestNormalizesUppercaseSha256() {
        val json = org.json.JSONObject(
            """{"versionCode":30,"apkUrl":"https://github.com/o/r/a.apk","size":10,"sha256":"${"AB".repeat(32)}"}""",
        )
        val r = UpdateChecker.parseManifest(json, "dev")
        assertEquals("ab".repeat(32), r?.sha256)
    }

    @Test
    fun manifestWithoutSha256StillParsesForApiFallback() {
        val json = org.json.JSONObject(
            """{"versionCode":30,"apkUrl":"https://github.com/o/r/a.apk"}""",
        )
        val r = UpdateChecker.parseManifest(json, "dev")
        assertNotEquals(null, r)
        assertNull(r!!.sha256)
    }

    // ------------------------------------------------------------ #406
    @Test
    fun boundedReadStopsAtLimit() {
        val limit = 16
        val small = "0123456789".reader()
        assertEquals("0123456789", UpdateChecker.readBounded(small, limit))
        val exact = "0123456789abcdef".reader()
        assertEquals("0123456789abcdef", UpdateChecker.readBounded(exact, limit))
        val huge = "x".repeat(1024).reader()
        assertNull("超限响应必须判为无效而不是读完", UpdateChecker.readBounded(huge, limit))
    }

    // ------------------------------------------------------------ #410
    @Test
    fun cleanupKeepsDownloadedNewerPackages() {
        // 只保留已安装版本会把「下载好还没装」的包全部删掉，缓存永不命中。
        assertTrue(UpdateInstaller.shouldKeepApk("coda-29.apk", keepVersionCode = 28))
        assertTrue(UpdateInstaller.shouldKeepApk("coda-28.apk", keepVersionCode = 28))
        assertTrue(!UpdateInstaller.shouldKeepApk("coda-27.apk", keepVersionCode = 28))
        assertTrue(!UpdateInstaller.shouldKeepApk("random.apk", keepVersionCode = 28))
        assertTrue(!UpdateInstaller.shouldKeepApk("coda-abc.apk", keepVersionCode = 28))
    }
}