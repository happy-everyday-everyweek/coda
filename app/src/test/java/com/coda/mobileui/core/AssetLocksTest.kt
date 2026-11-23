package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同一资产的并发获取必须串行（#806）。
 *
 * 续传启用后临时名是确定性的，两路并发会互写同一个 `.part`：
 * 一方 discard 删掉另一方正在写的字节，一方把半成品 rename 上线。
 */
class AssetLocksTest {
    @Test
    fun `同一路径的两次调用不会重叠`() {
        val dir = Files.createTempDirectory("coda-assetlock").toFile()
        val target = File(dir, "same.bin")
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.countDown()
                start.await(5, TimeUnit.SECONDS)
                AssetLocks.withLock(target) {
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(60)
                    inside.decrementAndGet()
                }
                done.countDown()
            }.start()
        }
        assertTrue("并发获取没有在 10s 内结束", done.await(10, TimeUnit.SECONDS))
        assertEquals("同一目标出现了重叠执行", 1, maxInside.get())
        dir.deleteRecursively()
    }

    @Test
    fun `不同路径可以并行`() {
        val dir = Files.createTempDirectory("coda-assetlock2").toFile()
        val a = File(dir, "a.bin")
        val b = File(dir, "b.bin")
        val both = CountDownLatch(2)
        val done = CountDownLatch(2)
        listOf(a, b).forEach { f ->
            Thread {
                AssetLocks.withLock(f) {
                    both.countDown()
                    // 两个不同目标必须能同时在锁内，否则首屏资源下载变成排队。
                    both.await(5, TimeUnit.SECONDS)
                }
                done.countDown()
            }.start()
        }
        assertTrue("不同目标被串行化了", done.await(10, TimeUnit.SECONDS))
        dir.deleteRecursively()
    }

    @Test
    fun `相对路径与绝对路径落到同一把锁`() {
        val dir = Files.createTempDirectory("coda-assetlock3").toFile()
        val abs = File(dir, "x.bin")
        val rel = File(abs.parent, "./x.bin")
        assertEquals(AssetLocks.keyOf(abs), AssetLocks.keyOf(rel))
        dir.deleteRecursively()
    }

    @Test
    fun `异常也会释放锁`() {
        val dir = Files.createTempDirectory("coda-assetlock4").toFile()
        val target = File(dir, "boom.bin")
        runCatching {
            AssetLocks.withLock(target) { throw IllegalStateException("boom") }
        }
        // 上一次抛异常后仍能拿到锁：否则这个资产永远无法再获取。
        val ok = AssetLocks.withLock(target) { 42 }
        assertEquals(42, ok)
        dir.deleteRecursively()
    }

    @Test
    fun `同线程可重入`() {
        val dir = Files.createTempDirectory("coda-assetlock5").toFile()
        val target = File(dir, "re.bin")
        val got = AssetLocks.withLock(target) { AssetLocks.withLock(target) { 7 } }
        assertEquals(7, got)
        dir.deleteRecursively()
    }
}
