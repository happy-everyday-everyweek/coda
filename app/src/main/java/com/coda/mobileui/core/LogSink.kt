package com.coda.mobileui.core

import java.io.File
import java.util.concurrent.ArrayBlockingQueue

/**
 * 日志落盘。
 *
 * 内核日志既来自高频的 stderr 读线程，也来自主线程（反向请求、各类回调）。
 * 原来在锁里同步 `appendText`，等于把磁盘 IO 与锁等待压到调用线程上，主线程会卡顿。
 *
 * 这里改成有界队列 + 单独的写线程批量追加：调用方只做入队，队列满时丢弃最旧的行
 * 而不是阻塞——日志不该反过来拖住业务。
 */
internal class LogSink(
    private val file: File,
) {
    private val queue = ArrayBlockingQueue<String>(CAPACITY)

    @Volatile
    private var running = true

    // 构造期不发布 this（OCR #819）：属性初始化里 start() 会让写线程
    // 在对象构造完成前跑起来。由调用方显式 start。
    private val worker =
        Thread({ drain() }, "coda-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }

    /** 构造后显式启动写线程。 */
    fun start() {
        worker.start()
    }

    /** 入队一行；队列满时丢掉最旧的一行腾位置，绝不阻塞调用线程。 */
    fun offer(line: String) {
        if (!running) return
        while (!queue.offer(line)) {
            // poll 为 null 说明别的消费者刚取空：不能 break 后不计数，
            // 也不能把「腾位失败」记成丢弃（OCR #819）。原子累加防竞态。
            if (queue.poll() == null) break
            droppedCount.incrementAndGet()
        }
    }

    /** 停止并把队列里剩下的写完：有界 join，进程退出不丢尾行（OCR #819）。 */
    fun close() {
        running = false
        worker.interrupt()
        runCatching { worker.join(2_000) }
    }

    private val droppedCount = java.util.concurrent.atomic.AtomicLong(0)

    /** 因为队列满而被丢弃的行数，用于在日志里说明「这里缺了一段」。 */
    val dropped: Long get() = droppedCount.get()

    private fun drain() {
        val batch = ArrayList<String>(BATCH)
        while (running || queue.isNotEmpty()) {
            batch.clear()
            val first =
                try {
                    queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
            if (first != null) {
                batch.add(first)
                queue.drainTo(batch, BATCH - 1)
            }
            if (batch.isEmpty()) continue
            runCatching {
                file.appendText(batch.joinToString(separator = "\n", postfix = "\n"))
            }
        }
    }

    private companion object {
        /** 队列容量：够缓冲一阵高频输出，又不至于占太多内存。 */
        const val CAPACITY = 4096

        /** 单次写盘的最大行数：批量追加，减少 IO 次数。 */
        const val BATCH = 128
    }
}
