package com.coda.mobileui.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 共享后台线程池。
 *
 * 在此之前各处用裸 `Thread { ... }.start()` 发起网络与文件任务：每次调用都新建一条线程，
 * 高频触发（抽屉同时拉仓库、分支、用户信息）会瞬间起好几条，且既不可取消也无法跟随生命周期。
 * 这里统一收口成一个有界线程池，线程按需创建后复用，名字带序号便于排查问题。
 *
 * 注意：长驻线程（内核读帧循环、手机控制服务端 accept 循环、自动化定时 tick）不走这里，
 * 它们需要各自独立且长期存在的线程。
 */
object Bg {
    private const val TAG = "Bg"

    /** 池内线程上限：短任务以网络等待为主，给得比 CPU 核数宽，又避免无界创建。 */
    private const val MAX_THREADS = 16

    private val counter = AtomicInteger(1)
    private val factory =
        ThreadFactory { r ->
            Thread(r, "coda-io-${counter.getAndIncrement()}").apply { isDaemon = true }
        }

    /**
     * 排队上限。
     *
     * 之前直接用 Executors.newFixedThreadPool，它的队列是无界的：调用方狂刷任务时
     * 内存会一路涨到 OOM，而且没有任何「忙不过来」的信号。
     */
    private const val QUEUE_CAPACITY = 128

    /**
     * 队列满时不丢任务、也不抛给调用方：由提交线程自己执行一次，形成背压。
     *
     * 这比 RejectedExecution 直接抛出去好——后者会把一次普通的后台请求变成崩溃。
     */
    private val pool =
        ThreadPoolExecutor(
            MAX_THREADS,
            MAX_THREADS,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            factory,
            ThreadPoolExecutor.CallerRunsPolicy(),
        )

    /** 提交一个后台任务，语义等同原来的 `Thread { block() }.start()`。 */
    fun run(block: () -> Unit) {
        try {
            pool.execute {
                // 单个任务抛异常不能带走进程：之前异常会从工作线程逃到默认处理器，直接杀进程。
                try {
                    block()
                } catch (e: Exception) {
                    AppLog.e(TAG, "后台任务失败", e)
                }
            }
        } catch (e: Exception) {
            // 池已关闭（进程退出阶段）或拒绝策略抛异常：记录后放弃，不把异常抛给调用方。
            AppLog.w(TAG, "后台任务未被接受：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 池是否已关闭。只用于状态查询：池本身不对外开放，避免被谁 shutdown 掉。 */
    fun isShutdown(): Boolean = pool.isShutdown
}
