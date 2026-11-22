package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载失败分类与内容校验判定（#729）。
 *
 * 这些判定以前散在 AssetFetcher 的 if/else 与 UpdateChecker 的 catch 里，
 * 只能靠真机联网触发。抽成纯函数后，「403 该换源」「取消不该换源」
 * 「大小对但 hash 不对必须判坏」这些约定可以直接钉住。
 */
class DownloadOutcomeTest {
    @Test
    fun `2xx 全部算成功`() {
        assertEquals(DownloadOutcome.Kind.OK, DownloadOutcome.ofHttp(200))
        assertEquals(DownloadOutcome.Kind.OK, DownloadOutcome.ofHttp(204))
        assertEquals(DownloadOutcome.Kind.OK, DownloadOutcome.ofHttp(206))
        assertEquals(DownloadOutcome.Kind.OK, DownloadOutcome.ofHttp(299))
    }

    @Test
    fun `401 与 403 是镜像拒绝`() {
        assertEquals(DownloadOutcome.Kind.REJECTED, DownloadOutcome.ofHttp(401))
        assertEquals(DownloadOutcome.Kind.REJECTED, DownloadOutcome.ofHttp(403))
    }

    @Test
    fun `404 是这个源上没有文件`() {
        assertEquals(DownloadOutcome.Kind.MISSING, DownloadOutcome.ofHttp(404))
    }

    @Test
    fun `5xx 是镜像自己挂了`() {
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, DownloadOutcome.ofHttp(500))
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, DownloadOutcome.ofHttp(502))
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, DownloadOutcome.ofHttp(503))
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, DownloadOutcome.ofHttp(599))
    }

    @Test
    fun `其它状态码归入未知 HTTP 失败而不是成功`() {
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, DownloadOutcome.ofHttp(301))
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, DownloadOutcome.ofHttp(400))
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, DownloadOutcome.ofHttp(429))
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, DownloadOutcome.ofHttp(600))
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, DownloadOutcome.ofHttp(0))
    }

    @Test
    fun `没给期望值时两项校验都跳过`() {
        assertEquals(
            DownloadOutcome.Kind.OK,
            DownloadOutcome.verify(actualSize = 123, expectedSize = 0, actualSha256 = null, expectedSha256 = null),
        )
        assertEquals(
            DownloadOutcome.Kind.OK,
            DownloadOutcome.verify(actualSize = 123, expectedSize = -1, actualSha256 = "abc", expectedSha256 = "  "),
        )
    }

    @Test
    fun `长度不符直接判坏`() {
        assertEquals(
            DownloadOutcome.Kind.VERIFY_FAILED,
            DownloadOutcome.verify(actualSize = 100, expectedSize = 101, actualSha256 = null, expectedSha256 = null),
        )
    }

    @Test
    fun `长度对但 hash 不符仍判坏`() {
        // 真实踩过：镜像返回字节数完全一致、内容被改写的 APK。
        assertEquals(
            DownloadOutcome.Kind.VERIFY_FAILED,
            DownloadOutcome.verify(
                actualSize = 219_309_172,
                expectedSize = 219_309_172,
                actualSha256 = "aa",
                expectedSha256 = "bb",
            ),
        )
    }

    @Test
    fun `给了期望 hash 但实际算不出来时判坏`() {
        assertEquals(
            DownloadOutcome.Kind.VERIFY_FAILED,
            DownloadOutcome.verify(actualSize = 10, expectedSize = 10, actualSha256 = null, expectedSha256 = "bb"),
        )
        assertEquals(
            DownloadOutcome.Kind.VERIFY_FAILED,
            DownloadOutcome.verify(actualSize = 10, expectedSize = 10, actualSha256 = " ", expectedSha256 = "bb"),
        )
    }

    @Test
    fun `hash 比对忽略大小写与首尾空白由调用方处理`() {
        assertEquals(
            DownloadOutcome.Kind.OK,
            DownloadOutcome.verify(actualSize = 10, expectedSize = 10, actualSha256 = "AbCd", expectedSha256 = "aBcD"),
        )
    }

    @Test
    fun `成功与取消都不换下一个镜像`() {
        assertFalse(DownloadOutcome.shouldTryNextMirror(DownloadOutcome.Kind.OK))
        assertFalse(DownloadOutcome.shouldTryNextMirror(DownloadOutcome.Kind.CANCELLED))
    }

    @Test
    fun `写入失败也不换源`() {
        // 内容已校验通过，换源重下同样写不进去，只是白耗流量。
        assertFalse(DownloadOutcome.shouldTryNextMirror(DownloadOutcome.Kind.PLACE_FAILED))
    }

    @Test
    fun `其余失败都换下一个镜像`() {
        val swap =
            listOf(
                DownloadOutcome.Kind.REJECTED,
                DownloadOutcome.Kind.MISSING,
                DownloadOutcome.Kind.SERVER_ERROR,
                DownloadOutcome.Kind.HTTP_OTHER,
                DownloadOutcome.Kind.IO,
                DownloadOutcome.Kind.VERIFY_FAILED,
            )
        swap.forEach { assertTrue("应换源：$it", DownloadOutcome.shouldTryNextMirror(it)) }
    }
}
