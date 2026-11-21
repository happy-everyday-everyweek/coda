package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 后台池的三条约定：会真的执行、单个任务失败不带走进程、池不对外开放关闭。 */
class BgTest {
    @Test
    fun runsSubmittedTasks() {
        val done = CountDownLatch(1)
        Bg.run { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun oneFailingTaskDoesNotStopTheNext() {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        Bg.run {
            first.countDown()
            throw IllegalStateException("boom")
        }
        Bg.run { second.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertFalse(Bg.isShutdown())
    }
}
