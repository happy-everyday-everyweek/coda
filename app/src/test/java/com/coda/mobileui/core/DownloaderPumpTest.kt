package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * 下载搬运循环（#729）。
 *
 * pump 以前藏在 AssetFetcher.download 里，只能靠真机联网触发。抽出来后
 * EOF 判定、连续空读上限、取消传播、hash 计算与进度节流都能用内存流钉住。
 */
class DownloaderPumpTest {
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 按给定序列返回读取长度的流：0 表示这次没读到字节，-1 表示 EOF。 */
    private class Scripted(
        private val script: List<Int>,
        private val payload: Byte = 7,
    ) : InputStream() {
        var reads = 0

        override fun read(): Int = payload.toInt()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = if (reads < script.size) script[reads] else -1
            reads++
            if (n > 0) java.util.Arrays.fill(b, off, off + minOf(n, len), payload)
            return n
        }
    }

    @Test
    fun `搬完全部字节并按需算 hash`() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val got =
            Downloader.pump(
                input = ByteArrayInputStream(data),
                out = out,
                total = data.size.toLong(),
                progress = null,
                cancel = null,
                hash = true,
                now = { 0L },
            )
        assertEquals(data.size.toLong(), got.bytes)
        assertEquals(sha(data), got.sha256)
        assertTrue(out.toByteArray().contentEquals(data))
    }

    @Test
    fun `不需要 hash 时不算`() {
        val got =
            Downloader.pump(
                input = ByteArrayInputStream(ByteArray(16)),
                out = ByteArrayOutputStream(),
                total = 16,
                progress = null,
                cancel = null,
                hash = false,
                now = { 0L },
            )
        assertEquals(16L, got.bytes)
        assertNull(got.sha256)
    }

    @Test
    fun `返回 0 不算 EOF，后续字节仍被写入`() {
        // 把 0 当 EOF 会静默截断内容，随后 sha256 必然失败并被误记成镜像的问题。
        val src = Scripted(listOf(0, 0, 10, 0, 5, -1))
        val out = ByteArrayOutputStream()
        val got =
            Downloader.pump(src, out, 15, null, null, true, now = { 0L })
        assertEquals(15L, got.bytes)
        assertEquals(15, out.size())
        assertEquals(sha(ByteArray(15) { 7 }), got.sha256)
    }

    @Test
    fun `连续空读超过上限抛 IO 而不是无限空转`() {
        val src = Scripted(List(200) { 0 })
        try {
            Downloader.pump(src, ByteArrayOutputStream(), 0, null, null, false, now = { 0L })
            fail("应抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("返回 0 字节"))
        }
        // 上限 64：第 65 次空读就该停。
        assertEquals(65, src.reads)
    }

    @Test
    fun `空读计数在成功读取后归零`() {
        val script = MutableList(60) { 0 }
        script.add(4)
        repeat(60) { script.add(0) }
        script.add(-1)
        val got =
            Downloader.pump(Scripted(script), ByteArrayOutputStream(), 4, null, null, false, now = { 0L })
        assertEquals(4L, got.bytes)
    }

    @Test
    fun `取消句柄置位后抛 CancellationException 且不算普通失败`() {
        val cancel = Downloader.Cancel()
        val src = Scripted(listOf(10, 10, 10, -1))
        val out = ByteArrayOutputStream()
        // 第一次读之后取消：用一个包装流在读第二次前置位。
        val wrapped =
            object : InputStream() {
                override fun read(): Int = src.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (src.reads >= 1) cancel.cancel()
                    return src.read(b, off, len)
                }
            }
        try {
            Downloader.pump(wrapped, out, 30, null, cancel, false, now = { 0L })
            fail("应抛 CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("取消"))
        }
        assertEquals(10, out.size())
    }

    @Test
    fun `总长未知时不回调进度，避免调用方除零`() {
        var calls = 0
        Downloader.pump(
            input = ByteArrayInputStream(ByteArray(2_000_000)),
            out = ByteArrayOutputStream(),
            total = 0,
            progress = Downloader.Progress { _, _, _ -> calls++ },
            cancel = null,
            hash = false,
            now = { 0L },
        )
        assertEquals(0, calls)
    }

    @Test
    fun `结束时一定补报一次最终进度`() {
        val seen = mutableListOf<Long>()
        Downloader.pump(
            input = ByteArrayInputStream(ByteArray(100)),
            out = ByteArrayOutputStream(),
            total = 100,
            progress = Downloader.Progress { done, total, _ ->
                assertEquals(100L, total)
                seen.add(done)
            },
            cancel = null,
            hash = false,
            now = { 0L },
        )
        assertEquals(100L, seen.last())
    }

    @Test
    fun `进度按字节或时间任一达标就上报`() {
        // 攒够 1MB：即使时间没走也要报。
        assertTrue(Downloader.shouldReport(done = 1_048_576, lastBytes = 0, nowMs = 10, lastMs = 10))
        // 时间到了 250ms：即使只下了几 KB 也要报，慢连接下进度条才会动。
        assertTrue(Downloader.shouldReport(done = 4096, lastBytes = 0, nowMs = 250, lastMs = 0))
        // 两条都没达标就不报。
        assertFalse(Downloader.shouldReport(done = 4096, lastBytes = 0, nowMs = 249, lastMs = 0))
        assertFalse(Downloader.shouldReport(done = 1_048_575, lastBytes = 0, nowMs = 0, lastMs = 0))
    }

    @Test
    fun `有上限读取在上限内正常返回`() {
        assertEquals("abc", Downloader.readBounded(StringReader("abc"), 10))
        assertEquals("abc", Downloader.readBounded(StringReader("abc"), 3))
    }

    @Test
    fun `超过上限判无效返回 null`() {
        assertNull(Downloader.readBounded(StringReader("abcd"), 3))
    }

    @Test
    fun `读取中途取消抛 CancellationException`() {
        val cancel = Downloader.Cancel()
        cancel.cancel()
        try {
            Downloader.readBounded(StringReader("abc"), 10, cancel)
            fail("应抛 CancellationException")
        } catch (e: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun `取消句柄默认未置位且置位后可读`() {
        val c = Downloader.Cancel()
        assertFalse(c.isCancelled)
        c.cancel()
        assertTrue(c.isCancelled)
    }
}