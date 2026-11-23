package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 分块执行器的失败语义（#807、#814、#815）。
 *
 * 用一个只实现必要方法的 HttpURLConnection 替身喂响应，把三条真机上极难复现的
 * 路径钉住：块失败不能污染调用方的取消令牌、非 206 不能算成功、
 * 续传前必须确认 part 文件的真实长度。
 */
class ChunkedFetchGuardTest {
    /** 只回答状态码、Content-Range 与 body 的假连接。 */
    private class FakeConn(
        private val code: Int,
        private val contentRange: String?,
        private val body: ByteArray,
    ) : HttpURLConnection(URL("https://example.invalid/f")) {
        var disconnected = false

        override fun getResponseCode(): Int = code

        override fun getHeaderField(name: String?): String? = if (name.equals("Content-Range", ignoreCase = true)) contentRange else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)

        override fun getErrorStream(): InputStream? = if (code >= 400) ByteArrayInputStream(ByteArray(0)) else null

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun connect() {}
    }

    private fun tmpDir(): File = Files.createTempDirectory("coda-chunkguard").toFile()

    @Test
    fun `块失败不取消调用方的令牌`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        val cancel = Downloader.Cancel()
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 200,
                cancel = cancel,
                progress = null,
                // 两块都回 503：镜像挂了，不是用户取消。
                open = { _, _ -> FakeConn(503, null, ByteArray(0)) },
            )
        assertEquals(DownloadOutcome.Kind.SERVER_ERROR, out.kind)
        // 令牌是调用方的资产：镜像回退循环还要用它跑下一个源。
        assertFalse("调用方令牌被块失败污染了", cancel.isCancelled)
        dir.deleteRecursively()
    }

    @Test
    fun `HTTP 200 的块响应不算成功`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        // 源忽略 Range 回了整份 body：照写会把整个文件塞进某一块的偏移。
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 200,
                cancel = null,
                progress = null,
                open = { _, _ -> FakeConn(200, null, ByteArray(200) { 9 }) },
            )
        assertNotEquals(DownloadOutcome.Kind.OK, out.kind)
        assertTrue(DownloadOutcome.shouldTryNextMirror(out.kind))
        // 必须带上「不支持 Range」标志：调用方据此退回单连接而不是白换一圈镜像。
        assertTrue("缺少 rangeUnsupported 标志", out.rangeUnsupported)
        dir.deleteRecursively()
    }

    @Test
    fun `镜像 5xx 不算不支持 Range`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = AtomicLongArray(chunks.size),
                total = 200,
                cancel = null,
                progress = null,
                open = { _, _ -> FakeConn(503, null, ByteArray(0)) },
            )
        // 503 是镜像挂了，换源有意义；不能误判成「这个源不支持 Range」。
        assertFalse(out.rangeUnsupported)
        dir.deleteRecursively()
    }

    @Test
    fun `用户取消不带不支持 Range 标志`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val cancel = Downloader.Cancel()
        cancel.cancel()
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = AtomicLongArray(chunks.size),
                total = 200,
                cancel = cancel,
                progress = null,
                open = { from, to -> FakeConn(206, "bytes $from-$to/200", ByteArray((to - from + 1).toInt()) { 1 }) },
            )
        assertFalse(out.rangeUnsupported)
        dir.deleteRecursively()
    }

    @Test
    fun `全部块拿到 206 才算完成`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val data = ByteArray(200) { (it % 251).toByte() }
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 200,
                cancel = null,
                progress = null,
                open = { from, to ->
                    FakeConn(
                        206,
                        "bytes $from-$to/200",
                        data.copyOfRange(from.toInt(), to.toInt() + 1),
                    )
                },
            )
        assertEquals(DownloadOutcome.Kind.OK, out.kind)
        assertEquals(200L, out.bytes)
        assertTrue(part.readBytes().contentEquals(data))
        dir.deleteRecursively()
    }

    @Test
    fun `用户取消令牌仍然生效`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        val cancel = Downloader.Cancel()
        cancel.cancel()
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 200,
                cancel = cancel,
                progress = null,
                open = { from, to -> FakeConn(206, "bytes $from-$to/200", ByteArray((to - from + 1).toInt()) { 1 }) },
            )
        assertEquals(DownloadOutcome.Kind.CANCELLED, out.kind)
        dir.deleteRecursively()
    }

    @Test
    fun `续传前 part 长度不符就丢弃已有进度`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val total = 200L
        val chunks = ChunkPlan.split(total = total, concurrency = 2)
        // 模拟缓存清理把 part 截短：done 声称各块都下了一半。
        java.io.RandomAccessFile(part, "rw").use { it.setLength(50) }
        val done = AtomicLongArray(chunks.size)
        done.set(0, 50)
        done.set(1, 50)
        val prepared = ChunkedFetch.prepare(part, total, done)
        assertTrue("长度不符时必须重置进度", prepared)
        assertEquals(0L, done.get(0))
        assertEquals(0L, done.get(1))
        dir.deleteRecursively()
    }

    @Test
    fun `part 不存在时重置进度`() {
        val dir = tmpDir()
        val part = File(dir, "absent.part")
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        done.set(0, 100)
        assertTrue(ChunkedFetch.prepare(part, 200, done))
        assertEquals(0L, done.get(0))
        dir.deleteRecursively()
    }

    @Test
    fun `part 长度正好时保留进度`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        java.io.RandomAccessFile(part, "rw").use { it.setLength(200) }
        val chunks = ChunkPlan.split(total = 200, concurrency = 2)
        val done = AtomicLongArray(chunks.size)
        done.set(0, 100)
        done.set(1, 40)
        assertFalse(ChunkedFetch.prepare(part, 200, done))
        assertEquals(100L, done.get(0))
        assertEquals(40L, done.get(1))
        dir.deleteRecursively()
    }

    @Test
    fun `run 自己也会校验 part 长度`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val total = 200L
        val chunks = ChunkPlan.split(total = total, concurrency = 2)
        // part 被截短但 done 声称第一块已完成：不重置的话第一块会被整块跳过，
        // 留下的零洞长度看起来完全正确。
        java.io.RandomAccessFile(part, "rw").use { it.setLength(60) }
        val done = AtomicLongArray(chunks.size)
        done.set(0, 100)
        val data = ByteArray(200) { (it % 251).toByte() }
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = total,
                cancel = null,
                progress = null,
                open = { from, to ->
                    FakeConn(206, "bytes $from-$to/200", data.copyOfRange(from.toInt(), to.toInt() + 1))
                },
            )
        assertEquals(DownloadOutcome.Kind.OK, out.kind)
        assertTrue("零洞被缝进了成品", part.readBytes().contentEquals(data))
        dir.deleteRecursively()
    }
}
