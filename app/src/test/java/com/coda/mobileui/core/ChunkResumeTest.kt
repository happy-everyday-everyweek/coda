package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块断点能不能采纳（#809、#815）。
 *
 * 分块的 part 文件被预分配到全长，`length() == total` 并不代表字节都在：
 * 缓存清理删掉或截短它之后，重新 setLength 会把空洞补成 0，而 done[] 仍声称
 * 那些字节已完成，于是零洞被缝进成品。判定必须把「文件实际在不在、长度对不对」
 * 和 URL/远端标识一起看。
 */
class ChunkResumeTest {
    private val chunks = ChunkPlan.split(total = 1000, concurrency = 2)

    private fun state(
        url: String = "https://a/f",
        total: Long = 1000,
        etag: String? = "\"e1\"",
        lastModified: String? = null,
        ranges: List<ChunkPlan.Chunk> = chunks,
        done: List<Long> = listOf(500L, 100L),
    ) = ResumeState.Companion.ChunkState(url, total, etag, lastModified, ranges, done)

    @Test
    fun `文件在且长度等于总长时才采纳`() {
        val v =
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            )
        assertTrue(v)
    }

    @Test
    fun `part 文件被缓存清理删掉后不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = false,
                partBytes = 0,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `part 被截短后不采纳`() {
        // 长度小于 total：done[] 声称的字节已经不在了，继续用会把零洞当成品。
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 600,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `part 比总长还长也不采纳`() {
        // 多出来的字节说明这份文件不是本次任务的，宁可重下。
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1200,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `URL 变了不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(url = "https://b/f"),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `总长变了不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 2000,
                chunks = ChunkPlan.split(total = 2000, concurrency = 2),
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `分块切法变了不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = ChunkPlan.split(total = 1000, concurrency = 4),
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `远端标识变了不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e2\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `本次没有任何远端标识时不采纳`() {
        // 无法确认远端没变过，续传等于赌运气。
        assertFalse(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = null,
                lastModified = null,
            ),
        )
    }

    @Test
    fun `记录没有远端标识时不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(etag = null, lastModified = null),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = "Mon",
            ),
        )
    }

    @Test
    fun `记录只带 LastModified 且一致时可采纳`() {
        assertTrue(
            ChunkResume.adopt(
                saved = state(etag = null, lastModified = "Mon"),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = null,
                lastModified = "Mon",
            ),
        )
    }

    @Test
    fun `本次多给了一个标识不影响采纳`() {
        // 上次只记到 ETag，这次服务端还给了 Last-Modified：同类型的 ETag 一致就够。
        assertTrue(
            ChunkResume.adopt(
                saved = state(),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = "Tue",
            ),
        )
    }

    @Test
    fun `标识类型错位时不采纳`() {
        // 记录里只有 ETag，本次只拿到 Last-Modified：没有任何同类型项可比。
        // CDN 各边缘节点返回的头部不一致，这种错位是现实场景，
        // 而 sha256 可选，错位的字节会被静默拼成成品。
        assertFalse(
            ChunkResume.adopt(
                saved = state(etag = "\"e1\"", lastModified = null),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = null,
                lastModified = "Tue",
            ),
        )
    }

    @Test
    fun `标识类型反向错位也不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = state(etag = null, lastModified = "Mon"),
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `没有记录时不采纳`() {
        assertFalse(
            ChunkResume.adopt(
                saved = null,
                url = "https://a/f",
                total = 1000,
                chunks = chunks,
                partExists = true,
                partBytes = 1000,
                etag = "\"e1\"",
                lastModified = null,
            ),
        )
    }

    @Test
    fun `非 206 的块响应一律不是成功`() {
        // #814：200 曾被 ofHttp 归成 OK，于是预分配的零文件被当成品放置。
        assertEquals(DownloadOutcome.Kind.IO, ChunkResume.chunkHttpKind(200))
        assertEquals(DownloadOutcome.Kind.IO, ChunkResume.chunkHttpKind(204))
        assertEquals(DownloadOutcome.Kind.REJECTED, ChunkResume.chunkHttpKind(403))
        assertEquals(DownloadOutcome.Kind.MISSING, ChunkResume.chunkHttpKind(404))
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, ChunkResume.chunkHttpKind(503))
        assertEquals(DownloadOutcome.Kind.HTTP_OTHER, ChunkResume.chunkHttpKind(416))
    }

    @Test
    fun `不支持 Range 的结果要换下一个源`() {
        assertTrue(DownloadOutcome.shouldTryNextMirror(ChunkResume.chunkHttpKind(200)))
    }
}
