package com.coda.mobileui.core

import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * 按目标文件串行化资产获取（#806）。
 *
 * `Downloader` 开启续传后用的是确定性临时名 `<name>.part` 加共享的 `.dlstate`，
 * 而启动补资源与设置页可能同时获取同一个资产。两路并发会互写同一个 part：
 * 一方的 `ResumeState.discard` 删掉另一方正在写的字节，一方把另一方写了一半的
 * 文件 rename 上线。确定性命名与并发是互斥的两个前提，必须补上排他。
 *
 * 用按路径的可重入锁而不是全局大锁：不同资产之间没有共享状态，
 * 串成一条会让首屏的多份资源下载变成排队。锁对象按 key 常驻，
 * 数量上限是资产种类数（十几个），不做回收——回收本身会引入
 * 「A 拿到锁对象、B 移除、C 新建另一个锁对象」的经典竞态。
 */
internal object AssetLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, ReentrantLock>()

    /** 锁的 key：用绝对路径，相对路径与符号链接写法不同也要落到同一把锁上。 */
    fun keyOf(target: File): String = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }

    /** 在 [target] 对应的锁内执行 [block]。同一路径的并发调用会串行。 */
    fun <T> withLock(
        target: File,
        block: () -> T,
    ): T {
        val lock = locks.computeIfAbsent(keyOf(target)) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** 当前已登记的锁数量。仅用于测试与诊断。 */
    fun size(): Int = locks.size
}
