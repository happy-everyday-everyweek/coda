package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 日志落盘。
 *
 * 关键是「调用方不阻塞」：内核日志既来自高频 stderr 线程也来自主线程，
 * 同步写盘会把 IO 与锁等待压到主线程上。
 */
class LogSinkTest {
    private fun target(): File {
        val dir = Files.createTempDirectory("logsink").toFile()
        dir.deleteOnExit()
        return File(dir, "core.log").apply { deleteOnExit() }
    }

    private fun awaitContent(
        file: File,
        expected: Int,
    ): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.readLines().size >= expected) break
            Thread.sleep(20)
        }
        return if (file.isFile) file.readLines() else emptyList()
    }

    @Test
    fun `lines are written in order`() {
        val file = target()
        val sink = LogSink(file)
        for (i in 1..50) sink.offer("line$i")
        val lines = awaitContent(file, 50)
        sink.close()
        assertEquals(50, lines.size)
        assertEquals("line1", lines.first())
        assertEquals("line50", lines.last())
    }

    @Test
    fun `offering never blocks the caller`() {
        val file = target()
        val sink = LogSink(file)
        val started = System.currentTimeMillis()
        // 远超队列容量：满了要丢最旧的，而不是把调用线程挂住。
        for (i in 1..20_000) sink.offer("burst$i")
        val elapsed = System.currentTimeMillis() - started
        sink.close()
        assertTrue("入队耗时过长：${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `close flushes what is still queued`() {
        val file = target()
        val sink = LogSink(file)
        sink.offer("tail-a")
        sink.offer("tail-b")
        sink.close()
        // close 之后写线程仍会把队列排空。
        val lines = awaitContent(file, 2)
        assertTrue(lines.contains("tail-b"))
    }
}
