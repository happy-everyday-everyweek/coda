package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多线程分块的切分与聚合（#731）。
 *
 * 分块最容易错的两处：区间边界（漏一个字节或重叠一个字节，最终 sha256 必然失败）、
 * 以及「什么时候不该分块」（小文件多连接握手反而更慢）。这两处都是纯算术，
 * 抽出来就能在 JVM 里穷举验证，不必真机下载大文件。
 */
class DownloadChunkTest {
    @Test
    fun `区间连续且不重叠不漏字节`() {
        val chunks = ChunkPlan.split(total = 1000, concurrency = 4)
        assertEquals(4, chunks.size)
        assertEquals(0L, chunks.first().from)
        assertEquals(999L, chunks.last().to)
        // 相邻块必须首尾相接：前一块 to + 1 == 后一块 from。
        for (i in 1 until chunks.size) {
            assertEquals(chunks[i - 1].to + 1, chunks[i].from)
        }
        assertEquals(1000L, chunks.sumOf { it.length })
    }

    @Test
    fun `不能整除时余数分给前面的块`() {
        val chunks = ChunkPlan.split(total = 10, concurrency = 3)
        assertEquals(listOf(4L, 3L, 3L), chunks.map { it.length })
        assertEquals(10L, chunks.sumOf { it.length })
        assertEquals(9L, chunks.last().to)
    }

    @Test
    fun `总长小于并发数时不产出空块`() {
        val chunks = ChunkPlan.split(total = 2, concurrency = 8)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length > 0 })
        assertEquals(2L, chunks.sumOf { it.length })
    }

    @Test
    fun `单字节只有一块`() {
        val chunks = ChunkPlan.split(total = 1, concurrency = 4)
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].from)
        assertEquals(0L, chunks[0].to)
        assertEquals(1L, chunks[0].length)
    }

    @Test
    fun `并发数被夹到 1 到 8`() {
        assertEquals(1, ChunkPlan.split(total = 1000, concurrency = 0).size)
        assertEquals(1, ChunkPlan.split(total = 1000, concurrency = -3).size)
        assertEquals(8, ChunkPlan.split(total = 1000, concurrency = 99).size)
    }

    @Test
    fun `总长为 0 或负数不产出任何块`() {
        assertTrue(ChunkPlan.split(total = 0, concurrency = 4).isEmpty())
        assertTrue(ChunkPlan.split(total = -1, concurrency = 4).isEmpty())
    }

    @Test
    fun `大文件穷举验证边界`() {
        // 用真实量级（100MB APK）与各种并发数组合，确认没有 off-by-one。
        for (n in 1..8) {
            val total = 104_857_600L + 7
            val chunks = ChunkPlan.split(total, n)
            assertEquals(total, chunks.sumOf { it.length })
            assertEquals(0L, chunks.first().from)
            assertEquals(total - 1, chunks.last().to)
            for (i in 1 until chunks.size) {
                assertEquals(chunks[i - 1].to + 1, chunks[i].from)
            }
        }
    }

    @Test
    fun `小文件不分块`() {
        // 阈值 8MB：多连接的握手开销比省下的传输时间还多。
        assertFalse(ChunkPlan.shouldSplit(total = 1024, concurrency = 4, rangesSupported = true))
        assertFalse(ChunkPlan.shouldSplit(total = 8L * 1024 * 1024 - 1, concurrency = 4, rangesSupported = true))
    }

    @Test
    fun `达到阈值且支持 Range 才分块`() {
        assertTrue(ChunkPlan.shouldSplit(total = 8L * 1024 * 1024, concurrency = 4, rangesSupported = true))
        assertTrue(ChunkPlan.shouldSplit(total = 100L * 1024 * 1024, concurrency = 4, rangesSupported = true))
    }

    @Test
    fun `不支持 Range 或大小未知一律不分块`() {
        assertFalse(ChunkPlan.shouldSplit(total = 100L * 1024 * 1024, concurrency = 4, rangesSupported = false))
        assertFalse(ChunkPlan.shouldSplit(total = 0, concurrency = 4, rangesSupported = true))
        assertFalse(ChunkPlan.shouldSplit(total = -1, concurrency = 4, rangesSupported = true))
    }

    @Test
    fun `并发数为 1 时不分块`() {
        assertFalse(ChunkPlan.shouldSplit(total = 100L * 1024 * 1024, concurrency = 1, rangesSupported = true))
    }

    @Test
    fun `Range 头按闭区间生成`() {
        val c = ChunkPlan.Chunk(index = 2, from = 100, to = 199)
        assertEquals("bytes=100-199", c.rangeHeader())
    }

    @Test
    fun `已完成块的剩余长度为 0`() {
        val c = ChunkPlan.Chunk(index = 0, from = 0, to = 99)
        assertEquals(100L, c.remaining(done = 0))
        assertEquals(40L, c.remaining(done = 60))
        assertEquals(0L, c.remaining(done = 100))
        // 记录比块长还大时不返回负数。
        assertEquals(0L, c.remaining(done = 200))
    }

    @Test
    fun `块内续传起点是块起点加已完成`() {
        val c = ChunkPlan.Chunk(index = 1, from = 1000, to = 1999)
        assertEquals(1000L, c.resumeFrom(done = 0))
        assertEquals(1500L, c.resumeFrom(done = 500))
    }

    @Test
    fun `进度聚合是各块已完成之和`() {
        val chunks = ChunkPlan.split(total = 1000, concurrency = 4)
        val done = listOf(250L, 100L, 0L, 50L)
        assertEquals(400L, ChunkPlan.totalDone(chunks, done))
    }

    @Test
    fun `进度聚合对缺失与超量记录做夹取`() {
        val chunks = ChunkPlan.split(total = 1000, concurrency = 4)
        // 记录条数不足：缺的按 0 算，不能抛索引越界。
        assertEquals(250L, ChunkPlan.totalDone(chunks, listOf(250L)))
        // 某块记录超过块长：夹到块长，否则总进度会超过 100%。
        assertEquals(1000L, ChunkPlan.totalDone(chunks, listOf(9999L, 9999L, 9999L, 9999L)))
        // 负数按 0 算。
        assertEquals(0L, ChunkPlan.totalDone(chunks, listOf(-5L, -1L, 0L, 0L)))
    }

    @Test
    fun `全部块完成才算整单完成`() {
        val chunks = ChunkPlan.split(total = 1000, concurrency = 4)
        assertFalse(ChunkPlan.allDone(chunks, listOf(250L, 250L, 250L, 249L)))
        assertTrue(ChunkPlan.allDone(chunks, listOf(250L, 250L, 250L, 250L)))
        assertFalse(ChunkPlan.allDone(chunks, emptyList()))
    }

    @Test
    fun `速度聚合是各块速度之和`() {
        assertEquals(6000L, ChunkPlan.totalSpeed(listOf(1000L, 2000L, 3000L)))
        assertEquals(0L, ChunkPlan.totalSpeed(emptyList()))
        // 负速度（时钟回拨等）不拉低总数。
        assertEquals(1000L, ChunkPlan.totalSpeed(listOf(1000L, -500L)))
    }

    @Test
    fun `默认并发数保守为 4`() {
        assertEquals(4, ChunkPlan.DEFAULT_CONCURRENCY)
    }
}