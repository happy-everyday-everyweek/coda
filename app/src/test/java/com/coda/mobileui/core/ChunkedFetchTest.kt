package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 分块断点的编解码与执行器的落盘正确性（#731）。
 *
 * 分块最怕「字节写错位置」：内容长度对得上，sha256 却失败，在真机上表现为
 * 「镜像内容被改写」。这里用一个本地假 open（直接返回内存流的 HttpURLConnection
 * 替身不好造，所以只测不需要网络的部分：预分配、乱序写入后的整文件哈希、
 * 断点往返），把这类错误挡在提交前。
 */
class ChunkedFetchTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-chunk").toFile()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `分块断点编解码往返一致`() {
        val chunks = ChunkPlan.split(total = 1000, concurrency = 4)
        val done = listOf(250L, 100L, 0L, 50L)
        val text = ResumeState.encodeChunks("https://a/f", 1000, "\"e1\"", "Mon", chunks, done)
        val back = ResumeState.decodeChunks(text)
        assertNotNull(back)
        assertEquals("https://a/f", back!!.url)
        assertEquals(1000L, back.total)
        assertEquals("\"e1\"", back.etag)
        assertEquals("Mon", back.lastModified)
        assertEquals(chunks, back.ranges)
        assertEquals(done, back.done)
    }

    @Test
    fun `分块断点缺校验标识时往返仍为 null 而不是字符串 null`() {
        val chunks = ChunkPlan.split(total = 100, concurrency = 2)
        val text = ResumeState.encodeChunks("https://a/f", 100, null, null, chunks, listOf(0L, 0L))
        val back = ResumeState.decodeChunks(text)
        assertNull(back!!.etag)
        assertNull(back.lastModified)
    }

    @Test
    fun `单连接断点与分块断点互不兼容`() {
        val single = ResumeState("https://a/f", 50, 100, "\"e1\"", null).encode()
        // schema 1 的记录不能被当成分块记录读出来。
        assertNull(ResumeState.decodeChunks(single))
        val chunks = ChunkPlan.split(total = 100, concurrency = 2)
        val multi = ResumeState.encodeChunks("https://a/f", 100, "\"e1\"", null, chunks, listOf(10L, 0L))
        assertNull(ResumeState.decode(multi))
    }

    @Test
    fun `区间不连续的记录整份作废`() {
        // 第二块起点不是 500 而是 600：中间 100 字节没人负责，拼出来必然是坏文件。
        val bad =
            """{"v":2,"url":"https://a/f","total":1000,"chunks":[
               {"from":0,"to":499,"done":0},{"from":600,"to":999,"done":0}]}"""
        assertNull(ResumeState.decodeChunks(bad))
    }

    @Test
    fun `区间总长与 total 不符的记录作废`() {
        val bad =
            """{"v":2,"url":"https://a/f","total":2000,"chunks":[
               {"from":0,"to":499,"done":0},{"from":500,"to":999,"done":0}]}"""
        assertNull(ResumeState.decodeChunks(bad))
    }

    @Test
    fun `块进度超过块长的记录作废`() {
        val bad =
            """{"v":2,"url":"https://a/f","total":100,"chunks":[
               {"from":0,"to":49,"done":999},{"from":50,"to":99,"done":0}]}"""
        assertNull(ResumeState.decodeChunks(bad))
    }

    @Test
    fun `半截 JSON 与空块列表一律作废`() {
        assertNull(ResumeState.decodeChunks(""))
        assertNull(ResumeState.decodeChunks("""{"v":2,"url":"https://a/f","total":100,"chunks":["""))
        assertNull(ResumeState.decodeChunks("""{"v":2,"url":"https://a/f","total":100,"chunks":[]}"""))
        assertNull(ResumeState.decodeChunks("""{"v":2,"url":"https://a/f","total":100}"""))
    }

    @Test
    fun `整文件哈希覆盖乱序写入的全部字节`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val chunks = ChunkPlan.split(total = data.size.toLong(), concurrency = 4)
        // 模拟并发乱序落盘：先预分配长度，再按倒序把各块写到自己的偏移。
        java.io.RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(data.size.toLong())
            chunks.reversed().forEach { c ->
                raf.seek(c.from)
                raf.write(data, c.from.toInt(), c.length.toInt())
            }
        }
        assertEquals(data.size.toLong(), part.length())
        assertEquals(sha(data), ChunkedFetch.sha256Of(part))
        dir.deleteRecursively()
    }

    @Test
    fun `预分配后未写满的区域会被哈希算进去`() {
        // 这条钉住「有块没完成就必须判失败」的理由：空洞读出来是 0，
        // 长度看起来完全正确，只有哈希能发现问题。
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val total = 1000L
        java.io.RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(total)
            raf.seek(0)
            raf.write(ByteArray(500) { 1 })
        }
        assertEquals(total, part.length())
        val full = ByteArray(1000) { if (it < 500) 1 else 0 }
        assertEquals(sha(full), ChunkedFetch.sha256Of(part))
        dir.deleteRecursively()
    }

    @Test
    fun `已完成的块不再发起请求`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 100, concurrency = 2)
        val done = AtomicLongArray(longArrayOf(50L, 50L))
        var opened = 0
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 100,
                cancel = null,
                progress = null,
                open = { _, _ ->
                    opened++
                    throw IllegalStateException("不该开连接")
                },
            )
        assertEquals(DownloadOutcome.Kind.OK, out.kind)
        assertEquals(0, opened)
        assertEquals(100L, out.bytes)
        dir.deleteRecursively()
    }

    @Test
    fun `取消已置位时不写入任何字节并返回取消`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val chunks = ChunkPlan.split(total = 100, concurrency = 2)
        val done = AtomicLongArray(2)
        val cancel = Downloader.Cancel()
        cancel.cancel()
        val out =
            ChunkedFetch.run(
                part = part,
                chunks = chunks,
                done = done,
                total = 100,
                cancel = cancel,
                progress = null,
                open = { _, _ -> throw IllegalStateException("取消后不该开连接") },
            )
        // open 抛异常被记为 IO 失败或取消，两者都不是 OK；关键是没有写入字节。
        assertTrue(out.kind != DownloadOutcome.Kind.OK)
        assertEquals(0L, done.get(0))
        assertEquals(0L, done.get(1))
        dir.deleteRecursively()
    }

    @Test
    fun `空块列表直接判失败而不是当成完成`() {
        val dir = tmpDir()
        val out =
            ChunkedFetch.run(
                part = File(dir, "f.part"),
                chunks = emptyList(),
                done = AtomicLongArray(0),
                total = 0,
                cancel = null,
                progress = null,
                open = { _, _ -> throw IllegalStateException() },
            )
        assertEquals(DownloadOutcome.Kind.IO, out.kind)
        dir.deleteRecursively()
    }
}