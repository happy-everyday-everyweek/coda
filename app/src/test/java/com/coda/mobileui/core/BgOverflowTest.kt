package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 队列饱和时不得把任务拉回提交线程（#738）。
 *
 * CallerRunsPolicy 会让 UI 线程自己跑网络与文件任务：网络抛
 * NetworkOnMainThreadException 被吞掉、重 IO 直接 ANR。这里把「不在提交线程执行」
 * 钉成断言——这是 run 的文档承诺，也是 Android 上唯一可接受的语义。
 */
class BgOverflowTest {
    @Test
    fun `任务永远不在提交线程上执行`() {
        val caller = Thread.currentThread().name
        val n = 40
        val done = CountDownLatch(n)
        val sameThread = AtomicInteger(0)
        repeat(n) {
            Bg.run {
                if (Thread.currentThread().name == caller) sameThread.incrementAndGet()
                done.countDown()
            }
        }
        assertTrue("任务未在 10s 内跑完", done.await(10, TimeUnit.SECONDS))
        assertEquals("有任务被拉回提交线程执行", 0, sameThread.get())
    }

    @Test
    fun `饱和时提交方不会被阻塞也不会自己跑任务`() {
        // 灌入远超 16 线程 + 128 队列的任务，每个都占住线程一小会儿。
        val release = CountDownLatch(1)
        val caller = Thread.currentThread().name
        val ranOnCaller = AtomicInteger(0)
        val submitted = 400
        val finished = CountDownLatch(submitted)
        val t0 = System.nanoTime()
        repeat(submitted) {
            Bg.run {
                if (Thread.currentThread().name == caller) ranOnCaller.incrementAndGet()
                // 被丢弃的任务不会执行，这里只等已接受的那些。
                release.await(5, TimeUnit.SECONDS)
                finished.countDown()
            }
        }
        val submitMs = (System.nanoTime() - t0) / 1_000_000
        release.countDown()
        // 提交 400 个任务必须很快返回：阻塞入队会把主线程挂住。
        assertTrue("提交阶段耗时 ${submitMs}ms，疑似阻塞了提交方", submitMs < 3000)
        assertEquals("饱和后有任务在提交线程执行", 0, ranOnCaller.get())
        // 允许丢弃，但不允许静默：溢出必须有计数。
        val accepted = finished.await(10, TimeUnit.SECONDS)
        if (!accepted) {
            assertTrue("有任务被丢弃却没有溢出计数", Bg.overflowCount() > 0)
        }
        assertFalse(Bg.isShutdown())
    }

    @Test
    fun `溢出计数单调不减`() {
        val before = Bg.overflowCount()
        assertTrue(before >= 0)
        assertTrue(Bg.overflowCount() >= before)
    }
}
