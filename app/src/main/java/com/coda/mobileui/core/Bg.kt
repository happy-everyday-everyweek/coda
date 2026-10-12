package com.coda.mobileui.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
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
    /** 池内线程上限：短任务以网络等待为主，给得比 CPU 核数宽，又避免无界创建。 */
    private const val MAX_THREADS = 16

    private val counter = AtomicInteger(1)
    private val factory = ThreadFactory { r ->
        Thread(r, "coda-io-${counter.getAndIncrement()}").apply { isDaemon = true }
    }

    val io: ExecutorService = Executors.newFixedThreadPool(MAX_THREADS, factory)

    /** 提交一个后台任务，语义等同原来的 `Thread { block() }.start()`。 */
    fun run(block: () -> Unit) {
        io.execute(block)
    }
}
